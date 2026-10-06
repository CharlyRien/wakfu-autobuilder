package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
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
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Locks RELAX THEN CHECK ([WakfuBuildSolver.relaxThenCheck]): a most-masteries leg of a request with floors is solved without
 * them first, then with them under the cut `objective ≤ the relaxed bound`, hinted with the relaxed solution.
 *  - The argument, build by build: the relaxed objective of every build is at least its floored one, on both legs, and every
 *    build the floored hard leg allows the relaxed one allows — the cut never removes a floored build.
 *  - End to end: the leg's result is the direct floored solve's (same score, same objective, both proven) when the relaxed
 *    optimum keeps its floors and when a floor binds, on both legs and over seeded random pools.
 *  - Nothing the relaxed stage shows breaks a floor or, on the hard leg, misses a target.
 *  - The budget: the two stages never add up to more than the leg's.
 */
class RelaxThenCheckTest {
    private val direct = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
    private val relaxing = direct.copy(relaxFloorsFirst = true)

    private val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val wind = Characteristic.RESISTANCE_ELEMENTARY_WIND
    private val fire = Characteristic.RESISTANCE_ELEMENTARY_FIRE
    private val allRes = Characteristic.RESISTANCE_ELEMENTARY
    private val distance = Characteristic.MASTERY_DISTANCE
    private val res4 = ElementFamily.RESISTANCE.elements

    private fun rows(vararg pairs: Pair<Characteristic, Int>) = pairs.map { TargetStat(it.first, it.second) }

    private fun params(targets: List<TargetStat>) =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
            targetStats = TargetStats(targets),
            searchDuration = 5.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = mm
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

    private class Leg(
        val results: List<SolverResult<BuildCombination>>,
        val outcome: WakfuBuildSolver.SolveOutcome?,
    ) {
        val final: SolverResult<BuildCombination>? get() = results.lastOrNull { it.progressPercentage == 100 && !it.greedyWarmStartEmission }
    }

    private fun solve(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        tuning: WakfuBuildSolver.SolverTuning,
        hard: Boolean,
    ): Leg {
        var outcome: WakfuBuildSolver.SolveOutcome? = null
        val results =
            runBlocking {
                WakfuBuildSolver.optimize(p, pool, emptyList(), emptyList(), tuning, hardConstraints = hard, onTermination = { outcome = it }).toList()
            }
        return Leg(results, outcome)
    }

    private fun scorerStats(
        p: WakfuBestBuildParams,
        build: BuildCombination,
    ) = FindMostMasteriesFromInputScoring.resolvedStats(p.targetStats, build, p.character.baseCharacteristicValues)

    /** The relax-then-check leg and the direct floored one end on the same result: same score, same objective, both proven. */
    private fun assertSameResult(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        hard: Boolean,
        described: String,
    ) {
        val directLeg = solve(p, pool, direct, hard)
        val relaxedLeg = solve(p, pool, relaxing, hard)
        val expected = directLeg.final
        val actual = relaxedLeg.final
        assertThat(actual == null).describedAs("a result iff the direct floored leg has one; $described").isEqualTo(expected == null)
        if (expected == null || actual == null) return
        assertThat(directLeg.outcome?.status).describedAs("the direct floored leg proves its optimum; $described").isEqualTo(CpSolverStatus.OPTIMAL)
        assertThat(relaxedLeg.outcome?.status).describedAs("so does the floored stage; $described").isEqualTo(CpSolverStatus.OPTIMAL)
        // (A request on several elements of a family is never stamped proven — the pre-filtered pool — in either.)
        assertThat(actual.isOptimal).describedAs("proven; $described").isEqualTo(expected.isOptimal)
        // On the hard leg the score is the objective's core, so two optima score alike; the soft leg's score divides by a continuous
        // factor where the objective multiplies by a bucketed one, so two optima can score apart — the objectives are compared.
        if (hard) assertThat(actual.matchPercentage).describedAs("score; $described").isEqualByComparingTo(expected.matchPercentage)
        assertThat(actual.mostMasteriesObjective).describedAs("objective; $described").isEqualTo(expected.mostMasteriesObjective)
        assertThat(relaxedLeg.outcome?.objectiveValue).describedAs("solver objective; $described").isEqualTo(directLeg.outcome?.objectiveValue)
        // The hard leg never shows a build that breaks a floor or misses a target — the relaxed stage's gate, the floored stage's
        // constraints. (A soft leg may: the floored stage trades a floor as the soft leg does, its score halved.)
        if (hard) {
            for (shown in relaxedLeg.results.filterNot { it.greedyWarmStartEmission }) {
                val holds = p.targetStats.hardLegHolds(scorerStats(p, shown.individual))
                assertThat(holds).describedAs("shown build ${shown.individual.equipments.map { it.equipmentId }}; $described").isTrue()
            }
        }
    }

