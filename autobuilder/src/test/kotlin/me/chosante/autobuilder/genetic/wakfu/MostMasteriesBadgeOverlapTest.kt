package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * docs/MOST_MASTERIES_PERF_PLAN.md §8.19 — E10-for-MM measurement harness (manual, env-gated). On the S2 frontier
 * request (lvl-245 CRA, distance mastery + AP16/MP8/CC100/HP12000, runes + subs, max rarity EPIC — CP-SAT does not
 * prove it within a short budget on a 4-core laptop) it measures:
 *
 *  - `cert`: the full-tier bound alone at the default stage-worker count vs ONE worker (+ the quick tier), with the
 *    pool / domination build;
 *  - `det`: the search's THROUGHPUT under a concurrent certificate, race-free — a 1-worker deterministic hard-leg
 *    solve (fixed det budget ⇒ identical trajectory by construction) timed alone vs beside a certificate looping at 1
 *    worker (LOAD1) or at the default worker count (LOADP): the wall-time ratio is the slowdown;
 *  - `search`: the GUI-like production path (wall-clock budget, cores − 1 workers), per budget and arm — `OFF` = no
 *    warm-up, then the OLD post-search quick → full chain (the "before" badge); `ON` = the production warm-up schedule
 *    ([MostMasteriesBoundCache.warmupStartDelay]) + the one-pass proof (the "after" badge); `ON0` = the warm-up from
 *    the search's start; `LOAD1` / `LOADP` = a certificate looping for the WHOLE budget. Per run: the improvement
 *    trajectory (`EMIT` lines), first build, last improvement, final objective, CP-SAT det time at the deadline, the
 *    warm-up's start / landing time, and the badge's delay after search end.
 *
 * ```shell
 * WAKFU_MM_OVERLAP=1 [WAKFU_MM_OVERLAP_PARTS=cert,det,search] [WAKFU_MM_OVERLAP_SECONDS=45,75] \
 *   [WAKFU_MM_OVERLAP_ARMS=OFF,ON] [WAKFU_MM_OVERLAP_REPS=2] [WAKFU_MM_OVERLAP_DET=15] \
 *   WAKFU_TEST_MAX_HEAP=3g WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MostMasteriesBadgeOverlapTest*' --rerun
 * ```
 *
 * Output: `MMOV …` lines in the JUnit XML.
 */
