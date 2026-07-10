package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Characteristic
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** A comparable identity for a stat effect that distinguishes a flat amount from a "% of level" one. */
private fun effectKey(e: SublimationEffect.StatEffect): Triple<Characteristic, String, Int> =
    when (e) {
        is SublimationEffect.Flat -> Triple(e.characteristic, "flat", e.value)
        is SublimationEffect.PercentOfLevel -> Triple(e.characteristic, "percentOfLevel", e.percentOfLevel)
    }

/**
 * Validates that the fully first-party sublimation pipeline (CDN `items.json` metadata + bdata State/StaticEffect
 * effects) reproduces the committed oracle's solver-relevant data — above all the **18 solver-choosable** subs.
 * Install-gated (decodes the local client binaries + fetches the CDN), so it is skipped in CI.
 */
class SublimationReproductionTest {
    private val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")

    /** stateId of the one sublimation whose oracle effect is a known WakForge regex bug (bdata is correct). */
    private val knownOracleBug = setOf(5444)

    /**
     * Subs the old WakForge pipeline wrongly excluded (indentation bug / "at start of fight" misphrasing /
     * being newer than its dump / the same %-of-level approximation as the audited 5448) but which bdata shows
     * are clean permanent static stats — accepted into the solver-choosable set (decision 2026-06-19). The
     * caveat sub 6000 (ambiguous duplicate damage) is deliberately NOT here — it stays forced-only via a
     * principled rule in [buildSublimations]. (5982 Devastate was formerly excluded as "multi-mastery" but is
     * now choosable: the objective and re-scorer credit only mode-applicable masteries, so it never over-values.
     * 5449 Elemental Concentration is the newest addition: its +20% DI / −30%-weakest-elements decodes to a
     * [me.chosante.common.SublimationEffect.BestElementConcentration], modeled soundly (max-damage, strongest-element-gated).)
     */
    private val acceptedAdditions = setOf(5445, 5449, 6013, 6026, 7075, 7864, 7865, 7879, 8390, 8391, 8393, 8492)

    @Test
    fun `bdata + CDN reproduces the oracle's choosable set and effects`() {
        assumeTrue(File(install, "contents/bdata/${Tables.STATE}.jar").isFile, "no local Wakfu install")

        val states = loadTable(install, Tables.STATE, Tables.STATE_SCHEMA)
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val actions = ActionCatalog.fetch(WakfuData.VERSION)
        val meta = ItemsCatalog.fetchSublimationMeta(WakfuData.VERSION)

        val built = buildSublimations(states, effects, actions, meta, CharacIdCatalog.load(install))
        val byId = built.associateBy { it.stateId }

        val oracle =
            LENIENT_JSON.decodeFromString(
                ListSerializer(Sublimation.serializer()),
                File(findRepositoryRoot(), "autobuilder/src/main/resources/sublimations.json").readText()
            )
        val oracleById = oracle.associateBy { it.stateId }
        val oracleChoosable = oracle.filter { it.solverChoosable }.map { it.stateId }.toSortedSet()
        val builtChoosable = built.filter { it.solverChoosable }.map { it.stateId }.toSortedSet()

        println("built ${built.size} subs | oracle ${oracle.size} | choosable built=${builtChoosable.size} oracle=${oracleChoosable.size}")
        println("metadata ids match oracle: ${built.map { it.stateId }.toSortedSet() == oracle.map { it.stateId }.toSortedSet()}")

        val expectedChoosable = (oracleChoosable + acceptedAdditions).toSortedSet()
        val unexpected = builtChoosable - expectedChoosable
        val missing = expectedChoosable - builtChoosable
        if (unexpected.isNotEmpty()) {
            println("\n!! UNEXPECTED choosable (would invent stats):")
            unexpected.forEach { println("   $it ${byId[it]?.name?.en}: cond=${byId[it]?.condition} effects=${byId[it]?.effects}") }
        }
        if (missing.isNotEmpty()) {
            println("\n!! MISSING from choosable (regression):")
            missing.forEach { println("   $it ${oracleById[it]?.name?.en}: oracle effects=${oracleById[it]?.effects}") }
        }
        println("accepted additions present: ${acceptedAdditions.all { it in builtChoosable }}; caveat sub excluded: ${listOf(6000).none { it in builtChoosable }}")

        println("\n── per-sub check of the oracle's choosable set ──")
        var effectMismatch = 0
        for (sid in oracleChoosable) {
            val b = byId.getValue(sid)
            val o = oracleById.getValue(sid)
            val bEff =
                b.effects
                    .filterIsInstance<SublimationEffect.StatEffect>()
                    .map(::effectKey)
                    .toSet()
            val oEff =
                o.effects
                    .filterIsInstance<SublimationEffect.StatEffect>()
                    .map(::effectKey)
                    .toSet()
            val condOk = b.condition?.type == o.condition?.type && b.condition?.value == o.condition?.value
            val mlOk = b.maxStackLevel == o.maxStackLevel
            val mtOk = b.maxTier == o.maxTier
            val effOk = bEff == oEff || sid in knownOracleBug
            if (!effOk) effectMismatch++
            val flag = if (effOk && condOk && mlOk && mtOk && b.solverChoosable) "ok " else "DIFF"
            println("  [$flag] $sid ${o.name.en}: ml ${b.maxStackLevel}/${o.maxStackLevel} mt ${b.maxTier}/${o.maxTier} cond ${b.condition?.type}/${o.condition?.type}")
            if (!effOk || !condOk || !mlOk || !mtOk) {
                println("        built : ${b.effects}")
                println("        oracle: ${o.effects}")
            }
        }

        // Featherweight (7088): its action-913 ramp must decode first-party to the exact in-game formula
        // ("+6% Damage Inflicted per MP above 4, max 24"). A positional-layout drift on a client bump fails
        // loudly here, like the Monster schema guard.
        assertEquals(
            SublimationEffect.PerStatStep(Characteristic.MOVEMENT_POINT, threshold = 4, perStep = 6, cap = 24, target = Characteristic.DAMAGE_INFLICTED),
            byId[7088]?.perStatStep,
            "Featherweight (7088) action-913 ramp decode"
        )

        // Unraveling (5077): its action-913 must decode first-party to a crit-mastery→elemental conversion, gated by
        // CRIT_AT_LEAST 40 (the benign `CRITICAL_BONUS >= 2` guard ignored). Locks the conversion shape + condition.
        assertEquals(
            SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, percent = 100),
            byId[5077]?.conversion,
            "Unraveling (5077) action-913 conversion decode"
        )
        assertEquals(
            SublimationConditionType.CRIT_AT_LEAST,
            byId[5077]?.condition?.type,
            "Unraveling (5077) crit condition"
        )

