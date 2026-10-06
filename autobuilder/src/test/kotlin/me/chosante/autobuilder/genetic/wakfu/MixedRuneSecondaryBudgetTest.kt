package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * CERTIFIER_VERSION 57: the max-damage single-type rune fold under a positive secondary budget. A `≤ 0` cap (the
 * Neutralité family holds EACH secondary mastery ≤ 0) does not rule out a part-filled item: an item's NEGATIVE line of a
 * capped secondary leaves that stat a budget a MIXED item fills — some sockets of the secondary rune, the rest elemental
 * — where no single type fits. v56's fold filled every item with one type, so it missed that optimum and CP-SAT's
 * `OPTIMAL` over it was a wrong badge. The fold now keeps per-type COUNTS on the carriers that can need a mix
 * ([MaxDamageRuneReads.mixedStats], [RuneModel.countCarriers]); the per-stat COUNT model (`forceRuneCountModel`) is the
 * exact reference. The AP-cell certificate reads a count carrier's types at their full-fill vertices, which bound every
 * mixed fill: it must stay ≥ the count-model optimum.
 */
class MixedRuneSecondaryBudgetTest {
    private val runes = WakfuBestBuildFinderAlgorithm.runes
    private val catalog = WakfuBestBuildFinderAlgorithm.sublimations

    private fun sub(fr: String) = catalog.single { it.name.fr == fr }

