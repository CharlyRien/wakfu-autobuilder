package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How long the free max-damage runes + subs model takes to give CP-SAT its FIRST solution — the PR-CI guard for what the
 * nightly short-budget proof tests caught late. The max-damage proof (the certificate badge and the E8 construct rescue)
 * reads the incumbent's raw proxy, which only a solver solution carries: a search that ends before CP-SAT's first solution
 * shows the greedy warm start and gets no proof at all. The rune choice collapse of CERTIFIER_VERSION 54 (explicit rune
 * picks while a Neutralité-family cap is choosable) moved that first solution from det ≈ 2.7 to det ≈ 14.6 on this request
 * (production pool), and the nightly's det-10 / 3 s searches stopped producing a proof (`docs/perf-review-backlog.md` §E).
 *
 * Measured with the deterministic protocol (1 worker + interleave + seed 1: det time does not depend on the machine) on the
 * production pool (domination on), stopped at the first solution.
 *
 * This lock only guards against FURTHER growth: [DET_BUDGET] sits above today's cost, which was accepted as the price of the
 * exact Neutralité model, so it would not have caught that change itself (det 17.6 when it landed). A tighter budget — about
 * 6, twice the det ≈ 2.7 before it — only makes sense once the search splits into a cap-free and a capped world (the open
 * follow-up in the same §E entry), whose cap-free world is the old compact collapse. A model change that needs more raises
 * [DET_BUDGET] with a measured reason, and checks that the short-search proof tests (`WakfuBuildSolverTest`, @Tag("slow"))
 * still have the budget they need. An OR-Tools bump can move det time: re-measure before raising.
 */
class MaxDamageFirstSolutionLatencyTest {
    private companion object {
        // Measured 14.6 (CERTIFIER_VERSION 56, Wakfu data 1.93, OR-Tools as pinned); 8.95 / 10.27 with the mixed-rune count
        // carriers (CERTIFIER_VERSION 57, before / after merging the item equip conditions) — a tuned solve varies between JVM
        // runs (AGENTS.md §9), so the budget keeps its headroom.
        const val DET_BUDGET = 20.0
    }

    @Test
    fun `the free level-110 runes and subs max-damage model reaches a first solution within its det budget`(): Unit =
        runBlocking {
            val level = 110
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats = TargetStats(emptyList()),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = true,
                    useSublimations = true,
                    damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                )
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .map { it.atLevel(level) }
                    .groupBy { it.itemType }
            var outcome: WakfuBuildSolver.SolveOutcome? = null
            val results =
                WakfuBuildSolver
                    .optimize(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        WakfuBuildSolver.SolverTuning(
                            numSearchWorkers = 1,
                            interleaveSearch = true,
                            maxDeterministicTime = DET_BUDGET,
                            applyDominationOverride = true,
                            stopAtFirstSolution = true
                        ),
                        onTermination = { outcome = it }
                    ).toList()
            // The measured value, for the next re-baseline (the test JVM's stdout lands in the JUnit report).
            println("first solution at det ${outcome?.deterministicTime} (budget $DET_BUDGET, status ${outcome?.status})")
            assertThat(results)
                .describedAs(
                    "CP-SAT found no solution within det %s (status %s, det used %s): the model now needs longer for its FIRST " +
                        "solution, so short searches end on the greedy warm start, without a proof",
                    DET_BUDGET,
                    outcome?.status,
                    outcome?.deterministicTime
                ).isNotEmpty
            assertThat(results.last().maxDamageRawProxy).describedAs("a solver solution carries the raw proxy the proof reads").isNotNull
        }
}
