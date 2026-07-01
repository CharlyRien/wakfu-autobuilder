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
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * External loop on top of the max-damage CP-SAT solve. It does two things CP-SAT alone can't:
 *
 *  1. **Proves a boss optimum by per-element enumeration.** A single in-model solve over several candidate
 *     elements (boss auto-element) does NOT prove on the full pool — summing/maxing several bilinear
 *     `throughput·perHit` terms leaves the dual bound uncloseable. So each playable candidate element is solved
 *     as its OWN single-element problem (one bilinear term ⇒ CP-SAT proves it), and the max over the proven
 *     per-element optima is provably the boss optimum (the global-best build's best element is found by that
 *     element's solve). A single-element request degenerates to one streamed solve.
 *  2. **Sequences resistance debuffs** (Sram/Sadida only). The damage-only objective can't see a value of AP
 *     that only pays off once a debuff is sequenced first (e.g. +1 AP to fit "debuff + a damage spell"), so for
 *     those classes it probes a window of AP targets around the winner's AP, each an AP-pinned single-element
 *     solve, and re-scores debuff-aware ([sequencedScore]). This phase is heuristic, so a result it improves
 *     stays "best found" (not proven).
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
    ): Flow<SolverResult<BuildCombination>> = run(baseParams, equipmentsByItemType, runes, sublimations, tuning = null)

    /** Back-compat for tests that drive the loop deterministically without sublimations. */
    internal fun run(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): Flow<SolverResult<BuildCombination>> = run(baseParams, equipmentsByItemType, runes, emptyList(), tuning)

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
    ): Flow<SolverResult<BuildCombination>> =
        callbackFlow {
            val clazz = baseParams.character.clazz
            // Phase 2 (the AP-window probes) exists ONLY so a resistance-reduction debuff can shift the
            // optimal AP — its debuff-aware re-score may prefer +d AP to fit "debuff + a damage spell".
            // Only Sram & Sadida own a confirmed enemy resistance debuff; for the other 16 classes the
            // debuff-aware score equals the damage-only score, so phase 1 is already the optimum and the AP
            // probing is provably redundant. Gate it on debuff presence.
            val hasResistanceDebuff =
                SpellCatalog.forClass(clazz).any { it.isConfirmedResistanceDebuff && (it.apCost ?: 0) >= 1 }

            // Phase 1 proves the boss optimum by ENUMERATING the candidate elements: each is solved as its own
            // SINGLE-element problem (one bilinear damage term ⇒ CP-SAT proves it), and the max over the proven
            // per-element optima is provably the boss optimum (the global-best build's best element is found by
            // that element's solve). The in-model `max`/sum over several elements in ONE solve does NOT prove on
            // the full pool — see docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md. A single-element request is one solve.
            // Only PLAYABLE candidate elements are enumerated: an element the class has no damage spells in is a
            // degenerate 0-damage solve that adds nothing (and need not be proven). If the class can play none of
            // the boss's elements, fall back to the first candidate so a (0-damage) build is still produced.
            val playableElements = SpellCatalog.playableElements(clazz).map { it.name }.toSet()
            val candidates = baseParams.damageScenario.candidateElements()
            // Whether the class can actually play any of the boss's elements. When false the search below falls
            // back to a degenerate 0-damage solve — which proves trivially but is NOT a meaningful optimum, so it
            // must not be reported as "proven" (see the final emit).
            val hasPlayableElement = candidates.any { it.first.name in playableElements }
            val elementParams =
                candidates
                    .filter { (element, _) -> element.name in playableElements }
                    .ifEmpty { candidates.take(1) }
                    .map { (element, resistance) ->
                        baseParams.copy(
                            damageScenario =
                                baseParams.damageScenario.copy(
                                    element = element,
                                    targetResistancePercent = resistance,
                                    elementResistances = null
                                )
                        )
                    }
            // Split the wall-clock budget across only the phases that actually run, so skipping the debuff phase
            // gives phase 1 the whole budget (faster proofs) instead of idling part of it.
            val activePhases = 1 + (if (hasResistanceDebuff) 1 else 0)
            val phaseBudget = (baseParams.searchDuration / activePhases).coerceAtLeast(1.seconds)
            var best: SolverResult<BuildCombination>? = null
            // The single-element scenario the winning [best] build was solved for — reused to pin the debuff
            // phase, so we never re-derive it with another bestSequencedRotation pass.
            var bestSolvedScenario: DamageScenario? = null
            var phase1Optimal = false
            // [consider] is called CONCURRENTLY by the parallel per-element probe collectors (a boss /
            // multi-candidate search streams each probe's best-so-far live), so the read-modify-write of
            // [best] and the streamed progress must be serialized. The expensive re-score runs OUTSIDE the
            // lock. The lock is uncontended on the single-element path (one collector).
            val considerLock = Any()
            var lastProgressSent = 0

            fun consider(
                result: SolverResult<BuildCombination>,
                solvedScenario: DamageScenario,
                progress: Int,
            ) {
                // Re-score against the FULL boss scenario (max over the build's playable elements), so the best
                // per-element-optimal build wins regardless of which element it was solved for. This deliberately
                // overrides the solver's own (proxy, debuff-blind) score: [sequencedScore] is the debuff-aware
                // rotation the CLI/GUI also display, so ranking by it keeps the shown damage == the scored damage.
                val score = sequencedScore(baseParams, result.individual)
                synchronized(considerLock) {
                    val current = best
                    // Highest score; on a tie prefer the PROVEN result so [best].isOptimal reflects the proof — the
                    // solver emits the optimum un-proven first, then proven (same build, same score), and a plain
                    // strict `>` would keep the earlier un-proven copy and under-report the proof at the final emit.
                    if (current == null ||
                        score > current.matchPercentage ||
                        (score.compareTo(current.matchPercentage) == 0 && result.isOptimal && !current.isOptimal)
                    ) {
                        best = result.copy(matchPercentage = score)
                        bestSolvedScenario = solvedScenario
                    }
                    // Improvements from parallel probes can arrive out of order — keep the streamed bar monotonic.
                    lastProgressSent = maxOf(lastProgressSent, progress)
                    // Streamed best-so-far is never "proven" — optimality is decided once at the final emit.
                    best?.let { trySend(it.copy(progressPercentage = lastProgressSent, isOptimal = false)) }
                }
            }

            val producer =
                launch {
                    // When phase 1 is the only phase it owns the whole 0–100 bar; otherwise it caps at 70%.
                    val phase1Ceiling = if (activePhases == 1) 100 else 70
                    if (elementParams.size == 1) {
                        // Single-element request: stream the one (provable) solve live — the common, fast path.
                        val solo = elementParams.single()
                        WakfuBuildSolver
                            .optimize(solo.copy(searchDuration = phaseBudget), equipmentsByItemType, runes, sublimations, tuning)
                            .collect {
                                phase1Optimal = it.isOptimal
                                consider(it, solo.damageScenario, (it.progressPercentage * phase1Ceiling / 100).coerceIn(0, phase1Ceiling))
                            }
                    } else {
                        // Boss / multi-candidate: prove each element independently (in parallel) and take the max.
                        // The boss optimum is proven iff EVERY element solve proved. Each probe is a full solve
                        // that rebuilds the model — the only thing that varies between them is the pinned element,
                        // so this repeats the (single-threaded) model construction N times; that is deliberate: a
                        // shared model would force the N solves to run SEQUENTIALLY (CP-SAT mutates solver state),
                        // and N parallel solves with N model builds beat one shared model solved N times in a row.
                        //
                        // Each probe STREAMS its improving solutions into [consider] as they arrive (not awaited
                        // as a batch), so a build appears within seconds. Awaiting the whole batch first left the
                        // GUI on the "Preparing OR-Tools model" overlay (build == null) for the ENTIRE budget,
                        // which on a long boss search looked like an infinite hang that never found a build.
                        val probesProved =
                            streamProbeBatch(elementParams, equipmentsByItemType, runes, sublimations, phaseBudget, phase1Ceiling, tuning) { probeParams, probe, progress ->
                                consider(probe, probeParams.damageScenario, progress)
                            }
                        phase1Optimal = probesProved.isNotEmpty() && probesProved.all { it }
                    }

                    // Phase 1's best score, snapshotted before the heuristic debuff phase can move it.
                    val phase1BestScore = best?.matchPercentage

                    // Phase 2: AP-pinned probes around A₀ on the winning element — only for a class whose
                    // resistance debuff can make a different AP win (gated above). Pinned to the phase-1 winner's
                    // element so each probe stays a fast single-element solve.
                    if (hasResistanceDebuff) {
                        val winner = best
                        val winnerElement = bestSolvedScenario
                        if (winner != null && winnerElement != null) {
                            val a0 = actualActionPoints(baseParams, winner.individual)
                            val monoProbeParams =
                                ((a0 - AP_WINDOW_BELOW)..(a0 + AP_WINDOW_ABOVE))
                                    .filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 }
                                    .map { target ->
                                        baseParams.copy(
                                            searchDuration = phaseBudget,
                                            maxDamageApTarget = target,
                                            damageScenario = winnerElement
                                        )
                                    }
                            runProbeBatch(monoProbeParams, equipmentsByItemType, runes, sublimations, phaseBudget, tuning)
                                .forEach { (probeParams, probe) -> if (probe != null) consider(probe, probeParams.damageScenario, 90) }
                        }
                    }

                    // Honest optimality. The per-element enumeration PROVES the boss optimum iff every element
                    // solve proved (phase1Optimal), the SHOWN build is itself a proven one (finalBest.isOptimal —
                    // guards the rare case where the streamed best is a precise-higher but un-proven intermediate),
                    // and the class can actually play one of the boss's elements (an all-unplayable fallback is a
                    // degenerate 0-damage solve, not a meaningful optimum). The debuff phase only makes the result
                    // heuristic — "best found", structural non-optimality more time won't move — when it ACTUALLY
                    // IMPROVED on phase 1; a probe that found nothing better (or never ran) leaves phase 1's
                    // proven optimum intact.
                    val finalBest = best
                    val improvedByDebuff =
                        hasResistanceDebuff && finalBest != null && phase1BestScore != null && finalBest.matchPercentage > phase1BestScore
                    val proven = phase1Optimal && finalBest?.isOptimal == true && hasPlayableElement && !improvedByDebuff
                    // Guaranteed delivery (suspending `send`, not `trySend`): the streamed best-so-far above is
                    // best-effort progress and may be dropped under back-pressure, but the FINAL/best build — what
                    // the CLI/GUI show and tests assert on — must never be lost to a saturated buffer. This mirrors
                    // the standard-path final emit (WakfuBuildSolver.executeSolverAndEmitResults). Using `trySend`
                    // here was a latent bug: under concurrency the many streamed intermediate emissions could
                    // saturate the buffer so the final build (incl. the proven optimum) was silently dropped, and
                    // the collected result fell back to an earlier, worse incumbent.
                    finalBest?.let {
                        send(it.copy(progressPercentage = 100, isOptimal = proven, maxDamageHeuristicPhases = improvedByDebuff))
                    }
                    close()
                }
            awaitClose { producer.cancel() }
        }

    /**
     * Debuff-aware per-turn damage of [build] against [params]'s (boss) scenario, divided by the same
     * required-target penalty as the scorer. Routes through [SpellRotationOptimizer.bestSequencedRotation],
     * which picks the build's best playable element (max over [DamageScenario.candidateElements]) and sequences
     * any resistance debuffs first — the same call the CLI/GUI use to display, so scored and shown damage agree.
     */
    private fun sequencedScore(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): BigDecimal {
        val totalDamage =
            SpellRotationOptimizer
                .bestSequencedRotation(build, params.character, params.character.clazz, params.damageScenario)
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
    ): List<Pair<WakfuBestBuildParams, SolverResult<BuildCombination>?>> {
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

    /**
     * Like [runProbeBatch] but STREAMS each probe's improving solutions to [onResult] as they are found,
     * instead of awaiting every probe and returning the batch. This lets the caller surface a best-so-far
     * build within seconds of the search starting — the boss / multi-element path used to await the whole
     * batch, leaving the GUI's "Preparing OR-Tools model" overlay up (no build) for the entire budget.
     *
     * Returns, per probe, whether its CP-SAT solve PROVED optimality — the boss optimum is proven iff every
     * element's solve proved. Core budgeting is identical to [runProbeBatch] (the concurrent native workers
     * never exceed host−1, and the batch fits [phaseBudget]). [progressCeiling] scales each probe's 0–100
     * progress into the bar's phase-1 ceiling. [onResult] is invoked concurrently from the probe collectors,
     * so it MUST be thread-safe (see [consider], which guards its shared state).
     */
    private suspend fun streamProbeBatch(
        probeParams: List<WakfuBestBuildParams>,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        phaseBudget: Duration,
        progressCeiling: Int,
        tuning: WakfuBuildSolver.SolverTuning?,
        onResult: (WakfuBestBuildParams, SolverResult<BuildCombination>, Int) -> Unit,
    ): List<Boolean> {
        if (probeParams.isEmpty()) return emptyList()

        suspend fun collectProbe(params: WakfuBestBuildParams): Boolean {
            var proved = false
            WakfuBuildSolver
                .optimize(params, equipmentsByItemType, runes, sublimations, tuning)
                .collect { result ->
                    // CP-SAT declares optimality once, on this element's final emission; its best emission is
                    // proven iff that happened, so OR-ing over the stream matches the old per-probe `isOptimal`.
                    if (result.isOptimal) proved = true
                    onResult(params, result, (result.progressPercentage * progressCeiling / 100).coerceIn(0, progressCeiling))
                }
            return proved
        }

        // Test path ([tuning] != null): det-time solves are reproducible regardless of parallelism, so run
        // them straight on IO with the tuning's own worker count (mirrors [runProbeBatch]).
        if (tuning != null) {
            return coroutineScope {
                probeParams
                    .map { base -> async(Dispatchers.IO) { collectProbe(base.copy(searchDuration = phaseBudget)) } }
                    .awaitAll()
            }
        }

        val host = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val plan = probePlan(probeParams.size, host, phaseBudget)
        val dispatcher = Dispatchers.IO.limitedParallelism(plan.concurrency)
        return coroutineScope {
            probeParams
                .map { base ->
                    async(dispatcher) { collectProbe(base.copy(searchDuration = plan.perProbeBudget, solverWorkers = plan.workersPerProbe)) }
                }.awaitAll()
        }
    }

    private suspend fun solveProbe(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): SolverResult<BuildCombination>? =
        WakfuBuildSolver
            .optimize(params, equipmentsByItemType, runes, sublimations, tuning)
            .toList()
            // Highest score, tie-broken toward the PROVEN result: when CP-SAT reaches the optimal score before
            // certifying it, the flow emits the same build first as `isOptimal=false` then as `isOptimal=true`;
            // a plain maxBy{score} would return the earlier un-proven copy and lose the per-element proof.
            .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))

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
