package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The stat-gate perf report (run on demand: `WAKFU_STAT_GATES_PERF=1`): the model size and the time to CP-SAT's first solution
 * of the GUI default requests (Cra, the default rows: AP 11, MP 4, range 4, crit 25, distance mastery, HP 2000, air resistance 0,
 * dodge 0; runes and sublimations on), most-masteries and max-damage, hard leg, production pool (domination on), with the
 * deterministic protocol (1 worker, seed 1, interleave). Level 110 is the GUI default; level 200 adds the gated end-game items
 * (Cartes And, the "max AP ≤ 11" belts, the lock / dodge / block / distance-mastery bands). The same file runs on `main` to
 * compare. Prints one `STAT_GATES_PERF` line per request.
 */
class StatGatesPerfHarnessTest {
    private fun params(
        level: Int,
        mode: ScoreComputationMode,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats =
            TargetStats(
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
            ),
        searchDuration = 120.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useSublimations = true,
        damageScenario = DamageScenario()
    )

    @Test
    fun `report the model size and the first-solution time of the GUI default requests`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_STAT_GATES_PERF") == "1")
            for (level in listOf(110, 200)) {
                for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
                    val p = params(level, mode)
                    val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                    val runes = WakfuBestBuildFinderAlgorithm.runes
                    val subs = WakfuBestBuildFinderAlgorithm.sublimations
                    val (vars, constraints, items) = WakfuBuildSolver.modelSizeForTest(p, pool, runes, subs, hardConstraints = true)
                    var outcome: WakfuBuildSolver.SolveOutcome? = null
                    val start = System.nanoTime()
                    WakfuBuildSolver
                        .optimize(
                            p,
                            pool,
                            runes,
                            subs,
                            WakfuBuildSolver.SolverTuning(
                                numSearchWorkers = 1,
                                randomSeed = 1,
                                interleaveSearch = true,
                                maxDeterministicTime = 120.0,
                                applyDominationOverride = true,
                                stopAtFirstSolution = true
                            ),
                            hardConstraints = true,
                            onTermination = { outcome = it }
                        ).toList()
                    val wallMs = (System.nanoTime() - start) / 1_000_000
                    println(
                        "STAT_GATES_PERF level=$level mode=$mode items=$items vars=$vars constraints=$constraints " +
                            "firstSolutionDet=${outcome?.deterministicTime} wallMs=$wallMs status=${outcome?.status}"
                    )
                }
            }
        }
}