        // Elemental Concentration (5449): its unconditional +DI plus the four-element "my element ≥ every other"
        // penalty fan-out must decode first-party to the best-element concentration spec (+20% Damage Inflicted,
        // −30% mastery on the three weakest elements). A positional-layout drift on a client bump fails loudly here.
        assertEquals(
            SublimationEffect.BestElementConcentration(damageInflictedBonus = 20, masteryPenaltyPercent = 30),
            byId[5449]?.bestElementConcentration,
            "Elemental Concentration (5449) best-element decode"
        )

        // Anatomy (5445) / Abnegation (7879): their self-referential "(elem mastery + %dmg) > rear/heal mastery" gate
        // is optimistically satisfied (Ignore, like the HP≥90 full-HP gate), so they decode to plain DAMAGE_INFLICTED
        // effects with NO condition and become choosable. Anatomy = −20% DI + +40% DI (rear-orientation gate);
        // Abnegation = −15% DI (its +30% heals is an unmodeled stat, dropped). Monotone ⇒ no reified-condition/
        // domination cost. Locks the decode + the optimistic-gate rule.
        assertEquals(null, byId[5445]?.condition, "Anatomy (5445) self-gate optimistically ignored (no condition)")
        assertEquals(
            setOf(-20, 40),
            byId[5445]
                ?.effects
                ?.filterIsInstance<SublimationEffect.StatEffect>()
                ?.filter { it.characteristic == Characteristic.DAMAGE_INFLICTED }
                ?.map { (it as SublimationEffect.Flat).value }
                ?.toSet(),
            "Anatomy (5445) decodes to −20% DI + +40% DI (rear)"
        )
        assertEquals(
            "BACK",
            byId[5445]
                ?.effects
                ?.firstOrNull { (it as? SublimationEffect.Flat)?.value == 40 }
                ?.scenarioGate
                ?.orientation,
            "Anatomy (5445) +40% DI is rear (BACK) gated"
        )
        assertEquals(null, byId[7879]?.condition, "Abnegation (7879) self-gate optimistically ignored (no condition)")
        assertEquals(
            listOf(-15),
            byId[7879]
                ?.effects
                ?.filterIsInstance<SublimationEffect.StatEffect>()
                ?.filter {
                    it.characteristic == Characteristic.DAMAGE_INFLICTED
                }?.map { (it as SublimationEffect.Flat).value },
            "Abnegation (7879) decodes to −15% DI"
        )

        // Chaos (7878): its five "set elemental mastery to 0" actions (560-563, 566) set the zeroesElementalMastery
        // flag (decoded first-party, +20% DI as a plain effect) — but the sub stays FORCED-INPUT-ONLY on purpose:
        // modeling the zeroing ~5× the max-damage proof time (see SublimationBuilder). Locks the flag + effect + the
        // deliberate not-choosable.
        assertEquals(true, byId[7878]?.zeroesElementalMastery, "Chaos (7878) zeroes elemental mastery (decoded)")
        assertEquals(false, byId[7878]?.solverChoosable, "Chaos (7878) stays forced-input-only (perf: modeling it ~5× the proof)")
        assertEquals(
            listOf(20),
            byId[7878]
                ?.effects
                ?.filterIsInstance<SublimationEffect.StatEffect>()
                ?.filter {
                    it.characteristic == Characteristic.DAMAGE_INFLICTED
                }?.map { (it as SublimationEffect.Flat).value },
            "Chaos (7878) decodes to +20% DI"
        )

        // The contract: every one of the audited 18 stays choosable with the same effects (5444's effect
        // differs — a corrected oracle bug), PLUS the 12 accepted clean discoveries, and NOTHING else (the
        // caveat sub 6000 + all combat subs stay forced-only — no invented stats).
        assertEquals(
            expectedChoosable,
            builtChoosable,
            "solver-choosable set must be the audited 18 + 12 accepted additions (unexpected=$unexpected missing=$missing)"
        )
        assertEquals(
            0,
            effectMismatch,
            "choosable subs whose effects differ from the oracle (excluding the known 5444 oracle bug)"
        )
    }
}
