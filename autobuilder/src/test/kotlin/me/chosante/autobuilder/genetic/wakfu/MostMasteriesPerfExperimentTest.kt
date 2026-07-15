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
        val hardLeg: Boolean = true,
        val masteryScoreUpperBound: Long? = null,
        // §8.5 S-D: hard-leg targets behind assumption literals / the recycled soft no-good cut.
        val hardAssumptions: Boolean = false,
        val noGoodCore: Set<Characteristic>? = null,
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

    /**
     * Campaign 2 baseline matrix (plan §8.1): one baseline arm per fixture, canonical protocol.
     * S1 = the reachable f5 hard leg (regression canary), S2 = the frontier shape run DIRECTLY on the
     * soft (penalized) model — the real fallback workload without spending the hard-leg prelude —
     * and S3 = a distance-mastery request with no required stat, isolating the inner mastery×DI
     * product (no penalty bucket, no overshoot fold).
     *
     * ```shell
     * WAKFU_MM_C2_BASELINE=1 [WAKFU_MM_PERF_AB_DET=600] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
     * ```
     */
    @Test
    fun `manual campaign-2 baseline matrix S1-S2-S3`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_C2_BASELINE") == "1")
            val det = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            require(det > 0.0) { "WAKFU_MM_PERF_AB_DET must be positive" }
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            // Provability probe (§9.10): drop CONDITIONAL subs to isolate whether the 16 reified
            // conditions (not the stacking — 0 cumulable choosable subs on this data) are the wall.
            val sublimations =
                WakfuBestBuildFinderAlgorithm.sublimations.let {
                    if (System.getenv("WAKFU_MM_C2_NOCONDSUBS") == "1") it.filter { s -> s.condition == null } else it
                }
            val selected =
                System
                    .getenv("WAKFU_MM_C2_BASELINE_FIXTURES")
                    ?.split(',')
                    ?.map { it.trim().uppercase() }
                    ?.toSet()
            val fixtures =
                listOf(
                    Triple("S1", f5Shape(level), true),
                    Triple("S2", frontierShape(level), false),
                    Triple("S3", diIsolateShape(level), false),
                    // §8.2bis S-E: the max-damage soft leg shares the same penalty axis.
                    Triple("S4", mdFrontierShape(level), false)
                ).filter { selected == null || it.first in selected }

            WakfuBuildSolver.warmUp()
            println("MM_C2_BASELINE START det=$det pool=${pool.values.sumOf { it.size }} fixtures=${fixtures.joinToString { it.first }}")
            for ((id, shape, hardLeg) in fixtures) {
                solve(Shape("$id-${shape.label}", shape.params), ExperimentConfig("baseline", hardLeg = hardLeg), det, pool, runes, sublimations)
            }
        }

    /**
     * §8.2 S-A POC: exact outer best-first interval branch-and-bound over the penalty bucket, on S2.
     * Every CP-SAT sub-solve sees a model WITHOUT the `core × multiplier` product: interval nodes
     * maximize the bare core (their proven dual bound × the interval's worst-case multiplier is a
     * sound node bound), singleton nodes get the exact constant-folded objective. Interval solutions
     * feed the incumbent via the (core, bucket) capture — `core × power6(bucket) × SCALE` is a valid
     * achievable lower bound (the overshoot bonus ≥ 0 is dropped).
     *
     * GO gate (plan §8.2/8.1): completes with ≤ 12 sub-solves AND less total deterministic time than
     * the monolithic soft solve, with the exact same folded optimum.
     *
     * ```shell
     * WAKFU_MM_SA_POC=1 [WAKFU_MM_SA_NODE_DET=60] [WAKFU_MM_SA_MAX_SOLVES=24] [WAKFU_MM_PERF_AB_DET=600] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
     * ```
     */
    @Test
    fun `manual S-A outer penalty-bucket branch-and-bound on S2`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_SA_POC") == "1")
            val nodeDet = System.getenv("WAKFU_MM_SA_NODE_DET")?.toDoubleOrNull() ?: 60.0
            val monolithDet = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            val maxSolves = System.getenv("WAKFU_MM_SA_MAX_SOLVES")?.toIntOrNull() ?: 24
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val sublimations = WakfuBestBuildFinderAlgorithm.sublimations
            val shape = frontierShape(level)
            WakfuBuildSolver.warmUp()

            val geometry = AtomicReference<Triple<Int, LongArray, Long>?>(null)
            val lastCapture = AtomicReference<Pair<Long, Long>?>(null)
            var solves = 0
            var totalDetUsed = 0.0

            suspend fun subSolve(
                interval: IntRange,
                folded: Boolean,
            ): WakfuBuildSolver.SolveOutcome? {
                val termination = AtomicReference<WakfuBuildSolver.SolveOutcome?>(null)
                lastCapture.set(null)
                WakfuBuildSolver
                    .optimize(
                        shape.params,
                        pool,
                        runes,
                        sublimations,
                        WakfuBuildSolver.SolverTuning(
                            numSearchWorkers = 1,
                            randomSeed = 1,
                            maxDeterministicTime = nodeDet,
                            interleaveSearch = true,
                            maxPresolveIterationsOverride = 1,
                            linearizationLevelOverride = 1,
                            applyDominationOverride = true,
                            mmPenaltyBucketInterval = interval,
                            mmPenaltyBucketFoldedObjective = folded,
                            mmPenaltyGeometryProbe = { m, p, t -> geometry.compareAndSet(null, Triple(m, p.copyOf(), t)) },
                            mmPenaltyBucketSolutionCapture = { core, bucket -> lastCapture.set(core to bucket) }
                        ),
                        hardConstraints = false,
                        onTermination = { termination.set(it) }
                    ).collect { }
                solves++
                val outcome = termination.get()
                totalDetUsed += outcome?.deterministicTime ?: 0.0
                println(
                    "MM_SA NODE interval=${interval.first}..${interval.last} folded=$folded " +
                        "status=${outcome?.status ?: "NA"} obj=${outcome?.objectiveValue ?: "NA"} " +
                        "bound=${outcome?.bestObjectiveBound ?: "NA"} det=${outcome?.deterministicTime ?: "NA"} " +
                        "capture=${lastCapture.get() ?: "NA"}"
                )
                return outcome
            }

            val scale = WakfuBuildSolver.OVERSHOOT_SCALE

            fun cappedMul(
                a: Long,
                b: Long,
            ): Long {
                val product =
                    java.math.BigInteger
                        .valueOf(a)
                        .multiply(java.math.BigInteger.valueOf(b))
                val cap = java.math.BigInteger.valueOf(Long.MAX_VALUE / 2)
                return when {
                    product > cap -> Long.MAX_VALUE / 2
                    product < cap.negate() -> -(Long.MAX_VALUE / 2)
                    else -> product.toLong()
                }
            }

            // Sound interval bound: the power table is monotone non-decreasing, so a non-negative core
            // is bounded by the TOP multiplier and a negative one by the BOTTOM multiplier (§8.2 review
            // note — an all-negative node must not be over-pruned).
            fun nodeBound(
                coreBound: Long,
                lo: Int,
                hi: Int,
                power: LongArray,
            ): Long = cappedMul(cappedMul(coreBound, if (coreBound >= 0) power[hi] else power[lo]), scale) + (scale - 1)

            val root = subSolve(0..MAX_POWER_TABLE_INDEX, folded = false)
            checkNotNull(root) { "root sub-solve returned no diagnostics" }
            val (maxIndex, power, totalExpected) = requireNotNull(geometry.get()) { "geometry probe did not fire" }
            println("MM_SA GEOMETRY maxIndex=$maxIndex totalExpected=$totalExpected powerMax=${power.last()}")

            var incumbent = Long.MIN_VALUE
            var incumbentBucket = -1

            fun harvestCapture() {
                lastCapture.get()?.let { (core, bucket) ->
                    val value = cappedMul(cappedMul(core, power[bucket.toInt()]), scale)
                    if (value > incumbent) {
                        incumbent = value
                        incumbentBucket = bucket.toInt()
                    }
                }
            }
            harvestCapture()

            data class Node(
                val lo: Int,
                val hi: Int,
                val bound: Long,
            )
            val queue = java.util.PriorityQueue<Node>(compareByDescending { it.bound })
            val exactAtBucket = mutableMapOf<Int, Long>()
            var abandoned = false
            if (root.status != CpSolverStatus.INFEASIBLE) {
                queue.add(Node(0, maxIndex, nodeBound(root.bestObjectiveBound, 0, maxIndex, power)))
            }

            while (queue.isNotEmpty()) {
                val node = queue.poll()
                if (node.bound <= incumbent) {
                    println("MM_SA PRUNE-REST topBound=${node.bound} incumbent=$incumbent queued=${queue.size + 1}")
                    break
                }
                if (solves >= maxSolves) {
                    abandoned = true
                    println("MM_SA ABANDON solves=$solves cap=$maxSolves topBound=${node.bound} incumbent=$incumbent")
                    break
                }
                if (node.lo == node.hi) {
                    val outcome = subSolve(node.lo..node.hi, folded = true)
                    when {
                        outcome?.status == CpSolverStatus.OPTIMAL && outcome.objectiveValue != null -> {
                            exactAtBucket[node.lo] = outcome.objectiveValue!!
                            if (outcome.objectiveValue!! > incumbent) {
                                incumbent = outcome.objectiveValue!!
                                incumbentBucket = node.lo
                            }
                        }
                        outcome?.status == CpSolverStatus.INFEASIBLE -> Unit
                        else -> abandoned = true
                    }
                } else {
                    val mid = (node.lo + node.hi) / 2
                    for (sub in listOf(node.lo..mid, (mid + 1)..node.hi)) {
                        val outcome = subSolve(sub, folded = false)
                        if (outcome == null) {
                            abandoned = true
                            continue
                        }
                        harvestCapture()
                        if (outcome.status != CpSolverStatus.INFEASIBLE) {
                            val childBound = minOf(node.bound, nodeBound(outcome.bestObjectiveBound, sub.first, sub.last, power))
                            queue.add(Node(sub.first, sub.last, childBound))
                        }
                    }
                }
                if (abandoned) break
            }

            // Exactness: an incumbent sourced from an interval capture dropped its overshoot bonus —
            // re-solve its bucket folded so the reported optimum is the exact lexicographic value.
            if (!abandoned && incumbentBucket >= 0 && incumbentBucket !in exactAtBucket) {
                val outcome = subSolve(incumbentBucket..incumbentBucket, folded = true)
                if (outcome?.status == CpSolverStatus.OPTIMAL && outcome.objectiveValue != null) {
                    exactAtBucket[incumbentBucket] = outcome.objectiveValue!!
                } else {
                    abandoned = true
                }
            }
            val outerOptimum = exactAtBucket.values.maxOrNull()
            val goGate = !abandoned && solves <= 12
            println(
                "MM_SA RESULT solves=$solves totalDet=${"%.4f".format(java.util.Locale.ROOT, totalDetUsed)} " +
                    "abandoned=$abandoned outerOptimum=${outerOptimum ?: "NA"} bucket=$incumbentBucket goGateSolves=$goGate"
            )

            val monolith = solve(Shape("S2-monolith", shape.params), ExperimentConfig("softBaseline", hardLeg = false), monolithDet, pool, runes, sublimations)
            if (!abandoned && outerOptimum != null && monolith.status == CpSolverStatus.OPTIMAL) {
                assertThat(outerOptimum)
                    .describedAs("S-A outer optimum must equal the monolithic soft folded optimum")
                    .isEqualTo(monolith.rawObjective)
            }
            println(
                "MM_SA COMPARE outerSolves=$solves outerDet=${"%.4f".format(java.util.Locale.ROOT, totalDetUsed)} " +
                    "monolithDet=${monolith.deterministicTime} monolithStatus=${monolith.status ?: "NA"} " +
                    "verdict=${if (goGate && outerOptimum != null && totalDetUsed < monolith.deterministicTime) "GO-CANDIDATE" else "CHECK"}"
            )
        }

    /**
     * §8.5 S-D A/B: recycle the hard leg's proven infeasibility as a soft no-good.
     *
     * Six same-JVM arms: (1-2) the S1 hard leg plain vs assumption-gated — reification must not
     * change the optimum, and its det delta prices the seam; (3-4) the frontier hard leg plain vs
     * assumption-gated — both must prove INFEASIBLE, arm 4 also yields the sufficient core;
     * (5-6) the S2 soft model without vs with the recycled no-good cut — logically implied, so both
     * must reach the same folded optimum; the measured question is the det trajectory.
     *
     * DROP conditions (plan §8.5): the core names every target AND the soft trajectory is unchanged.
     *
     * ```shell
     * WAKFU_MM_SD_AB=1 [WAKFU_MM_PERF_AB_DET=600] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
     * ```
     */
    @Test
    fun `manual S-D infeasibility-core no-good A-B`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_SD_AB") == "1")
            val det = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val sublimations = WakfuBestBuildFinderAlgorithm.sublimations
            val f5 = f5Shape(level)
            val frontier = frontierShape(level)
            WakfuBuildSolver.warmUp()
            println("MM_SD START det=$det pool=${pool.values.sumOf { it.size }}")

            // Arms 1-2: does the assumption gating tax the REACHABLE hard leg?
            val hardPlain = solve(Shape("SD-s1-plain", f5.params), ExperimentConfig("hardPlain"), det, pool, runes, sublimations)
            val hardAssume =
                solve(Shape("SD-s1-assume", f5.params), ExperimentConfig("hardAssume", hardAssumptions = true), det, pool, runes, sublimations)
            if (hardPlain.isOptimal && hardAssume.isOptimal) {
                assertThat(hardAssume.rawObjective)
                    .describedAs("assumption-gated hard leg: same optimum as the plain constraints")
                    .isEqualTo(hardPlain.rawObjective)
            }
            println(
                "MM_SD HARD-TAX plainDet=${hardPlain.deterministicTime} assumeDet=${hardAssume.deterministicTime} " +
                    "plainStatus=${hardPlain.status ?: "NA"} assumeStatus=${hardAssume.status ?: "NA"}"
            )

            // Arms 3-4: the INFEASIBLE frontier — arm 4 extracts the sufficient core.
            val core = AtomicReference<Set<Characteristic>?>(null)
            val infPlain = solve(Shape("SD-frontier-plain", frontier.params), ExperimentConfig("hardPlain"), det, pool, runes, sublimations)
            val infAssume =
                solve(
                    Shape("SD-frontier-assume", frontier.params),
                    ExperimentConfig("hardAssume", hardAssumptions = true),
                    det,
                    pool,
                    runes,
                    sublimations,
                    onCore = { core.set(it) }
                )
            println(
                "MM_SD CORE core=${core.get()?.joinToString() ?: "NA"} " +
                    "plainInfDet=${infPlain.deterministicTime} assumeInfDet=${infAssume.deterministicTime} " +
                    "plainStatus=${infPlain.status ?: "NA"} assumeStatus=${infAssume.status ?: "NA"}"
            )
            val extractedCore = core.get()
            if (extractedCore.isNullOrEmpty()) {
                println("MM_SD RESULT verdict=NO-CORE (no sufficient assumptions returned) — soft A/B skipped")
                return@runBlocking
            }

            // Arms 5-6: the soft model without vs with the recycled no-good.
            val softBaseline =
                solve(Shape("SD-s2-soft", frontier.params), ExperimentConfig("softBaseline", hardLeg = false), det, pool, runes, sublimations)
            val softNoGood =
                solve(
                    Shape("SD-s2-noGood", frontier.params),
                    ExperimentConfig("softNoGood", hardLeg = false, noGoodCore = extractedCore),
                    det,
                    pool,
                    runes,
                    sublimations
                )
            if (softBaseline.isOptimal && softNoGood.isOptimal) {
                assertThat(softNoGood.rawObjective)
                    .describedAs("the implied no-good must not change the soft optimum")
                    .isEqualTo(softBaseline.rawObjective)
                assertThat(softNoGood.scoredObjective)
                    .describedAs("the implied no-good must not change the scored optimum")
                    .isEqualByComparingTo(softBaseline.scoredObjective)
            }
            val fullCore = extractedCore.size == frontier.params.targetStats.count { it.characteristic.isRequiredMostMasteriesTarget() }
            println(
                "MM_SD COMPARE coreSize=${extractedCore.size} fullCore=$fullCore " +
                    "softBaselineDet=${softBaseline.deterministicTime} softNoGoodDet=${softNoGood.deterministicTime} " +
                    "baselineStatus=${softBaseline.status ?: "NA"} noGoodStatus=${softNoGood.status ?: "NA"} " +
                    "verdict=${if (softNoGood.deterministicTime < softBaseline.deterministicTime * 0.85) "GO-CANDIDATE" else "CHECK"}"
            )
        }

    /**
     * §8.4 S-C POC: exact outer best-first interval branch-and-bound over the DI FACTOR, on S3
     * (mono-element, no required stat — the bare mastery×DI product is the whole objective).
     * Unlike S-A, every sub-model here is fully LINEAR: interval nodes maximize the bare
     * non-negative tier M (factor constrained, product removed), singletons get `⌊M × d/100⌋` with a
     * constant d. A captured interval solution's `⌊M* × d* / 100⌋` is already the EXACT objective of
     * a real build, so incumbents are exact from the first node.
     *
     * GO gate (plan §8.4/8.1): completes with ≤ 12 sub-solves AND less total deterministic time than
     * the monolithic S3 solve (baseline det 97.98), with the exact optimum (baseline 10 985).
     *
     * ```shell
     * WAKFU_MM_SC_POC=1 [WAKFU_MM_SC_NODE_DET=60] [WAKFU_MM_SC_MAX_SOLVES=24] [WAKFU_MM_PERF_AB_DET=600] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
     * ```
     */
    @Test
    fun `manual S-C outer DI-factor branch-and-bound on S3`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_SC_POC") == "1")
            val nodeDet = System.getenv("WAKFU_MM_SC_NODE_DET")?.toDoubleOrNull() ?: 60.0
            val monolithDet = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            val maxSolves = System.getenv("WAKFU_MM_SC_MAX_SOLVES")?.toIntOrNull() ?: 24
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val sublimations = WakfuBestBuildFinderAlgorithm.sublimations
            val shape = diIsolateShape(level)
            WakfuBuildSolver.warmUp()

            val lastCapture = AtomicReference<Pair<Long, Long>?>(null)
            var solves = 0
            var totalDetUsed = 0.0

            suspend fun subSolve(
                interval: IntRange,
                folded: Boolean,
            ): WakfuBuildSolver.SolveOutcome? {
                val termination = AtomicReference<WakfuBuildSolver.SolveOutcome?>(null)
                lastCapture.set(null)
                WakfuBuildSolver
                    .optimize(
                        shape.params,
                        pool,
                        runes,
                        sublimations,
                        WakfuBuildSolver.SolverTuning(
                            numSearchWorkers = 1,
                            randomSeed = 1,
                            maxDeterministicTime = nodeDet,
                            interleaveSearch = true,
                            maxPresolveIterationsOverride = 1,
                            linearizationLevelOverride = 1,
                            applyDominationOverride = true,
                            mmDiFactorInterval = interval,
                            mmDiFactorFoldedObjective = folded,
                            mmPenaltyBucketSolutionCapture = { tier, factor -> lastCapture.set(tier to factor) }
                        ),
                        hardConstraints = false,
                        onTermination = { termination.set(it) }
                    ).collect { }
                solves++
                val outcome = termination.get()
                totalDetUsed += outcome?.deterministicTime ?: 0.0
                println(
                    "MM_SC NODE interval=${interval.first}..${interval.last} folded=$folded " +
                        "status=${outcome?.status ?: "NA"} obj=${outcome?.objectiveValue ?: "NA"} " +
                        "bound=${outcome?.bestObjectiveBound ?: "NA"} det=${outcome?.deterministicTime ?: "NA"} " +
                        "capture=${lastCapture.get() ?: "NA"}"
                )
                return outcome
            }

            val axisLo = (100L - DAMAGE_DI_FLOOR).toInt()
            val axisHi = (100L + DAMAGE_DI_MAX).toInt()

            // Exact ⌊tier × factor / 100⌋ — both node bounds and captured incumbents live in the
            // monolith's own objective units (its clamps are no-ops at these magnitudes).
            fun folded(
                tier: Long,
                factor: Long,
            ): Long = tier.coerceAtLeast(0L) * factor / 100L

            var incumbent = Long.MIN_VALUE
            var incumbentFactor = -1

            fun harvestCapture() {
                lastCapture.get()?.let { (tier, factor) ->
                    val value = folded(tier, factor)
                    if (value > incumbent) {
                        incumbent = value
                        incumbentFactor = factor.toInt()
                    }
                }
            }

            data class Node(
                val lo: Int,
                val hi: Int,
                val bound: Long,
            )
            val queue = java.util.PriorityQueue<Node>(compareByDescending { it.bound })
            val exactAtFactor = mutableMapOf<Int, Long>()
            var abandoned = false

            val root = subSolve(axisLo..axisHi, folded = false)
            checkNotNull(root) { "root sub-solve returned no diagnostics" }
            harvestCapture()
            if (root.status != CpSolverStatus.INFEASIBLE) {
                queue.add(Node(axisLo, axisHi, folded(root.bestObjectiveBound, axisHi.toLong())))
            }

            while (queue.isNotEmpty()) {
                val node = queue.poll()
                if (node.bound <= incumbent) {
                    println("MM_SC PRUNE-REST topBound=${node.bound} incumbent=$incumbent queued=${queue.size + 1}")
                    break
                }
                if (solves >= maxSolves) {
                    abandoned = true
                    println("MM_SC ABANDON solves=$solves cap=$maxSolves topBound=${node.bound} incumbent=$incumbent")
                    break
                }
                if (node.lo == node.hi) {
                    val outcome = subSolve(node.lo..node.hi, folded = true)
                    when {
                        outcome?.status == CpSolverStatus.OPTIMAL && outcome.objectiveValue != null -> {
                            exactAtFactor[node.lo] = outcome.objectiveValue!!
                            if (outcome.objectiveValue!! > incumbent) {
                                incumbent = outcome.objectiveValue!!
                                incumbentFactor = node.lo
                            }
                        }
                        outcome?.status == CpSolverStatus.INFEASIBLE -> Unit
                        else -> abandoned = true
                    }
                } else {
                    val mid = (node.lo + node.hi) / 2
                    for (sub in listOf(node.lo..mid, (mid + 1)..node.hi)) {
                        val outcome = subSolve(sub, folded = false)
                        if (outcome == null) {
                            abandoned = true
                            continue
                        }
                        harvestCapture()
                        if (outcome.status != CpSolverStatus.INFEASIBLE) {
                            val childBound = minOf(node.bound, folded(outcome.bestObjectiveBound, sub.last.toLong()))
                            queue.add(Node(sub.first, sub.last, childBound))
                        }
                    }
                }
                if (abandoned) break
            }

            val outerOptimum = if (incumbent == Long.MIN_VALUE) null else incumbent
            val goGate = !abandoned && solves <= 12
            println(
                "MM_SC RESULT solves=$solves totalDet=${"%.4f".format(java.util.Locale.ROOT, totalDetUsed)} " +
                    "abandoned=$abandoned outerOptimum=${outerOptimum ?: "NA"} factor=$incumbentFactor " +
                    "singletonsProven=${exactAtFactor.size} goGateSolves=$goGate"
            )

            val monolith = solve(Shape("S3-monolith", shape.params), ExperimentConfig("softBaseline", hardLeg = false), monolithDet, pool, runes, sublimations)
            if (!abandoned && outerOptimum != null && monolith.status == CpSolverStatus.OPTIMAL) {
                assertThat(outerOptimum)
                    .describedAs("S-C outer optimum must equal the monolithic S3 optimum")
                    .isEqualTo(monolith.rawObjective)
            }
            println(
                "MM_SC COMPARE outerSolves=$solves outerDet=${"%.4f".format(java.util.Locale.ROOT, totalDetUsed)} " +
                    "monolithDet=${monolith.deterministicTime} monolithStatus=${monolith.status ?: "NA"} " +
                    "verdict=${if (goGate && outerOptimum != null && totalDetUsed < monolith.deterministicTime) "GO-CANDIDATE" else "CHECK"}"
            )
        }

    /**
     * Piste 4: the M3 prototype bound injected as a redundant `masteryScore ≤ U` dual cut on the SOFT
     * (penalized) model — the remaining slow workload after the frontier INFEASIBLE reclassification
     * (plan §7.4). The cut is redundant for any sound U, so both arms must prove the same optimum;
     * the measured question is proof time against the P0.5 by-exhaustion dual wall.
     *
     * ```shell
     * WAKFU_MM_SOFT_CUT_AB=1 [WAKFU_MM_PERF_AB_DET=600] [WAKFU_MM_SOFT_CUT_U=<override>] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*'
     * ```
     */
    @Test
    fun `manual soft-leg M3-cut A-B`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_SOFT_CUT_AB") == "1")
            val det = System.getenv("WAKFU_MM_PERF_AB_DET")?.toDoubleOrNull() ?: 600.0
            val level = 245
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val sublimations = WakfuBestBuildFinderAlgorithm.sublimations
            val shape = selectedShapes(level).first { it.label == "f5" }

            val bound =
                System.getenv("WAKFU_MM_SOFT_CUT_U")?.toLongOrNull()
                    ?: requireNotNull(MostMasteriesBoundPrototype.bound(shape.params, pool, runes, sublimations)) {
                        "M3 prototype bailed on the f5 shape; pass WAKFU_MM_SOFT_CUT_U explicitly"
                    }
            WakfuBuildSolver.warmUp()
            println("MM_SOFT_CUT START det=$det bound=$bound pool=${pool.values.sumOf { it.size }}")

            val arms =
                listOf(
                    ExperimentConfig("softBaseline", hardLeg = false),
                    ExperimentConfig("softM3Cut", hardLeg = false, masteryScoreUpperBound = bound)
                )
            val summaries = arms.map { config -> solve(shape, config, det, pool, runes, sublimations) }
            val (baseline, cut) = summaries
            if (baseline.isOptimal && cut.isOptimal) {
                assertThat(cut.rawObjective)
                    .describedAs("softM3Cut: the redundant cut must not change the raw optimum")
                    .isEqualTo(baseline.rawObjective)
                assertThat(cut.scoredObjective)
                    .describedAs("softM3Cut: the redundant cut must not change the scored optimum")
                    .isEqualByComparingTo(baseline.scoredObjective)
            }
            val ratio = baseline.wallMs.toDouble() / cut.wallMs.coerceAtLeast(1)
            println(
                "MM_SOFT_CUT COMPARE bound=$bound baselineMs=${baseline.wallMs} cutMs=${cut.wallMs} " +
                    "baselineOverCut=${"%.3f".format(java.util.Locale.ROOT, ratio)} " +
                    "baselineDet=${baseline.deterministicTime} cutDet=${cut.deterministicTime} " +
                    "baselineStatus=${baseline.status ?: "NA"} cutStatus=${cut.status ?: "NA"}"
            )
        }

    private suspend fun solve(
        shape: Shape,
        config: ExperimentConfig,
        det: Double,
        pool: Map<ItemType, List<me.chosante.common.Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        onCore: ((Set<Characteristic>) -> Unit)? = null,
    ): Summary {
        val termination = AtomicReference<WakfuBuildSolver.SolveOutcome?>()
        // §9.10 REAL-PARALLELISM probe: `tuning == null` takes the PRODUCTION path — a wall-clock
        // parallel portfolio on (cores−1) threads, NOT the deterministic det-budget mode (which
        // interleaves workers for reproducibility and so runs on ~1 physical core even at
        // numSearchWorkers=8). Every prior "multi-worker" S4 run was actually deterministic mode;
        // this tests whether REAL 8-core wall-clock parallelism proves the sub-heavy soft leg.
        val prodParallel = System.getenv("WAKFU_MM_C2_PROD") == "1"
        val tuning =
            if (prodParallel) {
                null
            } else {
                WakfuBuildSolver.SolverTuning(
                // Canonical protocol = 1 worker. WAKFU_MM_C2_WORKERS overrides for INCUMBENT
                // banking only (S4-0b: push/prove the reference optimum) — multi-worker det/wall
                // numbers must never be compared against 1-worker arms.
                numSearchWorkers = System.getenv("WAKFU_MM_C2_WORKERS")?.toIntOrNull() ?: 1,
                randomSeed = 1,
                maxDeterministicTime = det,
                interleaveSearch = true,
                maxPresolveIterationsOverride = 1,
                linearizationLevelOverride = 1,
                applyDominationOverride = true,
                mmOvershootEncoding = config.overshootEncoding,
                mmProductEncoding = config.productEncoding,
                mmMasteryScoreUpperBound = config.masteryScoreUpperBound,
                mmHardTargetsAsAssumptions = config.hardAssumptions,
                mmSoftNoGoodCore = config.noGoodCore,
                mmInfeasibilityCoreCapture = onCore
                )
            }
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
                hardConstraints = config.hardLeg,
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

    private fun f5Shape(level: Int) =
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

    private fun frontierShape(level: Int) =
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

    /** No required stat at all: the objective is the bare mastery×DI product (plan §8.1 S3). */
    private fun diIsolateShape(level: Int) = Shape("diIsolate", params(level, emptyList()))

    /**
     * §8.2bis S-E: the MAX-DAMAGE soft leg on the frontier's unreachable targets — the same
     * required-target penalty product wraps the damage core (no overshoot fold, no survivability
     * floor by default), so the outer bucket decomposition transfers if S-A proves out on S2.
     */
    private fun mdFrontierShape(level: Int) =
        Shape(
            "mdFrontier",
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 16),
                            TargetStat(Characteristic.MOVEMENT_POINT, 8),
                            TargetStat(Characteristic.CRITICAL_HIT, 100),
                            TargetStat(Characteristic.HP, 12000)
                        )
                    ),
                // Production (WAKFU_MM_C2_PROD) uses this as the WALL-clock budget — override it.
                searchDuration = (System.getenv("WAKFU_MM_C2_WALL_SECONDS")?.toLongOrNull() ?: 600L).seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = System.getenv("WAKFU_MM_C2_NORUNES") != "1",
                // Regression probe (2026-07-15): the sub machinery (stacking copy chains + condition
                // reification) may be the CP-SAT provability wall. WAKFU_MM_C2_NOSUBS=1 tests whether
                // the soft penalized model proves WITHOUT it — the shape the pre-sub-features model was.
                useSublimations = System.getenv("WAKFU_MM_C2_NOSUBS") != "1"
            )
        )

    private fun selectedShapes(level: Int): List<Shape> =
        when (val selected = System.getenv("WAKFU_MM_PERF_AB_SHAPE")?.lowercase() ?: "all") {
            "all" -> listOf(f5Shape(level), frontierShape(level))
            "f5" -> listOf(f5Shape(level))
            "frontier" -> listOf(frontierShape(level))
            else -> error("Unknown WAKFU_MM_PERF_AB_SHAPE=$selected; expected f5, frontier, or all")
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