    // ---- The relaxed optimum keeps its floors / a floor binds ----------------------------------------------------------

    @Test
    fun `the relaxed optimum keeps its floors - the leg ends on it, proven, as the direct floored solve does`() {
        // Distance 100 with air +5 and dodge 0: the best build with or without the floors.
        val p = params(rows(distance to 1, Characteristic.ACTION_POINT to 7, wind to 0, Characteristic.DODGE to 0))
        val strong = item(1, ItemType.AMULET, mapOf(distance to 100, wind to 5, Characteristic.ACTION_POINT to 1))
        val weak = item(2, ItemType.AMULET, mapOf(distance to 60, Characteristic.ACTION_POINT to 1))
        val pool = pool(strong, weak)
        for (hard in listOf(true, false)) {
            assertSameResult(p, pool, hard, "hard=$hard")
            assertThat(
                solve(p, pool, relaxing, hard)
                    .final!!
                    .individual.equipments
                    .map { it.equipmentId }
            ).describedAs("hard=$hard").containsExactly(1)
        }
    }

    @Test
    fun `a floor binds - the floored stage finds the floored optimum and proves it, on both legs`() {
        // The relaxed optimum (distance 100) has air −20: the floored leg's optimum is the other amulet, on the hard leg (forbidden)
        // and on the soft one (100 halved is 50 < 60).
        val p = params(rows(distance to 1, Characteristic.ACTION_POINT to 7, wind to 0))
        val strong = item(1, ItemType.AMULET, mapOf(distance to 100, wind to -20, Characteristic.ACTION_POINT to 1))
        val weak = item(2, ItemType.AMULET, mapOf(distance to 60, Characteristic.ACTION_POINT to 1))
        val pool = pool(strong, weak)
        for (hard in listOf(true, false)) {
            assertSameResult(p, pool, hard, "hard=$hard")
            val leg = solve(p, pool, relaxing, hard)
            assertThat(
                leg.final!!
                    .individual.equipments
                    .map { it.equipmentId }
            ).describedAs("hard=$hard").containsExactly(2)
            if (hard) {
                assertThat(leg.results.filterNot { it.greedyWarmStartEmission }.flatMap { r -> r.individual.equipments.map { it.equipmentId } })
                    .describedAs("the relaxed optimum breaks the floor: never shown")
                    .doesNotContain(1)
            }
        }
    }

    @Test
    fun `targets out of reach - the soft leg relaxes too and ends on the direct floored optimum`() {
        // AP 20 is out of reach at level 1: the hard leg is infeasible, relaxed or not; the soft leg trades the floor as the
        // direct solve does.
        val p = params(rows(distance to 1, Characteristic.ACTION_POINT to 20, wind to 0, Characteristic.DODGE to 0))
        val strong = item(1, ItemType.AMULET, mapOf(distance to 100, wind to -20, Characteristic.ACTION_POINT to 1))
        val weak = item(2, ItemType.AMULET, mapOf(distance to 60, Characteristic.ACTION_POINT to 1))
        val clumsy = item(3, ItemType.BOOTS, mapOf(distance to 30, Characteristic.DODGE to -5))
        val pool = pool(strong, weak, clumsy)
        val hard = solve(p, pool, relaxing, hard = true)
        assertThat(hard.final).isNull()
        assertThat(hard.outcome?.status).describedAs("no build: statically (no outcome) or proven infeasible").isIn(null, CpSolverStatus.INFEASIBLE)
        assertSameResult(p, pool, hard = false, "soft")
    }

