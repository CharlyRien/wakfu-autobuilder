package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds

/**
 * External loop on top of the max-damage CP-SAT solve — the part that makes the build search aware of
 * the best **rotation sequencing** (including resistance debuffs) WITHOUT fusing that bilinear term into
 * CP-SAT.
 *
 * The CP-SAT objective picks AP and masteries to maximize the *damage-only* per-turn throughput; it
 * cannot see a value of AP that only pays off once a resistance debuff is sequenced first (e.g. +1 AP
 * to fit "debuff + a damage spell"). So this loop:
 *
 *  1. runs the unconstrained boss-aware solve to find the objective's natural AP `A₀`;
 *  2. probes a window of AP targets around `A₀` — one **AP-pinned** solve each (`maxDamageApTarget`),
 *     run **in parallel**;
 *  3. re-scores every resulting build with the **debuff-aware** sequenced rotation ([sequencedScore]);
 *  4. keeps the build with the highest real per-turn damage.
 *
 * Confined to max-damage: [WakfuBestBuildFinderAlgorithm.run] only routes here for that mode.
 */
object MaxDamageSearch {
    private const val AP_WINDOW_BELOW = 3
    private const val AP_WINDOW_ABOVE = 3
    private const val MAX_AP_TARGET = 20 // matches WakfuBuildSolver.MAX_ROTATION_AP — the throughput table's AP range
    private const val MIN_AP_TARGET = 1

    fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = run(baseParams, equipmentsByItemType, runes, tuning = null)

    /**
     * [tuning] is for tests only: when supplied, every underlying solve runs the deterministic CP-SAT
     * tuning (fixed workers/seed/det-time) instead of the wall-clock production path, so this loop is
     * reproducible. Production passes null.
     */
    internal fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> =
        callbackFlow {
            // Split the budget so the whole loop (unconstrained pass + parallel probes) fits ~one budget.
            val phaseBudget = (baseParams.searchDuration / 2).coerceAtLeast(1.seconds)
            var best: GeneticAlgorithmResult<BuildCombination>? = null

            fun consider(
                result: GeneticAlgorithmResult<BuildCombination>,
                progress: Int,
            ) {
                val score = sequencedScore(baseParams, result.individual)
                val current = best
                if (current == null || score > current.matchPercentage) best = result.copy(matchPercentage = score)
                // Never claim optimality: this external loop is a heuristic over a window of AP targets +
                // debuff sequencing, so it does NOT prove a global optimum even when each solve does.
                best?.let { trySend(it.copy(progressPercentage = progress, isOptimal = false)) }
            }

            val producer =
                launch {
                    // Phase 1 (0–50%): the unconstrained solve, streamed live but re-scored debuff-aware.
                    WakfuBuildSolver
                        .optimize(baseParams.copy(searchDuration = phaseBudget), equipmentsByItemType, runes, tuning)
                        .collect { consider(it, (it.progressPercentage / 2).coerceIn(0, 50)) }

                    val a0 = best?.individual?.let { actualActionPoints(baseParams, it) } ?: BASE_ACTION_POINTS

                    // Phase 2 (50–100%): AP-pinned probes around A₀, run in parallel.
                    val targets =
                        ((a0 - AP_WINDOW_BELOW)..(a0 + AP_WINDOW_ABOVE))
                            .filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 }
                            .toList()
                    // Split the cores across the parallel probes so the batch doesn't spawn
                    // probeCount × (cores−1) native threads and thrash the CPU (deterministic tuning sets
                    // its own worker count, so only override in production).
                    val probeWorkers =
                        if (tuning == null) {
                            ((Runtime.getRuntime().availableProcessors() - 1) / targets.size.coerceAtLeast(1)).coerceAtLeast(1)
                        } else {
                            null
                        }
                    val probes =
                        coroutineScope {
                            targets
                                .map { target ->
                                    async(Dispatchers.IO) {
                                        WakfuBuildSolver
                                            .optimize(
                                                baseParams.copy(searchDuration = phaseBudget, maxDamageApTarget = target, solverWorkers = probeWorkers),
                                                equipmentsByItemType,
                                                runes,
                                                tuning
                                            ).toList()
                                            .maxByOrNull { it.matchPercentage }
                                    }
                                }.awaitAll()
                        }
                    probes.filterNotNull().forEachIndexed { index, probe ->
                        consider(probe, 50 + ((index + 1) * 50 / targets.size.coerceAtLeast(1)))
                    }

                    best?.let { trySend(it.copy(progressPercentage = 100, isOptimal = false)) }
                    close()
                }
            awaitClose { producer.cancel() }
        }

    /** Debuff-aware per-turn damage of [build], divided by the same required-target penalty as the scorer. */
    private fun sequencedScore(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): BigDecimal {
        val rotation =
            SpellRotationOptimizer.bestSequencedRotation(build, params.character, params.character.clazz, params.damageScenario)
        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = params.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(params.damageScenario.element.masteryCharacteristic to 1),
                // Pass the real resistance targets so the penalty's stats include RESISTANCE_ELEMENTARY /
                // per-element resistances (an emptyMap read them as 0, so builds couldn't be ranked by a
                // required resistance set in max-damage mode).
                resistanceElementsWanted = params.targetStats.resistanceElementsWanted
            )
        val penalty = FindMaxDamageScoring.requiredConstraintPenaltyFactor(params.targetStats, stats)
        return rotation.totalExpectedDamage.toBigDecimal().divide(penalty, 4, RoundingMode.FLOOR)
    }

    private fun actualActionPoints(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): Int =
        computeCharacteristicsValues(
            buildCombination = build,
            characterBaseCharacteristics = params.character.baseCharacteristicValues,
            masteryElementsWanted = mapOf(params.damageScenario.element.masteryCharacteristic to 1),
            resistanceElementsWanted = emptyMap()
        )[Characteristic.ACTION_POINT] ?: BASE_ACTION_POINTS

    private const val BASE_ACTION_POINTS = 6
}
