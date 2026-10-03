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
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/** A CP-SAT proof over the top-8 heuristic's subset is never a global proof. */
class PrefilterOptimalityTest {
    private val tuning =
        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, applyDominationOverride = false)
    private val elementalTargets =
        listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1800), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1800))

    private fun params(
        mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        targets: List<TargetStat> = elementalTargets,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
        targetStats = TargetStats(targets),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = false,
        useSublimations = false
    )

    private fun equipment(
        id: Int,
        slot: ItemType,
        fire: Int,
        water: Int,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = slot,
        characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to fire, Characteristic.MASTERY_ELEMENTARY_WATER to water),
        maxShardSlots = 0
    )

    // In EACH slot eight fire-only and eight water-only items beat the balanced item on their own
    // stat, so it ranks ninth for both and disappears. Two balanced items yield min(1800,1800),
    // whereas the surviving specialists can only yield min(1008,1008). Level 1 has no Strength points.
    private val pool =
        listOf(ItemType.AMULET, ItemType.BELT)
            .mapIndexed { slotIndex, slot ->
                val base = slotIndex * 100
                slot to
                    (
                        (1..8).map { equipment(base + it, slot, 1000 + it, 0) } +
                            (1..8).map { equipment(base + 10 + it, slot, 0, 1000 + it) } +
                            equipment(base + 99, slot, 900, 900)
                    )
            }.toMap()

    @Test
    fun `top-eight loses the full-pool most-masteries optimum and must not report a global proof`(): Unit =
        runBlocking {
            val p = params()
            assertThat(WakfuBuildSolver.gatedPoolSizeForTest(p, pool)).isEqualTo(true to 32)
            val full = WakfuBuildSolver.maxDamageSolveForTest(p, pool, tuning, tightDomains = true, forceFullPool = true)
            val reduced = WakfuBuildSolver.maxDamageSolveForTest(p, pool, tuning, tightDomains = true)
            // These are deliberately RAW model statuses: both solves prove their respective optima.
            assertThat(full.isOptimal && reduced.isOptimal).isTrue()
            assertThat(full.selectedEquipmentIds).containsExactlyInAnyOrder(99, 199)
            assertThat(reduced.selectedEquipmentIds).doesNotContain(99, 199)
            assertThat(full.objective).isGreaterThan(reduced.objective)
            val results = WakfuBuildSolver.optimize(p, pool, tuning).toList()
            val last = results.last()
            val fullBuild = BuildCombination(pool.values.flatten().filter { it.equipmentId in full.selectedEquipmentIds }, CharacterSkills(1))
            val fullScore = FindMostMasteriesFromInputScoring.computeScore(p.targetStats, fullBuild, p.character.baseCharacteristicValues)
            assertThat(fullScore).isEqualByComparingTo("1800")
            assertThat(last.matchPercentage).isEqualByComparingTo("1008")
            println(
                "PREFILTER_REPRO reducedStatus=OPTIMAL fullStatus=OPTIMAL reducedScore=${last.matchPercentage} " +
                    "fullScore=$fullScore reportedOptimal=${last.isOptimal} " +
                    "badge=${WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, last, shouldContinue = { false })}"
            )
            assertThat(results).allSatisfy { assertThat(it.isOptimal).isFalse() }
        }

    private val prefilteredTargets =
        listOf(
            elementalTargets,
            listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 1800)),
            listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 10), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WATER, 10)),
            listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 10)),
            listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 0))
        )

    @Test
    fun `every scoring mode withholds optimality for multi-element mastery or resistance requests`(): Unit =
        runBlocking {
            for (mode in ScoreComputationMode.entries) {
                for (targets in prefilteredTargets) {
                    val p = params(mode, targets)
                    assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isTrue()
                    var outcome: WakfuBuildSolver.SolveOutcome? = null
                    val results =
                        WakfuBuildSolver.optimize(p, pool, emptyList(), emptyList(), tuning, onTermination = { outcome = it }).toList()
                    assertThat(outcome?.status).describedAs("$mode / $targets: subset solve finishes").isEqualTo(CpSolverStatus.OPTIMAL)
                    assertThat(results).isNotEmpty.allSatisfy { assertThat(it.isOptimal).describedAs("$mode / $targets").isFalse() }
                }
            }
        }

    @Test
    fun `two-stage hard-leg override cannot promote a prefiltered result`(): Unit =
        runBlocking {
            val p = params(targets = elementalTargets + TargetStat(Characteristic.ACTION_POINT, 6))
            var outcome: WakfuBuildSolver.SolveOutcome? = null
            val results =
                WakfuBuildSolver
                    .optimize(
                        p,
                        pool,
                        emptyList(),
                        emptyList(),
                        tuning,
                        hardConstraints = true,
                        mmTwoStageOvershoot = true,
                        onTermination = { outcome = it }
                    ).toList()
            assertThat(outcome?.status).isEqualTo(CpSolverStatus.OPTIMAL)
            assertThat(results).isNotEmpty.allSatisfy { assertThat(it.isOptimal).isFalse() }
        }

    @Test
    fun `most-masteries proof gates precede both the optimal flag and bound comparison`() {
        for (targets in prefilteredTargets) {
            val p = params(targets = targets)
            for (optimal in listOf(false, true)) {
                val result = SolverResult(BuildCombination(emptyList(), CharacterSkills(1)), BigDecimal.ONE, 100, isOptimal = optimal, mostMasteriesObjective = 100)
                assertThat(WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, result, shouldContinue = { false }))
                    .describedAs("$targets / optimal=$optimal")
                    .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
                // Equality would award ProvenOptimal, a larger bound ProvenWithin: neither is allowed.
                for (upper in listOf(100L, 200L)) {
                    val bound = MostMasteriesCertificate.Result(foldedBound = upper, coreBound = upper, states = 1, wallMs = 0)
                    assertThat(WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(p, bound, result))
                        .describedAs("$targets / optimal=$optimal / upper=$upper")
                        .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
                }
            }
        }
    }

    @Test
    fun `max-damage proof and construction also reject prefiltered requests`(): Unit =
        runBlocking {
            val p = params(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
            val last = WakfuBuildSolver.optimize(p, pool, tuning).toList().last()
            assertThat(MaxDamageSearch.proveOptimality(p, pool, emptyList(), emptyList(), last.copy(isOptimal = true), threads = 1))
                .isEqualTo(MaxDamageSearch.MaxDamageProof.Unavailable)
            assertThat(WakfuBuildSolver.dpConstructProvenOptimum(p, pool)).isNull()
        }

    @Test
    fun `single-element full-pool requests keep their proven optimum`(): Unit =
        runBlocking {
            for (mode in ScoreComputationMode.entries) {
                val p = params(mode, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1800)))
                assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isFalse()
                val last = WakfuBuildSolver.optimize(p, pool, tuning).toList().last()
                assertThat(last.isOptimal).describedAs("$mode").isTrue()
                if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                    assertThat(WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, last))
                        .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
                }
            }
        }
}
