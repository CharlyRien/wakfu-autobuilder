package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Sublimation
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

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
     * are clean permanent static stats — accepted into the solver-choosable set (decision 2026-06-19). The 2
     * caveat subs (5982 multi-mastery, 6000 ambiguous duplicate damage) are deliberately NOT here — they stay
     * forced-only via principled rules in [buildSublimations].
     */
    private val acceptedAdditions = setOf(6013, 6026, 7075, 7864, 7865, 8390, 8391, 8393, 8492)

    @Test
    fun `bdata + CDN reproduces the oracle's choosable set and effects`() {
        assumeTrue(File(install, "contents/bdata/${Tables.STATE}.jar").isFile, "no local Wakfu install")

        val states = loadTable(install, Tables.STATE, Tables.STATE_SCHEMA)
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val actions = ActionCatalog.fetch(WakfuData.VERSION)
        val meta = ItemsCatalog.fetchSublimationMeta(WakfuData.VERSION)

        val built = buildSublimations(states, effects, actions, meta)
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
        println("accepted additions present: ${acceptedAdditions.all { it in builtChoosable }}; caveat subs excluded: ${listOf(5982, 6000).none { it in builtChoosable }}")

        println("\n── per-sub check of the oracle's choosable set ──")
        var effectMismatch = 0
        for (sid in oracleChoosable) {
            val b = byId.getValue(sid)
            val o = oracleById.getValue(sid)
            val bEff = b.effects.map { it.characteristic to it.value }.toSet()
            val oEff = o.effects.map { it.characteristic to it.value }.toSet()
            val condOk = b.condition?.type == o.condition?.type && b.condition?.value == o.condition?.value
            val mlOk = b.maxLevel == o.maxLevel
            val effOk = bEff == oEff || sid in knownOracleBug
            if (!effOk) effectMismatch++
            val flag = if (effOk && condOk && mlOk && b.solverChoosable) "ok " else "DIFF"
            println("  [$flag] $sid ${o.name.en}: ml ${b.maxLevel}/${o.maxLevel} cond ${b.condition?.type}/${o.condition?.type}")
            if (!effOk || !condOk || !mlOk) {
                println("        built : ${b.effects}")
                println("        oracle: ${o.effects}")
            }
        }

        // The contract: every one of the audited 18 stays choosable with the same effects (5444's effect
        // differs — a corrected oracle bug), PLUS the 9 accepted clean discoveries, and NOTHING else (the 2
        // caveat subs + all combat subs stay forced-only — no invented stats).
        assertEquals(
            expectedChoosable,
            builtChoosable,
            "solver-choosable set must be the audited 18 + 9 accepted additions (unexpected=$unexpected missing=$missing)"
        )
        assertEquals(
            0,
            effectMismatch,
            "choosable subs whose effects differ from the oracle (excluding the known 5444 oracle bug)"
        )
    }
}
