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
 * and max-damage hard legs, the whole objective halved (once) on their soft legs and in precision — and a resistance row of
 * target 0 wants no element: it never makes a request multi-element, and its floor is read WITHOUT the random-element rolls
 * (its own lines + the "+all elements" ones), in the solver's model and in the scorers alike. Maximized masteries keep their
 * meaning (no floor; an element of target 0 stays wanted).
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
    private val distance = Characteristic.MASTERY_DISTANCE
    private val fireMastery = Characteristic.MASTERY_ELEMENTARY_FIRE

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
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
        targetStats = TargetStats(targets),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forcedItems,
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = false,
        useSublimations = false,
        damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
    )

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = 0
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
                    elementRows = rows,
                    resistanceFloorElements = ts.resistanceFloorElements
                )

            precision ->
                computeCharacteristicsValues(
                    build,
                    base,
                    ts.masteryElementsWanted,
                    ts.resistanceElementsWanted,
                    scoreComputationMode = precision,
                    elementRows = rows,
                    resistanceFloorElements = ts.resistanceFloorElements
                )

            else ->
                computeCharacteristicsValues(
                    build,
                    base,
                    mapOf(p.damageScenario.element.masteryCharacteristic to 1),
                    ts.resistanceElementsWanted,
                    scoreComputationMode = maxDamage,
                    damageScenario = p.damageScenario,
                    elementRows = rows,
                    resistanceFloorElements = ts.resistanceFloorElements
                )
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
    fun `the GUI default beside a fire resistance target is a request on one element - air is a floor, not a wanted element`() {
        val ts = TargetStats(guiDefaultRows + TargetStat(fire, 100))
        assertThat(ts.resistanceElementsWanted).isEqualTo(mapOf(fire to 100))
        assertThat(ts.resistanceFloorElements).containsExactly(wind)
        assertThat(ts.floorCharacteristics).containsExactly(Characteristic.DODGE)
        assertThat(ts.hasFloors).isTrue()
        assertThat(ts.needsItemPrefilter).describedAs("one wanted resistance: the full pool, no heuristic prefilter").isFalse()
        for (mode in ScoreComputationMode.entries) {
            assertThat(ts.readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).describedAs("$mode").isFalse()
            assertThat(ts.elementRowObjectives(mode)).describedAs("$mode").isNull()
        }
        // The GUI default alone: no wanted resistance at all, the same two floors.
        val default = TargetStats(guiDefaultRows)
        assertThat(default.resistanceElementsWanted).isEmpty()
        assertThat(default.resistanceFloorElements).containsExactly(wind)
        assertThat(default.floorCharacteristics).containsExactly(Characteristic.DODGE)
        // A second resistance WITH a target still makes it multi-element.
        assertThat(TargetStats(guiDefaultRows + TargetStat(fire, 100) + TargetStat(water, 100)).needsItemPrefilter).isTrue()
    }

    @Test
    fun `all resistances 0 is a floor on every element no other row wants`() {
        val alone = TargetStats(rows(distance to 1, allRes to 0))
        assertThat(alone.resistanceElementsWanted).isEmpty()
        assertThat(alone.resistanceFloorElements).containsExactly(water, fire, earth, wind)
        assertThat(alone.needsItemPrefilter).isFalse()

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

    // ---- The scorer's read of a floor ------------------------------------------------------------------------------------

    @Test
    fun `a resistance floor reads its own lines plus the generic ones, never a random roll`() {
        val p = params(mm, rows(distance to 1, allRes to 0, fire to 50))
        val amulet = item(1, ItemType.AMULET, mapOf(wind to -20, allRes to 25, earth to -40, fire to 10))
        val boots = item(2, ItemType.BOOTS, mapOf(oneRandomRes to 30))
        val stats = scorerStats(p, build(amulet, boots))
        assertThat(stats[wind]).describedAs("own −20 + generic 25").isEqualTo(5)
        assertThat(stats[water]).describedAs("generic only").isEqualTo(25)
        assertThat(stats[earth]).isEqualTo(-15)
        assertThat(stats[fire]).describedAs("the wanted element: own 10 + generic 25 + the roll 30").isEqualTo(65)
        assertThat(stats[allRes]).describedAs("floors + wanted cover all four: their minimum").isEqualTo(-15)
        assertThat(p.targetStats.floorBroken(stats)).isTrue()
    }

    // ---- Most-masteries ------------------------------------------------------------------------------------------------

    @Test
    fun `most-masteries hard leg - a floor forbids a negative resistance, generic lines count, a random roll does not`() {
        val p = params(mm, rows(distance to 1, wind to 0))
        val negative = item(1, ItemType.AMULET, mapOf(distance to 100, wind to -20))
        val plain = item(2, ItemType.AMULET, mapOf(distance to 60))
        val compensated = item(3, ItemType.AMULET, mapOf(distance to 80, wind to -20, allRes to 25))
        val roll = item(4, ItemType.BOOTS, mapOf(oneRandomRes to 50))
        val pool = pool(negative, plain, compensated, roll)

        val hard = solve(p, pool, hard = true)
        assertThat(hard.isOptimal).isTrue()
        assertThat(picked(hard)).describedAs("air ≥ 0 rules the 100-distance amulet out; −20 + 25 = 5 passes").contains(3).doesNotContain(1)
        assertThat(solve(p, pool, hard = true, pinned = setOf(1, 4)).status)
            .describedAs("the random roll lands on no wanted element: it cannot lift air")
            .isEqualTo(CpSolverStatus.INFEASIBLE)
        val pinned = solve(p, pool, hard = true, pinned = setOf(3))
        assertThat(pinned.modelFloorValues).isEqualTo(mapOf(wind to 5L))
        assertThat(scorerStats(p, build(compensated))[wind]).isEqualTo(5)
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

    // ---- Precision -------------------------------------------------------------------------------------------------------

    @Test
    fun `precision - a floor halves on its roll-free read, in the solver and the score alike`() {
        val p = params(precision, rows(distance to 50, wind to 0))
        val halved = item(1, ItemType.AMULET, mapOf(distance to 50, wind to -20, allRes to 10))
        val whole = item(2, ItemType.AMULET, mapOf(distance to 50, wind to -20, allRes to 25))
        val roll = item(3, ItemType.BOOTS, mapOf(oneRandomRes to 30))
        val pool = pool(halved, whole, roll)
        for ((ids, expectHalved) in listOf(setOf(1) to true, setOf(2) to false, setOf(1, 3) to true)) {
            val pinned = solve(p, pool, hard = false, pinned = ids)
            val stats = scorerStats(p, BuildCombination(pool.values.flatten().filter { it.equipmentId in ids }, CharacterSkills(1)))
            assertThat(pinned.modelHalved).describedAs("$ids").isEqualTo(expectHalved)
            assertThat(p.targetStats.precisionHalves(stats)).describedAs("$ids").isEqualTo(expectHalved)
            assertThat(precisionModelObjective(p.targetStats, stats)).describedAs("$ids").isEqualTo(pinned.objective)
        }
        assertThat(score(p, halved)).isEqualByComparingTo(BigDecimal(50))
        assertThat(score(p, whole)).isEqualByComparingTo(BigDecimal(100))
        assertThat(score(p, halved, roll)).describedAs("no roll lands on air").isEqualByComparingTo(BigDecimal(50))
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
        val a = item(1, ItemType.AMULET, mapOf(distance to 100, wind to 3))
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
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
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
            // come back empty-handed rather than re-solve without the floor and crown the −500 air amulet.
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
        assertThat(stats[wind]!!).describedAs("air, read without the rolls").isGreaterThanOrEqualTo(0)
        assertThat(stats[Characteristic.DODGE] ?: 0).isGreaterThanOrEqualTo(0)
        assertThat(p.targetStats.floorBroken(stats)).isFalse()
    }

    @Test
    @Tag("slow")
    fun `real data - the GUI default plus a fire resistance target is PROVEN optimal on the full pool, in most-masteries`() {
        // Measured 2026-10-05 (1 worker, seed 1, interleave): OPTIMAL at det 33.8 on the full level-110 pool — the prefiltered
        // search it replaces could never earn the badge.
        val p = guiParams(mm, guiDefaultRows + TargetStat(fire, 100))
        val (outcome, last) = realHardLeg(p, firstOnly = false)
        assertThat(outcome?.status).isEqualTo(CpSolverStatus.OPTIMAL)
        val result = checkNotNull(last)
        assertThat(result.isOptimal).describedAs("a full-pool OPTIMAL is a global proof").isTrue()
        assertThat(p.targetStats.floorBroken(scorerStats(p, result.individual))).isFalse()
        assertThat(WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, result.copy(mostMasteriesHardConstraintsMet = true)))
            .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
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
        val elements = ElementFamily.RESISTANCE.elements.shuffled(random)
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
                    for (element in ElementFamily.RESISTANCE.elements) if (random.nextInt(3) == 0) stats[element] = random.nextInt(-15, 25)
                    if (random.nextInt(3) == 0) stats[allRes] = random.nextInt(-12, 15)
                    if (random.nextInt(3) == 0) stats[Characteristic.DODGE] = random.nextInt(-10, 10)
                    if (random.nextInt(4) == 0) stats[Characteristic.LOCK] = random.nextInt(-10, 10)
                    if (random.nextInt(2) == 0) {
                        val (line, _) = ElementFamily.RESISTANCE.randomByCount[random.nextInt(3)]
                        stats[line] = if (random.nextInt(8) == 0) -random.nextInt(1, 15) else random.nextInt(5, 40)
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

    /** Every required row with a target > 0 met by the scorer's [stats] (per-element rows on their element, the aggregate on the min). */
    private fun scorerMeetsTargets(
        p: WakfuBestBuildParams,
        stats: Map<Characteristic, Int>,
    ): Boolean = p.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() && it.target > 0 }.all { (stats[it.characteristic] ?: 0) >= it.target }

    /** The scorer's value of every floor of [p] in [stats]. */
    private fun scorerFloors(
        p: WakfuBestBuildParams,
        stats: Map<Characteristic, Int>,
    ): Map<Characteristic, Long> = (p.targetStats.floorCharacteristics + p.targetStats.resistanceFloorElements).associateWith { (stats[it] ?: 0).toLong() }

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
                    val b = BuildCombination(items, CharacterSkills(1))
                    val stats = scorerStats(p, b)
                    val floors = scorerFloors(p, stats)
                    val broken = p.targetStats.floorBroken(stats)
                    val buildDescription = "$described build=$ids floors=$floors"
                    if (mode != precision) {
                        // HARD leg, build pinned: feasible ⇔ the scorer meets every target AND every floor holds.
                        val hard = solve(p, case.pool, hard = true, pinned = ids)
                        val met = scorerMeetsTargets(p, stats) && !broken
                        assertThat(hard.hasSolution).describedAs("hard leg feasible ⇔ scorer met; $buildDescription model=${hard.modelFloorValues}").isEqualTo(met)
                        if (hard.hasSolution) assertThat(floors).describedAs(buildDescription).containsAllEntriesOf(hard.modelFloorValues)
                        if (met) metBuilds += items to score(p, *items.toTypedArray())
                    }
                    // SOFT leg / precision, build pinned: the model reads the scorer's floor values and halves exactly when it does.
                    val soft = solve(p, case.pool, hard = false, pinned = ids)
                    assertThat(soft.isOptimal).describedAs("soft status %s; %s", soft.status, buildDescription).isTrue()
                    assertThat(floors).describedAs("model floors = scorer floors; $buildDescription").containsAllEntriesOf(soft.modelFloorValues)
                    // A floor the model does not read is one no build of the pool can break.
                    for ((floor, value) in floors) if (floor !in soft.modelFloorValues) assertThat(value).describedAs("$floor; $buildDescription").isGreaterThanOrEqualTo(0L)
                    val scorerHalves = if (mode == precision) p.targetStats.precisionHalves(stats) else broken
                    assertThat(soft.modelHalved ?: false).describedAs("halved; $buildDescription model=${soft.modelFloorValues}").isEqualTo(scorerHalves)
                    if (mode == precision) {
                        assertThat(precisionModelObjective(p.targetStats, stats)).describedAs("objective; $buildDescription").isEqualTo(soft.objective)
                    }
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
}
