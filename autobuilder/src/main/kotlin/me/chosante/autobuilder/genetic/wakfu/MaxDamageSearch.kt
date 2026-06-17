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
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds
import me.chosante.autobuilder.domain.SpellElement as DomainSpellElement

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
 *  3. (Lot 2 M2) enumerates **bi-element** probes `(element-pair × total-AP × split)` in parallel,
 *     pruned by dead pairs + Pareto frontier;
 *  4. re-scores every resulting build with the **debuff-aware** sequenced rotation ([sequencedScore]);
 *  5. keeps the build with the highest real per-turn damage across mono AND bi-element.
 *
 * Confined to max-damage: [WakfuBestBuildFinderAlgorithm.run] only routes here for that mode.
 */
object MaxDamageSearch {
    private const val AP_WINDOW_BELOW = 3
    private const val AP_WINDOW_ABOVE = 3
    internal const val MAX_AP_TARGET = 20 // matches WakfuBuildSolver.MAX_ROTATION_AP — the throughput table's AP range
    internal const val MIN_AP_TARGET = 1

    fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = run(baseParams, equipmentsByItemType, runes, sublimations, tuning = null)

    /** Back-compat for tests that drive the loop deterministically without sublimations. */
    internal fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = run(baseParams, equipmentsByItemType, runes, emptyList(), tuning)

