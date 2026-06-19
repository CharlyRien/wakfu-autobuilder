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
import kotlin.time.Duration
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

    /**
     * Cap on how many bi-element scenarios actually get a CP-SAT probe. The full enumeration is hundreds of
     * `(pair × AP × split)` scenarios for a 3-element class (~286 for CRA); probing them all forced the phase
     * budget into illegibly thin per-probe slices and ran far past the user's search duration. The scenarios
     * are ordered best-promise-first ([biElementScenarios]), so the top [MAX_BI_PROBES] keep the strongest
     * pairs/splits while leaving each probe a usable share of the budget.
     */
    internal const val MAX_BI_PROBES = 24

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
            val clazz = baseParams.character.clazz
            // Phase 2 (the AP-window probes) exists ONLY so a resistance-reduction debuff can shift the
            // optimal AP — its debuff-aware re-score may prefer +d AP to fit "debuff + a damage spell".
            // Only Sram & Sadida own a confirmed enemy resistance debuff; for the other 16 classes the
            // debuff-aware score equals the damage-only score, so phase 1's single solve is already the mono
            // optimum and the AP probing is provably redundant. Gate it on debuff presence.
            val hasResistanceDebuff =
                SpellCatalog.forClass(clazz).any { it.isConfirmedResistanceDebuff && (it.apCost ?: 0) >= 1 }
            // Phase 3 (bi-element) only makes sense when the REQUEST puts more than one element in play
            // (boss auto-element). A pinned single-element request is mono by definition — splitting AP
            // across two elements would answer a question the user didn't ask and keep the result heuristic.
            val multiElementRequest = baseParams.damageScenario.candidateElements().size > 1
            val biScenarios = if (multiElementRequest) biElementScenarios(clazz).take(MAX_BI_PROBES) else emptyList()
            // Split the wall-clock budget across only the phases that actually run, so skipping a phase gives
            // the remaining solves more time (faster proofs) instead of idling part of the budget.
            val activePhases = 1 + (if (hasResistanceDebuff) 1 else 0) + (if (biScenarios.isNotEmpty()) 1 else 0)
            val phaseBudget = (baseParams.searchDuration / activePhases).coerceAtLeast(1.seconds)
            var best: GeneticAlgorithmResult<BuildCombination>? = null
            var phase1Optimal = false

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
                // Streamed best-so-far is never "proven" — optimality is decided once at the final emit.
                best?.let { trySend(it.copy(progressPercentage = progress, isOptimal = false)) }
            }

            val producer =
                launch {
                    // When phase 1 is the only phase it owns the whole 0–100 bar; otherwise it caps at 30%.
                    val phase1Ceiling = if (activePhases == 1) 100 else 30
                    // Phase 1: the unconstrained solve, streamed live but re-scored debuff-aware.
                    WakfuBuildSolver
                        .optimize(baseParams.copy(searchDuration = phaseBudget), equipmentsByItemType, runes, sublimations, tuning)
                        .collect {
                            phase1Optimal = it.isOptimal
                            consider(it, (it.progressPercentage * phase1Ceiling / 100).coerceIn(0, phase1Ceiling))
                        }

                    // Phase 2: AP-pinned mono probes around A₀ — only for a class whose resistance debuff can
                    // make a different AP win (gated above).
                    if (hasResistanceDebuff) {
                        val a0 = best?.individual?.let { actualActionPoints(baseParams, it) } ?: BASE_ACTION_POINTS
                        val monoProbeParams =
                            ((a0 - AP_WINDOW_BELOW)..(a0 + AP_WINDOW_ABOVE))
                                .filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 }
                                .map { target -> baseParams.copy(searchDuration = phaseBudget, maxDamageApTarget = target) }
                        runProbeBatch(monoProbeParams, equipmentsByItemType, runes, sublimations, phaseBudget, tuning)
                            .forEach { (probeParams, probe) -> if (probe != null) consider(probe, 55, probeParams) }
                    }

                    // Phase 3: bi-element probes — enumerate (pair × AP × split), keep the best-promise
                    // [MAX_BI_PROBES] (the full set is ~hundreds for a 3-element class). Fills to 100%.
                    if (biScenarios.isNotEmpty()) {
                        val biStart = if (hasResistanceDebuff) 55 else 30
                        val biProbeParams =
                            biScenarios.map { (pair, totalAp, split) ->
                                baseParams.copy(
                                    searchDuration = phaseBudget,
                                    maxDamageApTarget = totalAp,
                                    maxDamageBiElement = BiElementSplit(pair.first, pair.second, split)
                                )
                            }
                        runProbeBatch(biProbeParams, equipmentsByItemType, runes, sublimations, phaseBudget, tuning)
                            .forEachIndexed { index, (probeParams, probe) ->
                                if (probe != null) {
                                    val progress = biStart + ((index + 1) * (100 - biStart) / biScenarios.size.coerceAtLeast(1))
                                    consider(probe, progress, probeParams)
                                }
                            }
                    }

                    // Honest optimality: when phase 1 was the ONLY phase AND CP-SAT proved it, this single
                    // solve is the provable optimum. Any probing phase (debuff AP window or bi-element) makes
                    // the result a heuristic max over a capped enumeration, so it stays "best found".
                    val proven = activePhases == 1 && phase1Optimal
                    best?.let { trySend(it.copy(progressPercentage = 100, isOptimal = proven)) }
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
     * All `(pair, totalAp, splitOnFirst)` scenarios to probe, ordered **best-promise first** (descending
     * combined base throughput `tableA[split] + tableB[totalAp − split]`) so a downstream cap
     * ([MAX_BI_PROBES]) keeps the strongest scenarios. Dead pairs (either element has no throughput) are
     * dropped; at each `(pair, totalAp)` only the Pareto-optimal interior splits survive.
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

        return livePairs
            .flatMap { pair ->
                val tableA = tables.getValue(pair.first)
                val tableB = tables.getValue(pair.second)
                (MIN_AP_TARGET..MAX_AP_TARGET).flatMap { totalAp ->
                    paretoFrontierSplits(tableA, tableB, totalAp).map { split ->
                        BiElementScenario(pair, totalAp, split, promise = tableA[split] + tableB[totalAp - split])
                    }
                }
            }.sortedByDescending { it.promise }
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

    /**
     * Runs a batch of AP/element probes and returns each probe's best result (or null if it found none).
     *
     * Production ([tuning] == null): the single most important fix for the GUI freeze. Each probe starts a
     * CP-SAT solve whose native workers pin cores at 100%; a plain `Dispatchers.IO` fan-out (≤64 coroutines)
     * therefore spawned dozens of CPU-bound solves at once, oversubscribing every core and starving the
     * Compose render thread. Here a [probePlan] bounds the concurrent solves and per-probe worker count so
     * their union never exceeds the host's cores−1 (one left for the UI), and slices [phaseBudget] across the
     * waves so the whole batch lands within it instead of taking `waves × phaseBudget`.
     *
     * Test path ([tuning] != null): det-time solves are machine-reproducible regardless of parallelism, so
     * they run straight on `Dispatchers.IO` with the tuning's own worker count (`solverWorkers` left null).
     */
    private suspend fun runProbeBatch(
        probeParams: List<WakfuBestBuildParams>,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        phaseBudget: Duration,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): List<Pair<WakfuBestBuildParams, GeneticAlgorithmResult<BuildCombination>?>> {
        if (probeParams.isEmpty()) return emptyList()

        if (tuning != null) {
            return coroutineScope {
                probeParams
                    .map { p -> async(Dispatchers.IO) { p to solveProbe(p, equipmentsByItemType, runes, sublimations, tuning) } }
                    .awaitAll()
            }
        }

        val host = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val plan = probePlan(probeParams.size, host, phaseBudget)
        val dispatcher = Dispatchers.IO.limitedParallelism(plan.concurrency)
        return coroutineScope {
            probeParams
                .map { base ->
                    val p = base.copy(searchDuration = plan.perProbeBudget, solverWorkers = plan.workersPerProbe)
                    async(dispatcher) { p to solveProbe(p, equipmentsByItemType, runes, sublimations, tuning) }
                }.awaitAll()
        }
    }

    private suspend fun solveProbe(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): GeneticAlgorithmResult<BuildCombination>? =
        WakfuBuildSolver
            .optimize(params, equipmentsByItemType, runes, sublimations, tuning)
            .toList()
            .maxByOrNull { it.matchPercentage }

    /** Concurrency / per-probe worker count / per-probe time budget for a batch of probes. See [probePlan]. */
    internal data class ProbePlan(
        val concurrency: Int,
        val workersPerProbe: Int,
        val perProbeBudget: Duration,
    )

    /**
     * Plans a batch of [probeCount] probes against a [host]-core budget and a [phaseBudget] wall-clock:
     *  - at most `min(probeCount, host)` solves run at once, each with `host / concurrency` workers, so the
     *    concurrent native threads (`concurrency × workersPerProbe`) never exceed [host] — leaving the UI a core;
     *  - the budget is split evenly across the `⌈probeCount / concurrency⌉` waves, so the batch finishes within
     *    [phaseBudget] (deliberately **no** lower floor — a floor let a 1s search balloon past its budget; the
     *    solver's own millisecond floor keeps a degenerate slice from meaning "no limit").
     */
    internal fun probePlan(
        probeCount: Int,
        host: Int,
        phaseBudget: Duration,
    ): ProbePlan {
        val safeHost = host.coerceAtLeast(1)
        val concurrency = probeCount.coerceIn(1, safeHost)
        val workersPerProbe = (safeHost / concurrency).coerceAtLeast(1)
        val waves = ((probeCount + concurrency - 1) / concurrency).coerceAtLeast(1)
        return ProbePlan(concurrency, workersPerProbe, phaseBudget / waves)
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
    /** Combined base throughput of the split — the ordering key for the best-promise-first cap. */
    val promise: Long = 0L,
)
