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
 * — where no single type fits. The all-or-nothing fold (≤ v56) filled every item with one type, so it missed that optimum and CP-SAT's
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
     * The all-or-nothing fold (≤ v56): 1,320,570. Count model and the fixed fold: 1,328,235 (+0.58 %).
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
        val oldFold = solve(p, reproPool, subs, mixed = false)
        val count = solve(p, reproPool, subs, count = true)
        val fixed = solve(p, reproPool, subs)
        println("MIXED_REPRO oldFold=${oldFold.objective}/${oldFold.isOptimal} count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}")
        for ((label, outcome) in listOf("all-or-nothing fold" to oldFold, "count model" to count, "fixed fold" to fixed)) {
            assertThat(outcome.isOptimal).describedAs("the %s proves its optimum", label).isTrue()
        }
        // The mechanism: the all-or-nothing fill proves a value below the real optimum (a wrong "Optimal proven").
        assertThat(oldFold.objective).describedAs("the all-or-nothing fold (≤ v56) misses the part-filled optimum").isEqualTo(1_320_570L)
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

    /**
     * The same flaw without any sublimation: a required HP row is a threshold too. CRA 50, three 4-socket items of 300
     * fire mastery, an HP row of 850 the skills and items fall just short of: one HP rune tops it up, the fold sold HP
     * runes only by the whole item. oldFold: 117,000 (hard leg); count model and the fixed fold: 117,500 — on the soft leg too
     * (117,000,000,000 vs 117,500,000,000, the soft objective's scale). A GUI-default request (HP 2000) has this shape.
     */
    @Test
    fun `a required HP row is a threshold a part-filled item tops up`() {
        val level = 50
        val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        val pool =
            listOf(ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.BOOTS)
                .mapIndexed { i, type -> item(820_001 + i, type, level, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300)) }
                .groupBy { it.itemType }
        val p =
            params(level, scenario, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.HP, 850)))
                .copy(useSublimations = false)
        for ((hard, expectedV56, expected) in listOf(Triple(true, 117_000L, 117_500L), Triple(false, 117_000_000_000L, 117_500_000_000L))) {
            val oldFold = solve(p, pool, emptyList(), mixed = false, hard = hard)
            val count = solve(p, pool, emptyList(), count = true, hard = hard)
            val fixed = solve(p, pool, emptyList(), hard = hard)
            println("MIXED_HP hard=$hard oldFold=${oldFold.objective}/${oldFold.isOptimal} count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}")
            for (outcome in listOf(oldFold, count, fixed)) assertThat(outcome.isOptimal).isTrue()
            assertThat(oldFold.objective).describedAs("hard=%s: the all-or-nothing fold (≤ v56) misses the topped-up build", hard).isEqualTo(expectedV56)
            assertThat(count.objective).describedAs("hard=%s: the count optimum", hard).isEqualTo(expected)
            assertThat(fixed.objective).describedAs("hard=%s: the fold with count carriers reaches it", hard).isEqualTo(expected)
        }
        // The target-aware certificate (the hard leg's) never reads HP: it bounds the topped-up build.
        val ledger = WakfuBuildSolver.certifyLedgerForTest(p, pool, runes, emptyList(), forceTier2All = true, targetAware = true)
        assertThat(ledger.maxCellObjective!!).isGreaterThanOrEqualTo(117_500L)
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
     * The AP-cell mirror reads a count carrier's types at their full-fill VERTICES — exactly the options oldFold's picks gave
     * the same carriers. So on every pool the certificate with count carriers equals, cell for cell and tier for tier
     * (the capped aux worlds included), the one over oldFold's picks: a vertex read short (one rune instead of a full fill)
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
                val oldFold =
                    WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        targetAware = targetAware,
                        runeMixedCarriers = false
                    )
                assertThat(now.first).describedAs("%s target-aware=%s: exact", c.label, targetAware).isEqualTo(oldFold.first)
                assertThat(now.second).describedAs("%s target-aware=%s: fast", c.label, targetAware).isEqualTo(oldFold.second)
                assertThat(now.third).describedAs("%s target-aware=%s: tier-1.5", c.label, targetAware).isEqualTo(oldFold.third)
                val (_, aux) = WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(c.params, c.pool, runes, c.subs, targetAware = targetAware)
                val (_, auxOld) =
                    WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        targetAware = targetAware,
                        runeMixedCarriers = false
                    )
                assertThat(aux).describedAs("%s target-aware=%s: the capped / aux worlds", c.label, targetAware).isEqualTo(auxOld)
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
     * Seeds 9, 18, 23, 30 and 38 diverged on oldFold (count beat the fold by 0.07–2.86 % in every mode); 1–4 did not. Every
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
                val oldFold = solve(c.params, c.pool, c.subs, mixed = false, hard = hard)
                println(
                    "MIXED_LOCK ${c.label} hard=$hard oldFold=${oldFold.objective}/${oldFold.isOptimal} " +
                        "count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}"
                )
                assertThat(count.isOptimal).describedAs("%s: the count model proves its optimum", c.label).isTrue()
                assertThat(fixed.isOptimal).describedAs("%s: the fold proves its optimum", c.label).isTrue()
                assertThat(fixed.objective).describedAs("%s: the fold's optimum is the count model's", c.label).isEqualTo(count.objective)
                if (oldFold.isOptimal && oldFold.objective < count.objective) diverged++

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
        assertThat(diverged).describedAs("cases where the all-or-nothing fold (≤ v56) misses the mixed optimum (the lock's sensitivity)").isGreaterThanOrEqualTo(10)
    }

    // ---- General fold: required rows, floors, the survivability floor ------------------------------------------------

    /**
     * Small general-fold pools (no sublimation, so no secondary cap masks a row): a few 4-socket items of fire mastery
     * with HP / per-element / "all resistances" / random-element resistance / dodge lines — negative in most pools — and
     * one of five request shapes, each reaching the fold through a different threshold read:
     *  0. an "all resistances" row of 0 (a floor; max-damage keeps it unsplit) with negative lines — the row's key (a);
     *  1. an "all resistances" row with a positive target — the row's key (a);
     *  2. a per-element resistance row of 0 with negative AGGREGATE / random-element lines — the negative source (b);
     *  3. the survivability floor (HP × resistances through `min(EHP, floor)`) beside an "all resistances" 0 row (c);
     *  4. HP and dodge rows with negative HP / dodge lines (the rule as first shipped).
     */
    private fun generalCase(seed: Long): Case {
        val rng = java.util.Random(seed * 7_919L + 3L)
        val shape = (seed % 5).toInt()
        val level = listOf(20, 35, 50, 80, 110)[rng.nextInt(5)]
        val fire = Characteristic.RESISTANCE_ELEMENTARY_FIRE
        val allRes = Characteristic.RESISTANCE_ELEMENTARY
        val negative = shape != 3 && rng.nextInt(4) != 0
        var id = 830_000 + (seed % 1_000).toInt() * 10
        val items =
            listOf(ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.BOOTS, ItemType.AMULET).take(3 + rng.nextInt(2)).map { type ->
                val stats = mutableMapOf<Characteristic, Int>(Characteristic.MASTERY_ELEMENTARY_FIRE to 40 + rng.nextInt(level * 3))
                if (negative && rng.nextInt(3) == 0) {
                    when (shape) {
                        0, 1 -> stats[if (rng.nextBoolean()) allRes else fire] = -(10 + rng.nextInt(120))
                        2 -> stats[if (rng.nextBoolean()) allRes else Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT] = -(10 + rng.nextInt(120))
                        else -> stats[if (rng.nextBoolean()) Characteristic.HP else Characteristic.DODGE] = -(10 + rng.nextInt(level * 4))
                    }
                }
                if (rng.nextInt(3) == 0) stats[Characteristic.HP] = (stats[Characteristic.HP] ?: 0) + rng.nextInt(level * 3)
                id++
                item(id, type, level, stats.filterValues { it != 0 }, listOf(2, 3, 4, 4)[rng.nextInt(4)])
            }
        val distance = TargetStat(Characteristic.MASTERY_DISTANCE, 1)
        val baseHp = 50 + level * 10
        val (targets, scenario) =
            when (shape) {
                0 -> listOf(distance, TargetStat(allRes, 0)) to DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                1 -> listOf(distance, TargetStat(allRes, 5 + rng.nextInt(level * 2))) to DamageScenario(rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                2 -> listOf(distance, TargetStat(fire, 0)) to DamageScenario(rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                3 ->
                    listOf(distance, TargetStat(allRes, 0)) to
                        DamageScenario(
                            rangeBand = RangeBand.DISTANCE,
                            orientation = Orientation.FACE,
                            survivabilityFloor = true,
                            minEffectiveHp = baseHp + rng.nextInt(baseHp)
                        )
                else ->
                    listOf(distance, TargetStat(Characteristic.HP, baseHp + rng.nextInt(level * 6)), TargetStat(Characteristic.DODGE, 0)) to
                        DamageScenario(rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
            }
        return Case(
            "general seed$seed shape$shape lvl$level negative=$negative items=${items.map { it.characteristics }} targets=${targets.map { it.characteristic to it.target }} " +
                "ehp=${scenario.minEffectiveHp}",
            params(level, scenario, targets).copy(useSublimations = false),
            items.groupBy { it.itemType },
            emptyList()
        )
    }

    /**
     * The general fold (a non-damage rune type in the request) against the count model on [generalCase] pools, both legs:
     * the fold with count carriers proves the count optimum, and the all-or-nothing fold misses it often enough to keep the
     * lock sensitive. RED without each of the three general-fold reads (an "all resistances" row keyed on its four
     * elements, a negative aggregate resistance line expanded onto them, the survivability floor).
     */
    @Test
    fun `the general fold proves the count-model optimum under rows, floors and the survivability floor`() {
        var diverged = 0
        val divergedShapes = HashSet<Int>()
        for (seed in 0L until 15L) {
            val c = generalCase(seed)
            for (hard in listOf(false, true)) {
                val count = solve(c.params, c.pool, c.subs, count = true, hard = hard)
                if (!count.hasSolution) continue
                val fixed = solve(c.params, c.pool, c.subs, hard = hard)
                val oldFold = solve(c.params, c.pool, c.subs, mixed = false, hard = hard)
                println(
                    "MIXED_GENERAL ${c.label} hard=$hard oldFold=${oldFold.objective}/${oldFold.isOptimal} " +
                        "count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}"
                )
                assertThat(count.isOptimal).describedAs("%s hard=%s: the count model proves its optimum", c.label, hard).isTrue()
                assertThat(fixed.isOptimal).describedAs("%s hard=%s: the fold proves its optimum", c.label, hard).isTrue()
                assertThat(fixed.objective).describedAs("%s hard=%s: the fold's optimum is the count model's", c.label, hard).isEqualTo(count.objective)
                if (oldFold.isOptimal && oldFold.objective < count.objective) {
                    diverged++
                    divergedShapes += (seed % 5).toInt()
                }
            }
        }
        println("MIXED_GENERAL diverged=$diverged shapes=$divergedShapes")
        assertThat(divergedShapes).describedAs("request shapes where the all-or-nothing fold misses the optimum").contains(0, 1)
        assertThat(diverged).isGreaterThanOrEqualTo(4)
    }

    /**
     * The review's repros of the three general-fold threshold reads the first rule missed (no sublimation; CRA fire /
     * distance / face). Level 200: helmet A (500 fire + a negative resistance line), helmet B (200 fire), chest and boots
     * (300 fire), all level 200 with 4 sockets.
     *  (a) an "all resistances" 0-row (max-damage keeps it unsplit) with −105 FIRE resistance on helmet A — and −160 on
     *      every element: the row is keyed on the four per-element runes;
     *  (b) a FIRE resistance 0-row with −105 on EVERY element (an aggregate line): the negative source is expanded;
     *  (c) the survivability floor (level 20, three 4-socket items of 60 fire, an "all resistances" 0-row, no negative line)
     *      swept over its EHP floor.
     * Each: the all-or-nothing fold proves less than the count model, the fold with count carriers proves the same.
     */
    @Test
    fun `an aggregate resistance row, an aggregate negative line and the survivability floor are thresholds`() {
        val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        val fireRes = Characteristic.RESISTANCE_ELEMENTARY_FIRE
        val allRes = Characteristic.RESISTANCE_ELEMENTARY

        fun level200Pool(negative: Pair<Characteristic, Int>) =
            listOf(
                item(840_001, ItemType.HELMET, 200, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, negative)),
                item(840_002, ItemType.HELMET, 200, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200)),
                item(840_003, ItemType.CHEST_PLATE, 200, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300)),
                item(840_004, ItemType.BOOTS, 200, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            ).groupBy { it.itemType }

        fun check(
            label: String,
            p: WakfuBestBuildParams,
            pool: Map<ItemType, List<Equipment>>,
        ): Boolean {
            var missed = false
            for (hard in listOf(false, true)) {
                val oldFold = solve(p, pool, emptyList(), mixed = false, hard = hard)
                val count = solve(p, pool, emptyList(), count = true, hard = hard)
                val fixed = solve(p, pool, emptyList(), hard = hard)
                println(
                    "MIXED_THRESHOLD $label hard=$hard oldFold=${oldFold.objective}/${oldFold.isOptimal} count=${count.objective}/${count.isOptimal} fixed=${fixed.objective}/${fixed.isOptimal}"
                )
                if (!count.hasSolution) continue
                for (outcome in listOf(oldFold, count, fixed)) assertThat(outcome.isOptimal).describedAs("%s hard=%s", label, hard).isTrue()
                assertThat(fixed.objective).describedAs("%s hard=%s: the fold reaches the count optimum", label, hard).isEqualTo(count.objective)
                if (oldFold.objective < count.objective) missed = true
            }
            return missed
        }
        val distance = TargetStat(Characteristic.MASTERY_DISTANCE, 1)
        val rows = { row: TargetStat -> params(200, scenario, listOf(distance, row)).copy(useSublimations = false) }
        assertThat(check("(a) all-res 0, -105 fire", rows(TargetStat(allRes, 0)), level200Pool(fireRes to -105))).isTrue()
        assertThat(check("(a) all-res 0, -160 all", rows(TargetStat(allRes, 0)), level200Pool(allRes to -160))).isTrue()
        assertThat(check("(b) fire-res 0, -105 all", rows(TargetStat(fireRes, 0)), level200Pool(allRes to -105))).isTrue()

        val small =
            listOf(ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.BOOTS)
                .mapIndexed { i, type -> item(840_101 + i, type, 20, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60)) }
                .groupBy { it.itemType }
        var survivabilityMissed = 0
        for (floor in 420..520 step 20) {
            val p =
                params(20, scenario.copy(survivabilityFloor = true, minEffectiveHp = floor), listOf(distance, TargetStat(allRes, 0)))
                    .copy(useSublimations = false)
            if (check("(c) survivability $floor", p, small)) survivabilityMissed++
        }
        assertThat(survivabilityMissed).describedAs("EHP floors where the all-or-nothing fold misses the optimum").isGreaterThan(0)
    }
}