    /**
     * [tuning] is for tests only: when supplied, every underlying solve runs the deterministic CP-SAT
     * tuning (fixed workers/seed/det-time) instead of the wall-clock production path, so this loop is
     * reproducible. Production passes null.
     */
    internal fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> =
        callbackFlow {
            val phaseBudget = (baseParams.searchDuration / 3).coerceAtLeast(1.seconds)
            var best: GeneticAlgorithmResult<BuildCombination>? = null

            fun consider(
                result: GeneticAlgorithmResult<BuildCombination>,
                progress: Int,
                probeParams: WakfuBestBuildParams = baseParams,
            ) {
                val score = sequencedScore(probeParams, result.individual)
                val current = best
                if (current == null || score > current.matchPercentage) {
                    // Carry the winning split so the display re-scores the same turn (null for mono probes).
                    best = result.copy(matchPercentage = score, maxDamageBiElement = probeParams.maxDamageBiElement)
                }
                best?.let { trySend(it.copy(progressPercentage = progress, isOptimal = false)) }
            }

            val producer =
                launch {
                    // Phase 1 (0–30%): the unconstrained solve, streamed live but re-scored debuff-aware.
                    WakfuBuildSolver
                        .optimize(baseParams.copy(searchDuration = phaseBudget), equipmentsByItemType, runes, sublimations, tuning)
                        .collect { consider(it, (it.progressPercentage * 30 / 100).coerceIn(0, 30)) }

                    val a0 = best?.individual?.let { actualActionPoints(baseParams, it) } ?: BASE_ACTION_POINTS

                    // Phase 2 (30–55%): AP-pinned mono probes around A₀, run in parallel.
                    val monoTargets =
                        ((a0 - AP_WINDOW_BELOW)..(a0 + AP_WINDOW_ABOVE))
                            .filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 }
                            .toList()
                    val monoWorkers = probeWorkers(monoTargets.size, tuning)
                    val monoProbes =
                        coroutineScope {
                            monoTargets
                                .map { target ->
                                    async(Dispatchers.IO) {
                                        val probeParams =
                                            baseParams.copy(
                                                searchDuration = phaseBudget,
                                                maxDamageApTarget = target,
                                                solverWorkers = monoWorkers
                                            )
                                        probeParams to
                                            WakfuBuildSolver
                                                .optimize(probeParams, equipmentsByItemType, runes, sublimations, tuning)
                                                .toList()
                                                .maxByOrNull { it.matchPercentage }
                                    }
                                }.awaitAll()
                        }
                    monoProbes.forEach { (probeParams, probe) ->
                        if (probe != null) consider(probe, 55, probeParams)
                    }

                    // Phase 3 (55–100%): bi-element probes — enumerate (pair × AP × split), pruned.
                    val biProbeScenarios = biElementScenarios(baseParams.character.clazz)
                    if (biProbeScenarios.isNotEmpty()) {
                        val biWorkers = probeWorkers(biProbeScenarios.size, tuning)
                        val biProbes =
                            coroutineScope {
                                biProbeScenarios
                                    .map { (pair, totalAp, split) ->
                                        async(Dispatchers.IO) {
                                            val probeParams =
                                                baseParams.copy(
                                                    searchDuration = phaseBudget,
                                                    maxDamageApTarget = totalAp,
                                                    maxDamageBiElement = BiElementSplit(pair.first, pair.second, split),
                                                    solverWorkers = biWorkers
                                                )
                                            probeParams to
                                                WakfuBuildSolver
                                                    .optimize(probeParams, equipmentsByItemType, runes, sublimations, tuning)
                                                    .toList()
                                                    .maxByOrNull { it.matchPercentage }
                                        }
                                    }.awaitAll()
                            }
                        biProbes.forEachIndexed { index, (probeParams, probe) ->
                            if (probe != null) {
                                val progress = 55 + ((index + 1) * 45 / biProbeScenarios.size.coerceAtLeast(1))
                                consider(probe, progress, probeParams)
                            }
                        }
                    }

                    best?.let { trySend(it.copy(progressPercentage = 100, isOptimal = false)) }
                    close()
                }
            awaitClose { producer.cancel() }
        }

    /**
     * Debuff-aware per-turn damage of [build], divided by the same required-target penalty as the scorer.
     * Routes through [SpellRotationOptimizer.bestSequencedTurn], which sums the joint bi-element turn when
     * [params] carries a [BiElementSplit] (shared debuffs, no double-spend) and is the mono rotation
     * otherwise — the same call the CLI/GUI use to display, so the scored and shown damage agree.
     */
    private fun sequencedScore(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): BigDecimal {
        val totalDamage =
            SpellRotationOptimizer
                .bestSequencedTurn(build, params.character, params.character.clazz, params.damageScenario, params.maxDamageBiElement)
                .totalExpectedDamage

        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = params.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(params.damageScenario.element.masteryCharacteristic to 1),
                resistanceElementsWanted = params.targetStats.resistanceElementsWanted
            )
        val penalty = FindMaxDamageScoring.requiredConstraintPenaltyFactor(params.targetStats, stats)
        return totalDamage.toBigDecimal().divide(penalty, 4, RoundingMode.FLOOR)
    }

    // ----- bi-element enumeration helpers -----

    /**
     * All `(pair, totalAp, splitOnFirst)` scenarios to probe. Dead pairs (either element has no
     * throughput) are dropped; at each `(pair, totalAp)` only the Pareto-optimal interior splits survive.
     */
    internal fun biElementScenarios(clazz: me.chosante.common.CharacterClass): List<BiElementScenario> {
        val playable =
            SpellCatalog
                .playableElements(clazz)
                .map { DomainSpellElement.valueOf(it.name) }
        if (playable.size < 2) return emptyList()

        val tables =
            playable.associateWith { element ->
                val spells =
                    SpellCatalog.damageSpells(clazz).filter {
                        it.element ==
                            me.chosante.common.SpellElement
                                .valueOf(element.name)
                    }
                SpellRotationOptimizer.baseThroughputTable(spells, MAX_AP_TARGET)
            }

        val livePairs =
            playable
                .flatMapIndexed { i, a ->
                    playable.drop(i + 1).map { b -> a to b }
                }.filter { (a, b) ->
                    tables.getValue(a).any { it > 0L } && tables.getValue(b).any { it > 0L }
                }

        return livePairs.flatMap { pair ->
            val tableA = tables.getValue(pair.first)
            val tableB = tables.getValue(pair.second)
            (MIN_AP_TARGET..MAX_AP_TARGET).flatMap { totalAp ->
                paretoFrontierSplits(tableA, tableB, totalAp).map { split ->
                    BiElementScenario(pair, totalAp, split)
                }
            }
        }
    }

    /**
     * At fixed `(pair, totalAp)`, returns the interior splits `a ∈ 1..totalAp-1` where both elements
     * have non-zero throughput AND no other split weakly dominates in both dimensions. With monotone
     * throughput tables this typically prunes ~A interior points down to ~3–5.
     */
    internal fun paretoFrontierSplits(
        tableA: LongArray,
        tableB: LongArray,
        totalAp: Int,
    ): List<Int> {
        val interior =
            (1 until totalAp).filter { a ->
                a < tableA.size &&
                    (totalAp - a) in tableB.indices &&
                    tableA[a] > 0L &&
                    tableB[totalAp - a] > 0L
            }
        return interior.filter { a ->
            val tA = tableA[a]
            val tB = tableB[totalAp - a]
            interior.none { other ->
                other != a &&
                    tableA[other] >= tA &&
                    tableB[totalAp - other] >= tB &&
                    (tableA[other] > tA || tableB[totalAp - other] > tB)
            }
        }
    }

    private fun probeWorkers(
        probeCount: Int,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): Int? =
        if (tuning == null) {
            ((Runtime.getRuntime().availableProcessors() - 1) / probeCount.coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            null
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

internal data class BiElementScenario(
    val pair: Pair<DomainSpellElement, DomainSpellElement>,
    val totalAp: Int,
    val splitOnFirst: Int,
)
