package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scaledWeight
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * §8.3 S-B dry-run: the incumbent-conditioned SOFT item ceiling, on S2 and S3 (never edits a pool).
 *
 * For every item `x` of the production (domination) pool it computes a sound upper bound on the
 * FULL folded soft objective of any build containing `x`:
 *
 *   `U(x) = ⌊Umastery(x) × (100 + Udi(x)) / 100⌋ × power6(UtargetBucket(x)) × SCALE + (SCALE − 1)`
 *
 * where each factor independently over-credits its axis via [CeilingAnalyzer] (all factors are
 * non-negative, so the product of upper bounds upper-bounds the product). The floor `L` is a real
 * incumbent from a SHORT production solve on the same model (its raw folded objective — never
 * re-derived arithmetic). `U(x) < L` ⇒ no build containing `x` can beat the incumbent, so the item
 * would be droppable before model construction. Equality is kept (`x` may win the tie-break).
 *
 * The bucket/power-table arithmetic mirrors `applyConstraintPenalty`/`bucketedIndex`/
 * `buildPowerTable` exactly (integer, BigInteger-scaled) — measurement-only duplication, flagged
 * in the report so a future productionization derives it from the model instead.
 *
 * Gates (plan §8.3): <10% rejected ⇒ DROP; 10-20% ⇒ research seam only; ≥20% ⇒ build the filter.
 *
 * ```shell
 * WAKFU_MM_SOFT_CEILING=1 [WAKFU_MM_SOFT_CEILING_FLOOR_DET=30] \
 *   ./gradlew :autobuilder:test --tests '*MostMasteriesSoftCeilingAnalysisTest*'
 * ```
 */
class MostMasteriesSoftCeilingAnalysisTest {
    @Test
    fun `manual per-item soft-objective ceiling screen at 245`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_SOFT_CEILING") == "1")
            val floorDet = System.getenv("WAKFU_MM_SOFT_CEILING_FLOOR_DET")?.toDoubleOrNull() ?: 30.0
            val level = 245

            val s2Targets =
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 16),
                    TargetStat(Characteristic.MOVEMENT_POINT, 8),
                    TargetStat(Characteristic.CRITICAL_HIT, 100),
                    TargetStat(Characteristic.HP, 12000)
                )
            WakfuBuildSolver.warmUp()
            screen("S2", level, s2Targets, floorDet)
            screen("S3", level, emptyList(), floorDet)
        }

    private suspend fun screen(
        label: String,
        level: Int,
        requiredTargets: List<TargetStat>,
        floorDet: Double,
    ) {
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + requiredTargets),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= params.maxRarity && it.rarity !in params.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(params, WakfuBestBuildFinderAlgorithm.sublimations))
        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)
        val analyzer = CeilingAnalyzer(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)

        // The floor L: a short PRODUCTION solve's raw folded incumbent — real scorer units, no
        // re-derived arithmetic. No incumbent within the budget ⇒ the screen is skipped.
        val termination = AtomicReference<WakfuBuildSolver.SolveOutcome?>(null)
        WakfuBuildSolver
            .optimize(
                params,
                pool,
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    maxDeterministicTime = floorDet,
                    interleaveSearch = true,
                    maxPresolveIterationsOverride = 1,
                    linearizationLevelOverride = 1,
                    applyDominationOverride = true
                ),
                hardConstraints = false,
                onTermination = { termination.set(it) }
            ).collect { }
        val outcome = termination.get()
        val floor = outcome?.objectiveValue
        if (floor == null || (outcome.status != CpSolverStatus.OPTIMAL && outcome.status != CpSolverStatus.FEASIBLE)) {
            println("MM_SOFT_CEILING shape=$label SKIP no floor incumbent (status=${outcome?.status ?: "NA"})")
            return
        }
        println("MM_SOFT_CEILING shape=$label floor=$floor floorStatus=${outcome.status} floorDet=${outcome.deterministicTime}")

        // Penalty geometry — mirrors applyConstraintPenalty/bucketedIndex/buildPowerTable exactly.
        val targetStats = params.targetStats
        val required = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        val totalExpected =
            required
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)
        val bucketSize =
            if (totalExpected <= MAX_POWER_TABLE_INDEX) 1L else ceil(totalExpected.toDouble() / MAX_POWER_TABLE_INDEX.toDouble()).toLong()
        val maxIndex = if (totalExpected <= MAX_POWER_TABLE_INDEX) totalExpected.toInt() else ((totalExpected + bucketSize - 1) / bucketSize).toInt()
        val maxPow = BigInteger.valueOf(maxIndex.toLong()).pow(6)
        val powScale =
            if (maxPow > BigInteger.valueOf(MAX_PENALTY_MULTIPLIER)) maxPow.divide(BigInteger.valueOf(MAX_PENALTY_MULTIPLIER)) else BigInteger.ONE

        fun power6(index: Int): Long =
            BigInteger
                .valueOf(index.toLong())
                .pow(6)
                .divide(powScale)
                .toLong()

        val scale = WakfuBuildSolver.OVERSHOOT_SCALE
        val diFactorMax = 100L + DAMAGE_DI_MAX

        var rejected = 0
        var unsupported = 0
        var boundByCore = 0
        var boundByBucket = 0
        val rejectedByType = mutableMapOf<ItemType, Int>()
        for (item in analyzer.all) {
            val uMastery = analyzer.ceiling(item, Characteristic.MASTERY_DISTANCE)
            val uDi = analyzer.ceiling(item, Characteristic.DAMAGE_INFLICTED)
            if (uMastery == null || uDi == null) {
                unsupported++
                continue
            }
            val diFactor = (100L + uDi).coerceIn(0L, diFactorMax)
            val uCore = (uMastery.coerceAtLeast(0L) * diFactor / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)

            val u: Long
            if (required.isEmpty()) {
                // S3 shape: the folded objective IS the bare core (no penalty, no overshoot fold).
                u = uCore
            } else {
                var uTotalActual = 0L
                var targetUnsupported = false
                for (target in required) {
                    val uActual = analyzer.ceiling(item, target.characteristic)
                    if (uActual == null) {
                        targetUnsupported = true
                        break
                    }
                    uTotalActual += targetStats.scaledWeight(target) * uActual.coerceAtMost(target.target.toLong())
                }
                if (targetUnsupported) {
                    unsupported++
                    continue
                }
                val bucket = (uTotalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                u = uCore * power6(bucket) * scale + (scale - 1)
            }

            if (u < floor) {
                rejected++
                rejectedByType.merge(item.itemType, 1, Int::plus)
                if (required.isEmpty() || uCore * power6(maxIndex) * scale + (scale - 1) < floor) boundByCore++ else boundByBucket++
            }
        }

        val total = analyzer.all.size
        val pct = 100.0 * rejected / total
        val verdict =
            when {
                pct < 10.0 -> "DROP"
                pct < 20.0 -> "RESEARCH-SEAM"
                else -> "BUILD"
            }
        println(
            "MM_SOFT_CEILING shape=$label pool=$total rejected=$rejected pct=${"%.2f".format(java.util.Locale.ROOT, pct)} " +
                "unsupported=$unsupported boundByCore=$boundByCore boundByBucket=$boundByBucket verdict=$verdict"
        )
        println(
            "MM_SOFT_CEILING shape=$label rejectedByType=" +
                rejectedByType.toList().sortedByDescending { it.second }.joinToString { "${it.first}:${it.second}" }
        )
    }
}
