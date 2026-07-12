package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Canonical same-JVM A/B harness for most-masteries model encodings.
 *
 * Protocol: hard leg, production domination, presolve 1 / linearization 1, one worker with
 * interleaving, fixed seed, and a deterministic-time budget. The arms run sequentially in this
 * JVM so their wall times are comparable. This is manual-only because a complete campaign is long:
 *
 * ```shell
 * WAKFU_MM_PERF_AB=1 WAKFU_MM_PERF_AB_DET=600 \
 *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
 * ```
 *
 * Select `f5`, `frontier`, or `all` with `WAKFU_MM_PERF_AB_SHAPE` (default: `all`).
 */
class MostMasteriesPerfExperimentTest {
    private data class ExperimentConfig(
        val label: String,
        val overshootEncoding: MmOvershootEncoding = MmOvershootEncoding.CURRENT,
        val productEncoding: MmProductEncoding = MmProductEncoding.CURRENT,
    )

    private data class Shape(
        val label: String,
        val params: WakfuBestBuildParams,
    )

    private data class Summary(
        val config: ExperimentConfig,
        val wallMs: Long,
        val status: CpSolverStatus?,
        val rawObjective: Long?,
        val bestBound: Long,
        val scoredObjective: java.math.BigDecimal?,
        val isOptimal: Boolean,
        val emissions: Int,
        val firstEmissionMs: Long?,
        val deterministicTime: Double,
        val branches: Long,
        val conflicts: Long,
    )

    private val configs =
        listOf(
            ExperimentConfig("baseline"),
            ExperimentConfig("overshootExact", overshootEncoding = MmOvershootEncoding.HARD_EXACT_SIMPLIFIED),
            ExperimentConfig("overshootHypograph", overshootEncoding = MmOvershootEncoding.HARD_HYPOGRAPH),
            ExperimentConfig("productTracked", productEncoding = MmProductEncoding.TRACKED),
            ExperimentConfig("productBinary", productEncoding = MmProductEncoding.BINARY)
        )

    @Test
    fun `manual canonical most-masteries encoding A-B`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_PERF_AB") == "1")
            val det = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            require(det > 0.0) { "WAKFU_MM_PERF_AB_DET must be positive" }
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val sublimations = WakfuBestBuildFinderAlgorithm.sublimations
            val shapes = selectedShapes(level)

            WakfuBuildSolver.warmUp()
            println(
                "MM_PERF_AB START det=$det shapes=${shapes.joinToString { it.label }} " +
                    "arms=${configs.joinToString { it.label }} pool=${pool.values.sumOf { it.size }}"
            )

            for (shape in shapes) {
                val summaries = configs.map { config -> solve(shape, config, det, pool, runes, sublimations) }
                val baseline = summaries.first()
                for (summary in summaries.drop(1)) {
                    if (baseline.isOptimal && summary.isOptimal) {
                        assertThat(summary.rawObjective)
                            .describedAs("${shape.label}/${summary.config.label}: exact raw optimum")
                            .isEqualTo(baseline.rawObjective)
                        assertThat(summary.scoredObjective)
                            .describedAs("${shape.label}/${summary.config.label}: exact scored optimum")
                            .isEqualByComparingTo(baseline.scoredObjective)
                    }
                    val ratio = baseline.wallMs.toDouble() / summary.wallMs.coerceAtLeast(1)
                    println(
                        "MM_PERF_AB COMPARE shape=${shape.label} arm=${summary.config.label} " +
                            "baselineMs=${baseline.wallMs} armMs=${summary.wallMs} " +
                            "baselineOverArm=${"%.3f".format(java.util.Locale.ROOT, ratio)} " +
                            "baselineStatus=${baseline.status ?: "NA"} armStatus=${summary.status ?: "NA"}"
                    )
                }
            }
        }

    private suspend fun solve(
        shape: Shape,
        config: ExperimentConfig,
        det: Double,
        pool: Map<ItemType, List<me.chosante.common.Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
    ): Summary {
        val termination = AtomicReference<WakfuBuildSolver.SolveOutcome?>()
        val tuning =
            WakfuBuildSolver.SolverTuning(
                numSearchWorkers = 1,
                randomSeed = 1,
                maxDeterministicTime = det,
                interleaveSearch = true,
                maxPresolveIterationsOverride = 1,
                linearizationLevelOverride = 1,
                applyDominationOverride = true,
                mmOvershootEncoding = config.overshootEncoding,
                mmProductEncoding = config.productEncoding
            )
        val t0 = System.nanoTime()
        var last: SolverResult<BuildCombination>? = null
        var emissions = 0
        var firstEmissionMs: Long? = null

        WakfuBuildSolver
            .optimize(
                shape.params,
                pool,
                runes,
                sublimations,
                tuning,
                hardConstraints = true,
                onTermination = { termination.set(it) }
            ).collect { result ->
                val elapsedMs = elapsedMs(t0)
                emissions++
                if (firstEmissionMs == null) firstEmissionMs = elapsedMs
                last = result
                println(
                    "MM_PERF_AB EMIT shape=${shape.label} arm=${config.label} tMs=$elapsedMs " +
                        "scoredObjective=${result.matchPercentage} optimal=${result.isOptimal}"
                )
            }

        val outcome = termination.get()
        val wallMs = elapsedMs(t0)
        checkNotNull(outcome) { "${shape.label}/${config.label}: solver returned no termination diagnostics" }
        val summary =
            Summary(
                config = config,
                wallMs = wallMs,
                status = outcome.status,
                rawObjective = outcome.objectiveValue,
                bestBound = outcome.bestObjectiveBound,
                scoredObjective = last?.matchPercentage,
                isOptimal = last?.isOptimal == true,
                emissions = emissions,
                firstEmissionMs = firstEmissionMs,
                deterministicTime = outcome.deterministicTime,
                branches = outcome.branches,
                conflicts = outcome.conflicts
            )
        println(
            "MM_PERF_AB SUMMARY shape=${shape.label} arm=${config.label} det=$det wallMs=${summary.wallMs} " +
                "status=${summary.status ?: "NA"} rawObjective=${summary.rawObjective ?: "NA"} " +
                "bestBound=${summary.bestBound} scoredObjective=${summary.scoredObjective ?: "NA"} " +
                "optimal=${summary.isOptimal} detUsed=${summary.deterministicTime} branches=${summary.branches} " +
                "conflicts=${summary.conflicts} emissions=${summary.emissions} firstEmissionMs=${summary.firstEmissionMs ?: "NA"}"
        )
        return summary
    }

    private fun selectedShapes(level: Int): List<Shape> {
        val f5 =
            Shape(
                "f5",
                params(
                    level,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6),
                        TargetStat(Characteristic.HP, 2000),
                        TargetStat(Characteristic.CRITICAL_HIT, 30)
                    )
                )
            )
        val frontier =
            Shape(
                "frontier",
                params(
                    level,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 16),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 12000)
                    )
                )
            )
        return when (val selected = System.getenv("WAKFU_MM_PERF_AB_SHAPE")?.lowercase() ?: "all") {
            "all" -> listOf(f5, frontier)
            "f5" -> listOf(f5)
            "frontier" -> listOf(frontier)
            else -> error("Unknown WAKFU_MM_PERF_AB_SHAPE=$selected; expected f5, frontier, or all")
        }
    }

    private fun params(
        level: Int,
        requiredTargets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats =
            TargetStats(
                listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + requiredTargets
            ),
        searchDuration = 600.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = true,
        useSublimations = true
    )

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