class MostMasteriesBadgeOverlapTest {
    private fun s2(seconds: Long): WakfuBestBuildParams {
        val level = 245
        return WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
            targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                        TargetStat(Characteristic.ACTION_POINT, 16),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 12000)
                    )
                ),
            searchDuration = seconds.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            useRunes = true,
            useSublimations = true
        )
    }

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun pct(
        bound: Long,
        incumbent: Long?,
    ): String = if (incumbent == null || incumbent <= 0) "NA" else "%.2f".format(Locale.ROOT, (bound.toDouble() / incumbent - 1) * 100)

    private fun describe(p: WakfuBestBuildFinderAlgorithm.MostMasteriesProof): String =
        when (p) {
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal -> "ProvenOptimal"
            is WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin -> "ProvenWithin(${"%.2f".format(Locale.ROOT, p.percent * 100)}%)"
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable -> "Unavailable"
        }

    /** A certificate looping on [workers] stage workers until stopped — the LOAD arms. */
    private class Load(
        params: WakfuBestBuildParams,
        workers: Int,
    ) {
        private val stop = AtomicBoolean(false)
        val completed = AtomicInteger()
        private val worker =
            thread(name = "mmov-load", isDaemon = true) {
                val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(params)
                val shape = requireNotNull(dominationShape(params, subs))
                val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(WakfuBestBuildFinderAlgorithm.poolFor(params), shape)
                while (!stop.get()) {
                    MostMasteriesCertificate.bound(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        subs,
                        shouldContinue = { !stop.get() },
                        parallelism = { workers }
                    ) ?: continue
                    completed.incrementAndGet()
                }
            }

        fun stop(): Int {
            stop.set(true)
            worker.join()
            return completed.get()
        }
    }

    @Test
    fun `manual MM badge overlap measurement`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_OVERLAP") == "1")

            fun list(name: String) =
                System
                    .getenv(name)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
            val parts = list("WAKFU_MM_OVERLAP_PARTS")?.toSet() ?: setOf("cert", "det", "search")
            val budgets = list("WAKFU_MM_OVERLAP_SECONDS")?.map { it.toLong() } ?: listOf(45L, 75L)
            val arms = list("WAKFU_MM_OVERLAP_ARMS") ?: listOf("OFF", "ON")
            val reps = System.getenv("WAKFU_MM_OVERLAP_REPS")?.toIntOrNull() ?: 2
            val detBudget = System.getenv("WAKFU_MM_OVERLAP_DET")?.toDoubleOrNull() ?: 15.0
            val rt = Runtime.getRuntime()
            println(
                "MMOV ENV cores=${rt.availableProcessors()} defaultStageWorkers=${LongLongMaxMap.defaultWorkers()} " +
                    "maxHeapMb=${rt.maxMemory() / 1_048_576} budgets=$budgets arms=$arms reps=$reps det=$detBudget parts=$parts"
            )
            WakfuBuildSolver.warmUp()
            val params = s2(budgets.first())
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(params)
            val runes = WakfuBestBuildFinderAlgorithm.runes

            if ("cert" in parts) {
                val p0 = System.nanoTime()
                val base = WakfuBestBuildFinderAlgorithm.poolFor(params)
                val shape = requireNotNull(dominationShape(params, subs))
                val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(base, shape)
                println("MMOV CERT pool ms=${ms(p0)} base=${base.values.sumOf { it.size }} dominated=${pool.values.sumOf { it.size }}")
                for ((label, full, workers) in listOf(
                    Triple("full-default", true, LongLongMaxMap.defaultWorkers()),
                    Triple("full-1", true, 1),
                    Triple("quick-default", false, LongLongMaxMap.defaultWorkers()),
                    Triple("full-default", true, LongLongMaxMap.defaultWorkers()),
                    Triple("full-1", true, 1)
                )) {
                    val t0 = System.nanoTime()
                    val r = requireNotNull(MostMasteriesCertificate.bound(params, pool, runes, subs, blockGate = full, parallelism = { workers }))
                    println("MMOV CERT arm=$label workers=$workers wallMs=${ms(t0)} folded=${r.foldedBound} core=${r.coreBound} states=${r.states}")
                }
            }

            if ("det" in parts) {
                val tuning =
                    WakfuBuildSolver.SolverTuning(
                        numSearchWorkers = 1,
                        randomSeed = 1,
                        interleaveSearch = true,
                        maxDeterministicTime = detBudget,
                        applyDominationOverride = true,
                        greedyWarmStart = true
                    )
                for (rep in 1..reps) {
                    for (arm in listOf("OFF", "LOAD1", "LOADP")) {
                        val load =
                            when (arm) {
                                "LOAD1" -> Load(params, 1)
                                "LOADP" -> Load(params, LongLongMaxMap.defaultWorkers())
                                else -> null
                            }
                        // Let the load reach its steady state (pool + first options) before the timed solve.
                        if (load != null) Thread.sleep(3_000)
                        val term = AtomicReference<WakfuBuildSolver.SolveOutcome?>()
                        val t0 = System.nanoTime()
                        WakfuBuildSolver
                            .optimize(
                                params,
                                WakfuBestBuildFinderAlgorithm.poolFor(params),
                                runes,
                                subs,
                                tuning,
                                hardConstraints = true,
                                onTermination = { term.set(it) }
                            ).collect { }
                        val wall = ms(t0)
                        val loops = load?.stop()
                        val o = term.get()
                        println(
                            "MMOV DET rep=$rep arm=$arm wallMs=$wall status=${o?.status} objective=${o?.objectiveValue} " +
                                "det=${o?.deterministicTime} branches=${o?.branches} conflicts=${o?.conflicts} loadBounds=${loops ?: "-"}"
                        )
                    }
                }
            }

            if ("search" in parts) {
                // JIT / native warm-up of the production search path, discarded.
                runSearch(s2(10), "WARMUP", subs)
                for (seconds in budgets) {
                    for (rep in 1..reps) {
                        for (arm in arms) runSearch(s2(seconds), arm, subs, rep)
                    }
                }
            }
        }

    private suspend fun runSearch(
        params: WakfuBestBuildParams,
        arm: String,
        subs: List<Sublimation>,
        rep: Int = 0,
    ) {
        val budget = params.searchDuration.inWholeSeconds
        val runes = WakfuBestBuildFinderAlgorithm.runes
        // A fresh pool per search, like the GUI (each search builds new params ⇒ a new pool map).
        val base = WakfuBestBuildFinderAlgorithm.poolFor(params)
        val term = AtomicReference<WakfuBuildSolver.SolveOutcome?>()
        // The S2 hard leg is feasible on 1.93 data, so the production hard-then-soft path IS this hard solve.
        val inner = WakfuBuildSolver.optimize(params, base, runes, subs, tuning = null, hardConstraints = true, onTermination = { term.set(it) })
        MostMasteriesBoundCache.clearForTest()
        val warmupStart =
            when (arm) {
                "ON" -> MostMasteriesBoundCache.warmupStartDelay(params.searchDuration)
                "ON0" -> Duration.ZERO
                else -> null
            }
        val flow = if (arm == "ON" || arm == "ON0") MostMasteriesBoundCache.withSearchTimeWarmup(params, base, subs, inner, warmupStart) else inner
        val load =
            when (arm) {
                "LOAD1" -> Load(params, 1)
                "LOADP" -> Load(params, LongLongMaxMap.defaultWorkers())
                else -> null
            }
        val t0 = System.nanoTime()
        // The warm-up's own timeline: when its bound lands in the memo (ms from search start).
        val warmDoneMs = AtomicLong(-1)
        val monitorStop = AtomicBoolean(false)
        val monitor =
            if (warmupStart != null) {
                thread(name = "mmov-monitor", isDaemon = true) {
                    while (!monitorStop.get() && warmDoneMs.get() < 0) {
                        if (MostMasteriesBoundCache.isCachedForTest(params)) warmDoneMs.set(ms(t0))
                        Thread.sleep(25)
                    }
                }
            } else {
                null
            }
        var firstMs = -1L
        var lastImproveMs = -1L
        var best: Long? = null
        var last: SolverResult<BuildCombination>? = null
        flow.collect { r ->
            val t = ms(t0)
            if (firstMs < 0) firstMs = t
            val obj = r.mostMasteriesObjective
            if (obj != null && (best == null || obj > best!!)) {
                best = obj
                lastImproveMs = t
                println("MMOV EMIT budget=$budget rep=$rep arm=$arm tMs=$t objective=$obj")
            }
            last = r
        }
        val endMs = ms(t0)
        val loops = load?.stop()
        val final = checkNotNull(last) { "$arm: no emission" }
        val o = term.get()
        println(
            "MMOV SEARCH budget=$budget rep=$rep arm=$arm firstMs=$firstMs lastImproveMs=$lastImproveMs endMs=$endMs optimal=${final.isOptimal} " +
                "objective=${final.mostMasteriesObjective} status=${o?.status} det=${o?.deterministicTime} " +
                "warmupStartMs=${warmupStart?.inWholeMilliseconds ?: "-"} loadBounds=${loops ?: "-"}"
        )
        when {
            arm == "OFF" -> {
                // BEFORE: the old post-search chain — rebuild the pool (fresh map ⇒ domination recomputed), quick tier,
                // then the full tier on the memoized pool, both at the default stage-worker count.
                val s0 = System.nanoTime()
                val shape = requireNotNull(dominationShape(params, subs))
                val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(WakfuBestBuildFinderAlgorithm.poolFor(params), shape)
                val quick = requireNotNull(MostMasteriesCertificate.bound(params, pool, runes, subs, blockGate = false))
                val quickMs = ms(s0)
                val full = requireNotNull(MostMasteriesCertificate.bound(params, pool, runes, subs, blockGate = true))
                val fullMs = ms(s0)
                println(
                    "MMOV BADGE budget=$budget rep=$rep arm=OFF quickAfterEndMs=$quickMs " +
                        "quick=${describe(WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(params, quick, final))} " +
                        "fullAfterEndMs=$fullMs full=${describe(WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(params, full, final))} " +
                        "gapPct=${pct(full.foldedBound, final.mostMasteriesObjective)}"
                )
            }
            arm == "ON" || arm == "ON0" -> {
                // AFTER: the one-pass proof — reads / joins the warm-up's bound, or computes it on demand when the
                // budget left no room for a warm-up.
                val s0 = System.nanoTime()
                val verdict = WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(params, final)
                val badgeMs = ms(s0)
                monitorStop.set(true)
                monitor?.join()
                println(
                    "MMOV BADGE budget=$budget rep=$rep arm=$arm badgeAfterEndMs=$badgeMs verdict=${describe(verdict)} " +
                        "warmupStartMs=${warmupStart?.inWholeMilliseconds ?: "none"} warmupDoneMs=${warmDoneMs.get()} searchEndMs=$endMs"
                )
            }
        }
        monitorStop.set(true)
    }
}