    private val neutralite = sub("Neutralité III")
    private val caps = listOf("Neutralité III", "Prétention III", "Ambition III", "Inflexibilité II").map(::sub)
    private val critSecret = sub("Secret critique")
    private val unraveling = sub("Dénouement")

    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 60.0, interleaveSearch = true)

    private fun params(
        level: Int,
        scenario: DamageScenario,
        targets: List<TargetStat> = listOf(TargetStat(scenario.rangeBand.masteryCharacteristic, 1)),
        forcedSubs: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        damageScenario = scenario,
        useRunes = true,
        useSublimations = true,
        forcedSublimations = forcedSubs
    )

    private fun item(
        id: Int,
        type: ItemType,
        level: Int,
        stats: Map<Characteristic, Int>,
        sockets: Int = 4,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("mr$id", "mr$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    private fun solve(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
        count: Boolean = false,
        mixed: Boolean = true,
        hard: Boolean = false,
    ) = WakfuBuildSolver.maxDamageSolveForTest(
        p,
        pool,
        tuning,
        tightDomains = true,
        runes = runes,
        sublimations = subs,
        forceRuneCountModel = count,
        hardConstraints = hard,
        runeMixedCarriers = mixed
    )

    private val melee = Characteristic.MASTERY_MELEE
    private val back = Characteristic.MASTERY_BACK

    /**
     * The minimal repro (delta-debugged from seed 9 of the measurement fuzz): CRA 245 fire / melee / back, Neutralité III
     * choosable, four socketed items whose melee (−102, −58) and rear (−214) lines give the cap a melee budget of 160 and a
     * rear budget of 214. Melee and rear runes are worth 3/2 of an elemental one (33 vs 22 at rune level 11) but come in
     * whole items of 4 × 33 = 132 under the fold: one item of each leaves 28 melee and 82 rear unspent (the skill points
     * absorb part of it, at the cost of what they buy elsewhere). The optimum fills the budgets with part-filled items.
     * v56's fold: 1,320,570. Count model and the fixed fold: 1,328,235 (+0.58 %).
     */
    private val reproLevel = 245
    private val reproScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.MELEE, orientation = Orientation.BACK)
    private val reproPool =
        listOf(
            item(810_001, ItemType.HELMET, 236, mapOf(Characteristic.MASTERY_ELEMENTARY to 414, melee to -102)),
            item(810_002, ItemType.BELT, 219, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 365, back to -214)),
            item(810_003, ItemType.CHEST_PLATE, 215, mapOf(Characteristic.MASTERY_ELEMENTARY to 369)),
            item(810_004, ItemType.RING, 208, mapOf(Characteristic.MASTERY_ELEMENTARY to 159, melee to -58))
        ).groupBy { it.itemType }

    @Test
    fun `a part-filled item under a negative secondary budget is the optimum the fold now reaches`() {
        val p = params(reproLevel, reproScenario)
        val subs = listOf(neutralite)
        val v56 = solve(p, reproPool, subs, mixed = false)
        val count = solve(p, reproPool, subs, count = true)
        val fixed = solve(p, reproPool, subs)
        println("MIXED_REPRO v56=${v56.objective}/${v56.isOptimal} count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}")
        for ((label, outcome) in listOf("v56 fold" to v56, "count model" to count, "fixed fold" to fixed)) {
            assertThat(outcome.isOptimal).describedAs("the %s proves its optimum", label).isTrue()
        }
        // The mechanism: v56's all-or-nothing fill proves a value below the real optimum (a wrong "Optimal proven").
        assertThat(v56.objective).describedAs("v56's fold misses the part-filled optimum").isEqualTo(1_320_570L)
        assertThat(count.objective).describedAs("the exact count-model optimum").isEqualTo(1_328_235L)
        assertThat(fixed.objective).describedAs("the fold with count carriers reaches the count optimum").isEqualTo(count.objective)

        // The exported build takes Neutralité III and mixes rune types on at least one item, every secondary ≤ 0.
        val result =
            runBlocking {
                WakfuBuildSolver.optimize(p, reproPool, runes, subs, tuning).toList().last()
            }
        assertThat(result.isOptimal).isTrue()
        val build = result.individual
        assertThat(build.sublimations.values.flatten()).contains(neutralite)
        val mixedItems = build.runes.filterValues { r -> r.map { it.characteristic }.distinct().size > 1 }
        println("MIXED_REPRO runes=${build.runes.map { (e, r) -> e.itemType to r.map { it.characteristic } }}")
        assertThat(mixedItems).describedAs("some item carries more than one rune type").isNotEmpty
        for ((equip, r) in build.runes) assertThat(r).describedAs("%s fills every socket", equip.itemType).hasSize(equip.maxShardSlots)

        // Every AP cell of every certificate tier bounds the count model pinned to that cell.
        val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, reproPool, runes, subs)
        var cells = 0
        for (ap in exact.keys.sorted()) {
            val pinned = solve(p.copy(maxDamageApTarget = ap), reproPool, subs, count = true)
            if (!pinned.hasSolution) continue
            assertThat(pinned.isOptimal).describedAs("AP=%d: the count model proves its cell", ap).isTrue()
            cells++
            for ((tier, bound) in listOf("exact" to exact.getValue(ap), "fast" to fast.getValue(ap), "tier1.5" to tier15.getValue(ap))) {
                assertThat(bound).describedAs("AP=%d: %s must certify this shape", ap, tier).isLessThan(Long.MAX_VALUE).isGreaterThanOrEqualTo(0L)
                assertThat(bound).describedAs("AP=%d: %s must bound the mixed-rune optimum", ap, tier).isGreaterThanOrEqualTo(pinned.objective)
            }
        }
        assertThat(cells).isGreaterThan(0)
    }

    @Test
    fun `only a threshold read with a budget makes a rune type mixed`() {
        val distance = Characteristic.MASTERY_DISTANCE
        val crit = Characteristic.MASTERY_CRITICAL
        val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)
        val p = params(230, scenario)
        val choosable = maxDamageRuneReads(p, caps + critSecret, emptySet())
        // No source can make a secondary negative: a taken cap holds its runes at 0 — the fold stays exact.
        assertThat(choosable.mixedStats(emptySet())).isEmpty()
        // A negative rear line: rear runes have a budget (and feed M). Distance has none.
        assertThat(choosable.mixedStats(setOf(back))).containsExactly(back)
        // A negative crit line alone buys nothing: under `crit ≤ 0` a crit rune is clamped out of Graw…
        assertThat(choosable.mixedStats(setOf(crit, distance))).containsExactly(distance)
        // …unless a conversion moves crit mastery into M (Unraveling).
        val converting = maxDamageRuneReads(p, caps + unraveling, emptySet())
        // (Unraveling's own `crit rate ≤ 10` condition is a threshold too — no rune feeds crit rate, so it never matters.)
        assertThat(converting.mixedStats(setOf(crit))).contains(crit).doesNotContain(distance, back)
        // A FORCED cap may be broken on purpose: every stat it reads is mixed, budget or not.
        val forced = maxDamageRuneReads(params(230, scenario, forcedSubs = listOf("Neutralité III")), listOf(neutralite), emptySet())
        assertThat(forced.mixedStats(emptySet())).contains(distance, back, crit)
        // A required row with a positive target is a threshold; a 0-target row on a stat nothing makes negative is not.
        val rows =
            maxDamageRuneReads(
                params(230, scenario, targets = listOf(TargetStat(distance, 1), TargetStat(Characteristic.HP, 2000), TargetStat(Characteristic.DODGE, 0))),
                emptyList(),
                emptySet()
            )
        assertThat(rows.mixedStats(emptySet())).containsExactly(Characteristic.HP)
        assertThat(rows.mixedStats(setOf(Characteristic.DODGE))).containsExactlyInAnyOrder(Characteristic.HP, Characteristic.DODGE)
        // Nothing modelled reads a threshold: the pure fold.
        assertThat(maxDamageRuneReads(p, emptyList(), emptySet()).mixedStats(setOf(back, distance, crit))).isEmpty()
    }

    /**
     * The AP-cell mirror reads a count carrier's types at their full-fill VERTICES — exactly the options v56's picks gave
     * the same carriers. So on every pool the certificate with count carriers equals, cell for cell and tier for tier
     * (the capped aux worlds included), the one over v56's picks: a vertex read short (one rune instead of a full fill)
     * would under-value a count carrier wherever a capped world binds.
     */
    @Test
    fun `the certificate reads a count carrier at the same vertices as the picks it replaces`() {
        val cases =
            listOf(Case("repro", params(reproLevel, reproScenario), reproPool, listOf(neutralite))) +
                listOf(9L, 23L, 30L).flatMap { seed -> listOf(case(seed, false, false), case(seed, true, false), case(seed, false, true)) }
        var auxCells = 0
        for (c in cases) {
            for (targetAware in listOf(false, true)) {
                val now = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(c.params, c.pool, runes, c.subs, targetAware = targetAware)
                val v56 =
                    WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        targetAware = targetAware,
                        runeMixedCarriers = false
                    )
                assertThat(now.first).describedAs("%s target-aware=%s: exact", c.label, targetAware).isEqualTo(v56.first)
                assertThat(now.second).describedAs("%s target-aware=%s: fast", c.label, targetAware).isEqualTo(v56.second)
                assertThat(now.third).describedAs("%s target-aware=%s: tier-1.5", c.label, targetAware).isEqualTo(v56.third)
                val (_, aux) = WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(c.params, c.pool, runes, c.subs, targetAware = targetAware)
                val (_, auxV56) =
                    WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        targetAware = targetAware,
                        runeMixedCarriers = false
                    )
                assertThat(aux).describedAs("%s target-aware=%s: the capped / aux worlds", c.label, targetAware).isEqualTo(auxV56)
                auxCells += aux.values.count { it in 0 until Long.MAX_VALUE }
            }
        }
        // The capped world must actually be priced on these pools, or the lock reads nothing.
        assertThat(auxCells).isGreaterThan(0)
    }

    // ---- Seeded lock --------------------------------------------------------------------------------------------

    private class Case(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
    )

    /**
     * Small negative-biased pools (the measurement fuzz's generator): socketed items near the character level, scenario
     * secondaries and crit mastery mostly NEGATIVE (a budget for the cap), part of the Neutralité family with Critical
     * Secret / Unraveling, choosable or one forced, free or with AP / MP / RANGE / CC rows.
     */
    private fun case(
        seed: Long,
        forced: Boolean,
        rows: Boolean,
    ): Case {
        val rng = java.util.Random(seed * 1_000_003L + 17L)
        val level = listOf(110, 170, 200, 230, 245)[rng.nextInt(5)]
        val range = if (rng.nextBoolean()) RangeBand.DISTANCE else RangeBand.MELEE
        val orientation = if (rng.nextBoolean()) Orientation.BACK else Orientation.FACE
        val berserk = rng.nextInt(4) == 0
        val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = range, orientation = orientation, berserk = berserk)
        val scenarioSecondaries =
            buildList {
                add(range.masteryCharacteristic)
                if (orientation.grantsRearMastery) add(back)
                if (berserk) add(Characteristic.MASTERY_BERSERK)
            }
        var id = 900_000 + (seed % 5_000).toInt() * 40

        fun drawn(type: ItemType): Equipment {
            val stats = mutableMapOf<Characteristic, Int>()
            stats[if (rng.nextInt(3) == 0) Characteristic.MASTERY_ELEMENTARY else Characteristic.MASTERY_ELEMENTARY_FIRE] = 60 + rng.nextInt(400)
            for (s in scenarioSecondaries) {
                if (rng.nextInt(10) < 4) stats[s] = if (rng.nextInt(5) == 0) rng.nextInt(80) else -(10 + rng.nextInt(250))
            }
            if (rng.nextInt(10) < 3) stats[Characteristic.MASTERY_CRITICAL] = if (rng.nextInt(4) == 0) rng.nextInt(80) else -(10 + rng.nextInt(200))
            if (rng.nextInt(10) < 4) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(15) - 3
            if (rng.nextInt(10) < 2) stats[Characteristic.ACTION_POINT] = 1
            if (rng.nextInt(10) < 2) stats[Characteristic.MOVEMENT_POINT] = 1
            if (rng.nextInt(10) < 2) stats[Characteristic.RANGE] = 1
            if (rng.nextInt(8) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 5 + rng.nextInt(10)
            id++
            return item(id, type, maxOf(1, level - rng.nextInt(60)), stats.filterValues { it != 0 }, listOf(2, 3, 4, 4, 4)[rng.nextInt(5)])
        }
        val slots =
            listOf(ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.SHOULDER_PADS, ItemType.BOOTS, ItemType.AMULET, ItemType.CAPE, ItemType.BELT)
                .shuffled(rng)
                .take(3 + rng.nextInt(3))
        val items = mutableListOf<Equipment>()
        for (slot in slots) repeat(2) { items += drawn(slot) }
        if (rng.nextBoolean()) repeat(2) { items += drawn(ItemType.RING) }
        if (rng.nextBoolean()) items += drawn(ItemType.ONE_HANDED_WEAPONS)
        val family = caps.filter { rng.nextBoolean() }.ifEmpty { listOf(caps[0]) }
        val extras = listOf(critSecret, unraveling).filter { rng.nextBoolean() }
        val forcedName = if (forced) (family.firstOrNull { it.rarity == SublimationRarity.NORMAL } ?: neutralite).name.fr else null
        val subs = (family + extras + listOfNotNull(forcedName?.let(::sub))).distinct()
        val targets =
            buildList {
                add(TargetStat(range.masteryCharacteristic, 1))
                if (rows) {
                    if (rng.nextBoolean()) add(TargetStat(Characteristic.ACTION_POINT, 7 + rng.nextInt(2)))
                    if (rng.nextBoolean()) add(TargetStat(Characteristic.MOVEMENT_POINT, 3 + rng.nextInt(2)))
                    if (rng.nextBoolean()) add(TargetStat(Characteristic.RANGE, rng.nextInt(2)))
                    if (rng.nextBoolean()) add(TargetStat(Characteristic.CRITICAL_HIT, 5 + rng.nextInt(20)))
                }
            }
        return Case(
            "seed$seed ${if (forced) "forced" else "choosable"} ${if (rows) "rows" else "free"} lvl$level $range/$orientation berserk=$berserk " +
                "subs=${subs.map { it.name.fr }}",
            params(level, scenario, targets, listOfNotNull(forcedName)),
            items.groupBy { it.itemType },
            subs
        )
    }

    /**
     * Seeds 9, 18, 23, 30 and 38 diverged on v56 (count beat the fold by 0.07–2.86 % in every mode); 1–4 did not. Every
     * case: the fixed fold proves the count-model optimum, and the certificate ledger (every cell confirmed exactly; the
     * target-aware one on the hard leg of a rowed request) is ≥ it. The divergence count keeps the lock sensitive: with
     * the count carriers disabled the first assertion fails on the diverging seeds.
     */
    @Test
    fun `the fold proves the count-model optimum and the certificate bounds it on seeded negative-budget pools`() {
        var diverged = 0
        for (seed in listOf(1L, 2L, 3L, 4L, 9L, 18L, 23L, 30L, 38L)) {
            for ((forced, rows) in listOf(false to false, true to false, false to true)) {
                val c = case(seed, forced, rows)
                val hard = rows
                val count = solve(c.params, c.pool, c.subs, count = true, hard = hard)
                if (!count.hasSolution) continue
                val fixed = solve(c.params, c.pool, c.subs, hard = hard)
                val v56 = solve(c.params, c.pool, c.subs, mixed = false, hard = hard)
                println(
                    "MIXED_LOCK ${c.label} hard=$hard v56=${v56.objective}/${v56.isOptimal} " +
                        "count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}"
                )
                assertThat(count.isOptimal).describedAs("%s: the count model proves its optimum", c.label).isTrue()
                assertThat(fixed.isOptimal).describedAs("%s: the fold proves its optimum", c.label).isTrue()
                assertThat(fixed.objective).describedAs("%s: the fold's optimum is the count model's", c.label).isEqualTo(count.objective)
                if (v56.isOptimal && v56.objective < count.objective) diverged++

                val ledger =
                    WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, forceTier2All = true, targetAware = hard)
                if (ledger.bailedCells.isEmpty()) {
                    assertThat(ledger.maxCellObjective).isNotNull
                    assertThat(ledger.maxCellObjective!!)
                        .describedAs("%s: the certificate must bound the mixed-rune optimum", c.label)
                        .isGreaterThanOrEqualTo(count.objective)
                }
            }
        }
        assertThat(diverged).describedAs("cases where v56's fold misses the mixed optimum (the lock's sensitivity)").isGreaterThanOrEqualTo(10)
    }
}
