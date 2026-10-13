package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Manual DETERMINISTIC A/B of the mixed-rune hybrid (docs/MIXED_RUNE_SECONDARY_BUDGET.md › "The hybrid"): the production
 * max-damage leg ([MaxDamageSearch.optimizeHardThenSoft], greedy warm start on, production pool — domination on) under the
 * canonical protocol (1 worker + interleave + seed 1, a deterministic budget), so the numbers do not depend on the machine's
 * load. Three arms: `fold` (`runeMixedCarriers = false`, the ≤ v61 all-or-nothing fill — inexact), `counts` (the plain count
 * model, v62 as first ported) and `hybrid` ([SolverTuning.mixedRuneHybrid]).
 *
 * ```shell
 * WAKFU_HYBRID_AB=1 WAKFU_HYBRID_AB_FIXTURES=MD110,MD200,MD245,MD245F WAKFU_HYBRID_AB_ARMS=fold,counts,hybrid \
 *   WAKFU_HYBRID_AB_DET=120 WAKFU_HYBRID_AB_LOG=/path WAKFU_MD_HYBRID_DEBUG=1 \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MixedRuneHybridAbTest*' --rerun
 * ```
 * `WAKFU_HYBRID_AB_FIRST=1` also measures each arm's det time to CP-SAT's first solution (the hard leg stopped at it; the
 * hybrid's fold stage IS the fold model, so its first solution is the fold's).
 */
class MixedRuneHybridAbTest {
    private val logFile: File? = System.getenv("WAKFU_HYBRID_AB_LOG")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun log(line: String) {
        synchronized(this) {
            println(line)
            logFile?.appendText(line + "\n")
        }
    }

    private fun guiTargets(): List<TargetStat> =
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
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

    private fun fixture(id: String): WakfuBestBuildParams =
        when (id) {
            "MD110" -> params(110, guiTargets())
            "MD200" -> params(200, guiTargets())
            "MD245" -> params(245, guiTargets())
            "MD110F" -> params(110, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            "MD245F" -> params(245, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            else -> error("unknown fixture $id")
        }

    private fun tuning(
        arm: String,
        det: Double,
    ): WakfuBuildSolver.SolverTuning {
        val base =
            WakfuBuildSolver.SolverTuning(
                numSearchWorkers = 1,
                randomSeed = 1,
                interleaveSearch = true,
                maxDeterministicTime = det,
                applyDominationOverride = true,
                greedyWarmStart = true
            )
        return when (arm) {
            "fold" -> base.copy(runeMixedCarriers = false)
            "counts" -> base
            "hybrid" -> base.copy(mixedRuneHybrid = true)
            else -> error("unknown arm $arm")
        }
    }

    @Test
    fun `deterministic A-B of the mixed-rune arms on the production max-damage leg`() {
        assumeTrue(System.getenv("WAKFU_HYBRID_AB") == "1", "manual harness: WAKFU_HYBRID_AB=1")
        val fixtures = (System.getenv("WAKFU_HYBRID_AB_FIXTURES") ?: "MD110").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val arms = (System.getenv("WAKFU_HYBRID_AB_ARMS") ?: "fold,counts,hybrid").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val det = System.getenv("WAKFU_HYBRID_AB_DET")?.toDouble() ?: 120.0
        val first = System.getenv("WAKFU_HYBRID_AB_FIRST") == "1"
        log("HYBRID_AB ENV det=$det fixtures=$fixtures arms=$arms mixedRunes=${WakfuBuildSolver.mixedRuneCarriersEnabled}")
        for (id in fixtures) {
            val p = fixture(id)
            val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            for (arm in arms) {
                if (first && arm != "hybrid") {
                    var outcome: WakfuBuildSolver.SolveOutcome? = null
                    val hard = p.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() }
                    val t0 = System.nanoTime()
                    runBlocking {
                        WakfuBuildSolver
                            .optimize(p, pool, runes, subs, tuning(arm, det).copy(stopAtFirstSolution = true, greedyWarmStart = false), hardConstraints = hard, onTermination = {
                                outcome =
                                    it
                            })
                            .toList()
                    }
                    log(
                        "HYBRID_AB FIRST $id arm=$arm det=${outcome?.deterministicTime} status=${outcome?.status} " +
                            "wallMs=${(System.nanoTime() - t0) / 1_000_000}"
                    )
                }
                val t0 = System.nanoTime()
                val results = runBlocking { MaxDamageSearch.optimizeHardThenSoft(p, pool, runes, subs, tuning(arm, det), greedyWarmStart = true).toList() }
                val wallMs = (System.nanoTime() - t0) / 1_000_000
                val last = results.lastOrNull { !it.greedyWarmStartEmission }
                log(
                    "HYBRID_AB FINAL $id arm=$arm objective=${last?.maxDamageObjective} raw=${last?.maxDamageRawProxy} " +
                        "hardLeg=${last?.maxDamageHardConstraintsMet} optimal=${last?.isOptimal} score=${last?.matchPercentage} " +
                        "emissions=${results.size} wallMs=$wallMs"
                )
            }
        }
    }
}