    @Test
    fun `a request without floors takes the direct solve`() {
        // Nothing to relax: the leg is the plain one (no relaxed stage — its result is the direct solve's, emission for emission).
        val p = params(rows(distance to 1, Characteristic.ACTION_POINT to 7))
        val pool = pool(item(1, ItemType.AMULET, mapOf(distance to 100, Characteristic.ACTION_POINT to 1)), item(2, ItemType.AMULET, mapOf(distance to 60)))
        val directLeg = solve(p, pool, direct, hard = true)
        val relaxedLeg = solve(p, pool, relaxing, hard = true)
        assertThat(relaxedLeg.results.map { it.individual.equipments.map { e -> e.equipmentId } to it.matchPercentage })
            .isEqualTo(directLeg.results.map { it.individual.equipments.map { e -> e.equipmentId } to it.matchPercentage })
    }

    // ---- The budget --------------------------------------------------------------------------------------------------

    @Test
    fun `the two stages never add up to more than the leg's budget`() {
        // Relaxed: at most half of the budget, never past its end; floored: what is left.
        assertThat(WakfuBuildSolver.relaxedStageBudget(120_000.0, 300.0)).isEqualTo(60_000.0)
        assertThat(WakfuBuildSolver.relaxedStageBudget(120_000.0, 100_000.0)).isEqualTo(20_000.0)
        assertThat(WakfuBuildSolver.relaxedStageBudget(1_000.0, 1_500.0)).isEqualTo(0.0)
        assertThat(WakfuBuildSolver.flooredStageBudget(120_000.0, 70_000.0)).isEqualTo(50_000.0)
        assertThat(WakfuBuildSolver.flooredStageBudget(1_000.0, 1_500.0)).isEqualTo(0.0)
        val random = Random(20261006)
        repeat(2_000) {
            val total = random.nextDouble(1.0, 600_000.0)
            val beforeRelaxed = random.nextDouble(0.0, total)
            val relaxed = WakfuBuildSolver.relaxedStageBudget(total, beforeRelaxed)
            // The relaxed stage runs its budget out at worst, then the floored model is built.
            val beforeFloored = beforeRelaxed + relaxed + random.nextDouble(0.0, total / 10)
            val floored = WakfuBuildSolver.flooredStageBudget(total, beforeFloored)
            assertThat(relaxed).isBetween(0.0, total * WakfuBuildSolver.RELAXED_STAGE_SHARE)
            assertThat(beforeRelaxed + relaxed + floored).isLessThanOrEqualTo(maxOf(total, beforeFloored) + 1e-6)
        }
    }

    // ---- Seeded fuzz: the argument build by build, and the leg end to end ---------------------------------------------

    private class Case(
        val p: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
    )

