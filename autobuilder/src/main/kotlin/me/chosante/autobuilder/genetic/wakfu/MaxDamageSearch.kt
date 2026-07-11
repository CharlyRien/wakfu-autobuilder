package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    private val logger = KotlinLogging.logger {}

    /**
     * Enable cross-restart disk persistence of the max-damage optimality certificate (B5): the same request
     * across app restarts reconstructs its "proven optimal" badge in seconds instead of re-running the
     * multi-minute certifier. Call ONCE at application startup (CLI / GUI `main`). Off by default so tests
     * never touch the real user cache; a disk hit is always verified against the live request before use.
     */
    fun enableCertificateDiskCache() = MaxDamageCertificateDiskCache.enableDefault()

    private const val AP_WINDOW_BELOW = 3
    private const val AP_WINDOW_ABOVE = 3
    internal const val MAX_AP_TARGET = 20 // matches WakfuBuildSolver.MAX_ROTATION_AP — the throughput table's AP range
    internal const val MIN_AP_TARGET = 1

    // ---- E10: search ∥ certificate overlap -----------------------------------------------------------
    // The post-search badge re-runs the multi-second-to-minute certificate AFTER the search finishes. Its
    // expensive parts are (mostly) incumbent-independent and cached (B4), so the search WARMS the cache in
    // the background as soon as a first incumbent streams: by the time the front-end asks for the proof,
    // the certificate is computed (full reconstruct) or in flight (the cache's single-flight makes the
    // proof WAIT on it instead of duplicating the DP). One background thread; superseded warm-ups (a new
    // search) are cancelled via the B8 flag.
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeWarmupCancelled = AtomicReference<AtomicBoolean?>()

    /** Test seam: the most recent warm-up job (join it to await cache population). */
    internal val warmupJobForTest = AtomicReference<Job?>()

    /**
     * The params-only certificate-support gates, mirroring [proveOptimality]'s early returns (prefiltered
     * pool / forced runes / survivability floor / multi-element ⇒ the proof never consults the certificate,
     * so warming it would be wasted work). Keep in sync with [proveOptimality].
     */
    private fun certificateWarmupEligible(baseParams: WakfuBestBuildParams): Boolean =
        !WakfuBuildSolver.needsItemPrefilter(baseParams.targetStats) &&
            baseParams.forcedRunesByItem.isEmpty() &&
            baseParams.forcedRunes.isEmpty() &&
            !(baseParams.damageScenario.survivabilityFloor && baseParams.damageScenario.minEffectiveHp > 0) &&
            baseParams.damageScenario
                .candidateElements()
                .size == 1

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

            // E10: warm the certificate cache in the background off the FIRST streamed incumbent, so the
            // post-search proof reconstructs (or single-flight-joins) instead of paying the DP cold. Any
            // feasible damage proxy is a sound elimination incumbent — a weak early one just leaves a few
            // more survivors, whose exact confirms accumulate into the same B4 cache the proof reads.
            // Skipped on the deterministic test path (tuning != null) so pinned tests stay byte-identical.
            val warmupCancelled = AtomicBoolean(false)
            // Flipped once the search flow completes: the warm-up certificate reads it through its
            // threadsProvider, so a warm-up that outlives the search stops throttling itself to 1 thread.
            val searchDone = AtomicBoolean(false)
            // The best damage proxy streamed so far — the warm-up certificate reads it right before its
            // elimination step (via incumbentProvider), so it prunes against the FINAL incumbent even though
            // it was launched on the first streamed result.
            val latestProxy =
                java.util.concurrent.atomic
                    .AtomicLong(Long.MIN_VALUE)
            val warmupLaunched = AtomicBoolean(false)
            var searchCompletedNormally = false
            activeWarmupCancelled.getAndSet(warmupCancelled)?.set(true) // supersede a previous search's warm-up

            // ---- Early stop on proof -----------------------------------------------------------------
            // Restores the "stops when proven" behavior CP-SAT used to provide by itself (the expanded
            // sublimation catalog leaves its dual bound open, so in-model OPTIMAL no longer closes on the
            // full pool). Once the warm-up certificate lands, the incumbent is checked against the
            // certified global ceiling — again on every later improvement — and the moment
            // `proxy ≥ maxCell` (with proveOptimality's own result gates: targets met, no heuristic
            // phase, under-count self-check) the remaining search budget is provably useless: phase 1 is
            // cancelled and the final emit carries the proof. Debuff classes (Sram/Sadida) keep their
            // full budget — their phase 2 re-ranks by a debuff-aware score the certificate does not
            // bound. The deterministic test path (tuning != null) never launches the warm-up, so pinned
            // tests are untouched.
            val warmupLedger = AtomicReference<CertLedger?>(null)
            val certProvenEarly = AtomicBoolean(false)
            val phase1JobRef = AtomicReference<Job?>(null)

            fun maybeStopSearchProven() {
                if (hasResistanceDebuff || certProvenEarly.get()) return
                val ledger = warmupLedger.get() ?: return
                val maxCell = ledger.maxCellObjective ?: return
                val current = synchronized(considerLock) { best } ?: return
                if (current.maxDamageHeuristicPhases) return
                val proxy = current.maxDamageRawProxy ?: current.maxDamageObjective ?: return
                if (!current.maxDamageHardConstraintsMet && !fullyMeetsRequiredTargets(baseParams, current.individual)) return
                // proveOptimality's mandatory self-check, applied identically: the incumbent's own AP
                // cell must upper-bound its proxy, or the certifier under-counted — never stop on that.
                val ownCell = ledger.cellObjectives[actualActionPoints(baseParams, current.individual)]
                if (ownCell != null && ownCell < proxy) return
                if (proxy >= maxCell && certProvenEarly.compareAndSet(false, true)) {
                    phase1JobRef.get()?.cancel()
                }
            }

            // Late-bound: [consider] must fire the warm-up, the warm-up's completion calls back into
            // [consider] (the constructed proven build) — a declaration cycle Kotlin local functions
            // cannot express directly. Assigned right after [maybeStartCertificateWarmup] is declared.
            var startWarmup: (SolverResult<BuildCombination>) -> Unit = {}

            fun consider(
                result: SolverResult<BuildCombination>,
                solvedScenario: DamageScenario,
                progress: Int,
            ) {
                startWarmup(result)
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
                // A better incumbent may cross the already-landed certificate ceiling — re-check.
                maybeStopSearchProven()
            }

            // Short-search rescue, phase 2: the incumbent fell short of the certified ceiling. When the
            // ledger's ARGMAX cell is exactly confirmed (it carries provenance — the cascade's break cell,
            // or any exact confirm), E8-construct that cell's build: a SUCCESSFUL construct's proxy reaches
            // a sound global upper bound, so the constructed build IS the proven optimum — feed it to
            // [consider] and end the search. If the argmax is an UNCONFIRMED fast bound (the cascade
            // skipped it) or the construct misses, fall back to the FULL (cascade-off) certificate — same
            // cache entry, so the cascade's confirms are reused and the post-search proof stays cheap.
            suspend fun maybeConstructProvenOptimum(ledger: CertLedger) {
                if (certProvenEarly.get() || hasResistanceDebuff || elementParams.size != 1) return
                if (warmupCancelled.get()) return
                // Only while the flow is still live: after the search ends the channel is closed — the
                // async post-search proof (proveOptimality + dpConstruct, cascade-aware) owns the rescue.
                if (searchDone.get()) return
                val argmaxCell =
                    ledger.cellObjectives.entries
                        .filter { it.value >= 0 }
                        .maxByOrNull { it.value }
                        ?.key ?: return
                val constructed =
                    if (argmaxCell in ledger.cellProvenance) {
                        runCatching {
                            WakfuBuildSolver.dpConstructProvenOptimum(
                                baseParams,
                                equipmentsByItemType,
                                runes,
                                sublimations,
                                incumbentObjective = latestProxy.get().takeIf { it != Long.MIN_VALUE },
                                precomputedLedger = ledger
                            )
                        }.getOrElse {
                            logger.warn(it) { "E8 construct failed during warm-up (non-fatal)." }
                            null
                        }
                    } else {
                        null
                    }
                if (constructed != null) {
                    consider(constructed, elementParams.single().damageScenario, 99)
                    // Deliberately NOT setting certProvenEarly directly: [consider] ranks by the
                    // debuff-aware sequencedScore, which can diverge (~0.6 %) from the raw proxy — if the
                    // constructed build did NOT become [best], flagging would badge the old, UNPROVEN
                    // incumbent. Re-derive instead: proven only if the CURRENT best crosses the ceiling.
                    maybeStopSearchProven()
                }
            }

            fun maybeStartCertificateWarmup(result: SolverResult<BuildCombination>) {
                if (tuning != null || !certificateWarmupEligible(baseParams)) return
                val proxy = result.maxDamageRawProxy ?: result.maxDamageObjective ?: return
                latestProxy.getAndUpdate { maxOf(it, proxy) }
                if (!warmupLaunched.compareAndSet(false, true)) return
                warmupJobForTest.set(
                    warmupScope.launch {
                        runCatching {
                            val ledger =
                                MaxDamageCertificateCache.certificate(
                                    baseParams,
                                    equipmentsByItemType,
                                    runes,
                                    sublimations,
                                    applyDomination = true,
                                    incumbentObjective = proxy,
                                    threads = 1,
                                    // 1 thread while the search's CP-SAT workers own the cores; the certificate's
                                    // dominant stage (tier-1.5) starts long after the ~1-min search ends, so the
                                    // provider lets the SAME in-flight compute (which the post-search proof joins
                                    // via the single-flight) scale to the full worker count the moment it is done.
                                    threadsProvider = { tier ->
                                        when {
                                            !searchDone.get() -> 1 // never compete with the search's CP-SAT workers
                                            tier == CertTier.TIER15 -> WakfuBuildSolver.certifierTier15Threads()
                                            tier == CertTier.FAST -> WakfuBuildSolver.certifierFastWorldThreads()
                                            else -> WakfuBuildSolver.certifierDefaultThreads()
                                        }
                                    },
                                    incumbentProvider = { latestProxy.get().takeIf { it != Long.MIN_VALUE } },
                                    // Cascade (short-search rescue): confirm survivors one cell at a time so a
                                    // WEAK incumbent does not pay a tier-1.5 pass for every survivor; the
                                    // construct below finishes the job (or the full fallback does).
                                    cascadeTier15 = true,
                                    isCancelled = { warmupCancelled.get() }
                                )
                            if (ledger != null) {
                                warmupLedger.set(ledger)
                                maybeStopSearchProven()
                                maybeConstructProvenOptimum(ledger)
                            }
                        }.onFailure { logger.warn(it) { "Certificate warm-up failed (non-fatal; the proof will compute cold)." } }
                    }
                )
            }

            startWarmup = ::maybeStartCertificateWarmup

            val producer =
                launch {
                    // When phase 1 is the only phase it owns the whole 0–100 bar; otherwise it caps at 70%.
                    val phase1Ceiling = if (activePhases == 1) 100 else 70
                    if (elementParams.size == 1) {
                        // Single-element request: stream the one (provable) solve live — the common, fast path.
                        // Runs as a CHILD job so the certificate early stop can cancel the remaining budget
                        // without killing the producer (the final proven emit below must still run).
                        val solo = elementParams.single()
                        val phase1Job =
                            launch {
                                optimizeHardThenSoft(solo.copy(searchDuration = phaseBudget), equipmentsByItemType, runes, sublimations, tuning, greedyWarmStart = true)
                                    .collect {
                                        phase1Optimal = it.isOptimal
                                        consider(it, solo.damageScenario, (it.progressPercentage * phase1Ceiling / 100).coerceIn(0, phase1Ceiling))
                                    }
                            }
                        phase1JobRef.set(phase1Job)
                        // The certificate may have landed before the job ref was published — re-check once.
                        maybeStopSearchProven()
                        phase1Job.join()
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
                            val requiredApTarget =
                                baseParams.targetStats
                                    .firstOrNull { it.characteristic == Characteristic.ACTION_POINT && it.target > 0 }
                                    ?.target ?: MIN_AP_TARGET
                            val monoProbeParams =
                                apProbeTargets(a0, requiredApTarget)
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
                    val proven =
                        (phase1Optimal && finalBest?.isOptimal == true && hasPlayableElement && !improvedByDebuff) ||
                            certProvenEarly.get()
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
                    searchCompletedNormally = true
                    searchDone.set(true)
                    close()
                }
            awaitClose {
                producer.cancel()
                // E10: a search cancelled mid-flight (user restart / window close) kills its warm-up too;
                // a NORMALLY completed search leaves it running — the post-search proof joins it via the
                // certificate cache's single-flight instead of recomputing.
                if (!searchCompletedNormally) warmupCancelled.set(true)
            }
        }

    /** The optimality verdict for a finished max-damage result (P4.2). */
    sealed interface MaxDamageProof {
        /** The shown build is the PROVEN optimum — CP-SAT closed the gap, or the certificate did. */
        data object ProvenOptimal : MaxDamageProof

        /** Not proven optimal, but the certificate BOUNDS the gap: the true optimum is at most [fraction] (a
         *  fraction, e.g. 0.02 = 2%) above the shown build's objective. */
        data class ProvenWithin(
            val fraction: Double,
        ) : MaxDamageProof

        /** No proof available — a boss/multi-element request CP-SAT did not fully prove, required-stat targets
         *  (penalty-multiplied objective, not comparable), forced runes/sublimations, or a bailed shape. */
        data object Unavailable : MaxDamageProof
    }

    /**
     * Post-search optimality proof for a max-damage [result] (P4.2), computed by the AP-cell certificate. Meant
     * to run ASYNC after the search flow completes — it can take seconds to minutes (a full exact tier-2 solve),
     * so it is deliberately OFF the search's critical path (the badge appears when ready).
     *
     * Logic (see `docs/CERTIFICATE_PROD_PLAN.md` §P4.2):
     *  - CP-SAT already proved it (`result.isOptimal`) ⇒ [MaxDamageProof.ProvenOptimal] with no certificate.
     *  - Otherwise the certificate's `maxCellObjective` (a proven upper bound on every build's DAMAGE) is compared
     *    to the incumbent's **unpenalized** damage proxy ([SolverResult.maxDamageRawProxy]): incumbent feasible +
     *    `proxy ≥ maxCell` ⇒ optimal; else the ratio bounds the gap.
     *  - A mandatory self-check (`cellObjectives[incumbentAp] ≥ proxy`) guards against a live under-count — on
     *    violation the badge is suppressed and the event logged (never a wrong "proven").
     *  - **Required-stat targets are supported** (they were previously skipped): the certificate bounds damage over
     *    ALL builds, so it certifies the incumbent when the incumbent FULLY meets every required target — then its
     *    shortfall multiplier is the flat maximum and "max damage" ⟺ "max penalized objective". A build that
     *    misses a target could out-score it on the penalized objective, so that case stays [MaxDamageProof.Unavailable].
     *  - Skipped (→ [MaxDamageProof.Unavailable]) for forced runes, a survivability soft-floor (a per-build
     *    multiplier the damage-only certificate does not model), an un-proven boss/multi-element request, a
     *    target-missing incumbent, or a bailed certifier shape.
     */
    fun proveOptimality(
        baseParams: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        result: SolverResult<BuildCombination>,
        threads: Int = WakfuBuildSolver.certifierDefaultThreads(),
        // B8: polled once per certifier DP stage. When the caller cancels the proof (search restarted / window
        // closed) the certificate bails promptly and this returns Unavailable with nothing cached.
        isCancelled: () -> Boolean = { false },
    ): MaxDamageProof {
        // The item prefilter (multi-element mastery / resistance targets) is a top-N-per-stat HEURISTIC that can
        // prune the true optimum. When it fires, NEITHER of the two proof authorities is sound: CP-SAT's `OPTIMAL`
        // holds only over the REDUCED pool (so `result.isOptimal` proves nothing global), and the certificate is
        // built through the same `buildModel`, which re-applies the prefilter — its ledger is an UNDER-count over
        // the reduced pool, which would let a wrong "proven optimal" badge slip through. Both defects are silent
        // (the deterministic test path pins tuning, which disables domination but NOT the prefilter). So withhold
        // the badge outright for any prefiltered request — the invariant is "when in doubt, no badge". (Certifying
        // prefiltered shapes soundly would require running the certificate over the FULL pool; that is a
        // certifier-pool change — CERTIFIER_VERSION bump + measurement — deliberately left as future work.)
        if (WakfuBuildSolver.needsItemPrefilter(baseParams.targetStats)) return MaxDamageProof.Unavailable
        if (result.isOptimal) return MaxDamageProof.ProvenOptimal
        // A debuff-improved (Sram/Sadida) result is structurally heuristic ⇒ never claim proof.
        if (result.maxDamageHeuristicPhases) return MaxDamageProof.Unavailable
        // Forced runes (per-item GUI or by-name CLI): the certificate over the unforced superset would be a
        // sound but loose bound the badge rarely meets — skip for a consistent, honest absence.
        if (baseParams.forcedRunesByItem.isNotEmpty() || baseParams.forcedRunes.isNotEmpty()) {
            return MaxDamageProof.Unavailable
        }
        // Survivability soft-floor: it multiplies the score by a per-build EHP factor the damage-only certificate
        // does not model, so neither the penalized objective NOR the raw damage proxy is a comparable bound — skip.
        if (baseParams.damageScenario.survivabilityFloor && baseParams.damageScenario.minEffectiveHp > 0) {
            return MaxDamageProof.Unavailable
        }
        // The certificate is per single element; a boss/multi-element request CP-SAT did not fully prove stays
        // unproven (per-element certificate composition is future work).
        if (baseParams.damageScenario.candidateElements().size > 1) return MaxDamageProof.Unavailable

        // Compare against the UNPENALIZED damage proxy — the value in the certificate ledger's units. It equals
        // [SolverResult.maxDamageObjective] when the request has no required targets (penalty = identity); with
        // required targets the objective is penalized and only the raw proxy is comparable. (The fallback keeps
        // pre-plumbing / hand-built results working; production max-damage results always carry the proxy.)
        val incumbentProxy = result.maxDamageRawProxy ?: result.maxDamageObjective ?: return MaxDamageProof.Unavailable

        // Required-target requests: the certificate bounds damage over ALL builds (it ignores the AP/MP/range
        // targets). That certifies the incumbent only when the incumbent FULLY meets every required target — then
        // its shortfall multiplier is the flat maximum, so `proxy ≥ maxCell ⇒ penalized-optimal`. If a target is
        // unmet the incumbent's multiplier is below max and a different (target-meeting, lower-damage) build could
        // out-score it on the penalized objective, so we cannot certify — honest absence. (Forced SUBLIMATIONS are
        // NOT gated here: the certificate credits supported forced subs and BAILS → null ledger → Unavailable below.)
        //
        // A hard-leg result ([SolverResult.maxDamageHardConstraintsMet]) meets every required target by
        // construction — the solver enforced `actual ≥ target` in its exact arithmetic — so trust that verdict
        // directly. Only re-derive through the scorer for other results (e.g. a soft-leg fallback). This avoids the
        // solver-vs-scorer percent-rounding divergence (HP is the only percent-affected required target) denying a
        // deserved badge when the scorer grid reads one point short of a target the solver provably met.
        if (!result.maxDamageHardConstraintsMet && !fullyMeetsRequiredTargets(baseParams, result.individual)) {
            return MaxDamageProof.Unavailable
        }

        val ledger =
            MaxDamageCertificateCache.certificate(
                baseParams,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = true,
                incumbentObjective = incumbentProxy,
                threads = threads,
                // Cascade: with a weak incumbent, confirm survivors one cell at a time instead of paying
                // a tier-1.5 pass for every one. Verdict-safe: unprocessed cells keep sound (looser) fast
                // bounds, which can only make this MORE conservative (never a wrong ProvenOptimal); the
                // construct path (dpConstruct) falls back to a full ledger when the argmax is unconfirmed.
                cascadeTier15 = true,
                isCancelled = isCancelled
            ) ?: return MaxDamageProof.Unavailable
        val maxCell = ledger.maxCellObjective ?: return MaxDamageProof.Unavailable // a cell bailed ⇒ no sound global bound

        // Self-check (mandatory): the incumbent's OWN cell must upper-bound the incumbent's damage proxy. A
        // violation means the certifier under-counted on live data — suppress the badge and log loudly.
        val incumbentAp = actualActionPoints(baseParams, result.individual)
        val cellBound = ledger.cellObjectives[incumbentAp]
        if (cellBound == null || cellBound < incumbentProxy) {
            logger.error {
                "Certificate self-check FAILED (badge suppressed): cell $incumbentAp bound=$cellBound < proxy=$incumbentProxy " +
                    "— the certifier under-counted on live data. Solve is unaffected."
            }
            return MaxDamageProof.Unavailable
        }
        // The certificate upper-bounds every build's damage; the incumbent is feasible ⇒ optimal iff its damage
        // meets the certified ceiling. (`≥`, not `==`: the cert is ≥ the true max, so proxy ≥ cert ⇒ optimal.)
        return if (incumbentProxy >= maxCell) {
            MaxDamageProof.ProvenOptimal
        } else {
            MaxDamageProof.ProvenWithin((maxCell.toDouble() / incumbentProxy.toDouble()) - 1.0)
        }
    }

    /**
     * Whether [build] fully satisfies every required (non-maximized) AP/MP/range/… target of [params] — so its
     * shortfall penalty multiplier is the flat maximum and the certificate's damage bound certifies the penalized
     * objective (see [proveOptimality]). True when the request has no required targets. Reuses the scorer's own
     * penalty math ([FindMaxDamageScoring.requiredConstraintPenaltyFactor], which returns 1 iff every required
     * target is met) so this can never drift from what the solver actually optimized.
     */
    private fun fullyMeetsRequiredTargets(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): Boolean {
        if (params.targetStats.none { it.characteristic.isRequiredMostMasteriesTarget() }) return true
        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = params.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(params.damageScenario.element.masteryCharacteristic to 1),
                resistanceElementsWanted = params.targetStats.resistanceElementsWanted
            )
        return FindMaxDamageScoring.requiredConstraintPenaltyFactor(params.targetStats, stats).compareTo(BigDecimal.ONE) <= 0
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
            optimizeHardThenSoft(params, equipmentsByItemType, runes, sublimations, tuning)
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

    /**
     * The max-damage solve, **hard-constraints first**. A request with required AP/MP/range/resistance/… targets
     * is first solved with those as HARD constraints (`actual ≥ target`) under a PLAIN damage objective — the
     * shape CP-SAT can prove — so the search finds the TRUE constrained optimum instead of the soft penalty's
     * foggy relaxation, which traps it at a sub-optimal build. Two payoffs: for a REACHABLE target the build is
     * the real optimum (often strictly better than the penalty's), and when the target does not cost damage the
     * plain objective proves OPTIMAL outright. If the hard model is INFEASIBLE (the target set is unreachable),
     * the hard flow emits nothing and we fall back to the SOFT penalty (the current behaviour — always returns
     * the closest build). A request with no required targets skips straight to the identical plain solve.
     *
     * Unlike the damage certificate, this generalises to EVERY constraint — resistance, HP, lock, … — because it
     * is the solver, not a damage-only bound, that enforces them.
     */
    private fun optimizeHardThenSoft(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: WakfuBuildSolver.SolverTuning?,
        // C8(3): greedy warm start, single-element path only — see [WakfuBuildSolver.optimize].
        greedyWarmStart: Boolean = false,
    ): Flow<SolverResult<BuildCombination>> {
        // Match [StatBuilder.addRequiredTargetHardConstraints]'s own `target > 0` filter: a request whose only
        // required-target stats are non-positive adds ZERO hard constraints, so the "hard" leg would be a plain
        // unpenalized solve that (being satisfiable) never falls through to the soft penalty. Skipping straight to
        // the identical plain solve here keeps the two predicates aligned and the behaviour honest.
        if (params.targetStats.none { it.characteristic.isRequiredMostMasteriesTarget() && it.target > 0 }) {
            return WakfuBuildSolver.optimize(params, equipmentsByItemType, runes, sublimations, tuning, maxDamageGreedyWarmStart = greedyWarmStart)
        }
        return flow {
            val start = TimeSource.Monotonic.markNow()
            var hardYieldedBuild = false
            WakfuBuildSolver
                .optimize(params, equipmentsByItemType, runes, sublimations, tuning, hardConstraints = true, maxDamageGreedyWarmStart = greedyWarmStart)
                .collect {
                    hardYieldedBuild = true
                    // Mark the hard-leg provenance so the certificate badge can trust the solver's own
                    // "targets met" verdict rather than re-deriving it through the scorer's divergent
                    // percent-rounding (see [SolverResult.maxDamageHardConstraintsMet]).
                    emit(it.copy(maxDamageHardConstraintsMet = true))
                }
            if (!hardYieldedBuild) {
                // No build from the hard leg — infeasible (unreachable targets) OR no incumbent found in a tiny
                // probe budget. Give the soft leg only the time the hard leg didn't spend, so the two legs
                // together stay within the caller's budget instead of each taking the full duration (~2×). Floored
                // to the solver's own 50 ms minimum so the remainder never rounds to the "unlimited" sentinel.
                val remaining = (params.searchDuration - start.elapsedNow()).coerceAtLeast(50.milliseconds)
                logger.info {
                    "Hard-constraint max-damage model yielded no build (infeasible or budget too small); " +
                        "falling back to the soft shortfall penalty."
                }
                WakfuBuildSolver
                    .optimize(
                        params.copy(searchDuration = remaining),
                        equipmentsByItemType,
                        runes,
                        sublimations,
                        tuning,
                        hardConstraints = false,
                        maxDamageGreedyWarmStart = greedyWarmStart
                    ).collect { emit(it) }
            }
        }
    }

    private suspend fun solveProbe(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: WakfuBuildSolver.SolverTuning?,
    ): SolverResult<BuildCombination>? =
        optimizeHardThenSoft(params, equipmentsByItemType, runes, sublimations, tuning)
            .toList()
            // Highest score, tie-broken toward the PROVEN result: when CP-SAT reaches the optimal score before
            // certifying it, the flow emits the same build first as `isOptimal=false` then as `isOptimal=true`;
            // a plain maxBy{score} would return the earlier un-proven copy and lose the per-element proof.
            .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))

    /**
     * The AP-window probe targets around the phase-1 winner's AP [a0]: the `[a0−3, a0+3]` window, clamped to the
     * throughput table's `[MIN_AP_TARGET, MAX_AP_TARGET]` range, excluding [a0] itself (already solved in phase 1).
     *
     * When the request carries a required AP target [requiredApTarget] (`> 0`), any probe below it is dropped: a
     * probe pins AP == target (an `addEquality` in `buildMaxDamageObjective`) while the hard leg additionally posts
     * `actual ≥ requiredApTarget`, so `target < requiredApTarget` is INFEASIBLE by construction and would pay a
     * doomed hard-then-soft double solve for nothing. With no required AP target, pass [MIN_AP_TARGET] (no extra
     * lower bound — the full window is kept).
     */
    internal fun apProbeTargets(
        a0: Int,
        requiredApTarget: Int,
    ): List<Int> =
        ((a0 - AP_WINDOW_BELOW)..(a0 + AP_WINDOW_ABOVE))
            .filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 && it >= requiredApTarget }

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

