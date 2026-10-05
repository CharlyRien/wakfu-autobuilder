package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Locks the FLOOR semantics of a row of target 0 ([TargetStats.floorCharacteristics], [TargetStats.resistanceFloorElements]):
 * on a REQUIRED stat (a resistance, dodge, lock…) it means "never below 0" in every mode — `actual ≥ 0` on the most-masteries
 * and max-damage hard legs, the whole objective halved (once) on their soft legs and in precision. A resistance row of target 0
 * wants no element — it never makes a request multi-element — and its floor reads the element as the game does: the family's
 * random-element rolls are placed over the wanted and the floored elements together (a positive roll can lift a floor; a
 * negative one only lands there when the elements no row names cannot take it), in the solver's model and in the scorers alike.
 * Maximized masteries keep their meaning (no floor; an element of target 0 stays wanted).
 */
class ZeroTargetRowsTest {
    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)

    private val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
    private val maxDamage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE

    private val wind = Characteristic.RESISTANCE_ELEMENTARY_WIND
    private val fire = Characteristic.RESISTANCE_ELEMENTARY_FIRE
    private val water = Characteristic.RESISTANCE_ELEMENTARY_WATER
    private val earth = Characteristic.RESISTANCE_ELEMENTARY_EARTH
    private val allRes = Characteristic.RESISTANCE_ELEMENTARY
    private val oneRandomRes = Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT
    private val twoRandomRes = Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT
    private val distance = Characteristic.MASTERY_DISTANCE
    private val fireMastery = Characteristic.MASTERY_ELEMENTARY_FIRE
    private val res4 = ElementFamily.RESISTANCE.elements
    private val faceFire = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)

    private fun rows(vararg pairs: Pair<Characteristic, Int>) = pairs.map { TargetStat(it.first, it.second) }

    /** The GUI's default request rows (UiState.defaultTargets). */
    private val guiDefaultRows =
        rows(
            Characteristic.ACTION_POINT to 11,
            Characteristic.MOVEMENT_POINT to 4,
            Characteristic.RANGE to 4,
            Characteristic.CRITICAL_HIT to 25,
            distance to 1,
            Characteristic.HP to 2000,
            wind to 0,
            Characteristic.DODGE to 0
        )

    private fun params(
        mode: ScoreComputationMode,
        targets: List<TargetStat>,
        forcedItems: List<String> = emptyList(),
        level: Int = 1,
        scenario: DamageScenario = faceFire,
        useSubs: Boolean = false,
        useRunes: Boolean = false,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 1, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forcedItems,
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = useRunes,
        useSublimations = useSubs,
        damageScenario = scenario
    )

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        level: Int = 1,
        rarity: Rarity = Rarity.LEGENDARY,
        sockets: Int = 0,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    private fun pool(vararg items: Equipment) = items.groupBy { it.itemType }

    /** The stats the scorer of [p]'s mode resolves for [build] — the exact `computeCharacteristicsValues` call it makes. */
    private fun scorerStats(
        p: WakfuBestBuildParams,
        build: BuildCombination,
    ): Map<Characteristic, Int> {
        val ts = p.targetStats
        val base = p.character.baseCharacteristicValues
        val rows = ts.elementRowObjectives(p.scoreComputationMode)
        return when (p.scoreComputationMode) {
            mm ->
                computeCharacteristicsValues(
                    build,
                    base,
                    ts.masteryElementsWanted,
                    ts.resistanceElementsWanted,
                    scoreComputationMode = mm,
                    masteryElementsToMinimize = ts.masteryElementsToMinimize,
                    resistanceElementsToMinimize = if (ts.any { it.characteristic == allRes }) ts.resistanceElementsWanted.keys.toList() else null,
                    elementRows = rows
                )

            precision ->
                computeCharacteristicsValues(
                    build,
                    base,
                    ts.masteryElementsWanted,
                    ts.resistanceElementsWanted,
                    scoreComputationMode = precision,
                    elementRows = rows
                )

            else -> FindMaxDamageScoring.penaltyStats(ts, build, base, p.damageScenario)
        }
    }

    private fun build(vararg items: Equipment) = BuildCombination(items.toList(), CharacterSkills(1))

    private fun score(
        p: WakfuBestBuildParams,
        vararg items: Equipment,
    ): BigDecimal {
        val b = build(*items)
        return when (p.scoreComputationMode) {
            precision -> FindClosestBuildFromInputScoring.computeScore(p.targetStats, b, p.character.baseCharacteristicValues)
            mm -> FindMostMasteriesFromInputScoring.computeScore(p.targetStats, b, p.character.baseCharacteristicValues)
            else -> FindMaxDamageScoring.computeScore(p.targetStats, b, p.character.baseCharacteristicValues, p.damageScenario)
        }
    }

    private fun solve(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        hard: Boolean,
        pinned: Set<Int>? = null,
    ) = WakfuBuildSolver.elementRowSolveForTest(p, pool, tuning, hardConstraints = hard, pinnedEquipmentIds = pinned, pinSkillsToZero = true)

    private fun picked(solve: WakfuBuildSolver.ElementRowSolve) =
        solve.build!!
            .equipments
            .map { it.equipmentId }
            .toSet()

    // ---- What a row of target 0 asks for (TargetStats) -----------------------------------------------------------------

    @Test
    fun `the GUI default beside a fire resistance target is a request on one element - air is a floor on its fold, not a wanted element`() {
        val ts = TargetStats(guiDefaultRows + TargetStat(fire, 100))
        assertThat(ts.resistanceElementsWanted).isEqualTo(mapOf(fire to 100))
        assertThat(ts.resistanceFloorElements).containsExactly(wind)
        assertThat(ts.floorCharacteristics).containsExactly(Characteristic.DODGE)
        assertThat(ts.hasFloors).isTrue()
        assertThat(ts.needsItemPrefilter).describedAs("one wanted resistance: the full pool, no heuristic prefilter").isFalse()
        for (mode in ScoreComputationMode.entries) {
            // The floor joins the family's fold — a roll can land on air — in every mode.
            assertThat(ts.readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).describedAs("$mode").isTrue()
            assertThat(ts.elementRowObjectives(mode)!!.resistance!!.elements).describedAs("$mode").containsExactly(fire, wind)
        }
        assertThat(ts.freeSinks(ElementFamily.RESISTANCE)).describedAs("water and earth take what no read element can").isEqualTo(2)
        // The GUI default alone: no wanted resistance at all, the same two floors, a fold over air alone.
        val default = TargetStats(guiDefaultRows)
        assertThat(default.resistanceElementsWanted).isEmpty()
        assertThat(default.resistanceFloorElements).containsExactly(wind)
        assertThat(default.floorCharacteristics).containsExactly(Characteristic.DODGE)
        assertThat(default.foldElements(ElementFamily.RESISTANCE)).containsExactly(wind)
        // A second resistance WITH a target still makes it multi-element.
        assertThat(TargetStats(guiDefaultRows + TargetStat(fire, 100) + TargetStat(water, 100)).needsItemPrefilter).isTrue()
    }

    @Test
    fun `all resistances 0 is a floor on every element no other row wants`() {
        val alone = TargetStats(rows(distance to 1, allRes to 0))
        assertThat(alone.resistanceElementsWanted).isEmpty()
        assertThat(alone.resistanceFloorElements).containsExactly(water, fire, earth, wind)
        assertThat(alone.needsItemPrefilter).isFalse()
        assertThat(alone.freeSinks(ElementFamily.RESISTANCE)).describedAs("every element is read: a roll has nowhere else to go").isEqualTo(0)

        val withFire = TargetStats(rows(allRes to 0, fire to 100))
        assertThat(withFire.resistanceElementsWanted).isEqualTo(mapOf(fire to 100))
        assertThat(withFire.resistanceFloorElements).describedAs("fire's own row asks for more").containsExactly(water, earth, wind)
        assertThat(withFire.needsItemPrefilter).isFalse()

        // An aggregate WITH a target wants all four: a 0-valued element row beside it is left to it (no floor).
        val withAggregate = TargetStats(rows(allRes to 100, wind to 0))
        assertThat(withAggregate.resistanceElementsWanted.keys).containsExactlyInAnyOrder(water, fire, earth, wind)
        assertThat(withAggregate.resistanceFloorElements).isEmpty()
        assertThat(withAggregate.needsItemPrefilter).isTrue()
    }

    @Test
    fun `a row of target 0 on a stat another row targets is left to that row`() {
        val dodge = TargetStats(listOf(TargetStat(Characteristic.DODGE, 0), TargetStat(Characteristic.DODGE, 50)))
        assertThat(dodge.floorCharacteristics).isEmpty()
        val air = TargetStats(listOf(TargetStat(wind, 0), TargetStat(wind, 80)))
        assertThat(air.resistanceElementsWanted).isEqualTo(mapOf(wind to 80))
        assertThat(air.resistanceFloorElements).isEmpty()
    }

    @Test
    fun `mastery rows of target 0 keep their meaning - wanted elements, no floor`() {
        val fireZero = TargetStats(rows(fireMastery to 0))
        assertThat(fireZero.masteryElementsWanted).isEqualTo(mapOf(fireMastery to 0))
        assertThat(fireZero.masteryElementsToMinimize).describedAs("most-masteries maximizes it whatever its target").containsExactly(fireMastery)
        assertThat(fireZero.hasFloors).isFalse()
        assertThat(fireZero.zeroMasteries).describedAs("precision's halving reads it").containsExactly(fireMastery)
        assertThat(
            TargetStats(rows(fireMastery to 1, Characteristic.MASTERY_ELEMENTARY_WATER to 0)).needsItemPrefilter
        ).describedAs("two wanted mastery elements, as before").isTrue()
        assertThat(
            TargetStats(rows(Characteristic.MASTERY_ELEMENTARY to 50, Characteristic.MASTERY_ELEMENTARY_WATER to 0)).zeroMasteries
        ).describedAs("covered by the aggregate").isEmpty()
        assertThat(TargetStats(rows(distance to 0)).zeroMasteries).containsExactly(distance)
        assertThat(TargetStats(rows(distance to 0)).floorCharacteristics).isEmpty()
    }

    @Test
    fun `a save of a request an older version pre-filtered is recognized - the old reading counted a resistance row of target 0 as wanted`() {
        // Single-element now, multi-element (pre-filtered, so never truly proven) under the old reading.
        assertThat(TargetStats(guiDefaultRows + TargetStat(fire, 100)).legacyNeedsItemPrefilter).isTrue()
        assertThat(TargetStats(rows(distance to 1, allRes to 0)).legacyNeedsItemPrefilter).isTrue()
        // The GUI default alone wanted only air: one element, then as now.
        assertThat(TargetStats(guiDefaultRows).legacyNeedsItemPrefilter).isFalse()
        assertThat(TargetStats(rows(distance to 1, fire to 100)).legacyNeedsItemPrefilter).isFalse()
        // Pre-filtered now is pre-filtered then.
        assertThat(TargetStats(rows(fire to 100, water to 100)).legacyNeedsItemPrefilter).isTrue()
    }

    // ---- The scorer's read of a floor ------------------------------------------------------------------------------------

    @Test
    fun `a resistance floor reads its own lines, the generic ones and the random roll the player puts there`() {
        val p = params(mm, rows(distance to 1, allRes to 0, fire to 50))
        val boots = item(2, ItemType.BOOTS, mapOf(oneRandomRes to 30))
        // Fire 35 before the roll, 15 short of its row; earth −15, a broken floor. The roll goes where it pays most: on fire it
        // meets the row and leaves the floor broken (the score halved), on earth it keeps the floor and leaves fire at 70 % (the
        // shortfall's (100 / 70)⁶ ≈ 8.5 divisor) — fire wins.
        val short = item(1, ItemType.AMULET, mapOf(wind to -20, allRes to 25, earth to -40, fire to 10))
        val shortStats = scorerStats(p, build(short, boots))
        assertThat(shortStats[wind]).describedAs("own −20 + generic 25").isEqualTo(5)
        assertThat(shortStats[water]).describedAs("generic only").isEqualTo(25)
        assertThat(shortStats[earth]).isEqualTo(-15)
        assertThat(shortStats[fire]).describedAs("own 10 + generic 25 + the roll 30").isEqualTo(65)
        assertThat(shortStats[allRes]).describedAs("all four read: their minimum").isEqualTo(-15)
        assertThat(p.targetStats.floorBroken(shortStats)).isTrue()
        // Fire already met: the roll lifts earth back to 15 — the floor holds and nothing is short.
        val met = item(1, ItemType.AMULET, mapOf(wind to -20, allRes to 25, earth to -40, fire to 40))
        val metStats = scorerStats(p, build(met, boots))
        assertThat(metStats[fire]).isEqualTo(65)
        assertThat(metStats[earth]).describedAs("−15 + the roll 30").isEqualTo(15)
        assertThat(metStats[allRes]).isEqualTo(5)
        assertThat(p.targetStats.floorBroken(metStats)).isFalse()
        assertThat(score(p, met, boots)).isEqualByComparingTo(score(p, item(1, ItemType.AMULET, mapOf(fire to 100))))
    }

    // ---- Most-masteries ------------------------------------------------------------------------------------------------

    @Test
    fun `most-masteries hard leg - a floor forbids a negative resistance, generic lines and a random roll lift it`() {
        val p = params(mm, rows(distance to 1, wind to 0))
        val negative = item(1, ItemType.AMULET, mapOf(distance to 100, wind to -20))
        val plain = item(2, ItemType.AMULET, mapOf(distance to 60))
        val compensated = item(3, ItemType.AMULET, mapOf(distance to 80, wind to -20, allRes to 25))
        val roll = item(4, ItemType.BOOTS, mapOf(oneRandomRes to 50))
        val pool = pool(negative, plain, compensated, roll)

        val hard = solve(p, pool, hard = true)
        assertThat(hard.isOptimal).isTrue()
        assertThat(picked(hard)).describedAs("the 100-distance amulet, its −20 air lifted by the roll the player puts on air").containsExactlyInAnyOrder(1, 4)
        assertThat(hard.modelFloorValues).isEqualTo(mapOf(wind to 30L))
        assertThat(scorerStats(p, build(negative, roll))[wind]).isEqualTo(30)
        assertThat(solve(p, pool, hard = true, pinned = setOf(1)).status).describedAs("no roll, nothing lifts air").isEqualTo(CpSolverStatus.INFEASIBLE)
        val pinned = solve(p, pool, hard = true, pinned = setOf(3))
        assertThat(pinned.modelFloorValues).describedAs("−20 + 25 on all elements").isEqualTo(mapOf(wind to 5L))
        assertThat(scorerStats(p, build(compensated))[wind]).isEqualTo(5)
    }

    @Test
    fun `a roll on two random elements lifts a floor beside a target - the review's repro`() {
        // Distance 100 with air −10 and "+20 resistance on 2 random elements": in game the roll lands on air (and any other element),
        // air reads +10. Read without the rolls the floor saw −10 and the hard leg PROVED the weaker item optimal.
        val strong = item(1, ItemType.AMULET, mapOf(distance to 100, wind to -10, twoRandomRes to 20))
        val weak = item(2, ItemType.AMULET, mapOf(distance to 60))
        val pool = pool(strong, weak)
        val airOnly = solve(params(mm, rows(distance to 1, wind to 0)), pool, hard = true)
        assertThat(airOnly.isOptimal).isTrue()
        assertThat(picked(airOnly)).containsExactly(1)
        assertThat(airOnly.modelFloorValues).isEqualTo(mapOf(wind to 10L))
        // Air 0 + fire 10: the two-element roll covers fire AND air (fire 20, air +10) — every row met; it used to be INFEASIBLE.
        val p = params(mm, rows(distance to 1, wind to 0, fire to 10))
        val withFire = solve(p, pool, hard = true)
        assertThat(withFire.isOptimal).isTrue()
        assertThat(picked(withFire)).containsExactly(1)
        val stats = scorerStats(p, build(strong))
        assertThat(stats[fire]).isEqualTo(20)
        assertThat(stats[wind]).isEqualTo(10)
    }

    @Test
    fun `a negative roll lands on a floor only when nothing else can take it - the review's repro`() {
        // "−30 on 1 random element" beside "all resistances 0": every element is a floor, so the −30 must break one. Read without
        // the rolls the four floors held and the hard leg PROVED the malus item optimal.
        val malus = item(1, ItemType.AMULET, mapOf(distance to 100, oneRandomRes to -30))
        val clean = item(2, ItemType.AMULET, mapOf(distance to 60))
        val pool = pool(malus, clean)
        val allFloors = params(mm, rows(distance to 1, allRes to 0))
        val hard = solve(allFloors, pool, hard = true)
        assertThat(hard.isOptimal).isTrue()
        assertThat(picked(hard)).containsExactly(2)
        assertThat(solve(allFloors, pool, hard = true, pinned = setOf(1)).status).isEqualTo(CpSolverStatus.INFEASIBLE)
        assertThat(allFloors.targetStats.floorBroken(scorerStats(allFloors, build(malus)))).isTrue()
        // Air 0 alone: the player puts the −30 on water, fire or earth — the floor holds.
        val airOnly = params(mm, rows(distance to 1, wind to 0))
        assertThat(picked(solve(airOnly, pool, hard = true))).containsExactly(1)
        assertThat(scorerStats(airOnly, build(malus))[wind]).isEqualTo(0)
    }

    @Test
    fun `most-masteries hard leg - a one-element roll meets the target or lifts the floor, never both`() {
        val p = params(mm, rows(distance to 1, fire to 300, wind to 0))
        val roll = item(2, ItemType.BOOTS, mapOf(oneRandomRes to 30))
        // Fire 310 with "−20 on all elements": met without the roll, which then lifts air to 10.
        val met = item(1, ItemType.AMULET, mapOf(distance to 10, fire to 330, allRes to -20))
        val feasible = solve(p, pool(met, roll), hard = true, pinned = setOf(1, 2))
        assertThat(feasible.hasSolution).isTrue()
        assertThat(feasible.modelElementValues).isEqualTo(mapOf(fire to 310L, wind to 10L))
        val stats = scorerStats(p, build(met, roll))
        assertThat(stats[fire]).isEqualTo(310)
        assertThat(stats[wind]).isEqualTo(10)
        // Fire 285: the roll on fire meets it but leaves air at −20; on air, fire stays short. No build meets both.
        val short = item(1, ItemType.AMULET, mapOf(distance to 10, fire to 305, allRes to -20))
        assertThat(solve(p, pool(short, roll), hard = true, pinned = setOf(1, 2)).status).isEqualTo(CpSolverStatus.INFEASIBLE)
    }

    @Test
    fun `most-masteries soft leg - a broken floor halves the objective and the score, once`() {
        val p = params(mm, rows(distance to 1, Characteristic.ACTION_POINT to 7, wind to 0, Characteristic.DODGE to 0))
        val broken = item(1, ItemType.AMULET, mapOf(distance to 100, Characteristic.ACTION_POINT to 1, wind to -20, Characteristic.DODGE to -3))
        val clean = item(2, ItemType.AMULET, mapOf(distance to 100, Characteristic.ACTION_POINT to 1))
        val pool = pool(broken, clean)

        val softBroken = solve(p, pool, hard = false, pinned = setOf(1))
        val softClean = solve(p, pool, hard = false, pinned = setOf(2))
        assertThat(softBroken.modelHalved).isTrue()
        assertThat(softClean.modelHalved).isFalse()
        assertThat(softBroken.modelFloorValues).isEqualTo(mapOf(wind to -20L, Characteristic.DODGE to -3L))
        // core × multiplier, then halved (no overshoot: AP is met exactly).
        assertThat(softBroken.objective!! * 2).isEqualTo(softClean.objective)
        assertThat(score(p, broken)).isEqualByComparingTo(BigDecimal(50))
        assertThat(score(p, clean)).isEqualByComparingTo(BigDecimal(100))

        // The orchestration: forced to wear the broken amulet, the hard leg is INFEASIBLE and the soft leg returns it, halved.
        val forced = p.copy(forcedItems = listOf("item1"))
        var termination: WakfuBuildSolver.SolveOutcome? = null
        val hardFlow =
            runBlocking {
                WakfuBuildSolver.optimize(forced, pool, emptyList(), emptyList(), tuning, hardConstraints = true, onTermination = { termination = it }).toList()
            }
        assertThat(hardFlow).isEmpty()
        assertThat(termination?.status).isEqualTo(CpSolverStatus.INFEASIBLE)
        val soft = runBlocking { WakfuBuildSolver.optimize(forced, pool, tuning).toList().last() }
        assertThat(soft.individual.equipments.map { it.equipmentId }).containsExactly(1)
        assertThat(soft.matchPercentage).isEqualByComparingTo(BigDecimal(50))
    }

    @Test
    fun `most-masteries - a mastery row of target 0 is still maximized and floors nothing`() {
        val p = params(mm, rows(fireMastery to 0))
        val strong = item(1, ItemType.AMULET, mapOf(fireMastery to 100, Characteristic.MASTERY_ELEMENTARY_WATER to -30))
        val weak = item(2, ItemType.AMULET, mapOf(fireMastery to 50))
        val hard = solve(p, pool(strong, weak), hard = true)
        assertThat(picked(hard)).containsExactly(1)
        assertThat(hard.modelFloorValues).isEmpty()
    }

    // ---- Max-damage ----------------------------------------------------------------------------------------------------

    @Test
    fun `max-damage hard leg - a floor forbids the stronger negative build, the soft leg halves it`() {
        val p = params(maxDamage, rows(wind to 0, Characteristic.DODGE to 0))
        val strong = item(1, ItemType.AMULET, mapOf(fireMastery to 300, wind to -20))
        val weak = item(2, ItemType.AMULET, mapOf(fireMastery to 120))
        val clumsy = item(3, ItemType.BOOTS, mapOf(fireMastery to 200, Characteristic.DODGE to -1))
        val pool = pool(strong, weak, clumsy)

        val hard = solve(p, pool, hard = true)
        assertThat(hard.isOptimal).isTrue()
        assertThat(picked(hard)).containsExactly(2)
        assertThat(solve(p, pool, hard = true, pinned = setOf(1)).status).isEqualTo(CpSolverStatus.INFEASIBLE)
        assertThat(solve(p, pool, hard = true, pinned = setOf(2, 3)).status).isEqualTo(CpSolverStatus.INFEASIBLE)

        for ((ids, broken) in listOf(setOf(1) to true, setOf(2) to false, setOf(2, 3) to true)) {
            val soft = solve(p, pool, hard = false, pinned = ids)
            assertThat(soft.modelHalved).describedAs("$ids").isEqualTo(broken)
            val stats = scorerStats(p, BuildCombination(pool.values.flatten().filter { it.equipmentId in ids }, CharacterSkills(1)))
            assertThat(FindMaxDamageScoring.requiredConstraintPenaltyFactor(p.targetStats, stats))
                .describedAs("$ids")
                .isEqualByComparingTo(if (broken) BigDecimal(2) else BigDecimal.ONE)
        }

        // A request whose only required rows are floors now runs the hard leg too (it used to skip straight to the plain solve).
        val results = runBlocking { MaxDamageSearch.optimizeHardThenSoft(p, pool, emptyList(), emptyList(), tuning).toList() }
        val last = results.last()
        assertThat(last.maxDamageHardConstraintsMet).isTrue()
        assertThat(last.individual.equipments.map { it.equipmentId }).containsExactly(2)
    }

    @Test
    fun `max-damage - a scenario-gated sublimation that keeps a floor counts in every reader, not only in the solver`() {
        // The review's repro: a berserk scenario, "dodge 0", a ring with fire mastery 300 and dodge −100, a 4-socket chest and
        // "Esquive Berserk III" (+150 dodge, berserk only). The solver counted the gated dodge; the search's ranking (sequencedScore),
        // its proof gate and a saved build's re-score read the stats without the scenario, saw dodge −100 and halved the build.
        val sub = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Esquive Berserk III" }
        val ring = item(9_001, ItemType.RING, mapOf(fireMastery to 300, Characteristic.DODGE to -100), level = 110)
        val carrier = item(9_002, ItemType.CHEST_PLATE, mapOf(fireMastery to 50), level = 110, sockets = 4)
        val berserk = faceFire.copy(berserk = true)
        val p = params(maxDamage, rows(Characteristic.DODGE to 0), level = 110, scenario = berserk, useSubs = true)
        val hard = WakfuBuildSolver.elementRowSolveForTest(p, pool(ring, carrier), tuning, hardConstraints = true, sublimations = listOf(sub), pinSkillsToZero = true)
        assertThat(hard.isOptimal).isTrue()
        val b = hard.build!!
        assertThat(
            b.sublimations.values
                .flatten()
                .map { it.name.fr }
        ).contains("Esquive Berserk III")
        assertThat(hard.modelFloorValues[Characteristic.DODGE]!!).isGreaterThanOrEqualTo(0L)
        val stats = FindMaxDamageScoring.penaltyStats(p.targetStats, b, p.character.baseCharacteristicValues, p.damageScenario)
        assertThat(stats[Characteristic.DODGE]!!).isEqualTo(hard.modelFloorValues[Characteristic.DODGE]!!.toInt())
        assertThat(p.targetStats.floorBroken(stats)).isFalse()
        // Every max-damage reader: no halving.
        val noFloor = p.copy(targetStats = TargetStats(emptyList()))
        assertThat(MaxDamageSearch.sequencedScore(p, b)).isEqualByComparingTo(MaxDamageSearch.sequencedScore(noFloor, b))
        assertThat(WakfuBestBuildFinderAlgorithm.rescore(p, b)).isEqualByComparingTo(MaxDamageSearch.sequencedScore(noFloor, b))
        assertThat(FindMaxDamageScoring.computeScore(p.targetStats, b, p.character.baseCharacteristicValues, berserk))
            .isEqualByComparingTo(FindMaxDamageScoring.computeScore(noFloor.targetStats, b, p.character.baseCharacteristicValues, berserk))
        // Without the berserk scenario the sub's dodge does not apply: the floor breaks — in the scorer as in the solver.
        val face = p.copy(damageScenario = faceFire)
        val faceStats = FindMaxDamageScoring.penaltyStats(face.targetStats, b, face.character.baseCharacteristicValues, face.damageScenario)
        assertThat(face.targetStats.floorBroken(faceStats)).isTrue()
    }

    // ---- Precision -------------------------------------------------------------------------------------------------------

    @Test
    fun `precision - a floor halves on its fold, in the solver and the score alike, and a free roll lifts it`() {
        val p = params(precision, rows(distance to 50, wind to 0))
        val halved = item(1, ItemType.AMULET, mapOf(distance to 50, wind to -20, allRes to 10))
        val whole = item(2, ItemType.AMULET, mapOf(distance to 50, wind to -20, allRes to 25))
        val roll = item(3, ItemType.BOOTS, mapOf(oneRandomRes to 30))
        val pool = pool(halved, whole, roll)
        for ((ids, expectHalved) in listOf(setOf(1) to true, setOf(2) to false, setOf(1, 3) to false)) {
            val pinned = solve(p, pool, hard = false, pinned = ids)
            val stats = scorerStats(p, BuildCombination(pool.values.flatten().filter { it.equipmentId in ids }, CharacterSkills(1)))
            assertThat(pinned.modelHalved).describedAs("$ids").isEqualTo(expectHalved)
            assertThat(p.targetStats.precisionHalves(stats)).describedAs("$ids").isEqualTo(expectHalved)
            assertThat(precisionModelObjective(p.targetStats, stats)).describedAs("$ids").isEqualTo(pinned.objective)
        }
        assertThat(score(p, halved)).isEqualByComparingTo(BigDecimal(50))
        assertThat(score(p, whole)).isEqualByComparingTo(BigDecimal(100))
        assertThat(score(p, halved, roll)).describedAs("the roll lands on air: −10 + 30").isEqualByComparingTo(BigDecimal(100))
    }

    @Test
    fun `precision - a mastery row of target 0 halves on its element's fold, generic lines and rolls included`() {
        val p = params(precision, rows(distance to 20, Characteristic.MASTERY_ELEMENTARY_WATER to 0))
        // Own water −10, "+15 on all elements": the fold reads 5, so nothing halves — the solver used to read −10 here.
        val amulet = item(1, ItemType.AMULET, mapOf(distance to 20, Characteristic.MASTERY_ELEMENTARY_WATER to -10, Characteristic.MASTERY_ELEMENTARY to 15))
        val pinned = solve(p, pool(amulet), hard = false, pinned = setOf(1))
        val stats = scorerStats(p, build(amulet))
        assertThat(stats[Characteristic.MASTERY_ELEMENTARY_WATER]).isEqualTo(5)
        assertThat(pinned.modelHalved).isFalse()
        assertThat(precisionModelObjective(p.targetStats, stats)).isEqualTo(pinned.objective)
        assertThat(score(p, amulet)).isEqualByComparingTo(BigDecimal(100))
    }

    // ---- The aggregate and non-elemental floors ------------------------------------------------------------------------

    @Test
    fun `all resistances 0 holds each element at 0 or more, and dodge 0 holds dodge`() {
        val p = params(mm, rows(distance to 1, allRes to 0, Characteristic.DODGE to 0))
        val earthMalus = item(1, ItemType.AMULET, mapOf(distance to 100, earth to -5))
        val dodgeMalus = item(2, ItemType.AMULET, mapOf(distance to 90, Characteristic.DODGE to -1))
        val clean = item(3, ItemType.AMULET, mapOf(distance to 50))
        val hard = solve(p, pool(earthMalus, dodgeMalus, clean), hard = true)
        assertThat(picked(hard)).containsExactly(3)
        val stats = scorerStats(p, build(earthMalus))
        assertThat(stats[allRes]).isEqualTo(-5)
        assertThat(p.targetStats.floorBroken(stats)).isTrue()
    }

    @Test
    fun `a floor no build of the pool can break adds nothing to the model`() {
        val p = params(mm, rows(distance to 1, Characteristic.HP to 0, wind to 0))
        val a = item(1, ItemType.AMULET, mapOf(distance to 100, wind to 3, oneRandomRes to 10))
        val hard = solve(p, pool(a), hard = true)
        assertThat(hard.isOptimal).isTrue()
        assertThat(hard.modelFloorValues).describedAs("HP and air never go below 0 on this pool").isEmpty()
    }

    // ---- The E8 construct --------------------------------------------------------------------------------------------

    /** A CRA fire max-damage request at level 50 (the certified tiny-pool shape of E8ConstructGateTest) carrying [rows]. */
    private fun fireParams(rows: List<TargetStat> = emptyList()) =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 50, 0, CharacterSkills(50)),
            targetStats = TargetStats(rows),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = maxDamage,
            useRunes = false,
            useSublimations = false,
            damageScenario = faceFire
        )

    @Test
    fun `the E8 construct meets the floors - it never crowns a build the hard leg forbids`(): Unit =
        runBlocking {
            // The best amulet by far carries −500 air resistance.
            // (−500: more than the Intelligence resistance points can make up.)
            val strong = item(1, ItemType.AMULET, mapOf(fireMastery to 800, wind to -500))
            val clean = item(2, ItemType.AMULET, mapOf(fireMastery to 300))
            val belt = item(3, ItemType.BELT, mapOf(fireMastery to 500, Characteristic.ACTION_POINT to 1))
            val ring = item(4, ItemType.RING, mapOf(fireMastery to 200))
            val pool = pool(strong, clean, belt, ring)

            val free = WakfuBuildSolver.dpConstructProvenOptimum(fireParams(), pool)
            assertThat(free).describedAs("the free request constructs its proven optimum").isNotNull
            assertThat(free!!.individual.equipments.map { it.equipmentId }).contains(1)

            val floored = fireParams(rows(wind to 0))
            assertThat(isFreeMaxDamageShape(floored.targetStats)).describedAs("a floor stays admitted").isTrue()
            val hardTuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            val flooredOptimum = WakfuBuildSolver.optimize(floored, pool, emptyList(), emptyList(), hardTuning, hardConstraints = true).toList().last()
            assertThat(flooredOptimum.isOptimal).isTrue()
            assertThat(flooredOptimum.individual.equipments.map { it.equipmentId }).doesNotContain(1)
            assertThat(flooredOptimum.maxDamageRawProxy!!).isLessThan(free.maxDamageRawProxy!!)
            // The ledger ignores the floor — its bound is the free optimum's, which no floored build reaches: the construct must
            // come back empty-handed rather than re-solve without the floor and crown the −500 air amulet. (Its full-pool
            // fallback, futile behind a binding floor, is skipped.)
            assertThat(WakfuBuildSolver.dpConstructProvenOptimum(floored, pool)).isNull()

            // A floor the free optimum already meets: constructed like the free request, and flagged as meeting its floors.
            val met = WakfuBuildSolver.dpConstructProvenOptimum(fireParams(rows(Characteristic.DODGE to 0)), pool)
            assertThat(met).isNotNull
            assertThat(met!!.isOptimal).isTrue()
            assertThat(met.maxDamageHardConstraintsMet).isTrue()
            assertThat(met.maxDamageRawProxy).isEqualTo(free.maxDamageRawProxy)
        }

    // ---- Real data: the GUI's default request beside a fire resistance target -----------------------------------------

    private fun guiParams(
        mode: ScoreComputationMode,
        rows: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, 110, 0),
        targetStats = TargetStats(rows),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode
    )

    /** The hard leg of [p] on its production pool (domination on), deterministic; [firstOnly] stops at the first solution. */
    private fun realHardLeg(
        p: WakfuBestBuildParams,
        firstOnly: Boolean,
    ): Pair<WakfuBuildSolver.SolveOutcome?, SolverResult<BuildCombination>?> {
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
        val tuning =
            WakfuBuildSolver.SolverTuning(
                numSearchWorkers = 1,
                randomSeed = 1,
                interleaveSearch = true,
                maxDeterministicTime = 120.0,
                applyDominationOverride = true,
                stopAtFirstSolution = firstOnly
            )
        var outcome: WakfuBuildSolver.SolveOutcome? = null
        val results =
            runBlocking {
                WakfuBuildSolver
                    .optimize(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.activeSublimations(p),
                        tuning,
                        hardConstraints = true,
                        onTermination = { outcome = it }
                    ).toList()
            }
        return outcome to results.lastOrNull { it.progressPercentage == 100 }
    }

    @Test
    fun `real data - the GUI default plus a fire resistance target searches the full pool and its hard leg keeps air and dodge at 0 or more`() {
        val p = guiParams(mm, guiDefaultRows + TargetStat(fire, 100))
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
        val (prefiltered, size) = WakfuBuildSolver.gatedPoolSizeForTest(p, pool)
        assertThat(prefiltered).describedAs("no heuristic item prefilter any more").isFalse()
        assertThat(size)
            .describedAs("the full eligible pool, as for the default request alone")
            .isEqualTo(WakfuBuildSolver.gatedPoolSizeForTest(guiParams(mm, guiDefaultRows), pool).second)
        val (_, first) = realHardLeg(p, firstOnly = true)
        val stats = scorerStats(p, checkNotNull(first).individual)
        assertThat(stats[fire]!!).isGreaterThanOrEqualTo(100)
        assertThat(stats[wind]!!).describedAs("air, on the fold").isGreaterThanOrEqualTo(0)
        assertThat(stats[Characteristic.DODGE] ?: 0).isGreaterThanOrEqualTo(0)
        assertThat(p.targetStats.floorBroken(stats)).isFalse()
    }

    @Test
    @Tag("slow")
    fun `real data - the GUI default plus a fire resistance target is PROVEN optimal on the full pool, in most-masteries`() {
        // A full-pool OPTIMAL — the prefiltered search it replaces could never earn the badge.
        val p = guiParams(mm, guiDefaultRows + TargetStat(fire, 100))
        val (outcome, last) = realHardLeg(p, firstOnly = false)
        assertThat(outcome?.status).isEqualTo(CpSolverStatus.OPTIMAL)
        val result = checkNotNull(last)
        assertThat(result.isOptimal).describedAs("a full-pool OPTIMAL is a global proof").isTrue()
        assertThat(p.targetStats.floorBroken(scorerStats(p, result.individual))).isFalse()
        assertThat(WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, result.copy(mostMasteriesHardConstraintsMet = true)))
            .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
    }

    // ---- The model ⇔ scorer contract, per solved build -----------------------------------------------------------------

    /** Every required row with a target > 0 met by the scorer's [stats] (per-element rows on their element, the aggregate on the min). */
    private fun scorerMeetsTargets(
        p: WakfuBestBuildParams,
        stats: Map<Characteristic, Int>,
    ): Boolean = p.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() && it.target > 0 }.all { (stats[it.characteristic] ?: 0) >= it.target }

    /** [stats] with the model's placement of the resistance fold ([modelElementValues]) in place of the scorer's. */
    private fun withModelPlacement(
        stats: Map<Characteristic, Int>,
        modelElementValues: Map<Characteristic, Long>,
    ): Map<Characteristic, Int> {
        val out = stats.toMutableMap()
        for ((element, value) in modelElementValues) if (element in res4) out[element] = value.toInt()
        val placed = res4.filter { it in modelElementValues }
        if (placed.isNotEmpty()) out[allRes] = placed.minOf { out.getValue(it) }
        return out
    }

    /**
     * Checks one solve of [p] (build pinned or not) against the scorers, and returns what failed — the contract the fold must keep:
     *  - a floor the model does not read is one no build of the pool can break;
     *  - a non-elemental floor reads the same value in both;
     *  - hard legs: a solution keeps every floor and meets every target in the scorer's read too;
     *  - soft legs: the scorer's placement is never worse than the model's by what the score divides by (the solver weighs the
     *    same trade on its bucketed multiplier, the score on the continuous one);
     *  - precision: the scorer's placement reaches the solver's own objective exactly.
     */
    private fun contractViolations(
        p: WakfuBestBuildParams,
        solve: WakfuBuildSolver.ElementRowSolve,
        hard: Boolean,
    ): List<String> {
        val build = solve.build ?: return emptyList()
        val ts = p.targetStats
        val stats = scorerStats(p, build)
        val violations = mutableListOf<String>()
        val floors = (ts.floorCharacteristics + ts.resistanceFloorElements).associateWith { (stats[it] ?: 0).toLong() }
        for ((floor, value) in floors) if (floor !in solve.modelFloorValues && value < 0L) violations += "pruned floor $floor reads $value"
        for (floor in ts.floorCharacteristics) {
            val model = solve.modelFloorValues[floor] ?: continue
            if (floors[floor] != model) violations += "floor $floor: model $model, scorer ${floors[floor]}"
        }
        when {
            hard -> {
                if (ts.floorBroken(stats)) violations += "hard leg, scorer breaks a floor: $floors"
                if (!scorerMeetsTargets(p, stats)) violations += "hard leg, scorer misses a target"
                if (solve.modelFloorValues.values.any { it < 0L }) violations += "hard leg, model floor below 0: ${solve.modelFloorValues}"
            }

            p.scoreComputationMode == precision -> {
                val objective = precisionModelObjective(ts, stats)
                if (objective != solve.objective) violations += "precision objective: model ${solve.objective}, scorer placement $objective"
            }

            else -> {
                val scorer = ts.requiredPenaltyFactor(stats)
                val model = ts.requiredPenaltyFactor(withModelPlacement(stats, solve.modelElementValues))
                if (scorer > model) violations += "soft leg: the scorer's placement divides by $scorer, the model's by $model"
            }
        }
        return violations
    }

    // ---- Seeded fuzz: per build, the model and the scorers agree on every floor -----------------------------------------

    private class FuzzCase(
        val p: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
    )

    private fun fuzzCase(
        random: Random,
        mode: ScoreComputationMode,
    ): FuzzCase {
        val rows = mutableListOf<TargetStat>()
        val elements = res4.shuffled(random)
        // 0..2 resistance rows with a target (wanted), then floors on (some of) the others — or the aggregate floor.
        val wanted = random.nextInt(3)
        elements.take(wanted).forEach { rows += TargetStat(it, 10 + random.nextInt(40), 1 + random.nextInt(5)) }
        if (random.nextInt(4) == 0) {
            rows += TargetStat(allRes, 0)
        } else {
            elements.drop(wanted).filter { random.nextInt(2) == 0 }.forEach { rows += TargetStat(it, 0) }
        }
        if (random.nextInt(2) == 0) rows += TargetStat(Characteristic.DODGE, 0)
        if (random.nextInt(3) == 0) rows += TargetStat(Characteristic.LOCK, 0)
        // Precision's score needs a row with a target (and the occasional mastery of target 0).
        if (mode == precision) {
            rows += TargetStat(distance, 20 + random.nextInt(40))
            if (random.nextInt(3) == 0) rows += TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 0)
        }
        if (mode == mm) rows += TargetStat(distance, 1)
        var id = 0
        val pool =
            listOf(ItemType.AMULET, ItemType.BOOTS, ItemType.CAPE).associateWith { type ->
                List(2) {
                    val stats = mutableMapOf<Characteristic, Int>()
                    for (element in res4) if (random.nextInt(3) == 0) stats[element] = random.nextInt(-15, 25)
                    if (random.nextInt(3) == 0) stats[allRes] = random.nextInt(-12, 15)
                    if (random.nextInt(3) == 0) stats[Characteristic.DODGE] = random.nextInt(-10, 10)
                    if (random.nextInt(4) == 0) stats[Characteristic.LOCK] = random.nextInt(-10, 10)
                    if (random.nextInt(2) == 0) {
                        val (line, _) = ElementFamily.RESISTANCE.randomByCount[random.nextInt(3)]
                        stats[line] = if (random.nextInt(5) == 0) -random.nextInt(1, 25) else random.nextInt(5, 40)
                    }
                    if (random.nextInt(4) == 0) stats[Characteristic.MASTERY_ELEMENTARY_WATER] = random.nextInt(-12, 12)
                    if (random.nextInt(4) == 0) stats[Characteristic.MASTERY_ELEMENTARY] = random.nextInt(-5, 15)
                    stats[distance] = random.nextInt(0, 40)
                    stats[fireMastery] = (stats[fireMastery] ?: 0) + random.nextInt(0, 40)
                    item(2000 + id++, type, stats)
                }
            }
        return FuzzCase(params(mode, rows), pool)
    }

    private fun allBuilds(pool: Map<ItemType, List<Equipment>>): List<List<Equipment>> =
        pool.values.fold(listOf(emptyList())) { acc, items -> acc.flatMap { partial -> listOf(partial) + items.map { partial + it } } }

    private fun runFuzz(
        seed: Int,
        casesPerMode: Int,
    ): Int {
        val random = Random(seed)
        var checked = 0
        for (mode in ScoreComputationMode.entries) {
            repeat(casesPerMode) { caseIndex ->
                val case = fuzzCase(random, mode)
                val p = case.p
                val described = "mode=$mode case=$caseIndex rows=${p.targetStats.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" }}"
                assertThat(p.targetStats.needsItemPrefilter).describedAs("a floor never makes a request multi-element; $described").isEqualTo(
                    p.targetStats.resistanceElementsWanted.size > 1
                )
                val metBuilds = mutableListOf<Pair<List<Equipment>, BigDecimal>>()
                for (items in allBuilds(case.pool)) {
                    val ids = items.map { it.equipmentId }.toSet()
                    val stats = scorerStats(p, BuildCombination(items, CharacterSkills(1)))
                    val buildDescription = "$described build=$ids"
                    if (mode != precision) {
                        // HARD leg, build pinned: feasible ⇔ the scorer's placement meets every target AND keeps every floor.
                        val hard = solve(p, case.pool, hard = true, pinned = ids)
                        val met = scorerMeetsTargets(p, stats) && !p.targetStats.floorBroken(stats)
                        assertThat(hard.hasSolution).describedAs("hard leg feasible ⇔ scorer met; $buildDescription model=${hard.modelElementValues}").isEqualTo(met)
                        assertThat(contractViolations(p, hard, hard = true)).describedAs(buildDescription).isEmpty()
                        if (met) metBuilds += items to score(p, *items.toTypedArray())
                    }
                    // SOFT leg / precision, build pinned.
                    val soft = solve(p, case.pool, hard = false, pinned = ids)
                    assertThat(soft.isOptimal).describedAs("soft status %s; %s", soft.status, buildDescription).isTrue()
                    assertThat(contractViolations(p, soft, hard = false)).describedAs("$buildDescription model=${soft.modelElementValues}").isEmpty()
                    checked++
                }
                // End to end, most-masteries hard leg: it returns a build iff one meets the targets and the floors, and the best of those.
                if (mode == mm) {
                    val hard = solve(p, case.pool, hard = true)
                    assertThat(hard.hasSolution).describedAs(described).isEqualTo(metBuilds.isNotEmpty())
                    if (hard.hasSolution) {
                        assertThat(score(p, *hard.build!!.equipments.toTypedArray())).describedAs(described).isEqualByComparingTo(metBuilds.maxOf { it.second })
                    }
                }
                // End to end, precision: the solver's optimum is the best build of the solver's own objective, on the scorer's reads.
                if (mode == precision) {
                    val best = solve(p, case.pool, hard = false)
                    val bestObjective = allBuilds(case.pool).maxOf { precisionModelObjective(p.targetStats, scorerStats(p, BuildCombination(it, CharacterSkills(1)))) }
                    assertThat(best.objective).describedAs(described).isEqualTo(bestObjective)
                }
            }
        }
        return checked
    }

    @Test
    fun `seeded fuzz - the model and the scorers agree on every floor, in every mode and leg`() {
        assertThat(runFuzz(seed = 20261006, casesPerMode = 12)).isGreaterThan(900)
    }

    @Test
    @Tag("slow")
    fun `seeded fuzz, full run - the model and the scorers agree on every floor, in every mode and leg`() {
        assertThat(runFuzz(seed = 20261007, casesPerMode = 80)).isGreaterThan(6000)
    }

    // ---- Wider fuzz (the review's): real sublimations and runes, levels above 1, scenarios --------------------------------

    private val floorSubNames =
        setOf(
            "Vivacité II",
            "Visibilité II",
            "Carapace II",
            "Science du placement",
            "Combat rapproché II",
            "Evasion III",
            "Interception III",
            "Furie",
            "Force Herculéenne",
            "Esquive Berserk III",
            "Tacle Berserk III",
            "Ravage III",
            "Inflexibilité",
            "Critique Berserk III"
        )
    private val floorSubs: List<Sublimation> by lazy { WakfuBestBuildFinderAlgorithm.sublimations.filter { it.name.fr in floorSubNames } }
    private val floorRunes: List<RuneType> by lazy {
        WakfuBestBuildFinderAlgorithm.runes.filter { it.characteristic in res4 || it.characteristic == Characteristic.DODGE || it.characteristic == Characteristic.LOCK }
    }

    private fun widerRows(
        random: Random,
        mode: ScoreComputationMode,
    ): List<TargetStat> {
        val rows = mutableListOf<TargetStat>()
        val els = res4.shuffled(random)
        when (random.nextInt(8)) {
            0 -> els.filter { random.nextBoolean() }.forEach { rows += TargetStat(it, 0) }
            1 -> {
                rows += TargetStat(els[0], 10 + random.nextInt(40), 1 + random.nextInt(3))
                els.drop(1).filter { random.nextBoolean() }.forEach { rows += TargetStat(it, 0) }
            }
            2 -> {
                rows += TargetStat(allRes, 0)
                if (random.nextBoolean()) rows += TargetStat(els[0], 10 + random.nextInt(40))
            }
            3 -> {
                rows += TargetStat(allRes, 5 + random.nextInt(20))
                rows += TargetStat(els[0], 0)
            }
            4 -> {
                rows += TargetStat(els[0], 0)
                rows += TargetStat(els[1], 0)
            }
            5 -> {
                rows += TargetStat(els[0], 10 + random.nextInt(30))
                rows += TargetStat(els[1], 10 + random.nextInt(30))
                rows += TargetStat(els[2], 0)
            }
            6 -> {
                rows += TargetStat(els[0], 0)
                rows += TargetStat(els[0], 15)
            }
            else -> rows += TargetStat(els[0], 0)
        }
        if (random.nextInt(2) == 0) rows += TargetStat(Characteristic.DODGE, 0)
        if (random.nextInt(3) == 0) rows += TargetStat(Characteristic.LOCK, 0)
        val extraFloors =
            listOf(
                Characteristic.INITIATIVE,
                Characteristic.WILLPOWER,
                Characteristic.RESISTANCE_BACK,
                Characteristic.RESISTANCE_CRITICAL,
                Characteristic.BLOCK_PERCENTAGE,
                Characteristic.DAMAGE_INFLICTED,
                Characteristic.RANGE,
                Characteristic.CRITICAL_HIT
            )
        if (random.nextInt(3) == 0) rows += TargetStat(extraFloors.random(random), 0)
        if (random.nextInt(4) == 0) rows += TargetStat(Characteristic.HP, 50 + random.nextInt(200))
        when (mode) {
            mm -> {
                rows += TargetStat(distance, 1)
                if (random.nextInt(4) == 0) rows += TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 0)
            }
            precision -> {
                rows += TargetStat(distance, 20 + random.nextInt(40))
                if (random.nextInt(3) == 0) rows += TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 0)
            }
            else -> Unit
        }
        return rows
    }

    private fun widerPool(
        random: Random,
        level: Int,
        baseId: Int,
    ): Map<ItemType, List<Equipment>> {
        var id = baseId
        val types = listOf(ItemType.AMULET, ItemType.BOOTS, ItemType.CAPE, ItemType.CHEST_PLATE, ItemType.RING)
        return types.associateWith { type ->
            List(if (type == ItemType.RING) 3 else 2) {
                val stats = mutableMapOf<Characteristic, Int>()
                for (element in res4) if (random.nextInt(3) == 0) stats[element] = random.nextInt(-25, 30)
                if (random.nextInt(3) == 0) stats[allRes] = random.nextInt(-20, 20)
                if (random.nextInt(3) == 0) {
                    val (line, _) = ElementFamily.RESISTANCE.randomByCount[random.nextInt(3)]
                    stats[line] = if (random.nextInt(5) == 0) -random.nextInt(1, 30) else random.nextInt(5, 40)
                }
                if (random.nextInt(3) == 0) stats[Characteristic.DODGE] = random.nextInt(-40, 20)
                if (random.nextInt(3) == 0) stats[Characteristic.LOCK] = random.nextInt(-40, 20)
                if (random.nextInt(6) == 0) stats[Characteristic.INITIATIVE] = random.nextInt(-20, 20)
                if (random.nextInt(6) == 0) stats[Characteristic.WILLPOWER] = random.nextInt(-20, 20)
                if (random.nextInt(6) == 0) stats[Characteristic.RESISTANCE_BACK] = random.nextInt(-40, 30)
                if (random.nextInt(6) == 0) stats[Characteristic.RESISTANCE_CRITICAL] = random.nextInt(-40, 30)
                if (random.nextInt(8) == 0) stats[Characteristic.BLOCK_PERCENTAGE] = random.nextInt(-10, 10)
                if (random.nextInt(8) == 0) stats[Characteristic.DAMAGE_INFLICTED] = random.nextInt(-10, 10)
                if (random.nextInt(8) == 0) stats[Characteristic.RANGE] = random.nextInt(-1, 2)
                if (random.nextInt(8) == 0) stats[Characteristic.CRITICAL_HIT] = random.nextInt(-5, 8)
                if (random.nextInt(5) == 0) stats[Characteristic.HP] = random.nextInt(-30, 120)
                if (random.nextInt(4) == 0) stats[Characteristic.MASTERY_ELEMENTARY_WATER] = random.nextInt(-12, 12)
                if (random.nextInt(4) == 0) stats[Characteristic.MASTERY_ELEMENTARY] = random.nextInt(-5, 15)
                stats[distance] = random.nextInt(0, 40)
                stats[fireMastery] = (stats[fireMastery] ?: 0) + random.nextInt(0, 40)
                val rarity =
                    when (random.nextInt(8)) {
                        0 -> Rarity.EPIC
                        1 -> Rarity.RELIC
                        else -> Rarity.LEGENDARY
                    }
                item(id++, type, stats, level = level, rarity = rarity, sockets = listOf(0, 3, 4)[random.nextInt(3)])
            }
        }
    }

    /**
     * The review's fuzz: per case a random request (floors on resistances, dodge, lock, initiative…; per-element rows; HP), a random
     * pool of five slots at level 1, 70 or 140 with real floor-relevant sublimations and runes, a random scenario (berserk, rear
     * hits), each leg solved free and with two random builds pinned — every solution held to [contractViolations]. Returns how
     * many solutions were checked with a floor.
     */
    private fun runWiderFuzz(
        seeds: List<Int>,
        casesPerMode: Int,
    ): Int {
        val violations = mutableListOf<String>()
        var withFloors = 0
        var nextId = 100_000
        for (seed in seeds) {
            val random = Random(seed)
            for (mode in ScoreComputationMode.entries) {
                repeat(casesPerMode) { caseIndex ->
                    val level = listOf(1, 70, 140)[random.nextInt(3)]
                    val rows = widerRows(random, mode)
                    val useSubs = level > 1 && random.nextBoolean()
                    val useRunes = level > 1 && random.nextInt(3) == 0
                    val scenario = faceFire.copy(orientation = if (random.nextBoolean()) Orientation.FACE else Orientation.BACK, berserk = random.nextInt(3) == 0)
                    val p = params(mode, rows, level = level, scenario = scenario, useSubs = useSubs, useRunes = useRunes)
                    nextId += 40
                    val pool = widerPool(random, level, nextId)
                    val described = "seed=$seed mode=$mode case=$caseIndex level=$level subs=$useSubs runes=$useRunes berserk=${scenario.berserk} rows=${p.targetStats.map {
                        "${it.characteristic}:${it.target}x${it.userDefinedWeight}"
                    }}"
                    val legs = if (mode == precision) listOf(false) else listOf(true, false)
                    val allItems = pool.values.flatten()
                    for (hard in legs) {
                        val pins: List<Set<Int>?> =
                            listOf<Set<Int>?>(null) +
                                List(2) {
                                    val chosen = mutableSetOf<Int>()
                                    for ((type, items) in pool) {
                                        if (type == ItemType.RING) {
                                            items.shuffled(random).take(random.nextInt(3)).forEach { chosen += it.equipmentId }
                                        } else if (random.nextInt(3) != 0) {
                                            chosen += items[random.nextInt(items.size)].equipmentId
                                        }
                                    }
                                    chosen
                                }.filter { ids -> BuildCombination(allItems.filter { it.equipmentId in ids }, CharacterSkills(level)).isValid() }
                        for (pin in pins) {
                            val solve =
                                WakfuBuildSolver.elementRowSolveForTest(
                                    p,
                                    pool,
                                    tuning.copy(maxDeterministicTime = 20.0),
                                    hardConstraints = hard,
                                    runes = if (useRunes) floorRunes else emptyList(),
                                    sublimations = if (useSubs) floorSubs else emptyList(),
                                    pinnedEquipmentIds = pin,
                                    pinSkillsToZero = level == 1
                                )
                            if (!solve.hasSolution) {
                                // A soft leg always has a solution — save for a negative required target (a CLI-only shape, filed apart).
                                val negativeRequired = p.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() && it.target < 0 }
                                if (!hard && !negativeRequired) violations += "soft leg without a solution (${solve.status}); $described pin=$pin"
                                continue
                            }
                            if (p.targetStats.hasFloors) withFloors++
                            contractViolations(p, solve, hard).forEach { violations += "$it; $described hard=$hard pin=$pin" }
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty()
        return withFloors
    }

    @Test
    fun `wider fuzz - real sublimations and runes, levels above 1, scenarios - the fold holds the contract`() {
        assertThat(runWiderFuzz(seeds = listOf(424_201), casesPerMode = 6)).isGreaterThan(40)
    }

    @Test
    @Tag("slow")
    fun `wider fuzz, full run - real sublimations and runes, levels above 1, scenarios - the fold holds the contract`() {
        assertThat(runWiderFuzz(seeds = listOf(424_201, 424_202, 424_203), casesPerMode = 14)).isGreaterThan(300)
    }
}