    // [maxWanted]: how many resistance elements a request may want — two makes a slow soft leg (direct or relaxed), so the quick
    // run keeps to one and the full run draws both.
    private fun fuzzCase(
        random: Random,
        maxWanted: Int,
    ): Case {
        val rows = mutableListOf<TargetStat>()
        val elements = res4.shuffled(random)
        val wanted = random.nextInt(maxWanted + 1)
        elements.take(wanted).forEach { rows += TargetStat(it, 10 + random.nextInt(40), 1 + random.nextInt(5)) }
        when (random.nextInt(4)) {
            0 -> rows += TargetStat(allRes, 0)
            else -> elements.drop(wanted).filter { random.nextBoolean() }.forEach { rows += TargetStat(it, 0) }
        }
        if (random.nextBoolean()) rows += TargetStat(Characteristic.DODGE, 0)
        if (random.nextInt(3) == 0) rows += TargetStat(Characteristic.LOCK, 0)
        // A floor for sure (the shape relax-then-check solves).
        if (rows.none { it.target == 0 }) rows += TargetStat(Characteristic.DODGE, 0)
        // An AP target, sometimes out of reach (the soft leg's shape).
        when (random.nextInt(3)) {
            0 -> rows += TargetStat(Characteristic.ACTION_POINT, 7)
            1 -> rows += TargetStat(Characteristic.ACTION_POINT, 12)
            else -> Unit
        }
        rows += TargetStat(distance, 1)
        var id = 0
        val pool =
            listOf(ItemType.AMULET, ItemType.BOOTS, ItemType.CAPE).associateWith { type ->
                List(2) {
                    val stats = mutableMapOf<Characteristic, Int>()
                    for (element in res4) if (random.nextInt(3) == 0) stats[element] = random.nextInt(-15, 25)
                    if (random.nextInt(3) == 0) stats[allRes] = random.nextInt(-12, 15)
                    if (random.nextInt(3) == 0) stats[Characteristic.DODGE] = random.nextInt(-10, 10)
                    if (random.nextInt(4) == 0) stats[Characteristic.LOCK] = random.nextInt(-10, 10)
                    if (random.nextInt(3) == 0) stats[Characteristic.ACTION_POINT] = 1
                    if (random.nextInt(2) == 0) {
                        val (line, _) = ElementFamily.RESISTANCE.randomByCount[random.nextInt(3)]
                        stats[line] = if (random.nextInt(3) == 0) -random.nextInt(1, 25) else random.nextInt(5, 40)
                    }
                    stats[distance] = random.nextInt(0, 60)
                    item(5000 + id++, type, stats)
                }
            }
        return Case(params(rows), pool)
    }

    private fun allBuilds(pool: Map<ItemType, List<Equipment>>): List<List<Equipment>> =
        pool.values.fold(listOf(emptyList())) { acc, items -> acc.flatMap { partial -> listOf(partial) + items.map { partial + it } } }

    private fun runFuzz(
        seed: Int,
        cases: Int,
        maxWanted: Int,
    ): Int {
        val random = Random(seed)
        var checked = 0
        repeat(cases) { caseIndex ->
            val case = fuzzCase(random, maxWanted)
            val p = case.p
            val described = "seed=$seed case=$caseIndex rows=${p.targetStats.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" }}"
            assertThat(p.targetStats.hasFloors).describedAs(described).isTrue()
            for (hard in listOf(true, false)) {
                // The argument, build by build: relaxed objective ≥ floored objective; floored hard-feasible ⇒ relaxed hard-feasible.
                for (items in allBuilds(case.pool)) {
                    val ids = items.map { it.equipmentId }.toSet()
                    val floored = WakfuBuildSolver.elementRowSolveForTest(p, case.pool, direct, hard, pinnedEquipmentIds = ids, pinSkillsToZero = true)
                    val relaxed =
                        WakfuBuildSolver.elementRowSolveForTest(p, case.pool, direct, hard, pinnedEquipmentIds = ids, pinSkillsToZero = true, relaxFloors = true)
                    val buildDescription = "$described hard=$hard build=$ids"
                    assertThat(floored.isOptimal || !floored.hasSolution).describedAs(buildDescription).isTrue()
                    if (!floored.hasSolution) continue
                    assertThat(relaxed.isOptimal).describedAs("relaxed allows every floored build; $buildDescription").isTrue()
                    assertThat(relaxed.objective!!).describedAs("relaxed ≥ floored; $buildDescription").isGreaterThanOrEqualTo(floored.objective!!)
                    assertThat(relaxed.modelFloorValues).describedAs("the relaxed model reads no floor; $buildDescription").isEmpty()
                    checked++
                }
                // End to end.
                assertSameResult(p, case.pool, hard, "$described hard=$hard")
            }
        }
        return checked
    }

    @Test
    fun `seeded fuzz - the relaxed objective bounds the floored one, build by build, and the leg ends on the floored optimum`() {
        assertThat(runFuzz(seed = 20261006, cases = 30, maxWanted = 1)).isGreaterThan(500)
    }

    @Test
    @Tag("slow")
    fun `seeded fuzz, full run - the relaxed objective bounds the floored one, build by build, and the leg ends on the floored optimum`() {
        assertThat(runFuzz(seed = 20261007, cases = 60, maxWanted = 2)).isGreaterThan(1_000)
    }
}