/**
 * Session cache (P4.3, made incumbent-independent by B4) for the max-damage certificate ledger — memoizes the
 * expensive raw per-cell bounds so a repeat proof of the same shape skips the recompute. Memory-only, thread-safe,
 * unbounded (a session runs only a handful of distinct shapes).
 *
 * **B4: the key excludes the incumbent.** The raw parts — the per-cell fast array and each cell's exact
 * `maxPerHit` — are pure functions of (model, pool, scenario); only the ELIMINATION boundary (which cells are
 * confirmed exact vs. kept fast) depends on the incumbent. So the cache stores an incumbent-free [RawEntry]
 * ({fast array, exact-by-cell, exact-bailed cells, shape-bail}) and RECONSTRUCTS the ledger for any incumbent by
 * re-running only the trivial elimination arithmetic — no model build, no DP. A re-search with a tweaked duration,
 * a restart, or an improved incumbent now returns its verdict near-instantly instead of re-running the ~68 s fast
 * pass plus the surviving exact cells. Because B1's flood control confirms survivors loosest-fast-first, the FIRST
 * run already computes (and caches) the highest-fast survivor — the one that decides a lost badge — so a
 * subsequent run with any incumbent is a full hit. The entry ACCUMULATES exact values across runs; a survivor not
 * yet cached forces a one-off recompute (which merges its new exacts back in).
 *
 * The key is otherwise conservative: the request [WakfuBestBuildParams] minus the ledger-irrelevant fields
 * (search duration, stop-on-match, AP pin, worker count), the data + certifier versions, and the sorted pool /
 * rune / sublimation ids. A hit is always sound; over-keying only ever costs a recompute. Bumping
 * [WakfuBuildSolver.CERTIFIER_VERSION] invalidates every entry.
 */
object MaxDamageCertificateCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<Key, RawEntry>()

    // E10 single-flight: at most ONE certificate compute per key at a time. A concurrent caller (the
    // post-search proof arriving while the search-time warm-up is still computing the same shape) WAITS for
    // the in-flight compute, then reconstructs from the merged entry — instead of duplicating a
    // seconds-to-minutes DP. The latch is created by the computing thread and counted down in `finally`.
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Key, java.util.concurrent.CountDownLatch>()

    /** Test seam: how many actual certificate COMPUTES ran (reconstructions and single-flight waits don't count). */
    internal val computeCountForTest =
        java.util.concurrent.atomic
            .AtomicLong()

    val size: Int get() = cache.size

    fun clear() = cache.clear()

    fun certificate(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        applyDomination: Boolean,
        incumbentObjective: Long?,
        threads: Int,
        // Dynamic per-tier thread count (takes precedence over [threads] when set) — see
        // [WakfuBuildSolver.maxDamageCertificate]. The warm-up uses it to scale up once the search is done.
        threadsProvider: ((CertTier) -> Int)? = null,
        // Dynamic incumbent (resolved before elimination) — see [WakfuBuildSolver.maxDamageCertificate].
        incumbentProvider: (() -> Long?)? = null,
        // Cascade tier-1.5 (short-search rescue) — see [StatBuilder.certifyLedgerCascadeTier15]. A cascaded
        // (possibly partial) ledger MERGES into the same cache entry: per-cell confirms are incumbent-
        // independent values, so a later full compute simply fills the cells the cascade skipped.
        cascadeTier15: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): CertLedger? {
        val key = keyFor(params, equipmentsByItemType, runes, sublimations, applyDomination)
        val fingerprint = fingerprintOf(key)

        // B5: on an in-memory miss, try the disk cache — the SAME shape across app restarts reconstructs its badge
        // in seconds instead of re-running the multi-minute certifier. A disk hit is verified byte-for-byte against
        // the live request's fingerprint before it is trusted; any anomaly returns null and we fall through to a
        // recompute (always sound).
        if (cache[key] == null) {
            MaxDamageCertificateDiskCache.load(fingerprint)?.let { record ->
                cache.putIfAbsent(key, RawEntry.fromRecord(record))
            }
        }

        while (true) {
            // Fast path: a cached entry can reconstruct this incumbent's ledger from the raw parts (no DP, no build).
            cache[key]?.let { entry -> reconstruct(entry, incumbentObjective)?.let { return it } }
            if (isCancelled()) return null

            val myLatch = java.util.concurrent.CountDownLatch(1)
            val other = inFlight.putIfAbsent(key, myLatch)
            if (other != null) {
                // E10: another thread is already computing this shape (typically the search-time warm-up) —
                // wait for it (cancellation-responsive poll), then retry reconstruction with its merged entry.
                while (!other.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (isCancelled()) return null
                }
                continue
            }
            try {
                // Double-check under the flight slot: the entry may have completed between the reconstruct
                // attempt above and claiming the slot.
                val existing = cache[key]
                existing?.let { entry -> reconstruct(entry, incumbentObjective)?.let { return it } }

                // Miss, or a survivor not yet cached: recompute (this incumbent) and merge the raw parts into the
                // entry. A cancelled (B8) / bailed-to-null run returns null and caches nothing. B6: when an entry
                // already exists (a partial hit — its incumbent-independent fast bounds are cached but some
                // survivor's tier-1.5/exact is not), reuse those fast bounds so the recompute SKIPS the tier-1
                // fast DP and only re-runs the survivor confirmation.
                computeCountForTest.incrementAndGet()
                val ledger =
                    WakfuBuildSolver.maxDamageCertificate(
                        params,
                        equipmentsByItemType,
                        runes,
                        sublimations,
                        applyDomination,
                        incumbentObjective,
                        threads,
                        threadsProvider,
                        incumbentProvider,
                        cascadeTier15,
                        isCancelled,
                        precomputedFast = existing?.fastObjectives,
                        precomputedBailed = existing?.bailed,
                        precomputedTier15 = existing?.tier15ByCell?.toMap(),
                        precomputedExact = existing?.exactByCell?.toMap(),
                        precomputedProv = existing?.provByCell?.toMap()
                    ) ?: return null
                val merged = cache.compute(key) { _, e -> (e ?: RawEntry(ledger)).also { it.merge(ledger) } }!!
                // Persist the accumulated raw bounds (never a bailed shape — it carries no sound global bound).
                if (merged.bailed.isEmpty()) MaxDamageCertificateDiskCache.store(fingerprint, merged.toRecord(fingerprint))
                return ledger
            } finally {
                inFlight.remove(key)
                myLatch.countDown()
            }
        }
    }

    /** Build the incumbent-free cache key, normalizing the fields that do not affect the ledger. */
    private fun keyFor(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        applyDomination: Boolean,
    ): Key =
        Key(
            dataVersion = me.chosante.common.WakfuData.VERSION,
            certifierVersion = WakfuBuildSolver.CERTIFIER_VERSION,
            // Normalize the fields that do not affect the ledger so duration / worker-count changes still hit.
            params =
                params.copy(
                    searchDuration = Duration.ZERO,
                    stopWhenBuildMatch = false,
                    maxDamageApTarget = null,
                    solverWorkers = null
                ),
            poolIds =
                equipmentsByItemType.values
                    .flatten()
                    .map { it.equipmentId }
                    .sorted(),
            runeIds = runes.map { it.id }.sorted(),
            subIds = sublimations.map { it.stateId }.sorted(),
            applyDomination = applyDomination
        )

    /** Test seam: the canonical disk fingerprint of a request (see [fingerprintOf]). */
    internal fun fingerprintForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        applyDomination: Boolean,
    ): String = fingerprintOf(keyFor(params, equipmentsByItemType, runes, sublimations, applyDomination))

    /**
     * A canonical, injective fingerprint of the incumbent-free cache [Key] — the FULL identity a disk record is
     * verified against (see [MaxDamageCertificateDiskCache]). Every atom is length-prefixed (`<len>|<value>`) so
     * the encoding is prefix-free: distinct keys never collide. Sets/maps are sorted (their `equals` is
     * order-insensitive); lists keep order (their `equals` is not). It therefore distinguishes any two
     * `equals`-unequal keys — a hit can only ever be for the exact same request. The four fields the [Key]
     * already normalized away (search duration, stop-on-match, AP pin, worker count) are constant here and
     * omitted. A tripwire test ([WakfuBuildSolverTest] `fingerprint covers every field`) fails if a field is
     * added to the fingerprinted graph without being encoded here.
     */
    private fun fingerprintOf(key: Key): String {
        val sb = StringBuilder(512)

        fun tok(value: String) {
            sb
                .append(value.length)
                .append('|')
                .append(value)
                .append(';')
        }

        fun tok(value: Int) = tok(value.toString())

        fun tok(value: Long) = tok(value.toString())

        fun tok(value: Boolean) = tok(if (value) "1" else "0")

        tok(key.dataVersion)
        tok(key.certifierVersion)
        tok(key.applyDomination)

        val p = key.params
        // Character: class + level + minLevel + the NET skill allocation (CharacterSkills' own equality is the
        // level plus its allCharacteristicValues — its skill objects have identity semantics — so mirror that).
        tok(p.character.clazz.name)
        tok(p.character.level)
        tok(p.character.minLevel)
        val skillValues = p.character.characterSkills.allCharacteristicValues
        tok(p.character.characterSkills.level)
        tok(skillValues.fixedValues.size)
        skillValues.fixedValues.entries.sortedBy { it.key.name }.forEach {
            tok(it.key.name)
            tok(it.value)
        }
        tok(skillValues.percentValues.size)
        skillValues.percentValues.entries.sortedBy { it.key.name }.forEach {
            tok(it.key.name)
            tok(it.value)
        }

        // TargetStats is a HashSet<TargetStat> (order-insensitive) → sort by (characteristic, target, weight).
        val targets = p.targetStats.sortedWith(compareBy({ it.characteristic.name }, { it.target }, { it.userDefinedWeight }))
        tok(targets.size)
        targets.forEach {
            tok(it.characteristic.name)
            tok(it.target)
            tok(it.userDefinedWeight)
        }

        tok(p.maxRarity.name)
        val excludedRarities = p.excludedRarities.map { it.name }.sorted()
        tok(excludedRarities.size)
        excludedRarities.forEach { tok(it) }

        tok(p.forcedItems.size)
        p.forcedItems.forEach { tok(it) }
        tok(p.excludedItems.size)
        p.excludedItems.forEach { tok(it) }
        tok(p.scoreComputationMode.name)

        tok(p.useRunes)
        tok(p.forcedRunes.size)
        p.forcedRunes.forEach { tok(it) }
        val forcedRunesByItem = p.forcedRunesByItem.entries.sortedBy { it.key }
        tok(forcedRunesByItem.size)
        forcedRunesByItem.forEach { (item, runeIds) ->
            tok(item)
            tok(runeIds.size)
            runeIds.forEach { tok(it) } // order-significant (List equality)
        }

        tok(p.useSublimations)
        tok(p.maxSublimationTier ?: -1)
        tok(p.forcedSublimations.size)
        p.forcedSublimations.forEach { tok(it) }
        // Exclusions already shrink the sub identity list (key.subIds) below, but tokenizing the param keeps
        // the fingerprint injective at the request level too (belt and braces — a stale hit is a wrong badge).
        tok(p.excludedSublimations.size)
        p.excludedSublimations.forEach { tok(it) }
        tok(p.forcedPassives.size)
        p.forcedPassives.forEach { tok(it) }

        // DamageScenario (a data class): every field, elementResistances sorted (order-insensitive Map equality).
        val ds = p.damageScenario
        tok(ds.element.name)
        tok(ds.rangeBand.name)
        tok(ds.orientation.name)
        tok(ds.berserk)
        tok(ds.healing)
        tok(ds.critCapPercent)
        tok(ds.targetResistancePercent)
        tok(ds.baseDamage)
        val elementResistances = ds.elementResistances
        if (elementResistances == null) {
            tok("null")
        } else {
            val sorted = elementResistances.entries.sortedBy { it.key.name }
            tok(sorted.size)
            sorted.forEach {
                tok(it.key.name)
                tok(it.value)
            }
        }
        tok(ds.survivabilityFloor)
        tok(ds.minEffectiveHp)

        // Pool / rune / sublimation identity (already sorted by the caller).
        tok(key.poolIds.size)
        key.poolIds.forEach { tok(it) }
        tok(key.runeIds.size)
        key.runeIds.forEach { tok(it) }
        tok(key.subIds.size)
        key.subIds.forEach { tok(it) }

        return sb.toString()
    }

    /**
     * Rebuild the ledger for [incumbent] from an entry's cached raw parts, mirroring [certifyLedger] EXACTLY — or
     * `null` when a survivor it would confirm is not yet cached (⇒ the caller recomputes). Byte-identical to a
     * fresh compute: same survivor order (loosest fast first), same B1 early-stop, same exact-bailed handling.
     */
    private fun reconstruct(
        entry: RawEntry,
        incumbent: Long?,
    ): CertLedger? {
        if (entry.bailed.isNotEmpty()) return CertLedger(emptyMap(), entry.bailed, emptySet(), null)
        val fastObj = entry.fastObjectives
        val survivors =
            fastObj.keys
                .filter { incumbent == null || fastObj.getValue(it) > incumbent }
                .sortedByDescending { fastObj.getValue(it) }
        val usedExact = LinkedHashMap<Int, Long>()
        val usedTier15 = LinkedHashMap<Int, Long>()
        val reconBailed = linkedSetOf<Int>()
        // Mirror certifyLedger EXACTLY. With an incumbent (production path), tier-1.5 clears survivors ≤ incumbent
        // and only the rest go to the exact tier (B1 early-stop). With incumbent == null (oracle path) tier-1.5 is
        // not run — every survivor is confirmed exactly.
        val stillSurviving = ArrayList<Int>()
        if (incumbent != null) {
            for (a in survivors) {
                // Mirror the compute path's cache reuse: a cached EXACT decides a survivor outright —
                // the cascaded compute never produces a tier-1.5 value for such cells, so requiring one
                // here made every repeat proof of a cascade-touched shape recompute needlessly.
                val exact = entry.exactByCell[a]
                if (exact != null) {
                    usedExact[a] = exact
                    continue
                }
                val t15 = entry.tier15ByCell[a] ?: return null // uncached tier-1.5 survivor ⇒ recompute
                usedTier15[a] = t15
                if (t15 <= incumbent) continue // cleared by tier-1.5
                stillSurviving += a
            }
        } else {
            stillSurviving += survivors
        }
        for (a in stillSurviving) {
            when {
                entry.exactByCell.containsKey(a) -> {
                    val obj = entry.exactByCell.getValue(a)
                    usedExact[a] = obj
                    // B1 early-stop only applies with an incumbent (null = confirm every survivor).
                    if (incumbent != null && obj > incumbent) break
                }
                a in entry.exactBailed -> reconBailed += a // exact bailed here (known) ⇒ keep tier-1.5/fast, not tier-2
                else -> return null // uncached survivor ⇒ cannot reconstruct soundly ⇒ recompute
            }
        }
        val cellObjectives = fastObj.keys.associateWith { usedExact[it] ?: usedTier15[it] ?: fastObj.getValue(it) }
        // E8 item A (perf): re-attach the winning (world, crit-step) for the exactly-used cells (a cached entry may
        // predate provenance ⇒ this map is simply sparser, and the E8 fast-path falls back where it is missing).
        val recProv = usedExact.keys.mapNotNull { c -> entry.provByCell[c]?.let { c to it } }.toMap()
        return CertLedger(
            cellObjectives,
            emptySet(),
            usedExact.keys.toSet(),
            cellObjectives.values.maxOrNull(),
            fastObj,
            reconBailed,
            usedTier15,
            recProv
        )
    }

    /** The incumbent-independent raw parts of a shape's certificate; [exactByCell] / [exactBailed] accumulate. */
    private class RawEntry private constructor(
        val fastObjectives: Map<Int, Long>,
        val bailed: Set<Int>,
    ) {
        val exactByCell = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val exactBailed: MutableSet<Int> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()

        // B7: the incumbent-independent tier-1.5 objective per computed survivor cell (accumulates like exactByCell).
        val tier15ByCell = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        // E8 item A (perf): the winning (world, crit-step) per exactly-confirmed cell (accumulates like exactByCell),
        // so the fast-path replays one explain pass instead of the full N-worlds scan. Incumbent-independent.
        val provByCell = java.util.concurrent.ConcurrentHashMap<Int, CellProvenance>()

        constructor(seed: CertLedger) : this(seed.fastObjectives, seed.bailedCells)

        fun merge(ledger: CertLedger) {
            ledger.tier2Cells.forEach { exactByCell[it] = ledger.cellObjectives.getValue(it) }
            exactBailed.addAll(ledger.exactBailedCells)
            tier15ByCell.putAll(ledger.tier15Objectives)
            provByCell.putAll(ledger.cellProvenance)
        }

        /** B5: freeze the accumulated raw bounds into their on-disk form, tagged with the request [fingerprint]. */
        fun toRecord(fingerprint: String): DiskRecord =
            DiskRecord(
                fingerprint = fingerprint,
                fastObjectives = fastObjectives,
                bailed = bailed,
                exactByCell = exactByCell.toMap(),
                exactBailed = exactBailed.toSet(),
                tier15ByCell = tier15ByCell.toMap(),
                provByCell = provByCell.toMap()
            )

        companion object {
            /** B5: rebuild an entry from a verified on-disk [record] (its fingerprint already matches this request). */
            fun fromRecord(record: DiskRecord): RawEntry =
                RawEntry(record.fastObjectives, record.bailed).apply {
                    exactByCell.putAll(record.exactByCell)
                    exactBailed.addAll(record.exactBailed)
                    tier15ByCell.putAll(record.tier15ByCell)
                    provByCell.putAll(record.provByCell)
                }
        }
    }

    private data class Key(
        val dataVersion: String,
        val certifierVersion: Int,
        val params: WakfuBestBuildParams,
        val poolIds: List<Int>,
        val runeIds: List<Int>,
        val subIds: List<Int>,
        val applyDomination: Boolean,
    )
}
