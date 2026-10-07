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
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * docs/MOST_MASTERIES_PERF_PLAN.md §8.20 — the GUI-DEFAULT most-masteries request on the PRODUCTION path (manual,
 * env-gated; the port of the perf pass's `PerfBaselineE0Test` most-masteries leg). CRA, runes + subs, max rarity EPIC,
 * wall-clock budget, and the GUI's default target rows exactly as `BuildSearchModel.toTargetStats` sends them in
 * most-masteries mode (`UiState.defaultTargetValues`: RANGE 4 and the 0-valued wind-resistance / dodge rows included —
 * `expandGlobalResistance` only drops 0-valued element rows when a global resistance row exists, and the defaults have
 * none). Per level:
 *  - the search's milestones (first build, last improvement, end, CP-SAT proof, hard-leg provenance);
 *  - the production badge ([WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality]: the search-time warm-up's memoized
 *    bound, so its delay is measured from the search's END);
 *  - the certificate's own verdict on the final incumbent even when CP-SAT proved it (the production badge then stops
 *    at "ProvenOptimal" without reading the bound), with both reads and the incumbent's core.
 *
 * Mimic a 4-core laptop with the shipped GUI heap:
 * ```shell
 * WAKFU_MM_COVERAGE=1 [WAKFU_MM_COVERAGE_LEVELS=245,110] [WAKFU_MM_COVERAGE_SECONDS=120] \
 *   [WAKFU_MM_COVERAGE_BOUND_ONLY=1] WAKFU_TEST_MAX_HEAP=3g WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MostMasteriesCoverageHarnessTest*' --rerun
 * ```
 * Output: `MMCOV …` lines in the JUnit XML (`BOUND_ONLY`: the bound alone, no search).
 */
class MostMasteriesCoverageHarnessTest {
    private fun guiDefaultTargets(): List<TargetStat> =
        listOf(
            TargetStat(Characteristic.ACTION_POINT, 11),
            TargetStat(Characteristic.MOVEMENT_POINT, 4),
            TargetStat(Characteristic.RANGE, 4),
            TargetStat(Characteristic.CRITICAL_HIT, 25),
            TargetStat(Characteristic.MASTERY_DISTANCE, 1),
            TargetStat(Characteristic.HP, 2000),
            TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0),
            TargetStat(Characteristic.DODGE, 0)
        )

    private fun params(
        level: Int,
        seconds: Long,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(guiDefaultTargets()),
        searchDuration = seconds.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = true,
        useSublimations = true
    )

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun describe(p: WakfuBestBuildFinderAlgorithm.MostMasteriesProof): String =
        when (p) {
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal -> "ProvenOptimal"
            is WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin -> "ProvenWithin(${"%.2f".format(Locale.ROOT, p.percent * 100)}%)"
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable -> "Unavailable"
        }

    @Test
    fun `manual GUI-default most-masteries badge on the production path`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_COVERAGE") == "1")
            val seconds = System.getenv("WAKFU_MM_COVERAGE_SECONDS")?.toLongOrNull() ?: 120L
            val levels =
                System
                    .getenv("WAKFU_MM_COVERAGE_LEVELS")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?: listOf(245, 110)
            // WAKFU_MM_COVERAGE_BOUND_ONLY=1: skip the search — the bound alone (all stage workers), its reads and wall.
            val boundOnly = System.getenv("WAKFU_MM_COVERAGE_BOUND_ONLY") == "1"
            val rt = Runtime.getRuntime()
            println("MMCOV ENV cores=${rt.availableProcessors()} maxHeapMb=${rt.maxMemory() / 1_048_576} seconds=$seconds levels=$levels boundOnly=$boundOnly")
            WakfuBuildSolver.warmUp()
            for (level in levels) {
                val p = params(level, seconds)
                val supported = MostMasteriesCertificate.supportsRequest(p, WakfuBestBuildFinderAlgorithm.activeSublimations(p))
                if (boundOnly) {
                    val b0 = System.nanoTime()
                    val bound = WakfuBestBuildFinderAlgorithm.mostMasteriesQualityBound(p)
                    println(
                        "MMCOV BOUND level=$level supportsRequest=$supported bound=${bound != null} soft=${bound?.foldedBound} " +
                            "targetsMet=${bound?.hardFoldedBound} core=${bound?.coreBound} targetsMetCore=${bound?.hardCoreBound} " +
                            "states=${bound?.states} wallMs=${bound?.wallMs} callMs=${ms(b0)} binding=${bound?.hardBindingState}"
                    )
                    continue
                }
                val t0 = System.nanoTime()
                var firstMs: Long? = null
                var lastImproveMs = 0L
                var bestObjective: Long? = null
                var last: SolverResult<BuildCombination>? = null
                WakfuBestBuildFinderAlgorithm.run(p).collect { r ->
                    val t = ms(t0)
                    if (firstMs == null) firstMs = t
                    val objective = r.mostMasteriesObjective
                    if (objective != null && (bestObjective == null || objective > bestObjective!!)) {
                        bestObjective = objective
                        lastImproveMs = t
                    }
                    last = r
                }
                val endMs = ms(t0)
                val final = checkNotNull(last) { "level $level: no emission" }
                println(
                    "MMCOV SEARCH level=$level supportsRequest=$supported firstMs=$firstMs lastImproveMs=$lastImproveMs endMs=$endMs " +
                        "optimal=${final.isOptimal} hardLeg=${final.mostMasteriesHardConstraintsMet} objective=${final.mostMasteriesObjective}"
                )
                val p0 = System.nanoTime()
                val badge = WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, final)
                println("MMCOV BADGE level=$level verdict=${describe(badge)} afterSearchEndMs=${ms(p0)}")
                // The certificate's own verdict on this incumbent (memoized bound when the warm-up ran; computed here, all
                // workers, otherwise) — what the badge reads whenever CP-SAT has not proven the result itself.
                val c0 = System.nanoTime()
                val bound = WakfuBestBuildFinderAlgorithm.mostMasteriesQualityBound(p)
                val boundMs = ms(c0)
                val objective = final.mostMasteriesObjective
                if (bound == null || objective == null) {
                    println("MMCOV CERT level=$level bound=${bound != null} objective=$objective (no comparison)")
                    continue
                }
                val certOnly = WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(p, bound, final.copy(isOptimal = false))
                val incumbentCore = MostMasteriesCertificate.fullTargetsMultiplier(p)?.let { objective / (it * WakfuBuildSolver.OVERSHOOT_SCALE) }
                println(
                    "MMCOV CERT level=$level verdict=${describe(certOnly)} soft=${bound.foldedBound} targetsMet=${bound.hardFoldedBound} " +
                        "core=${bound.coreBound} targetsMetCore=${bound.hardCoreBound} incumbent=$objective incumbentCore=$incumbentCore " +
                        "states=${bound.states} wallMs=${bound.wallMs} readMs=$boundMs binding=${bound.hardBindingState}"
                )
            }
        }
}
