package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverSolutionCallback
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Two-tier max-damage certificate for a single element (see `docs/CERTIFICATE_PROD_PLAN.md` §P3). All
 * values are in OBJECTIVE units (the ONE scaling formula
 * `clampedTable[ap] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE`), so they compare
 * directly against CP-SAT objectives.
 *
 * - [cellObjectives]: per-AP-cell certified upper bound — the EXACT value for [tier2Cells], the fast
 *   value for cells eliminated below the incumbent (both are sound upper bounds; eliminated cells only
 *   ever need `≤ incumbent`). Excludes [bailedCells].
 * - [bailedCells]: cells with NO sound bound at all (the fast pass shape-bailed — all-or-nothing in
 *   practice). Their presence forces [maxCellObjective] to `null`.
 * - [tier2Cells]: cells CONFIRMED by the exact pass. A surviving cell on which the exact pass itself
 *   bails keeps its (still sound) fast value and is deliberately NOT listed here — that is not a bail.
 * - [maxCellObjective]: `max` over [cellObjectives]; `null` iff [bailedCells] is non-empty (an
 *   unbounded cell means no provable ceiling for the element).
 */
data class CertLedger(
    val cellObjectives: Map<Int, Long>,
    val bailedCells: Set<Int>,
    val tier2Cells: Set<Int>,
    val maxCellObjective: Long?,
    // B4 (incumbent-free cache): the RAW, incumbent-independent per-cell parts, so a re-search of the SAME shape
    // with a different incumbent can reconstruct its ledger from cache instead of re-running the DPs.
    // - [fastObjectives]: the fast-tier upper bound for EVERY cell (empty iff [bailedCells] non-empty). Unlike
    //   [cellObjectives] (which is the exact value on [tier2Cells]), this is the pure fast array, so a new
    //   incumbent's elimination boundary can be recomputed. A value-typed Map (not LongArray) so data-class
    //   equality stays structural.
    // - [exactBailedCells]: survivors whose EXACT pass itself bailed (a sound bound still exists — they keep fast).
    //   Cached so the reconstruction knows to keep such a cell at fast rather than treating it as "not yet computed".
    // - [tier15Objectives] (B7): survivors sharpened by the step-1 tier-1.5 pass, incumbent-independent, in OBJECTIVE
    //   units. A cell CLEARED by tier-1.5 (bound ≤ incumbent) carries this value in [cellObjectives]; cached so a
    //   re-search reconstructs the elimination without re-running the sharpened DP. Empty on the oracle
    //   (`forceTier2All` / no-incumbent) path, which confirms every survivor exactly.
    val fastObjectives: Map<Int, Long> = emptyMap(),
    val exactBailedCells: Set<Int> = emptySet(),
    val tier15Objectives: Map<Int, Long> = emptyMap(),
    // E8 item A (perf): the winning (certifier world, crit-step) per EXACT-confirmed cell ([tier2Cells]). Captured
    // for free during the exact pass so the E8 fast-path replays that ONE (world, c) as a single explain pass to
    // recover the argmax build's items, instead of re-running the whole N-worlds provenance scan (~minutes at high
    // level). Additive + optional: a cell absent here (an old cache entry, or a tier-1.5-cleared argmax) makes the
    // fast-path fall back to the full scan — sound and correct, just slower. It changes NO bound, so there is NO
    // CERTIFIER_VERSION bump and NO oracle re-run.
    val cellProvenance: Map<Int, CellProvenance> = emptyMap(),
)

/**
 * E8 item A (perf): the winning (certifier world, crit-step) of a cell's EXACT certificate bound — see
 * [CertLedger.cellProvenance]. [worldIndex] indexes the deterministic `certifierWorlds` list and [c] is the
 * arithmetic crit total; both are re-derived under the same [WakfuBuildSolver.CERTIFIER_VERSION] (which the
 * cache key pins), so a cached pointer can never bind to a different world enumeration.
 */
@Serializable
data class CellProvenance(
    val worldIndex: Int,
    val c: Int,
)

// Solver-wide numeric bounds/scales, de-nested from the object so StatBuilder.kt (same package) sees them
// by bare name instead of importing 30+ object members (B1 of docs/code-review-followups.md).
internal const val STAT_ABS_MAX = 10_000_000L
internal const val PERCENT_ABS_MAX = 10_000L
internal const val PRODUCT_ABS_MAX = STAT_ABS_MAX * PERCENT_ABS_MAX
internal const val STAT_WITH_PERCENT_ABS_MAX = STAT_ABS_MAX + (PRODUCT_ABS_MAX / 100) + 10
internal const val MAX_POWER_TABLE_INDEX = 2_000
internal const val MAX_PENALTY_MULTIPLIER = 1_000_000L
internal const val MAX_NORMAL_SUBLIMATIONS = 10L // Wakfu: at most 10 NORMAL sublimations (one per socketed gear slot).
internal const val MAX_SUBLIMATIONS_TOTAL = MAX_NORMAL_SUBLIMATIONS + 2L // + 1 epic + 1 relic (dedicated slots) = 12.
internal const val NORMAL_SUB_SOCKET_COST = 3L // a normal sublimation needs a 3-socket carrier for its ordered colour pattern.
internal const val MAX_OUT_OF_COMBAT_AP = 16L
internal const val MAX_OUT_OF_COMBAT_MP = 8L
internal const val MAX_OUT_OF_COMBAT_WP = 20L
internal const val MIN_OUT_OF_COMBAT_CRIT = -9L // negative-crit gear is condition-limited to ≥ −9% total.
internal const val DAMAGE_MASTERY_MAX = 100_000L
internal const val CLAMP_INTERMEDIATE_MAX = 8_000_000_000L
internal const val DAMAGE_GRAW_MAX = 400L * DAMAGE_MASTERY_MAX + 100L * (DAMAGE_MASTERY_MAX * 6)
internal const val DAMAGE_SCORE_ABS_MAX = (100L + DAMAGE_DI_MAX) * DAMAGE_GRAW_MAX
internal const val MAX_ROTATION_AP = 20L
internal const val PER_TURN_THROUGHPUT_MAX = 60_000L
internal const val RES_FACTOR_MIN = 10L // res capped at +90% → factor ≥ 10
internal const val RES_FACTOR_MAX = 200L // weakness floored at −100% → factor ≤ 200
internal const val PERHIT_DOWNSCALE = 100_000L
internal const val PERHIT_SCALED_MAX = DAMAGE_SCORE_ABS_MAX / PERHIT_DOWNSCALE + 1 // ≈ 5.1e6
internal const val ROTATION_RAW_MAX = PER_TURN_THROUGHPUT_MAX * PERHIT_SCALED_MAX // ≈ 3.06e11
internal const val ROTATION_RAW_RES_MAX = ROTATION_RAW_MAX * RES_FACTOR_MAX // ≈ 6.12e13
internal const val FINAL_DOWNSCALE = 20L
internal const val DAMAGE_PERTURN_ABS_MAX = ROTATION_RAW_RES_MAX / FINAL_DOWNSCALE // ≈ 3.06e12
internal const val FAST_C_SEGMENT_STEP = 8
internal const val EHP_HP_MAX = 1_000_000L
internal const val EHP_AVG_RESIST_CAP = 80L
internal const val EHP_MAX = EHP_HP_MAX * (100L + EHP_AVG_RESIST_CAP) / 100L
internal const val PRECISION_OVERFLOW_BOUND = 1_000_000_000L

object WakfuBuildSolver {
    private val logger = KotlinLogging.logger {}

    // MASTERY_SCORE_ABS_MAX is shared with the re-scorer — see ScoreComputationMode.kt.

    // Out-of-combat hardcaps (Wakfu): the equipped sheet can't exceed these. In-combat bonuses — including
    // start-of-combat sublimations — may go beyond, so the cap is on the PRE-sublimation value.

    // Bounds for the max-damage objective's nonlinear terms. Masteries / DI are clamped into these
    // (well above any real build) so the CP-SAT multiplication variables keep small, stable domains.
    // DAMAGE_DI_FLOOR / DAMAGE_DI_MAX are shared with the re-scorers — see ScoreComputationMode.kt.

    // Spell-aware / boss-aware per-turn damage (max-damage mode only). The per-turn value is
    // `(throughput × perHit) × resFactor`, scaled to keep every CP-SAT variable domain modest (≤ ~6e13,
    // well inside int64 so presolve never overflows) while preserving ranking resolution. The per-hit
    // core is first divided by [PERHIT_DOWNSCALE] (keeps ~5M levels — fine even for low-level builds),
    // then the `× resFactor` product is divided by [FINAL_DOWNSCALE] so the value — and then the
    // power-6 constraint penalty (× MAX_PENALTY_MULTIPLIER) — stays under Long.MAX/2.

    /**
     * Certifier semantics version — **bump on ANY change to the certifier** (fast pass, exact pass,
     * orchestrator, scaling formula, world enumeration). Keys the P4.3 session cache alongside
     * [me.chosante.common.WakfuData.VERSION], so a certifier change invalidates every cached per-cell bound
     * instead of silently serving a stale (possibly now-unsound) certificate.
     *
     * History — 8: fast-pass DenseDp port; 9: (superseded) subCap 10→12 + item-flag normal filter; 10: n
     * counts NORMAL-slot subs only — epic/relic subs ride their dedicated slots at the same n (budgets/topK
     * back on the normal cap) + exact sub-MP debit (no ≥0 clamp) for MP-sourced ramps; 11: (superseded) first
     * cut of cumulable stacking — the keptSubs pool-duplication was in place but the model's copy vars leaked
     * into the passive DI/mastery constants at their untracked ±1e7 domain, exploding the bound; 12: cumulable
     * stacking done right — [keptSubs] duplicates a cumulable sub's single-copy Raw [Sublimation.maxCopies]
     * times, and the model's copy vars are DROPPED from the certifier term lists ([certifierDroppedVars]) so
     * their value rides the base subVar term instead of leaking into the constants; 13: FORCED cumulable subs
     * stack as well — their base copy stays in the constants (and pre-charges its slot via `subCap`) while their
     * `maxCopies − 1` extra copies join the OPTIONAL pools, so the budget/crit-window machinery prices them like
     * any other copy; 14: multiplicity encoding — the DP passes collapse a cumulable sub's duplicated entries
     * into ONE stage taking j ∈ 0..maxCopies copies at once (`normalTransitionStages`). Same reachable set,
     * identical certified values (a mult > 1 sub is unconditional and never a ramp, so its per-copy contribution
     * is constant), but half the transition stages — v13 paid one full frontier sweep per COPY, which cost 3.4×
     * on the lvl-110 badge proof and ≥7.8× on a lvl-245 back+berserk request. keptSubs stays duplicated for the
     * slot-counting consumers (budgets / segment edges / minSubsToCover); 15: FAMILY BUDGETS — mono-axis
     * unconditional transition subs (pure-DI, pure-mastery) leave the DP stages entirely and are priced at
     * harvest as sorted-prefix budgets (`diPrefix` / `grawBudgetPrefix` + `budgetMax` split enumeration over the
     * free slots), exactly like the pre-existing pure-crit / pure-AP budgets; all-zero Raws (off-element DI subs
     * in a mono-element scenario) are dropped outright. Reachable value set identical (sorted-prefix selection
     * is exact for a mono-axis family) ⇒ certified values unchanged; only the DP frontier shrinks.
     */
    const val CERTIFIER_VERSION: Int = 15

    // Min wall-clock gap between intermediate best-so-far emissions. Each emission re-runs the heavy
    // solutionToBuild + scoreFor (a knapsack rotation in max-damage) ON the native solve thread, stealing
    // cycles from search/proof. Intermediate snapshots are pure progress — re-rendering the in-flight build
    // more than ~twice a second has no UX value — so coalescing to one per 500ms returns those cycles to the
    // solver without affecting the result: the FINAL build is recomputed unconditionally after solve() and
    // delivered via a guaranteed (suspending) send, so throttling intermediates can never drop or reorder it.
    private const val INTERMEDIATE_EMIT_THROTTLE_MS = 500L

    // E8 fallback (see [dpConstructProvenOptimum]): deterministic-time budget for the full-pool
    // feasibility re-solve (`rawScore ≥ bound`, stop at first solution). It only runs when the restricted
    // fast path misses the bound — before the fallback existed those shapes produced NO construction at
    // all — so a generous budget trades bounded extra latency (async, badge-only path) for reliability.
    private const val E8_FALLBACK_DETERMINISTIC_BUDGET = 300.0

    // FAST tier-1 certifier (P2): crit-grid step for the per-segment 3-D passes. Each segment folds point
    // graw at its top crit, so the fold looseness on the critM slice is bounded by ~step/c — smaller = tighter
    // but more segments (linear cost). Tune against the 110/245 fast-vs-exact ratios.

    // Survivability soft-floor (Lot 5, opt-in). The effective-HP proxy EHP ≈ HP·(100+avgResist)/100 is
    // bucketed against the floor and feeds a GENTLE power-2 penalty (vs the power-6 used for hard AP/MP
    // targets) so missing the floor only *nudges* the damage objective — never dominates it. Resistance
    // is averaged over the 4 elements and capped at EHP_AVG_RESIST_CAP (Wakfu's soft resist ceiling), so
    // one extreme element can't inflate the proxy. EHP_MAX bounds the proxy's CP-SAT domain (HP·1.8); it
    // is far above any real build.
    private const val SURVIVABILITY_PENALTY_POWER = 2

    // Max gentle-penalty multiplier; the EHP penalty rescales by this then divides back out, so meeting
    // the floor is a no-op and missing it scales the damage down by at most (max/atFloor) — a soft tax.
    private const val MAX_SURVIVABILITY_MULTIPLIER = 1_000L

    // Lexicographic scale for the "most masteries" overshoot tie-breaker. The primary objective tops
    // out at MASTERY_SCORE_ABS_MAX * MAX_PENALTY_MULTIPLIER = 1e14; multiplying it by this scale and
    // adding a bonus in [0, OVERSHOOT_SCALE) keeps the combined objective (~1e18) well under
    // Long.MAX/2 (~4.6e18) while guaranteeing one unit of primary always beats any overshoot bonus.
    // See [withOvershootTieBreaker].
    private const val OVERSHOOT_SCALE = 10_000L

    // The GA scorers weight each target by a Double = (100 / target) * userDefinedWeight, which is
    // almost always < 1 for high targets (e.g. HP target 2000 -> 0.05). Truncating that to Long with
    // .toLong() collapsed those weights to 0, silently dropping HP and any target > 100 from the
    // objective. We instead carry the weight in fixed-point (x WEIGHT_SCALE), which preserves both
    // the per-target 100/target normalization and userDefinedWeight. Because the same scale is
    // applied to the expected and the actual score, the success ratio that drives the penalty is
    // unchanged.
    private const val WEIGHT_SCALE = 1_000L

    internal val NON_ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    internal val NEGATIVE_MASTERY_PENALTY =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BERSERK
        )

    private val RANDOM_MASTERY_COUNTS =
        mapOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    init {
        OrToolsNativeLoader.load()
    }

    private val warmedUp =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Pays OR-Tools' one-time cold-start cost up front, off any search's critical path. The very
     * first real search is otherwise slow because touching this object loads the native library
     * (`init` above), the CP-SAT Java types are class-loaded on first use, and the solver spins up
     * its worker-thread pool / engine state on the first `solve`. We trigger all of that here on a
     * throwaway model so later searches start warm; subsequent searches are already fast because
     * none of this is repeated. Idempotent and safe to call from any thread (e.g. during app
     * startup, concurrently with other warm-up work).
     */
    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        // Referencing this object already ran `init { OrToolsNativeLoader.load() }`.
        val model = CpModel()
        val a = model.newBoolVar("warmup_a")
        val b = model.newBoolVar("warmup_b")
        model.addLessOrEqual(LinearExpr.sum(arrayOf<IntVar>(a, b)), 1L)
        model.maximize(
            LinearExpr
                .newBuilder()
                .addTerm(a, 1L)
                .addTerm(b, 1L)
                .build()
        )
        val solver = CpSolver()
        solver.parameters.maxTimeInSeconds = 1.0
        solver.parameters.logSearchProgress = false
        // Deliberately NOT the full core count. What warm-up actually pays off is the native-library
        // load, JNI/class initialization and the first solve's code paths — none of which need many
        // workers (CpSolver spins its worker pool up per solve, so nothing about a big pool persists
        // anyway). Saturating every core here starved the GUI's AWT event thread during startup: on
        // macOS any window operation (zoom, raise, resize) then stalled until warm-up finished and
        // the whole app appeared frozen. Two workers still exercise the multi-worker portfolio path
        // while leaving the UI thread (and the OS) breathing room.
        solver.parameters.numSearchWorkers = 2
        solver.solve(model)
    }

    /** Fixed-point version of [TargetStats.weight] so sub-unit weights survive integer arithmetic. */
    internal fun TargetStats.scaledWeight(targetStat: TargetStat): Long = (weight(targetStat) * WEIGHT_SCALE).roundToLong()

    internal val ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        )

    internal val ELEMENTARY_RESISTANCES =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE,
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND
        )

    // Upper bound for the "exceed the target once everything is met" tie-breaker. Far above any
    // realistic scaled overflow, so the clamp never triggers in practice while keeping the
    // lexicographic objective (hit targets first, then maximise overflow) inside Long range.

    internal val RANDOM_RESISTANCES =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
        )

    // Per-element random lines paired with how many distinct elements each rolls onto. Used to fold
    // random masteries/resistances into specific elements exactly as the scorers do.
    internal val MASTERY_RANDOM_BY_COUNT =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    internal val RESISTANCE_RANDOM_BY_COUNT =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    /**
     * The prefilter (a top-N-per-stat HEURISTIC that trades global optimality for tractability) is needed
     * only when a single elemental fold has **more than one** wanted element: that is exactly the case where
     * [applyGreedyRandom] mints the per-item × per-element assignment booleans + O(elements²) ordering
     * constraints that explode the full late-game pool. A SINGLE specific element (the common request, e.g.
     * "fire mastery") takes the cheap `effectiveCount == elementCount` random branch with no assignment vars,
     * so the full pool stays small and proves the true global optimum fast (measured: ≤3.5 s on the full
     * level-245 pool — see PrefilterBenchmark). Keeping the heuristic there only pruned the optimum for no
     * tractability gain, so we now solve those on the full pool. Multi-element / aggregate requests still
     * prefilter (the explosion is real for them); pool dominance-pruning to drop it there too is future work.
     */
    internal fun needsItemPrefilter(targetStats: TargetStats): Boolean = targetStats.masteryElementsWanted.size > 1 || targetStats.resistanceElementsWanted.size > 1

    /**
     * Restricts each slot to the items that can plausibly matter for the requested stats. The full
     * pool produces a CP-SAT model with tens of thousands of booleans that presolve cannot reduce in
     * time; keeping only the strongest items per requested characteristic (plus forced items) shrinks
     * the model dramatically, so presolve stays fast and the search reaches strong solutions.
     */
    private fun prefilterRelevantEquipments(
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        params: WakfuBestBuildParams,
        topPerCharacteristic: Int = 8,
    ): Map<ItemType, List<Equipment>> {
        val relevant = relevantCharacteristics(params.targetStats)
        if (relevant.isEmpty()) return equipmentsByItemType
        val forced = params.forcedItems.map { it.lowercase() }.toSet()

        return equipmentsByItemType.mapValues { (_, items) ->
            val keep = LinkedHashSet<Equipment>()
            items.filter { it.name.fr.lowercase() in forced }.forEach { keep.add(it) }
            for (characteristic in relevant) {
                items
                    .asSequence()
                    .filter { it.valueFor(characteristic) > 0 }
                    .sortedByDescending { it.valueFor(characteristic) }
                    .take(topPerCharacteristic)
                    .forEach { keep.add(it) }
            }
            if (keep.isEmpty()) items else keep.toList()
        }
    }

    private fun relevantCharacteristics(targetStats: TargetStats): Set<Characteristic> {
        val result = mutableSetOf<Characteristic>()
        for (targetStat in targetStats) {
            val characteristic = targetStat.characteristic
            result.add(characteristic)
            when (characteristic) {
                // Aggregate request: every element is wanted, fed by specific + generic + random.
                Characteristic.MASTERY_ELEMENTARY -> {
                    result.addAll(ELEMENTARY_MASTERIES)
                    result.addAll(RANDOM_MASTERY_COUNTS.keys)
                }
                // Specific element (e.g. fire): the generic "+all elements" stat and random masteries
                // also feed it, so keep those items — but not the sibling elements, which do not.
                in ELEMENTARY_MASTERIES -> {
                    result.add(Characteristic.MASTERY_ELEMENTARY)
                    result.addAll(RANDOM_MASTERY_COUNTS.keys)
                }

                Characteristic.RESISTANCE_ELEMENTARY -> {
                    result.addAll(ELEMENTARY_RESISTANCES)
                    result.addAll(RANDOM_RESISTANCES)
                }

                in ELEMENTARY_RESISTANCES -> {
                    result.add(Characteristic.RESISTANCE_ELEMENTARY)
                    result.addAll(RANDOM_RESISTANCES)
                }

                else -> Unit
            }
        }
        return result
    }

    /**
     * Test-only knobs to make a solve bit-for-bit reproducible. Production never passes this — the
     * default `null` keeps the real search (wall-clock budget, a worker per core, no fixed seed),
     * which is fast but intentionally non-deterministic. A test that drives that real search can stop
     * at a *sub-optimal feasible* build on a slow/loaded CI runner (the AP/MP/range/crit targets are
     * objective penalties, not hard constraints, so a poor feasible solution can violate them). With
     * a tuning, CP-SAT instead runs a fixed worker count + fixed seed + a **deterministic-time**
     * budget — work-unit based, not wall-clock — so it returns the *same proven optimum* on any
     * machine, however slow or loaded.
     */
    internal data class SolverTuning(
        val numSearchWorkers: Int = 8,
        val randomSeed: Int = 1,
        val maxDeterministicTime: Double = 60.0,
        val maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig(),
        // D3: `interleaveSearch` makes a 1-worker solve fully machine-reproducible (the canonical
        // deterministic protocol: 1 worker + interleave + fixed seed). Multi-worker results — even with a
        // det-time budget and a fixed seed — are worker-RACE-dependent, so a proof-by-deadline assert on
        // them flakes on oversubscribed CI. Default false keeps every existing tuning byte-identical.
        val interleaveSearch: Boolean = false,
        // C8(2) A/B seams: override the presolve-iteration cap / linearization level on the STANDARD solve
        // path (production pins presolve=1 + linearization=1 for the non-max-damage modes; the A/B measures
        // whether max-damage's 3/2 combination also wins for most-masteries). Null = today's behavior.
        val maxPresolveIterationsOverride: Int? = null,
        val linearizationLevelOverride: Int? = null,
        // Profiling seam: override the "domination only when tuning == null" production coupling, so a
        // deterministic (tuned) solve can measure the PRODUCTION pool (domination ON) — the C8(2)-era tuned
        // runs silently measured the full pool. Null = today's behavior (tuned ⇒ full pool).
        val applyDominationOverride: Boolean? = null,
        // C8(3) A/B seam: enable the most-masteries greedy warm start (instant emission + CP-SAT hint,
        // see [MostMasteriesWarmStart]) on the tuned path. Default false keeps every existing
        // deterministic test byte-identical; production follows its own gate in [optimize].
        val greedyWarmStart: Boolean = false,
        // E8 fallback: stop the search at the FIRST solution instead of running the budget out. Only
        // meaningful together with [optimize]'s `maxDamageRawFloor` — there ANY feasible solution already
        // sits at the certificate bound (the floor is a sound per-cell upper bound), so proving optimality
        // on top is pure waste; the final emission delivers the stopped-at solution.
        val stopAtFirstSolution: Boolean = false,
    )

    fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, sublimations, tuning = null)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning?,
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, emptyList(), emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: SolverTuning?,
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: SolverTuning?,
        // Max-damage hard-constraints-first pass: enforce the required targets as HARD `actual ≥ target`
        // constraints under a plain damage objective. INFEASIBLE (unreachable targets) ⇒ the flow emits nothing;
        // the caller ([MaxDamageSearch.optimizeHardThenSoft]) then re-runs with this false (the soft penalty).
        // Default false keeps every existing caller (all deterministic tests) byte-identical.
        hardConstraints: Boolean = false,
        // E8 fallback (max-damage only): a HARD `rawScore ≥ floor` constraint. With the floor set to a
        // certificate cell bound (a sound UPPER bound at that pinned AP cell), the solve becomes a
        // FEASIBILITY search: any solution it finds already reaches the bound — combine with
        // [SolverTuning.stopAtFirstSolution]. An unreachable floor (a loose bound) yields INFEASIBLE ⇒
        // the flow emits nothing, which the E8 caller maps to null (sound: no badge, never a wrong one).
        maxDamageRawFloor: Long? = null,
        // C8(3), max-damage: enable the greedy warm start (instant first emission + CP-SAT hint). Set ONLY
        // by [MaxDamageSearch]'s SINGLE-ELEMENT path — on the boss/multi-element probes the composed proof
        // still rides CP-SAT's in-model OPTIMAL, where the historical measurement showed a hint can slow it.
        maxDamageGreedyWarmStart: Boolean = false,
    ): Flow<SolverResult<BuildCombination>> =
        callbackFlow {
            // The native solve blocks its worker thread and cannot be interrupted by coroutine cancellation, so
            // hold the [CpSolver] here and stop it from [awaitClose] on flow teardown. The in-callback stop
            // (onSolutionCallback → stopSearch) only fires for models that reach a solution; an INFEASIBLE solve
            // never does, and without this would keep its native workers pinned until maxTimeInSeconds after the
            // collector is gone (the max-damage hard leg's infeasible case). CpSolver.stopSearch() is synchronized
            // (thread-safe), so calling it from the teardown thread is safe.
            val solverHandle =
                java.util.concurrent.atomic
                    .AtomicReference<CpSolver?>()
            val job =
                launch(Dispatchers.IO) {
                    // C8(3) greedy warm start — computed BEFORE buildModel (which is ~seconds on the lvl-245
                    // max-damage shape and used to gate the first emission at ~6.4 s): the greedy needs only
                    // the raw pre-filtered pool, so the first build streams in ~0.3 s. The CP-SAT hint is
                    // applied after the model exists; a pick domination later removed simply is not hinted
                    // (hints are advisory). Optimality-neutral both ways. Gated to production (tuning == null)
                    // or the explicit A/B flag; max-damage additionally requires the caller's single-element
                    // opt-in (see [maxDamageGreedyWarmStart]).
                    val greedyEnabled = tuning?.greedyWarmStart ?: true
                    val warmStart =
                        when {
                            !greedyEnabled -> null
                            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                                MostMasteriesWarmStart.greedyBuild(params, equipmentsByItemType.values.flatten())
                            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE && maxDamageGreedyWarmStart ->
                                MostMasteriesWarmStart.greedyMaxDamageBuild(params, equipmentsByItemType.values.flatten())
                            else -> null
                        }
                    val warmScore =
                        warmStart?.let { combination ->
                            val score = scoreFor(params, combination)
                            trySend(SolverResult(combination, score, 0))
                            score
                        }
                    // Domination runs only on the production (wall-clock) path: tuning == null. The deterministic
                    // test path keeps the full pool so existing tests are untouched; the soundness lock toggles it.
                    val built =
                        buildModel(
                            params,
                            equipmentsByItemType,
                            runes,
                            sublimations,
                            applyDomination = tuning?.applyDominationOverride ?: (tuning == null),
                            maxDamageExperiment = tuning?.maxDamageExperiment ?: MaxDamageExperimentConfig.DEFAULT,
                            hardConstraints = hardConstraints
                        )
                    // C2: a hard-constraints model with a required target above its reachable ceiling is PROVABLY
                    // infeasible — skip the doomed CP-SAT solve entirely and emit nothing. The caller
                    // ([MaxDamageSearch.optimizeHardThenSoft]) already treats an empty hard leg as its fallback
                    // trigger, so the soft (penalized) leg runs exactly as it would after a CP-SAT INFEASIBLE — only
                    // without paying the full-budget native solve first.
                    if (built.maxDamageStaticallyInfeasible) {
                        close()
                        return@launch
                    }
                    // E8 fallback floor — see the parameter doc. rawScore is always populated in max-damage
                    // mode; a null (another mode) simply ignores the floor, and E8 never calls those modes.
                    if (maxDamageRawFloor != null) {
                        built.maxDamageRawScore?.let { built.model.addGreaterOrEqual(it, maxDamageRawFloor) }
                    }
                    // C8(3): the warm start streamed above; hint the equipment layer now the model exists,
                    // so the search starts from that incumbent instead of near zero. The greedy score also
                    // becomes the intermediate-emission floor — consumers keep the LAST emission, so a later,
                    // WORSE snapshot would visibly regress the displayed build.
                    warmStart?.let { combination ->
                        val picked = combination.equipments.toHashSet()
                        for ((equip, v) in built.equipVars) built.model.addHint(v, if (equip in picked) 1L else 0L)
                    }
                    executeSolverAndEmitResults(
                        built.model,
                        params,
                        built.allEquips,
                        built.equipVars,
                        built.skillVars,
                        built.runeModel,
                        built.subModel,
                        built.maxDamageRawScore,
                        this@callbackFlow,
                        tuning,
                        onSolverReady = { solverHandle.set(it) },
                        suppressBelowScore = warmScore
                    )
                    close()
                }
            awaitClose {
                solverHandle.get()?.stopSearch()
                job.cancel()
            }
        }

    private class BuiltModel(
        val model: CpModel,
        val objective: IntVar,
        // Max-damage only: the UNPENALIZED per-turn damage proxy var (see [MaxDamageObjectiveVars.rawScore]).
        // Read on the solved assignment to stamp [SolverResult.maxDamageRawProxy]; null in the other modes.
        val maxDamageRawScore: IntVar?,
        val allEquips: List<Equipment>,
        val equipVars: Map<Equipment, IntVar>,
        val skillVars: Map<SkillCharacteristic, IntVar>,
        val runeModel: RuneModel,
        val subModel: SublimationModel,
        // Max-damage only: every tracked objective-chain var with its name and reachable [LongRange], for
        // the soundness test (empty for the other modes). See [DomainTracker] / [maxDamageVarBoundsForTest].
        val maxDamageTracked: List<Triple<IntVar, String, LongRange>> = emptyList(),
        // Precision only: same, for [precisionVarBoundsForTest] (empty for the other modes).
        val precisionTracked: List<Triple<IntVar, String, LongRange>> = emptyList(),
        // Max-damage only: the EXACT per-AP-cell certifier objective (single-element), captured when buildModel
        // is called with certifyAllApForTest = true. Key = AP, value = certifier objective (or -1 where the
        // certifier bails to CP-SAT). Empty otherwise. See [certifierCellObjectivesForTest].
        val certifierObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only: the FAST tier-1 per-AP-cell objective (sound upper bound, -1 where it bails),
        // captured alongside the exact one when certifyAllApForTest = true and no audit cell filter is set.
        // Empty otherwise. Compared against [certifierObjectivesForTest] by the `fast ≥ exact` lock.
        val certifierFastObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only (B7): the TIER-1.5 sharpened per-AP-cell objective (sound upper bound, -1 where it bails),
        // captured alongside exact+fast when certifyAllApForTest = true. Empty otherwise. Sits between them in the
        // `fast ≥ tier1.5 ≥ exact` lock.
        val certifierTier15ObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only: the two-tier [CertLedger] (P3.2), captured when certifyLedgerForTest = true. Null otherwise.
        val certifierLedgerForTest: CertLedger? = null,
        // Provenance lines for [certifyExplainCellForTest] (empty otherwise).
        val certifierExplainForTest: List<String> = emptyList(),
        // E8 item A: the STRUCTURED provenance — the winning composition's equipmentIds (empty otherwise).
        val certifierExplainItemIds: List<Int> = emptyList(),
        // C2: max-damage hard-constraints only — true when a required target exceeds its reachable ceiling, so the
        // model is PROVABLY infeasible and [optimize] can skip the CP-SAT solve. Always false in the other modes.
        val maxDamageStaticallyInfeasible: Boolean = false,
        // C7: the crit·diff AM-GM bound actually added as a constraint (null = the cut did not fire). See
        // [StatBuilder.critDiffJointCutBoundForTest] / [maxDamageCritDiffCutBoundForTest].
        val critDiffJointCutBoundForTest: Long? = null,
    )

    /**
     * Exact `floor((a · b) / divisor)` clamped to `[0, cap]`. Used where a HARD CP-SAT upper bound is derived
     * from a product of two Longs: computing it as a `Double` (`a.toDouble() * b`) silently loses precision once
     * the product exceeds 2^53, and rounding the bound DOWN below the true maximum would cut the optimum out of
     * the model. BigInteger keeps the floor exact; the clamp both enforces the `[0, cap]` domain and keeps the
     * final `.toLong()` in range (an over-cap product would otherwise wrap negative).
     */
    internal fun clampedProductQuotient(
        a: Long,
        b: Long,
        divisor: Long,
        cap: Long,
    ): Long =
        (BigInteger.valueOf(a) * BigInteger.valueOf(b) / BigInteger.valueOf(divisor))
            .max(BigInteger.ZERO)
            .min(BigInteger.valueOf(cap))
            .toLong()

    // C3: the number of distinct base-pool objects the domination memo retains before it clears (a single search
    // touches ONE pool object; this bounds cross-search retention so the identity map can't grow unboundedly).
    private const val DOMINATION_MEMO_MAX_POOLS = 8

    // C3 memo: (basePool identity → (shape → filtered pool)). The per-slot domination filter is re-run on every
    // [buildModel] call, and one max-damage search makes ~12–20 (element probes + AP probes + hard/soft legs). The
    // filtered pool is a PURE function of (basePool, shape), so memoize it. Keyed by basePool IDENTITY —
    // [MaxDamageSearch] threads the SAME pool object through every probe, and identity can never serve a wrong pool
    // — AND the [DominationShape] VALUE, which encodes the scored element (its `compared`/`minimized` sets),
    // targets, subs and floor: a different element yields a different shape ⇒ a SEPARATE entry, so the memo is
    // automatically PER-ELEMENT (sharing one element's dominance relation across elements would prune the wrong
    // per-element optimum). A prefiltered basePool is a fresh object each call ⇒ identity miss ⇒ recompute
    // (correct, just no win) — fine, the prefilter case is rare. Concurrent same-key computes are idempotent.
    private val dominationFilterMemo:
        java.util.IdentityHashMap<Map<ItemType, List<Equipment>>, MutableMap<DominationShape, Map<ItemType, List<Equipment>>>> =
        java.util.IdentityHashMap()

    private fun filterDominatedPoolMemoized(
        basePool: Map<ItemType, List<Equipment>>,
        shape: DominationShape,
    ): Map<ItemType, List<Equipment>> {
        val perShape =
            synchronized(dominationFilterMemo) {
                if (dominationFilterMemo.size > DOMINATION_MEMO_MAX_POOLS) dominationFilterMemo.clear()
                dominationFilterMemo.getOrPut(basePool) { java.util.concurrent.ConcurrentHashMap() }
            }
        // getOrPut on the concurrent inner map may double-compute under a race, but [filterDominatedPool] is a pure
        // deterministic function, so both threads produce a structurally-identical pool — a benign, idempotent race.
        return perShape.getOrPut(shape) { filterDominatedPool(basePool, shape.pinned, shape.compared, shape.minimized) }
    }

    /**
     * Assembles the full CP-SAT model — item / skill / rune / sublimation vars, validity constraints, and the
     * mode's objective — and calls [CpModel.maximize]. Extracted from [optimize] so the test-only
     * [maxDamageObjectiveValueForTest] builds a byte-identical model (no drift between the production solve and
     * the objective-readout used by the bi-element tests).
     */
    private fun buildModel(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        // Max-damage only: declare the objective-chain vars sized to their reachable domains (so CP-SAT can
        // prove OPTIMAL). false reproduces the loose guard domains — the soundness test's reference build.
        tightDomains: Boolean = true,
        // Benchmark/test seam: bypass the prefilter so the full pool is solved regardless of the gate.
        forceFullPool: Boolean = false,
        // Test seam: force the per-stat rune COUNT model even where the single-type fold would apply, so a
        // test can assert the fold preserves the optimum (fold == count optimum on a rune pool).
        forceRuneCountModel: Boolean = false,
        // Test seam: force the rune socket cap back to `≤` (disable exact fill), so a test can assert
        // most-masteries exact fill preserves the ≤-model optimum (exact-fill optimum == ≤ optimum).
        forceRuneLeq: Boolean = false,
        // Production path only: drop per-slot dominated items ([filterDominatedPool]) — provably optimum-
        // preserving in all three (monotone) modes. Off by default so the deterministic test path sees the full
        // pool unchanged; the production [optimize] passes true and the soundness lock toggles it.
        applyDomination: Boolean = false,
        maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
        maxDamageObjectiveCutoff: Long? = null,
        // Hard-constraints-first max-damage solve: required targets become HARD `actual ≥ target` constraints
        // under a plain damage objective (no shortfall penalty). Threaded to [buildMaxDamageObjective].
        hardConstraints: Boolean = false,
        // Test seam: when true, the max-damage build also runs [certifyMaxPerHitAtAp] for every AP cell and
        // stores the resulting objectives in [BuiltModel.certifierObjectivesForTest] (single-element only).
        certifyAllApForTest: Boolean = false,
        // Test seam: PROVENANCE — explain the winning certificate state of this AP cell into
        // [BuiltModel.certifierExplainForTest] (single-element only). See [StatBuilder.certifyExplainAtAp].
        certifyExplainCellForTest: Int? = null,
        // E8 item A (perf): a cached winning (world, crit-step) for [certifyExplainCellForTest] — when set, the
        // explain replays only that one pass instead of the N-worlds scan (see [certifyExplainAtApFromProvenance]).
        certifyExplainProvenanceForTest: CellProvenance? = null,
        // Test seam: thread count for the FAST pass (P3.1 warm-once parallelism); 1 = serial (default).
        certifyFastThreadsForTest: Int = 1,
        // Dynamic per-tier thread count for the certificate (see [StatBuilder.certifierThreadsProvider]).
        certifierThreadsProvider: ((CertTier) -> Int)? = null,
        // Dynamic incumbent, resolved right before elimination (see [StatBuilder.certifierIncumbentProvider]).
        certifierIncumbentProvider: (() -> Long?)? = null,
        // Cascade tier-1.5 (see [StatBuilder.certifyLedgerCascadeTier15]).
        certifyLedgerCascadeTier15: Boolean = false,
        // Test seam: run ONLY the fast pass in the certify-for-test block (skip the exact per-cell ledger).
        certifyFastOnlyForTest: Boolean = false,
        // Test seam (P3.2 orchestrator): compute the two-tier [CertLedger] into [BuiltModel.certifierLedgerForTest].
        certifyLedgerForTest: Boolean = false,
        certifyLedgerIncumbentForTest: Long? = null,
        certifyLedgerForceTier2AllForTest: Boolean = false,
        // B6: reuse a prior compute's SCALED per-cell fast bounds (+ bail set) for this shape, skipping the tier-1
        // fast DP in [certifyLedger] (a pure, byte-identical function of the shape). Null ⇒ compute the fast pass.
        certifyLedgerPrecomputedFast: Map<Int, Long>? = null,
        certifyLedgerPrecomputedBailed: Set<Int>? = null,
        certifyLedgerPrecomputedTier15: Map<Int, Long>? = null,
        certifyLedgerPrecomputedExact: Map<Int, Long>? = null,
        certifyLedgerPrecomputedProv: Map<Int, CellProvenance>? = null,
        // B8: polled once per certifier DP stage; when it flips true the certifier bails (sound) so a cancelled
        // proof stops promptly. Default never-cancel keeps the deterministic test/model builds byte-identical.
        certifierCancelled: () -> Boolean = { false },
    ): BuiltModel {
        // Phase timing (WAKFU_BUILD_MODEL_TIMING=1): where the ~seconds of model construction go on the
        // big shapes — one stderr line per buildModel call. No behavior change.
        val bmTimingEnabled = System.getenv("WAKFU_BUILD_MODEL_TIMING") == "1"
        val bmStart = if (bmTimingEnabled) System.nanoTime() else 0L
        var bmLast = bmStart
        val bmPhases = StringBuilder()

        fun bmMark(label: String) {
            if (!bmTimingEnabled) return
            val now = System.nanoTime()
            bmPhases
                .append(label)
                .append('=')
                .append((now - bmLast) / 1_000_000)
                .append("ms ")
            bmLast = now
        }
        val model = CpModel()

        // Full pool gives the provable *global* optimum and stays tractable for most queries.
        // Only multi-element mastery/resistance targets activate the heavy random-element modelling that
        // explodes on the full late-game pool, so we prefilter exactly (and only) those cases.
        val basePool =
            if (!forceFullPool && needsItemPrefilter(params.targetStats)) {
                prefilterRelevantEquipments(equipmentsByItemType, params)
            } else {
                equipmentsByItemType
            }
        // Sound per-slot domination: drop items provably beaten in their own slot (all three modes are monotone;
        // see [dominationShape]). Skipped for forced items / forced conditional subs and for forceFullPool
        // (the full reference). Removes no optimal build, so the proven optimum is identical — only the model shrinks.
        // Stats a dangerous (≤/exact/parity) conditional sub reads — non-monotone, so domination pins them AND
        // most-masteries exact socket fill must avoid them (null = un-analyzable / forced ⇒ both stay conservative).
        val dominationShape = dominationShape(params, sublimations)
        val activeDomination = if (applyDomination && !forceFullPool) dominationShape else null
        // C3: memoized so the ~12–20 buildModel calls of one search share the per-element filter result.
        val pool = if (activeDomination != null) filterDominatedPoolMemoized(basePool, activeDomination) else basePool
        bmMark("domination")
        val allEquips = orderEquipments(pool)
        val equipVars = model.createEquipmentVariables(allEquips)
        bmMark("equipVars")
        val skillVars = model.createSkillVariables(params.character.characterSkills)
        // The single-type rune fold (createRuneModel) is sound unless a sublimation IN PLAY requires a
        // POSITIVE secondary-mastery cap (`secondary ≤ N`, N>0): there an intra-item secondary/elemental
        // MIX can be optimal, which the fold can't express. Every solver-choosable secondary-cap sub has
        // N=0 (⇒ all-elemental, no mix), so the default search folds; this guard future-proofs the data and
        // a forced sub with N>0.
        val forcedSubNames = params.forcedSublimations.map { it.lowercase() }.toSet()
        val secondaryCapMixSubInPlay =
            sublimations.any { sub ->
                val inPlay = (sub.solverChoosable && params.useSublimations) || sub.name.fr.lowercase() in forcedSubNames || sub.name.en.lowercase() in forcedSubNames
                inPlay &&
                    sub.condition?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST &&
                    (sub.condition?.value ?: 0) > 0
            }
        val allowRuneFold = !forceRuneCountModel && !secondaryCapMixSubInPlay
        val runeModel = model.createRuneModel(params, allEquips, equipVars, runes, allowRuneFold, dominationShape?.pinned, forceRuneLeq)
        bmMark("runeModel")
        val subModel = model.createSublimationModel(params, allEquips, equipVars, sublimations)
        bmMark("subModel")
        // A normal sublimation does NOT reserve rune sockets. Golden runes (colour-agnostic) form its ordered
        // colour pattern AND still carry their stat — doubling where the item favours that colour — so a carrier
        // keeps a full set of runes alongside the sub. Carrier eligibility (≥3-socket item) and the
        // ≤1-normal-sub-per-item cap live in createSublimationModel; rune capacity (Σ runes ≤ sockets) lives in
        // createRuneModel. The two no longer share a socket budget.

        model.addBuildValidityConstraints(allEquips, equipVars)
        model.addForcedItemsEquippedConstraints(params, allEquips, equipVars)
        bmMark("validity")

        var maxDamageTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var maxDamageRawScore: IntVar? = null
        var precisionTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var certifierObjectives: Map<Int, Long> = emptyMap()
        var certifierFastObjectives: Map<Int, Long> = emptyMap()
        var certifierTier15Objectives: Map<Int, Long> = emptyMap()
        var certifierLedger: CertLedger? = null
        var certifierExplain: List<String> = emptyList()
        var certifierExplainItemIds: List<Int> = emptyList()
        var critDiffJointCutBound: Long? = null
        // C2: set by the max-damage hard-constraints branch when a required target exceeds its reachable ceiling.
        var maxDamageStaticallyInfeasible = false
        val objective =
            when (params.scoreComputationMode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                    model.buildMostMasteriesObjective(params, allEquips, equipVars, skillVars, runeModel, subModel)

                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> {
                    // Declare the precision stat chain on its reachable domains (like max-damage), instead of
                    // the loose 10M guard: every reach is a sound superset of the attainable value (locked by
                    // [precisionVarBoundsForTest]), so the optimum is unchanged while presolve / the LP
                    // relaxation work on tight bounds. tightDomains=false reproduces the loose reference build.
                    val statBuilder =
                        StatBuilder(
                            model,
                            params,
                            allEquips,
                            equipVars,
                            skillVars,
                            runeModel,
                            subModel,
                            tight = tightDomains,
                            // Decouple from the max-damage experiment default (see [MaxDamageExperimentConfig.NON_MAX_DAMAGE]).
                            maxDamageExperiment = MaxDamageExperimentConfig.NON_MAX_DAMAGE
                        )
                    val obj = model.buildPrecisionObjective(params, statBuilder)
                    precisionTracked = statBuilder.tracker.tracked()
                    obj
                }

                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> {
                    val statBuilder =
                        StatBuilder(
                            model,
                            params,
                            allEquips,
                            equipVars,
                            skillVars,
                            runeModel,
                            subModel,
                            tight = tightDomains,
                            maxDamageExperiment = maxDamageExperiment,
                            certifyForTest = certifyAllApForTest,
                            certifyExplainCell = certifyExplainCellForTest,
                            certifyExplainProvenance = certifyExplainProvenanceForTest,
                            certifyFastThreads = certifyFastThreadsForTest,
                            certifierThreadsProvider = certifierThreadsProvider,
                            certifierIncumbentProvider = certifierIncumbentProvider,
                            certifyLedgerCascadeTier15 = certifyLedgerCascadeTier15,
                            certifyFastOnly = certifyFastOnlyForTest,
                            certifyLedgerForTest = certifyLedgerForTest,
                            certifyLedgerIncumbent = certifyLedgerIncumbentForTest,
                            certifyLedgerForceTier2All = certifyLedgerForceTier2AllForTest,
                            certifyLedgerPrecomputedFast = certifyLedgerPrecomputedFast,
                            certifyLedgerPrecomputedBailed = certifyLedgerPrecomputedBailed,
                            certifyLedgerPrecomputedTier15 = certifyLedgerPrecomputedTier15,
                            certifyLedgerPrecomputedExact = certifyLedgerPrecomputedExact,
                            certifyLedgerPrecomputedProv = certifyLedgerPrecomputedProv,
                            certifierCancelled = certifierCancelled
                        )
                    val built = model.buildMaxDamageObjective(params, statBuilder, maxDamageObjectiveCutoff, hardConstraints)
                    maxDamageRawScore = built.rawScore
                    maxDamageStaticallyInfeasible = built.staticallyInfeasible
                    maxDamageTracked = statBuilder.tracker.tracked()
                    certifierObjectives = statBuilder.certifierObjectivesForTest
                    certifierFastObjectives = statBuilder.certifierFastObjectivesForTest
                    certifierTier15Objectives = statBuilder.certifierTier15ObjectivesForTest
                    certifierLedger = statBuilder.certifierLedgerForTest
                    certifierExplain = statBuilder.certifierExplainForTest
                    certifierExplainItemIds = statBuilder.certifierExplainItemIds
                    critDiffJointCutBound = statBuilder.critDiffJointCutBoundForTest
                    built.objective
                }
            }
        model.maximize(objective)

        bmMark("objective")
        if (bmTimingEnabled) {
            System.err.println(
                "BUILD_MODEL_TIMING mode=${params.scoreComputationMode} totalMs=${(System.nanoTime() - bmStart) / 1_000_000} $bmPhases"
            )
        }
        return BuiltModel(
            model,
            objective,
            maxDamageRawScore,
            allEquips,
            equipVars,
            skillVars,
            runeModel,
            subModel,
            maxDamageTracked,
            precisionTracked,
            certifierObjectives,
            certifierFastObjectives,
            certifierTier15Objectives,
            certifierLedger,
            certifierExplain,
            certifierExplainItemIds,
            maxDamageStaticallyInfeasible,
            critDiffJointCutBound
        )
    }

    /** Configures a deterministic, machine-reproducible max-damage solver (full presolve + level-2 linearization). */
    private fun deterministicMaxDamageSolver(tuning: SolverTuning): CpSolver {
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.linearizationLevel = 2
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        if (tuning.interleaveSearch) solver.parameters.interleaveSearch = true
        return solver
    }

    /**
     * Test-only: assemble the model exactly like [optimize] (via [buildModel]) and return the **maximized
     * objective value**, requiring a deterministic [SolverTuning] and a proven `OPTIMAL` status. Needed because
     * the streamed [SolverResult.matchPercentage] is computed by the single-element scorer and so
     * cannot read the bi-element objective for an interior split — see the Lot 2 tests in WakfuBuildSolverTest.
     */
    internal fun maxDamageObjectiveValueForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
    ): Long {
        val built = buildModel(params, equipmentsByItemType, emptyList(), emptyList(), maxDamageExperiment = tuning.maxDamageExperiment)
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL) { "expected OPTIMAL, got $status" }
        return solver.objectiveValue().toLong()
    }

    /** A max-damage solve's outcome: its maximized objective and whether CP-SAT *proved* it optimal. */
    internal data class MaxDamageSolveOutcome(
        val objective: Long,
        val isOptimal: Boolean,
        val hasSolution: Boolean,
        // The equipmentIds selected in the solved assignment (empty when there is no solution). Lets a test assert
        // that a specific item survived the pool build and was actually picked — e.g. the A1 lock that a generic-
        // resistance item required to meet a hard resistance target is NOT domination-pruned.
        val selectedEquipmentIds: Set<Int> = emptySet(),
    )

    internal data class MaxDamageTimedProfile(
        val status: String,
        val objective: Long,
        val bestBound: Long,
        val objectiveCutoff: Long?,
        val wallTimeSec: Double,
        val deterministicTime: Double,
        val booleans: Long,
        val branches: Long,
        val conflicts: Long,
        val restarts: Long,
        val lpIterations: Long,
        val variables: Int,
        val constraints: Int,
        val poolSize: Int,
        val experiment: MaxDamageExperimentConfig,
    ) {
        val hasSolution: Boolean
            get() = objective != Long.MIN_VALUE
    }

    internal fun timedMaxDamageProfileForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        workers: Int,
        seconds: Double,
        applyDomination: Boolean,
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
        maxPresolveIterations: Int = 3,
        linearizationLevel: Int = 2,
        deterministicLimit: Double? = null,
        // Pure CP-SAT solver-parameter knobs (no model change) — A/B research only. CP-SAT proves the SAME
        // optimum regardless of these, so they are soundness-safe by construction; the only question they answer
        // is whether the proof closes in less deterministic time. null/false = CP-SAT's default.
        symmetryLevel: Int? = null,
        probingLevel: Int? = null,
        objectiveShaving: Boolean = false,
        searchBranching: Int? = null,
        objectiveCutoff: Long? = null,
        // Portfolio-composition research knobs (parameter-only, soundness-safe like the above).
        logSearch: Boolean = false,
        sharedTreeWorkers: Int? = null,
        ignoreSubsolvers: List<String> = emptyList(),
        extraSubsolvers: List<String> = emptyList(),
        interleave: Boolean = false,
        numFullSubsolvers: Int? = null,
        detectLinearizedProduct: Boolean = false,
        // C4: screen the CONSTRAINED hard-leg shape (required targets as `actual ≥ target`, plain objective) — the
        // shape whose bilinear dual gap C6 targets — instead of the soft-penalty relaxation. Default false = today.
        hardConstraints: Boolean = false,
    ): MaxDamageTimedProfile {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = applyDomination,
                maxDamageExperiment = experiment,
                maxDamageObjectiveCutoff = objectiveCutoff,
                hardConstraints = hardConstraints
            )
        objectiveCutoff?.let { built.model.addGreaterOrEqual(built.objective, it) }
        val solver = CpSolver()
        solver.parameters.logSearchProgress = logSearch
        solver.parameters.linearizationLevel = linearizationLevel
        solver.parameters.maxPresolveIterations = maxPresolveIterations
        solver.parameters.numSearchWorkers = workers
        solver.parameters.randomSeed = 1
        if (symmetryLevel != null) solver.parameters.symmetryLevel = symmetryLevel
        if (probingLevel != null) solver.parameters.cpModelProbingLevel = probingLevel
        if (objectiveShaving) solver.parameters.useObjectiveShavingSearch = true
        if (searchBranching != null) {
            solver.parameters.searchBranching =
                com.google.ortools.sat.SatParameters.SearchBranching
                    .forNumber(searchBranching)
        }
        if (sharedTreeWorkers != null) solver.parameters.sharedTreeNumWorkers = sharedTreeWorkers
        ignoreSubsolvers.forEach { solver.parameters.addIgnoreSubsolvers(it) }
        extraSubsolvers.forEach { solver.parameters.addExtraSubsolvers(it) }
        if (interleave) solver.parameters.interleaveSearch = true
        if (numFullSubsolvers != null) solver.parameters.numFullSubsolvers = numFullSubsolvers
        if (detectLinearizedProduct) solver.parameters.detectLinearizedProduct = true
        // Production proves with a DETERMINISTIC-time limit (reproducible, and a much faster solver mode for
        // this problem than a wall-clock limit). When deterministicLimit is set, use it as the real budget and
        // keep maxTimeInSeconds only as a safety cap so a stuck run can't hang.
        if (deterministicLimit != null) {
            solver.parameters.maxDeterministicTime = deterministicLimit
        }
        solver.parameters.maxTimeInSeconds = seconds
        val status = solver.solve(built.model)
        if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1" &&
            (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE)
        ) {
            val skills =
                built.skillVars.entries
                    .mapNotNull { (ch, v) -> solver.value(v).takeIf { it > 0 }?.let { "${ch.characteristic}=$it" } }
            System.err.println("SOLVE_DEBUG ap=${params.maxDamageApTarget} obj=${solver.objectiveValue().toLong()} skills=$skills")
            val subs =
                built.subModel.subVars.entries
                    .filter { solver.value(it.value) > 0 }
                    .map { it.key.name.en }
            System.err.println("SOLVE_DEBUG_SUBS $subs")
        }
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        val proto = built.model.model()
        val stats = solver.responseStats()
        return MaxDamageTimedProfile(
            status = status.toString(),
            objective = if (hasSolution) solver.objectiveValue().toLong() else Long.MIN_VALUE,
            bestBound = solver.bestObjectiveBound().toLong(),
            objectiveCutoff = objectiveCutoff,
            wallTimeSec = solver.wallTime(),
            deterministicTime = deterministicTimeFrom(stats),
            booleans = longStatFrom(stats, "booleans"),
            branches = solver.numBranches(),
            conflicts = solver.numConflicts(),
            restarts = longStatFrom(stats, "restarts"),
            lpIterations = longStatFrom(stats, "lp_iterations"),
            variables = proto.variablesCount,
            constraints = proto.constraintsCount,
            poolSize = built.allEquips.size,
            experiment = experiment
        )
    }

    /**
     * Test-only: assemble the max-damage model like [optimize] and return the EXACT per-AP-cell certifier
     * objective for every AP cell (single-element only). Key = AP, value = the certifier's objective for a
     * build pinned to that AP, or -1 where [certifyMaxPerHitAtAp] bails (the production path falls back to
     * CP-SAT there). One model build certifies every cell, so a soundness / exactness test can compare the
     * certifier against the per-cell CP-SAT optimum (via [timedMaxDamageProfileForTest] with a pinned
     * `maxDamageApTarget`) without rebuilding per AP.
     */
    internal fun certifierCellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Map<Int, Long> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true
        ).certifierObjectivesForTest

    /**
     * Test-only: like [certifierCellObjectivesForTest] but returns BOTH the exact and the FAST tier-1
     * per-cell objectives from a single model build (`exact to fast`). The `fast ≥ exact` soundness lock
     * asserts `fast[a] ≥ exact[a]` for every non-bailed cell — a single violation is a release-blocking
     * under-count (the fast pass would certify a build below a real one).
     */
    internal fun certifierExactAndFastCellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Pair<Map<Int, Long>, Map<Int, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true
        ).let { it.certifierObjectivesForTest to it.certifierFastObjectivesForTest }

    /**
     * Test-only (B7): the exact, FAST tier-1, and sharpened TIER-1.5 per-cell objectives from a single model build
     * (`Triple(exact, fast, tier15)`). The `fast ≥ tier1.5 ≥ exact` soundness lock asserts the ordering per
     * non-bailed cell — an under-count anywhere (a bound below a real build) is a release-blocking wrong badge.
     */
    internal fun certifierExactFastTier15CellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Triple<Map<Int, Long>, Map<Int, Long>, Map<Int, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true
        ).let { Triple(it.certifierObjectivesForTest, it.certifierFastObjectivesForTest, it.certifierTier15ObjectivesForTest) }

    /**
     * Test-only: the FAST tier-1 per-cell objective ledger computed with [threads] worker threads (P3.1
     * warm-once parallelism). At `threads = 1` this equals [certifierExactAndFastCellObjectivesForTest]'s
     * fast map; the parallel-equality lock asserts it is identical for `threads > 1` on every iteration.
     */
    internal fun certifierFastLedgerForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        threads: Int = 1,
    ): Map<Int, Long> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true,
            certifyFastThreadsForTest = threads,
            certifyFastOnlyForTest = true
        ).certifierFastObjectivesForTest

    /**
     * Default worker-thread count for the certificate orchestrator (P3.2). Memory-aware (B2): the parallel path
     * is correct (locked by the panel-scale parallel-equality + determinism tests) but each concurrent exact
     * tier-2 DP holds ~1 GB of frontier state at level 245, so fanning the 6 worlds of a survivor cell across
     * threads OOMs a stock ~4 GB heap. Rather than ship serial-by-default forever, [certifierThreadsForHeap]
     * bounds the worker count by the runtime's free heap: a stock ~4 GiB heap still resolves to 1 (the previous
     * safe default), while a large heap opens the parallel tier for a ~2–3× faster badge. No `CERTIFIER_VERSION`
     * bump — the thread count changes no bound value (the merge is an order-independent max; determinism locked).
     */
    internal fun certifierDefaultThreads(): Int = certifierThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    /**
     * Pure, total heap→worker-count formula behind [certifierDefaultThreads] (extracted so it is unit-testable
     * without a specific `-Xmx`). Reserve ~2 GiB for the model + warm fast-pass caches + base app, then allow one
     * worker per ~1.25 GiB of the remainder (each concurrent world-DP is ~1 GB + headroom), capped at
     * `min(6, cores − 1)` to leave the UI/OS a core. Floors at 1 (never zero, even on a tiny heap). A −Xmx4g heap
     * resolves to 1 (2 GiB remainder / 1.25 GiB = 1) — the safe serial default; ≥ ~5.5 GiB opens a second worker.
     */
    internal fun certifierThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = 2 * gib
        val perWorker = gib + gib / 4 // 1.25 GiB per concurrent exact DP (with headroom)
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(6, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    /**
     * Worker count for the TIER-1.5 pass specifically. Its (cell × world) tasks are step-1 FAST DPs — dense
     * boxes plus small Pareto frontiers — not the ~1 GiB exact tier-2 DPs the [certifierThreadsForHeap]
     * formula is calibrated for. Measured on the real lvl-245 back+berserk shape: 6 concurrent tier-1.5
     * workers held the WHOLE process at ~2.6 GiB RSS (~0.4 GiB per worker including shared caches), while the
     * exact-tier formula resolves a stock heap to 1 worker and left production's dominant certificate stage
     * serial. So tier-1.5 gets its own heap-aware sizing: reserve ~1.5 GiB (model + warm fast caches + app),
     * one worker per ~0.4 GiB of remainder, same `min(6, cores − 1)` cap, floor 1. The packaged GUI's -Xmx3g
     * resolves to 3 workers; a stock 4 GiB heap to 6; tiny heaps stay serial.
     */
    internal fun certifierTier15Threads(): Int = certifierTier15ThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    /**
     * Worker count for the FAST tier's per-world passes. A fast world DP is heavier than a tier-1.5
     * single-cell one (its dense box spans EVERY AP cell) but far lighter than an exact tier-2 DP, so it
     * gets its own sizing: reserve ~1.5 GiB, one worker per ~0.6 GiB of remainder, capped at
     * `min(5, cores − 1)` — at most 5 worlds ever remain after the serial warm-once world. A stock 4 GiB
     * heap resolves to 4, the packaged GUI's -Xmx3g to 2, tiny heaps stay serial.
     */
    internal fun certifierFastWorldThreads(): Int = certifierFastWorldThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    internal fun certifierFastWorldThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = gib + gib / 2
        val perWorker = 3 * gib / 5 // ~0.6 GiB per concurrent all-cells fast world DP
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(5, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    internal fun certifierTier15ThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = gib + gib / 2 // 1.5 GiB base (model + warm fast-pass caches + app)
        val perWorker = 2 * gib / 5 // ~0.4 GiB per concurrent (cell × world) step-1 fast DP (measured)
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(6, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    /**
     * PRODUCTION certificate API (P4.1). Builds the max-damage model once for a **single-element** [params]
     * scenario (the certifier needs `StatBuilder`'s terms — this initializes OR-Tools natives, which the
     * production path has already warmed), then runs the two-tier orchestrator ([CertLedger]).
     *
     * Returns `null` when no certificate is available: a **multi-element / boss** scenario (the model's
     * per-element certifier seam only fires for one candidate element — compose per element instead), a
     * non-max-damage mode, or a forced-rune / forced-sublimation shape the certifier bails on. A non-null
     * result is a sound upper-bound ledger in OBJECTIVE units (directly comparable to CP-SAT objectives).
     *
     * @param incumbentObjective a feasible objective (the best build found) — cells whose bound is `≤` it are
     *   eliminated on the fast value; `null` confirms every non-bailed cell exactly.
     * @param threads worker count for both tiers; defaults to [certifierDefaultThreads] (serial — see its doc).
     * @param isCancelled (B8) polled once per certifier DP stage; a cancelled run bails (sound) and returns null,
     *   so the caller declines the badge (Unavailable) and — crucially — never caches an incomplete ledger.
     */
    fun maxDamageCertificate(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = true,
        incumbentObjective: Long? = null,
        threads: Int = certifierDefaultThreads(),
        // Re-read at each tier's start when set (takes precedence over [threads]) — the warm-up passes a
        // provider that returns 1 while the search runs and [certifierDefaultThreads] once it is done.
        threadsProvider: ((CertTier) -> Int)? = null,
        // Re-read once, right before elimination — the warm-up passes the search's LATEST feasible proxy so
        // the certificate eliminates against the final incumbent instead of the weak first-streamed one.
        incumbentProvider: (() -> Long?)? = null,
        // Cascade tier-1.5 (short-search rescue) — see [StatBuilder.certifyLedgerCascadeTier15].
        cascadeTier15: Boolean = false,
        isCancelled: () -> Boolean = { false },
        // B6: reuse a prior compute's SCALED fast bounds (+ bail set) for this shape ⇒ skip the tier-1 fast DP.
        precomputedFast: Map<Int, Long>? = null,
        precomputedBailed: Set<Int>? = null,
        // B4/B7 compute-path reuse: per-cell tier-1.5 / exact confirms (+ provenance) from a prior compute.
        precomputedTier15: Map<Int, Long>? = null,
        precomputedExact: Map<Int, Long>? = null,
        precomputedProv: Map<Int, CellProvenance>? = null,
    ): CertLedger? {
        val ledger =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = applyDomination,
                certifyFastThreadsForTest = threads,
                // Default: per-tier calibrated counts — production proofs get parallel tier-1.5 and
                // fast-world workers even on a stock heap; the exact tier keeps the caller's [threads].
                certifierThreadsProvider =
                    threadsProvider
                        ?: { tier ->
                            when (tier) {
                                CertTier.TIER15 -> maxOf(threads, certifierTier15Threads())
                                CertTier.FAST -> maxOf(threads, certifierFastWorldThreads())
                                else -> threads
                            }
                        },
                certifierIncumbentProvider = incumbentProvider,
                certifyLedgerCascadeTier15 = cascadeTier15,
                certifyLedgerForTest = true,
                certifyLedgerIncumbentForTest = incumbentObjective,
                certifyLedgerForceTier2AllForTest = false,
                certifyLedgerPrecomputedFast = precomputedFast,
                certifyLedgerPrecomputedBailed = precomputedBailed,
                certifyLedgerPrecomputedTier15 = precomputedTier15,
                certifyLedgerPrecomputedExact = precomputedExact,
                certifyLedgerPrecomputedProv = precomputedProv,
                certifierCancelled = isCancelled
            ).certifierLedgerForTest
        // A cancelled run may have bailed mid-way (a sound but incomplete ledger). Never surface or cache it.
        return if (isCancelled()) null else ledger
    }

    /**
     * Test-only: the two-tier [CertLedger] (P3.2 orchestrator) for these params. [incumbentObjective] drives
     * elimination (null = confirm every non-bailed cell); [forceTier2All] confirms every non-bailed cell
     * exactly regardless of the incumbent (the oracle-equality lock). [threads] worker count for both tiers.
     */
    internal fun certifyLedgerForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        incumbentObjective: Long? = null,
        forceTier2All: Boolean = false,
        threads: Int = 1,
    ): CertLedger =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyFastThreadsForTest = threads,
            certifyLedgerForTest = true,
            certifyLedgerIncumbentForTest = incumbentObjective,
            certifyLedgerForceTier2AllForTest = forceTier2All
        ).certifierLedgerForTest!!

    /** Test-only PROVENANCE: the backtracked composition of [cell]'s winning certificate state. */
    internal fun certifierExplainForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
    ): List<String> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell
        ).certifierExplainForTest

    /**
     * E8 item A: the STRUCTURED provenance of a cell's winning certificate state — the winning composition's
     * equipmentIds, so the fast-path restricts the pool by id instead of regex-parsing the `slot:` [certifierExplainForTest]
     * strings (which break on item names containing `" + "` / `"(di+"`). Empty when the certifier bails ⇒ the seam falls back.
     */
    internal fun certifierExplainItemIdsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
    ): List<Int> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell
        ).certifierExplainItemIds

    /**
     * E8 item A (perf): the same STRUCTURED provenance as [certifierExplainItemIdsForTest] but from a CACHED winning
     * (world, crit-step) [provenance] — replays only that one explain pass instead of the full N-worlds scan (which
     * costs ~minutes at high level). Empty if the certifier bails or the pointer is out of range ⇒ the seam falls back.
     */
    internal fun certifierExplainItemIdsFromProvenanceForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
        provenance: CellProvenance,
    ): List<Int> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell,
            certifyExplainProvenanceForTest = provenance
        ).certifierExplainItemIds

    /**
     * E8 fast-path (plan §4 E8, measured GO — SOLVER_PERFORMANCE §7): CONSTRUCT the proven optimum from the
     * certificate DP instead of a full CP-SAT solve. Two tiers:
     *  1. FAST: backtrack the argmax cell's provenance ITEMS, restrict the pool to them, and re-solve that tiny
     *     pool (CP-SAT re-derives runes/subs/skills freely). Measured: free lvl-110 (1,310,980) and lvl-245
     *     (16,909,590) both construct in one tiny re-solve (ratio 1.0) — DP-seconds instead of CP-SAT-minutes.
     *  2. FALLBACK: the provenance items need not REALIZE the bound — the frontier abstraction can credit a sub
     *     whose value only a slightly different item set unlocks (the runes+subs lvl-110 optimum: 10 normal subs
     *     where the provenance set only realizes 9) — so when the fast tier misses, re-solve the FULL pool at the
     *     pinned argmax cell as a FEASIBILITY problem (`rawScore ≥ bound` + stop-at-first-solution): any solution
     *     found sits at the bound, no optimality proof needed.
     * SOUND by construction: the DP's argmax bound is a sound UPPER bound on the global optimum, so a result is
     * returned ONLY when a re-solved raw proxy REACHES that bound (`proxy ≥ cellBound`) — which certifies the build
     * IS the global optimum. Returns null when neither tier can (a loose bound, an invalid build) ⇒ the caller
     * keeps the incumbent, so best-effort construction is safe (a miss only costs the badge, never correctness).
     * Free single-element max-damage only (the DP-provable shape).
     */
    internal suspend fun dpConstructProvenOptimum(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        incumbentObjective: Long? = null,
        // The caller ALREADY holds the ledger to construct from (the warm-up's cascade path): skip the
        // cache round-trip — a cascaded PARTIAL entry cannot always be reconstructed for this incumbent,
        // and recomputing it here would pay the full tier-1.5 batch the cascade exists to avoid.
        precomputedLedger: CertLedger? = null,
    ): SolverResult<BuildCombination>? {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        if (params.targetStats.any { it.target > 0 }) return null // free shapes only — the DP can't model targets
        val ledger =
            if (precomputedLedger != null) {
                precomputedLedger
            } else if (incumbentObjective != null) {
                // Production path (an incumbent from the completed search): reuse the CACHED certificate —
                // `proveOptimality` populated it under the same key, so construction adds no extra ledger DP. The
                // argmax cell (objective > the incumbent it's rescuing) is confirmed at the exact tier, so its bound
                // is tight enough for the re-solve to reach.
                MaxDamageCertificateCache.certificate(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = incumbentObjective,
                    threads = certifierDefaultThreads(),
                    cascadeTier15 = true
                ) ?: return null
            } else {
                // Standalone (no incumbent, e.g. the manual proof test): force EVERY cell to the exact tier so any
                // shape's argmax is exact — no cache, no incumbent pruning.
                certifyLedgerForTest(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = null,
                    forceTier2All = true
                )
            }
        var argmax =
            ledger.cellObjectives.entries
                .filter { it.value >= 0 }
                .maxByOrNull { it.value } ?: return null
        var constructLedger = ledger
        if (precomputedLedger == null && incumbentObjective != null && argmax.key !in ledger.cellProvenance) {
            // The cascaded ledger's argmax is an UNCONFIRMED fast bound (the cascade's bet missed) — the
            // re-solve below could never reach it. Recompute the FULL ledger (cascade off; the cache
            // reuses every fast bound and confirmed cell, so only the skipped survivors pay) and
            // construct from its exactly-confirmed argmax — verdict parity with the pre-cascade path.
            constructLedger =
                MaxDamageCertificateCache.certificate(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = incumbentObjective,
                    threads = certifierDefaultThreads()
                ) ?: return null
            argmax =
                constructLedger.cellObjectives.entries
                    .filter { it.value >= 0 }
                    .maxByOrNull { it.value } ?: return null
        }
        val cell = argmax.key
        val bound = argmax.value
        // E8 item A: recover the argmax cell's winning items as typed equipmentIds (no fragile `slot:`-string parse).
        // Phase 2 (perf): the badge's exact pass captured the argmax cell's winning (world, crit-step) into the
        // cached ledger, so replay just that ONE explain pass. When it is absent (an old cache entry, or a
        // tier-1.5-cleared argmax that never ran exactly), fall back to the full N-worlds scan — sound, just slower.
        val provIds =
            (
                constructLedger.cellProvenance[cell]?.let { prov ->
                    certifierExplainItemIdsFromProvenanceForTest(
                        params,
                        equipmentsByItemType,
                        runes,
                        sublimations,
                        applyDomination = true,
                        cell = cell,
                        provenance = prov
                    )
                } ?: certifierExplainItemIdsForTest(params, equipmentsByItemType, runes, sublimations, applyDomination = true, cell = cell)
            ).toSet()
        val debug = System.getenv("WAKFU_E8_DEBUG") == "1"
        // FAST path: re-solve the pool restricted to the provenance items — ~seconds, and reaches the bound on
        // most shapes (measured: free lvl-110 / lvl-245 construct in one tiny re-solve).
        val fast =
            if (provIds.isNotEmpty()) {
                val restricted =
                    equipmentsByItemType
                        .mapValues { (_, items) -> items.filter { it.equipmentId in provIds } }
                        .filterValues { it.isNotEmpty() }
                if (restricted.isEmpty()) {
                    null
                } else {
                    optimize(params.copy(maxDamageApTarget = cell), restricted, runes, sublimations, SolverTuning(maxDeterministicTime = 120.0))
                        .toList()
                        .maxByOrNull { it.matchPercentage }
                }
            } else {
                null
            }
        // For a FREE shape objective == raw proxy (no penalty); maxDamageObjective is always populated, the raw
        // proxy only when its var survives — so fall back. Both are the ledger-comparable scaled units.
        val fastProxy = fast?.let { it.maxDamageRawProxy ?: it.maxDamageObjective }
        if (debug) System.err.println("E8_DBG fast cell=$cell bound=$bound proxy=$fastProxy valid=${fast?.individual?.isValid()}")
        if (fast != null && fastProxy != null && fastProxy >= bound && fast.individual.isValid()) return fast.copy(isOptimal = true)
        // FALLBACK: the provenance item-set need not REALIZE the bound — the certifier's frontier abstraction can
        // credit a sublimation whose value only a slightly different item set unlocks (e.g. the 10th normal sub on
        // a fuller sub loadout), so the restricted re-solve tops out below the bound. Re-solve the FULL pool at the
        // pinned argmax cell with a HARD `rawScore ≥ bound` floor: a pure FEASIBILITY search (the bound is a sound
        // upper bound at that cell, so any solution found sits exactly at it — no optimality proof, which is the
        // part the timed search couldn't close), stopped at the first solution, under the canonical deterministic
        // protocol (1 worker + interleave) so the construction is machine-reproducible. A loose (unreachable)
        // bound comes back INFEASIBLE ⇒ empty flow ⇒ null — the caller keeps the incumbent, soundness untouched.
        val fallback =
            optimize(
                params.copy(maxDamageApTarget = cell),
                equipmentsByItemType,
                runes,
                sublimations,
                SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = E8_FALLBACK_DETERMINISTIC_BUDGET,
                    stopAtFirstSolution = true
                ),
                maxDamageRawFloor = bound
            ).toList().maxByOrNull { it.matchPercentage } ?: return null
        val proxy = fallback.maxDamageRawProxy ?: fallback.maxDamageObjective ?: return null
        if (debug) System.err.println("E8_DBG fallback cell=$cell bound=$bound proxy=$proxy valid=${fallback.individual.isValid()}")
        return if (proxy >= bound && fallback.individual.isValid()) fallback.copy(isOptimal = true) else null
    }

    /**
     * Test-only: solve a max-damage model with either reachable ([tightDomains] = true) or loose guard
     * ([tightDomains] = false) declared domains, returning its objective + status WITHOUT requiring OPTIMAL.
     * The optimum-preservation lock asserts the two declare the same optimum (tight only changes provability,
     * never the answer) and that the tight build is the one that *proves* it.
     */
    internal fun maxDamageSolveForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        tightDomains: Boolean,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        forceRuneCountModel: Boolean = false,
        applyDomination: Boolean = false,
        forceRuneLeq: Boolean = false,
        // Enforce the required targets as HARD `actual ≥ target` constraints (INFEASIBLE ⇒ hasSolution false),
        // matching the production hard-constraints-first pass. Default false keeps existing callers byte-identical.
        hardConstraints: Boolean = false,
    ): MaxDamageSolveOutcome {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains,
                forceRuneCountModel = forceRuneCountModel,
                applyDomination = applyDomination,
                forceRuneLeq = forceRuneLeq,
                hardConstraints = hardConstraints,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        return MaxDamageSolveOutcome(
            objective = if (hasSolution) solver.objectiveValue().toLong() else Long.MIN_VALUE,
            isOptimal = status == com.google.ortools.sat.CpSolverStatus.OPTIMAL,
            hasSolution = hasSolution,
            selectedEquipmentIds =
                if (hasSolution) {
                    built.equipVars
                        .filterValues { solver.value(it) > 0L }
                        .keys
                        .map { it.equipmentId }
                        .toSet()
                } else {
                    emptySet()
                }
        )
    }

    /** Test seam: the per-slot domination pre-filter applied to [pool], pinning [pinned] to equality (empty = full). */
    internal fun filterDominatedPoolForTest(
        pool: Map<ItemType, List<Equipment>>,
        pinned: Set<Characteristic> = emptySet(),
    ): Map<ItemType, List<Equipment>> = filterDominatedPool(pool, pinned)

    /** A tracked objective-chain var's solved value against its declared reachable `[lo, hi]`. */
    internal data class MaxDamageVarBound(
        val name: String,
        val value: Long,
        val lo: Long,
        val hi: Long,
    ) {
        val withinBound: Boolean get() = value in lo..hi
    }

    internal data class MaxDamageReachableRange(
        val name: String,
        val lo: Long,
        val hi: Long,
    ) {
        val span: Long get() = hi - lo
    }

    internal fun maxDamageReachableRangesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        tightDomains: Boolean = true,
        applyDomination: Boolean = false,
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
    ): List<MaxDamageReachableRange> {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains = tightDomains,
                applyDomination = applyDomination,
                maxDamageExperiment = experiment
            )
        return built.maxDamageTracked.map { (_, name, range) -> MaxDamageReachableRange(name, range.first, range.last) }
    }

    /**
     * Test-only **soundness probe**: build the max-damage model with **loose** guard domains (so the solver is
     * free to push every objective-chain var as high as a real build allows) while still *recording* each var's
     * propagated reachable `[lo, hi]`, solve it, and return every tracked var's solved value vs that range. A
     * value outside its range means the interval arithmetic UNDER-estimated — i.e. the production (tight) build
     * would have declared a too-small domain and **silently cut the optimum**. The proven optimum's own var
     * values are exactly the ones that must fit, so this catches every optimum-threatening under-estimate.
     */
    internal fun maxDamageVarBoundsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        // false (default) = the soundness-probe use: loose guard domains so the solver can push each var to its
        // true max. true = the plan §2.2 bound-layer AUDIT use: PRODUCTION (tight) domains so the same
        // reachable box is recorded but the solve is tractable at lvl-245 (loose product encodings blow up there).
        tightDomains: Boolean = false,
        // false (default, soundness probe keeps the full pool). true = the AUDIT use: run the production domination
        // pre-filter so the lvl-245 full-epic pool doesn't blow the model up on build (sound — domination preserves
        // the optimum, and the recorded boxes then match the production model).
        applyDomination: Boolean = false,
    ): List<MaxDamageVarBound> {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains = tightDomains,
                applyDomination = applyDomination,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
            "soundness probe needs a solution, got $status"
        }
        return built.maxDamageTracked.map { (v, name, range) ->
            MaxDamageVarBound(name, solver.value(v), range.first, range.last)
        }
    }

    /**
     * C2 test seam: whether the HARD-constraints max-damage model is statically infeasible — i.e. some required
     * target exceeds its reachable ceiling, so [optimize] skips the CP-SAT solve and falls straight to the soft
     * leg. Builds the model only (no solve) and returns [BuiltModel.maxDamageStaticallyInfeasible].
     */
    internal fun maxDamageStaticallyInfeasibleForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): Boolean = buildModel(params, equipmentsByItemType, runes, sublimations, hardConstraints = true).maxDamageStaticallyInfeasible

    /**
     * C7 test seam: the crit·diff AM-GM bound the model-build actually ADDED as a constraint, or null when
     * the cut did not fire (flag off, %-skill bail, or self-disabled against term's declared reach). The
     * firing fixture asserts non-null so the exhaustive-optimum comparison provably exercises the cut.
     */
    internal fun maxDamageCritDiffCutBoundForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
    ): Long? =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            maxDamageExperiment = experiment
        ).critDiffJointCutBoundForTest

    /** C3 test seam: the memoized per-slot domination filter (keyed on basePool identity + [DominationShape] value). */
    internal fun filterDominatedPoolMemoizedForTest(
        basePool: Map<ItemType, List<Equipment>>,
        shape: DominationShape,
    ): Map<ItemType, List<Equipment>> = filterDominatedPoolMemoized(basePool, shape)

    /**
     * Test-only soundness probe for the **precision** reachable domains — the precision analogue of
     * [maxDamageVarBoundsForTest]. Builds the precision model with loose guard domains (so the solver freely
     * pushes every stat-chain var as high as a real build allows) while still recording each var's propagated
     * reachable `[lo, hi]`, solves, and returns each tracked var's solved value vs that range. A value outside
     * its range means the interval arithmetic UNDER-estimated — i.e. the production (tight) build would have
     * declared a too-small domain and silently cut the optimum.
     */
    internal fun precisionVarBoundsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): List<MaxDamageVarBound> {
        require(params.scoreComputationMode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
            "precisionVarBoundsForTest needs precision params, got ${params.scoreComputationMode}"
        }
        val built = buildModel(params, equipmentsByItemType, runes, sublimations, tightDomains = false)
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
            "soundness probe needs a solution, got $status"
        }
        return built.precisionTracked.map { (v, name, range) ->
            MaxDamageVarBound(name, solver.value(v), range.first, range.last)
        }
    }

    /** Benchmark probe (any mode): model size + solve status/score, with the prefilter optionally bypassed. */
    internal data class BenchOutcome(
        val status: String,
        val numVariables: Int,
        val numConstraints: Int,
        val wallTimeSec: Double,
        val score: BigDecimal,
        val poolSize: Int,
    )

    /**
     * Test-only: build + solve [params] on [equipmentsByItemType] with a deterministic [tuning], optionally
     * bypassing the item prefilter ([forceFullPool]); reports CP-SAT status, model size and the *scored*
     * result so a benchmark can compare full-pool vs prefiltered on objective AND tractability.
     */
    internal fun solveForBenchmark(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        forceFullPool: Boolean,
    ): BenchOutcome {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                emptyList(),
                emptyList(),
                tightDomains = true,
                forceFullPool = forceFullPool,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) solver.parameters.linearizationLevel = 2
        val status = solver.solve(built.model)
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        val score =
            if (hasSolution) {
                val build = solutionToBuild(params, built.allEquips, built.equipVars, built.skillVars, built.runeModel, built.subModel) { solver.value(it) }
                scoreFor(params, build)
            } else {
                BigDecimal.ZERO
            }
        val proto = built.model.model()
        return BenchOutcome(
            status = status.toString(),
            numVariables = proto.variablesCount,
            numConstraints = proto.constraintsCount,
            wallTimeSec = solver.wallTime(),
            score = score,
            poolSize = built.allEquips.size
        )
    }

    /** Test-only: whether [params] would be prefiltered, and the resulting distinct-item pool size (no solve). */
    internal fun gatedPoolSizeForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
    ): Pair<Boolean, Int> {
        val prefiltered = needsItemPrefilter(params.targetStats)
        val pool = if (prefiltered) prefilterRelevantEquipments(equipmentsByItemType, params) else equipmentsByItemType
        return prefiltered to orderEquipments(pool).size
    }

    private fun CpModel.createSkillVariables(characterSkills: CharacterSkills): Map<SkillCharacteristic, IntVar> {
        val skillVars = mutableMapOf<SkillCharacteristic, IntVar>()

        fun addCategory(
            assignable: Assignable<*>,
            namePrefix: String,
        ) {
            val sumExpr = LinearExpr.newBuilder()
            for (skill in assignable.getCharacteristics()) {
                val varName = "skill_${namePrefix}_${skill.name.toIdentifier()}"
                val skillVar = newIntVar(0, skillVariableCap(skill, assignable), varName)
                skillVars[skill] = skillVar
                sumExpr.addTerm(skillVar, 1)
            }
            addLessOrEqual(sumExpr.build(), assignable.maxPointsToAssign.toLong())
        }

        addCategory(characterSkills.intelligence, "intel")
        addCategory(characterSkills.strength, "strength")
        addCategory(characterSkills.agility, "agility")
        addCategory(characterSkills.luck, "luck")
        addCategory(characterSkills.major, "major")

        return skillVars
    }

    private fun skillVariableCap(
        skill: SkillCharacteristic,
        assignable: Assignable<*>,
    ): Long = min(skill.maxPointsAssignable, assignable.maxPointsToAssign).toLong()

    internal fun skillVariableCaps(characterSkills: CharacterSkills): Map<SkillCharacteristic, Long> {
        val caps = mutableMapOf<SkillCharacteristic, Long>()

        fun add(assignable: Assignable<*>) {
            assignable.getCharacteristics().forEach { skill ->
                caps[skill] = skillVariableCap(skill, assignable)
            }
        }

        add(characterSkills.intelligence)
        add(characterSkills.strength)
        add(characterSkills.agility)
        add(characterSkills.luck)
        add(characterSkills.major)

        return caps
    }

    private fun CpModel.createEquipmentVariables(allEquips: List<Equipment>): Map<Equipment, IntVar> =
        allEquips.associateWith { equip ->
            newBoolVar("equip_${equip.equipmentId}")
        }

    /**
     * Whether a scenario-gated effect can fire for this request — the solver's `params`-shaped adapter over the
     * shared [scenarioGateMatchesCore] (the single source of truth for the gate decision; see SublimationSemantics).
     * Kept here as `WakfuBuildSolver.scenarioGateMatches` because the CP-SAT model builders call it that way.
     */
    internal fun scenarioGateMatches(
        gate: me.chosante.common.ScenarioGate?,
        params: WakfuBestBuildParams,
    ): Boolean =
        scenarioGateMatchesCore(
            gate,
            params.scoreComputationMode,
            params.damageScenario,
            params.character.level,
            params.targetStats.masteryElementsWanted.keys
        )

    private fun CpModel.addBuildValidityConstraints(
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
    ) {
        val itemTypesLimits =
            mapOf(
                ItemType.AMULET to 1L,
                ItemType.EMBLEM to 1L,
                ItemType.SHOULDER_PADS to 1L,
                ItemType.RING to 2L,
                ItemType.BOOTS to 1L,
                ItemType.CHEST_PLATE to 1L,
                ItemType.CAPE to 1L,
                ItemType.HELMET to 1L,
                ItemType.PETS to 1L,
                ItemType.MOUNTS to 1L,
                ItemType.BELT to 1L
            )

        for ((type, limit) in itemTypesLimits) {
            val typeEquips = allEquips.filter { it.itemType == type }
            if (typeEquips.isNotEmpty()) {
                val sumExpr = LinearExpr.sum(typeEquips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, limit)
            }
        }

        // Weapons rules
        val twoHanded = allEquips.filter { it.itemType == ItemType.TWO_HANDED_WEAPONS }
        val oneHanded = allEquips.filter { it.itemType == ItemType.ONE_HANDED_WEAPONS }
        val offHand = allEquips.filter { it.itemType == ItemType.OFF_HAND_WEAPONS }

        val sumTwoHanded = LinearExpr.sum(twoHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOneHanded = LinearExpr.sum(oneHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOffHand = LinearExpr.sum(offHand.map { equipVars.getValue(it) }.toTypedArray())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOneHanded)
                .build(),
            1L
        )
        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOffHand)
                .build(),
            1L
        )

        // Rarity rules
        val relics = allEquips.filter { it.rarity == Rarity.RELIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (relics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(relics), 1L)
        }

        val epics = allEquips.filter { it.rarity == Rarity.EPIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (epics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(epics), 1L)
        }

        // Same ring name is not allowed
        val ringsByName =
            allEquips
                .filter { it.itemType == ItemType.RING }
                .groupBy { it.name.fr.lowercase() }
        for ((_, equips) in ringsByName) {
            if (equips.size > 1) {
                val sumExpr = LinearExpr.sum(equips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, 1L)
            }
        }
    }

    /**
     * Forces every user-imposed item ([WakfuBestBuildParams.forcedItems], matched on the French name) to be
     * *equipped* — not merely "the only candidate in its slot". [WakfuBestBuildFinderAlgorithm.groupAndFilterEquipments]
     * narrows a forced slot's pool down to the forced item, but the slot itself is still optional (`Σ ≤ 1`), so an
     * imposed item that improves no requested stat would otherwise be left unequipped — reading as "forcing didn't
     * work". Constraining `Σ(same-named) ≥ 1` makes "forcer un objet" mean the item is actually in the build.
     *
     * Matched per French name and gated on existence, so a typo'd / out-of-data forced name is a no-op (you cannot
     * force a non-existent item). Two distinct items forced into the same single-occupancy slot is a contradictory
     * request that makes the model infeasible (no build); surfacing a clear pre-search message for that is tracked
     * separately (backlog ENG-2). Rings are forced per name and have two slots, so two forced rings both equip.
     */
    private fun CpModel.addForcedItemsEquippedConstraints(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
    ) {
        if (params.forcedItems.isEmpty()) return
        for (forcedName in params.forcedItems.map { it.lowercase() }.toSet()) {
            val sameName = allEquips.filter { it.name.fr.lowercase() == forcedName }
            if (sameName.isEmpty()) continue
            addGreaterOrEqual(LinearExpr.sum(sameName.map { equipVars.getValue(it) }.toTypedArray()), 1L)
        }
    }

    /**
     * Objective for "most masteries" mode: maximize the *requested* masteries — scaled by the build's global
     * **% Damage Inflicted** so the proxy is damage-faithful (see [StatBuilder.diAdjustedPerElementMasteryScore]) —
     * under the required-stat constraints. There is deliberately no tie-breaker that fills otherwise-empty
     * slots, so a slot whose items cannot improve any requested stat (nor the DI factor) is left empty in the
     * proven optimum. This is why, e.g., an item set asking only for distance mastery + AP/MP/HP comes
     * back with no mount: every mount in the data carries only [Characteristic.MASTERY_ELEMENTARY],
     * which contributes to none of those targets, so adding one cannot raise the objective and the
     * proven optimum leaves the slot empty. (Decision: keep as-is; see the engine discussion in
     * AGENTS.md §4.)
     */
    private fun CpModel.buildMostMasteriesObjective(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
    ): IntVar {
        val statBuilder =
            StatBuilder(
                this,
                params,
                allEquips,
                equipVars,
                skillVars,
                runeModel,
                subModel,
                // Decouple from the max-damage experiment default (see [MaxDamageExperimentConfig.NON_MAX_DAMAGE]).
                maxDamageExperiment = MaxDamageExperimentConfig.NON_MAX_DAMAGE
            )
        statBuilder.applyOutOfCombatCaps()
        val targetStats = params.targetStats
        val targetCharacteristics = targetStats.map { it.characteristic }.toSet()

        // Damage-faithful proxy: maximized mastery sum × (1 + DI/100). Mirrored exactly by the re-scorer
        // (FindMostMasteriesFromInputScoring) so the solver optimum and the scored optimum stay in lockstep.
        // C8: [diAdjustedPerElementMasteryScore] now also returns a sound reachable ceiling on that score, used as
        // the product-box bound below (was the loose MASTERY_SCORE_ABS_MAX), tightening the objective's McCormick
        // envelope on the required-target path.
        val (masteryScore, masteryScoreReach) = statBuilder.diAdjustedPerElementMasteryScore(targetStats, targetCharacteristics)

        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        val penalized = applyConstraintPenalty(params, statBuilder, masteryScore, masteryScoreReach)
        if (requiredTargets.isEmpty()) {
            return penalized.objective
        }

        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)

        // Lexicographic tie-breaker: among builds the primary objective ranks equally, prefer the one
        // that exceeds the required targets the most (weighted by the same per-constraint priorities).
        // This is what makes the solver spend otherwise objective-neutral skill points into HP/CC%
        // (and, among ties, pick gear that overshoots) instead of leaving them unused — free in-game
        // value the player would always take. It can never trade a maximized-mastery point for
        // overshoot; see [withOvershootTieBreaker].
        val overshoot = statBuilder.overshootScore(requiredTargets, totalExpectedScore, targetStats)
        return withOvershootTieBreaker(penalized.objective, penalized.bound, overshoot, totalExpectedScore)
    }

    /**
     * The two max-damage objective vars: [rawScore] is the **unpenalized** per-turn damage proxy (before the
     * survivability floor and the required-target multiplier) — the value in [CertLedger] units, surfaced so the
     * post-search certificate can compare against it even for required-target requests; [objective] is what
     * CP-SAT actually maximizes.
     */
    private data class MaxDamageObjectiveVars(
        val rawScore: IntVar,
        val objective: IntVar,
        // C2: true when a hard-constraints solve is PROVABLY infeasible (a required target exceeds its reachable
        // ceiling). Lets [optimize] skip the doomed CP-SAT solve. Always false outside the hard-constraints path.
        val staticallyInfeasible: Boolean = false,
    )

    /**
     * Objective for "max-damage" mode: maximize expected damage for the requested [DamageScenario]
     * (Wakfu's exact formula, see [FindMaxDamageScoring]). The build-dependent core is the product
     * `D · Graw` with `D = 100 + ΣDI`, `Graw = 400·M + crit·(M + 5·criticalMastery)` and
     * `M = 100 + ΣMastery` — derived so that `D·Graw ∝ E[dmg]` (the scenario's constant Base /
     * orientation / resistance factors are dropped since they scale every build equally). Required
     * AP/MP/range/… targets are then enforced with the same shortfall penalty as most-masteries mode.
     * Unlike most-masteries this has no overshoot tie-breaker: the damage objective already strongly
     * differentiates builds, so there is no large class of objective-ties left to refine. Returns both the
     * penalized [MaxDamageObjectiveVars.objective] and the unpenalized [MaxDamageObjectiveVars.rawScore].
     */
    private fun CpModel.buildMaxDamageObjective(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
        objectiveCutoff: Long? = null,
        // Hard-constraints-first solve: enforce the required AP/MP/range/… targets as HARD constraints
        // (`actual ≥ target`) under a PLAIN damage objective, instead of the soft shortfall penalty. The
        // caller ([WakfuBuildSolver.optimize] with hardConstraints = true) tries this first; if the model is
        // INFEASIBLE (unreachable targets) it re-solves with the penalty (this flag false). See
        // [StatBuilder.addRequiredTargetHardConstraints].
        hardConstraints: Boolean = false,
    ): MaxDamageObjectiveVars {
        statBuilder.applyOutOfCombatCaps()
        // External-loop AP probe: pin the build to exactly N AP so each breakpoint can be evaluated (used by the
        // debuff AP-window probes in MaxDamageSearch).
        params.maxDamageApTarget?.let { addEquality(statBuilder.actionPointVar(), newConstant(it.toLong())) }
        val damageScore = statBuilder.perTurnDamageScore(params.damageScenario, params.character.clazz, objectiveCutoff)
        // Survivability soft-floor (opt-in): gently tax the damage score when the build's effective-HP
        // proxy is below the floor, BEFORE the hard-target penalty. Folding it into the core score (rather
        // than chaining a second multiply onto the already-near-Long.MAX/2 penalized objective) keeps the
        // objective on the same DAMAGE_PERTURN_ABS_MAX domain, so applyConstraintPenalty's bounds are untouched.
        val scenario = params.damageScenario
        val survivableScore =
            if (scenario.survivabilityFloor && scenario.minEffectiveHp > 0) {
                // Clamp to the proxy's reachable ceiling — a min-EHP above EHP_MAX would be unsatisfiable by
                // construction and collapse every build's multiplier toward zero (a damage-blind objective).
                applySurvivabilityFloor(statBuilder, damageScore, scenario.minEffectiveHp.toLong().coerceAtMost(EHP_MAX))
            } else {
                damageScore
            }
        // rawScore is the unpenalized damage proxy (`damageScore`), the value the certificate ledger bounds.
        // The survivability floor and the required-target penalty both wrap it into `objective`; the certificate
        // does not model either, so [proveOptimality] compares against rawScore (and bails on a survivability
        // floor, whose per-build multiplier makes even rawScore non-comparable).
        // HARD-constraints mode: required targets are `actual ≥ target` constraints (added below) and the
        // objective is the PLAIN damage score — no penalty product, so the model is the shape CP-SAT proves.
        // If no required target exists the hard pass is identical to the un-penalised solve.
        if (hardConstraints) {
            val staticallyInfeasible = statBuilder.addRequiredTargetHardConstraints()
            return MaxDamageObjectiveVars(rawScore = damageScore, objective = survivableScore, staticallyInfeasible = staticallyInfeasible)
        }
        return MaxDamageObjectiveVars(
            rawScore = damageScore,
            objective = applyConstraintPenalty(params, statBuilder, survivableScore, DAMAGE_PERTURN_ABS_MAX).objective
        )
    }

    /**
     * Multiplies the max-damage [coreScore] by a **gentle** survivability penalty so a build whose
     * effective-HP proxy ([StatBuilder.effectiveHpVar]) is below [minEffectiveHp] ranks below an
     * equal-damage tankier build, while a build at or above the floor is left untouched. The penalty
     * reuses the exact required-target machinery — bucket `min(EHP, floor)` against the floor, look the
     * bucket up in a power table, multiply — but with a power-2 table (not the power-6 used for hard
     * AP/MP/range targets), so survivability only *nudges* the optimum, never dominates damage.
     *
     * Because the table is normalised so the at-or-above-floor bucket maps to [MAX_SURVIVABILITY_MULTIPLIER]
     * and we divide the product back out by that same max, meeting the floor is an exact no-op
     * (`score · max / max = score`) and missing it scales the score down by `bucket^2 / maxIndex^2` — a
     * smooth soft tax that vanishes at the floor. The result is clamped back onto [DAMAGE_PERTURN_ABS_MAX]
     * so downstream bounds are unchanged.
     */
    private fun CpModel.applySurvivabilityFloor(
        statBuilder: StatBuilder,
        coreScore: IntVar,
        minEffectiveHp: Long,
    ): IntVar {
        val ehp = statBuilder.effectiveHpVar()
        // cappedEhp = min(EHP, floor): only the shortfall below the floor matters; overshoot is not rewarded.
        val cappedEhp = newIntVar(0L, minEffectiveHp, "ehpCappedAtFloor")
        addMinEquality(cappedEhp, arrayOf(ehp, newConstant(minEffectiveHp)))

        val (indexVar, maxIndex) = bucketedIndex(cappedEhp, minEffectiveHp)
        val powerTable = buildGentlePowerTable(maxIndex.toLong())

        val bucketMultiplier = newIntVar(0, powerTable.maxValue, "survivabilityBucketMultiplier")
        addElement(indexVar, powerTable.values, bucketMultiplier)
        // The integer bucketing can map the floor value itself to maxIndex-1 (when the bucket size doesn't
        // divide the floor), which would tax a build that MEETS the floor. Force the multiplier to its max
        // whenever the floor is cleared (cappedEhp == floor ⟺ EHP ≥ floor), so "meeting the floor is an exact
        // no-op" holds for every floor, not just floors ≤ MAX_POWER_TABLE_INDEX.
        val clearsFloor = newBoolVar("ehpClearsFloor")
        addEquality(cappedEhp, newConstant(minEffectiveHp)).onlyEnforceIf(clearsFloor)
        addLessOrEqual(cappedEhp, newConstant(minEffectiveHp - 1)).onlyEnforceIf(clearsFloor.not())
        val clearsBonus = newIntVar(0L, powerTable.maxValue, "survivabilityClearsBonus")
        addEquality(clearsBonus, LinearExpr.term(clearsFloor, powerTable.maxValue))
        val multiplier = newIntVar(0, powerTable.maxValue, "survivabilityMultiplier")
        addMaxEquality(multiplier, arrayOf(bucketMultiplier, clearsBonus))

        // boosted = coreScore · multiplier, then ÷ maxMultiplier → back onto the core's domain.
        val boostedBound = safeMultiply(DAMAGE_PERTURN_ABS_MAX, powerTable.maxValue)
        val boosted = newIntVar(0L, boostedBound, "survivabilityBoosted")
        addMultiplicationEquality(boosted, coreScore, multiplier)
        val penalized = newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "survivabilityPenalized")
        addDivisionEquality(penalized, boosted, newConstant(powerTable.maxValue.coerceAtLeast(1L)))
        return penalized
    }

    /**
     * Wraps a build-dependent [coreScore] (mastery sum or expected damage) with the required-target
     * shortfall penalty: when no required targets exist the core score is the objective; otherwise it
     * is multiplied by a power-6 penalty multiplier driven by how fully the AP/MP/range/… constraints
     * are met. Returns the penalized objective var and the absolute bound of its domain — the latter is
     * what the most-masteries overshoot tie-breaker needs. [coreScoreAbsMax] bounds the result.
     */
    private fun CpModel.applyConstraintPenalty(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
        coreScore: IntVar,
        coreScoreAbsMax: Long,
    ): PenalizedObjective {
        val targetStats = params.targetStats
        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (requiredTargets.isEmpty()) {
            return PenalizedObjective(coreScore, coreScoreAbsMax)
        }

        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)

        val totalActualScore = statBuilder.totalActualScore(requiredTargets, totalExpectedScore, targetStats)
        val totalActualScoreForPenalty = maxVar(totalActualScore, 1L, totalExpectedScore, "totalActualScoreForPenalty")

        val (indexVar, maxIndex) = bucketedIndex(totalActualScoreForPenalty, totalExpectedScore)
        val powerTable = buildPowerTable(maxIndex.toLong(), coreScoreAbsMax)

        val multiplier = newIntVar(0, powerTable.maxValue, "penaltyMultiplier")
        addElement(indexVar, powerTable.values, multiplier)

        val maxObjective = safeMultiply(coreScoreAbsMax, powerTable.maxValue)
        val objectiveBound = maxObjective.coerceAtMost(Long.MAX_VALUE / 2)
        val objective = newIntVar(-objectiveBound, objectiveBound, "objectiveScore")
        addMultiplicationEquality(objective, coreScore, multiplier)
        return PenalizedObjective(objective, objectiveBound)
    }

    internal data class PenalizedObjective(
        val objective: IntVar,
        val bound: Long,
    )

    /**
     * Folds a lexicographic overshoot tie-breaker under [primaryObjective], returning
     * `primaryObjective * OVERSHOOT_SCALE + bonus` where `bonus ∈ [0, OVERSHOOT_SCALE)` is the
     * weighted overshoot normalised into that range. Because `bonus < OVERSHOOT_SCALE`, even a
     * one-unit improvement in the (integer) primary objective — worth `OVERSHOOT_SCALE` after scaling
     * — always dominates any overshoot gain. So this never sacrifices a maximized-mastery point for
     * overshoot; it only ranks builds the primary objective already considers tied. [totalExpectedScore]
     * (≥ 1) is the same denominator the penalty uses, so the bonus is proportional to how far the
     * build exceeds its targets relative to what was asked.
     */
    private fun CpModel.withOvershootTieBreaker(
        primaryObjective: IntVar,
        primaryBound: Long,
        rawOvershoot: IntVar,
        totalExpectedScore: Long,
    ): IntVar {
        val scaledRaw = newIntVar(0, safeMultiply(totalExpectedScore, OVERSHOOT_SCALE - 1), "overshootScaled")
        addEquality(scaledRaw, LinearExpr.term(rawOvershoot, OVERSHOOT_SCALE - 1))

        val bonus = newIntVar(0, OVERSHOOT_SCALE - 1, "overshootBonus")
        addDivisionEquality(bonus, scaledRaw, newConstant(totalExpectedScore))

        val combinedBound = safeMultiply(primaryBound, OVERSHOOT_SCALE) + OVERSHOOT_SCALE
        val combined = newIntVar(-combinedBound, combinedBound, "objectiveWithOvershoot")
        addEquality(
            combined,
            LinearExpr
                .newBuilder()
                .addTerm(primaryObjective, OVERSHOOT_SCALE)
                .addTerm(bonus, 1)
                .build()
        )
        return combined
    }

    private fun CpModel.buildPrecisionObjective(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
    ): IntVar {
        statBuilder.applyOutOfCombatCaps()
        return statBuilder.precisionScore(params.targetStats)
    }

    /** Exact score of a candidate build, using the scorer that matches the requested mode. */
    private fun scoreFor(
        params: WakfuBestBuildParams,
        combination: BuildCombination,
    ): BigDecimal =
        when (params.scoreComputationMode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                FindMostMasteriesFromInputScoring.computeScore(
                    targetStats = params.targetStats,
                    buildCombination = combination,
                    characterBaseCharacteristics = params.character.baseCharacteristicValues
                )

            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                FindClosestBuildFromInputScoring.computeScore(
                    targetStats = params.targetStats,
                    buildCombination = combination,
                    characterBaseCharacteristics = params.character.baseCharacteristicValues
                )

            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                maxDamageRotationScore(params, combination)
        }

    /**
     * Spell-aware / boss-aware max-damage score: the best **per-turn rotation** damage the build can
     * deal — across all candidate elements (boss-aware element choice) using the class's real spell kit
     * — divided by the same required-target shortfall penalty as [FindMaxDamageScoring]. Kept in lockstep
     * with [buildMaxDamageObjective]: both compute `max_e (throughput_e × perHit_e × resFactor_e)` and
     * apply the AP/MP/range penalty, so the build the objective maximizes is the one the solver emits.
     * The rotation also folds in the scenario's positional ×multiplier (face/side/back) — a uniform constant
     * the objective deliberately drops since it scales every build equally, so it sharpens the *displayed*
     * per-turn damage without changing which build (or element) ranks highest.
     */
    private fun maxDamageRotationScore(
        params: WakfuBestBuildParams,
        combination: BuildCombination,
    ): BigDecimal {
        val rotationDamage =
            SpellRotationOptimizer
                .bestAcrossElements(combination, params.character, params.character.clazz, params.damageScenario)
                .totalExpectedDamage
                .toBigDecimal()
        val stats =
            computeCharacteristicsValues(
                buildCombination = combination,
                characterBaseCharacteristics = params.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(params.damageScenario.element.masteryCharacteristic to 1),
                // Pass the real resistance targets so the penalty's stats see RESISTANCE_ELEMENTARY / per-
                // element resistances (an emptyMap made them read 0, mis-ranking builds when the user sets a
                // required resistance in max-damage mode).
                resistanceElementsWanted = params.targetStats.resistanceElementsWanted
            )
        val penalty = FindMaxDamageScoring.requiredConstraintPenaltyFactor(params.targetStats, stats)
        return rotationDamage.divide(penalty, 4, RoundingMode.FLOOR)
    }

    private suspend fun executeSolverAndEmitResults(
        model: CpModel,
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
        // Max-damage only: the unpenalized damage-proxy var, read on each emitted solution to stamp
        // [SolverResult.maxDamageRawProxy] (the certificate-comparable value). Null in the other modes.
        maxDamageRawScoreVar: IntVar?,
        scope: ProducerScope<SolverResult<BuildCombination>>,
        tuning: SolverTuning?,
        // Hands the freshly-created solver to the caller so it can stop the (otherwise uninterruptible) native
        // solve from another thread on flow teardown — see the `awaitClose` in [optimize].
        onSolverReady: (CpSolver) -> Unit = {},
        // C8(3): floor for best-effort INTERMEDIATE emissions — the greedy warm start already streamed a
        // build with this score, and consumers keep the LAST emission, so streaming a worse snapshot would
        // visibly regress the displayed build. The final (guaranteed) send stays unconditional.
        suppressBelowScore: BigDecimal? = null,
    ) {
        val solver = CpSolver()
        onSolverReady(solver)
        solver.parameters.logSearchProgress = false
        // Max-damage declares its objective-chain vars with reachable domains, which finally makes the LP
        // relaxation worth building; engage the level-2 linearization so CP-SAT can certify the bound and
        // prove OPTIMAL. Gated to max-damage to leave the other modes' tuned search paths untouched.
        val maxDamage = params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        if (maxDamage) solver.parameters.linearizationLevel = 2
        if (tuning == null) {
            // Production: a parallel portfolio (CP-SAT's equivalent of the GA's parallel scoring),
            // bounded by the user's wall-clock search duration.
            //
            // We deliberately leave one core for the host: CP-SAT spawns this many *native* threads
            // and pins them at 100%, which otherwise starves the Compose render thread / EDT and
            // makes the GUI window visibly freeze during a search. One fewer worker is a negligible
            // throughput loss next to a responsive UI (and keeps the terminal responsive for the CLI).
            //
            // The single-iteration presolve cap was a symptom of the old loose domains (full presolve never
            // terminated on the loose max-damage objective). With reachable domains presolve folds the tight
            // bounds through the constraint network quickly, so max-damage affords a few iterations — the
            // other modes keep the light single pass their larger/looser models rely on to stay in budget.
            solver.parameters.maxPresolveIterations = if (maxDamage) 3 else 1
            // Millisecond precision (not inWholeSeconds, which FLOORS): the max-damage external loop slices
            // its phase budget into sub-second per-probe limits, and a floored 0.0 means "no time limit" to
            // OR-Tools — which is exactly how a fan-out of probes could run unbounded. Floored to 50ms so a
            // valid budget is never rounded down to the unlimited sentinel.
            solver.parameters.maxTimeInSeconds = (params.searchDuration.inWholeMilliseconds.toDouble() / 1000.0).coerceAtLeast(0.05)
            solver.parameters.numSearchWorkers =
                params.solverWorkers ?: (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        } else {
            // Deterministic, machine-independent solve for tests — see [SolverTuning]. A fixed worker
            // count + seed + a deterministic-time budget (not wall-clock) make CP-SAT reach the same
            // proven optimum on every machine, removing the flakiness of a wall-clock-bounded search.
            // Presolve runs in full here (tests only ever solve the small, prefiltered model) so the
            // optimality proof finishes quickly.
            solver.parameters.numSearchWorkers = tuning.numSearchWorkers
            solver.parameters.randomSeed = tuning.randomSeed
            solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
            if (tuning.interleaveSearch) solver.parameters.interleaveSearch = true
            tuning.maxPresolveIterationsOverride?.let { solver.parameters.maxPresolveIterations = it }
            tuning.linearizationLevelOverride?.let { solver.parameters.linearizationLevel = it }
        }

        val startTime = System.currentTimeMillis()

        val cb =
            object : CpSolverSolutionCallback() {
                private var lastEmitMs = 0L

                override fun onSolutionCallback() {
                    // The native solve blocks the IO thread, so coroutine cancellation can't interrupt
                    // it directly; stopping the search here (next time a solution is found) is the
                    // CP-SAT idiom that lets the GUI's cancel actually end the work. Kept BEFORE the
                    // throttle so cancel latency is unchanged.
                    if (!scope.isActive) {
                        stopSearch()
                        return
                    }

                    // E8 fallback: the first solution is already at the raw-score floor (= the certificate
                    // bound), so stop immediately — the guaranteed final send below delivers it. No
                    // intermediate emission needed.
                    if (tuning?.stopAtFirstSolution == true) {
                        stopSearch()
                        return
                    }

                    // Throttle the heavy rescore: building + scoring every improving solution on the solve
                    // thread starves the search. Snapshots are best-effort progress (trySend, consumers keep
                    // only the last), so skipping some is invisible — the proven final build is emitted
                    // separately and unconditionally below. The first solution always passes (lastEmitMs = 0).
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs < INTERMEDIATE_EMIT_THROTTLE_MS) return
                    lastEmitMs = now

                    val combination = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { value(it) }
                    val actualScore = scoreFor(params, combination)
                    if (suppressBelowScore != null && actualScore < suppressBelowScore) return

                    val progress = ((now - startTime).toDouble() / params.searchDuration.inWholeMilliseconds.toDouble() * 100).toInt()
                    scope.trySend(
                        SolverResult(
                            combination,
                            actualScore,
                            progress.coerceAtMost(100),
                            maxDamageObjective = if (maxDamage) objectiveValue().toLong() else null,
                            maxDamageRawProxy = if (maxDamage) maxDamageRawScoreVar?.let { value(it) } else null
                        )
                    )
                }
            }

        try {
            val status = solver.solve(model, cb)
            logger.debug { "Solver status returned: $status" }
            logger.debug { "Solver response stats:\n${solver.responseStats()}" }

            if (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
                val finalComb = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { solver.value(it) }
                val finalScore = scoreFor(params, finalComb)
                // Guaranteed delivery (suspending send, not trySend): intermediate best-so-far
                // emissions are best-effort progress and may be dropped under back-pressure, but the
                // final/optimal build must never be lost to a saturated callbackFlow buffer.
                if (scope.isActive) {
                    scope.send(
                        SolverResult(
                            individual = finalComb,
                            matchPercentage = finalScore,
                            progressPercentage = 100,
                            isOptimal = status == com.google.ortools.sat.CpSolverStatus.OPTIMAL,
                            maxDamageObjective = if (maxDamage) solver.objectiveValue().toLong() else null,
                            maxDamageRawProxy = if (maxDamage) maxDamageRawScoreVar?.let { solver.value(it) } else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Solver failed while searching for the best build." }
        }
    }

    /**
     * The player's selected passive loadout: each [WakfuBestBuildParams.forcedPassives] name resolved to a
     * [Passive] of the character's class, de-duplicated and capped to the level's passive slots
     * ([PassiveCatalog.slotsForLevel]). Unknown names are dropped. Shared by the stat-folding ([StatBuilder])
     * and the result ([solutionToBuild]) so what is scored equals what the build carries.
     */
    internal fun resolvedPassives(params: WakfuBestBuildParams): List<Passive> {
        if (params.forcedPassives.isEmpty()) return emptyList()
        val slots = PassiveCatalog.slotsForLevel(params.character.level)
        return params.forcedPassives
            .mapNotNull { PassiveCatalog.findByName(params.character.clazz, it) }
            .distinct()
            .take(slots)
    }

    /**
     * Rebuilds a [BuildCombination] from a solved assignment. Skill points are mapped back by the
     * *position* of each skill in [CharacterSkills.allCharacteristic] — identical between the params'
     * skills (used to create the variables) and the fresh skills here — never by name: two distinct
     * skills share the name "Resistance Elementary" (Intelligence vs Major), so a name lookup would
     * cross-assign their points and corrupt both the build and its recomputed score.
     */

    private fun solutionToBuild(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
        valueOf: (IntVar) -> Long,
    ): BuildCombination {
        val equippedItems = allEquips.filter { valueOf(equipVars.getValue(it)) > 0L }

        val optimizedSkills = CharacterSkills(params.character.level)
        val originalSkills = params.character.characterSkills.allCharacteristic
        optimizedSkills.allCharacteristic.forEachIndexed { index, skill ->
            skillVars[originalSkills[index]]?.let { skill.setPointAssigned(valueOf(it).toInt()) }
        }

        val runes =
            equippedItems
                .associateWith { equip ->
                    runeModel.runeVars[equip].orEmpty().flatMap { (stat, runeVar) ->
                        // Fold model: runeVar is a boolean pick ⇒ that one type fills ALL of the item's sockets.
                        val count =
                            if (runeModel.singleTypePerItem) {
                                if (valueOf(runeVar) > 0L) equip.maxShardSlots else 0
                            } else {
                                valueOf(runeVar).toInt()
                            }
                        val effectiveCount =
                            if (runeModel.isSuppressed(equip, stat, valueOf)) {
                                0
                            } else {
                                count
                            }
                        val rune = runeModel.runeTypeFor(runeVar, stat)
                        if (effectiveCount > 0 && rune != null) List(effectiveCount) { rune } else emptyList()
                    }
                }.filterValues { it.isNotEmpty() }

        // Sublimations keyed by carrier item: a normal sub by its assignment var, an epic/relic sub by the
        // equipped epic/relic item (whose dedicated slot hosts it). This is what the GUI renders per item.
        val epicItem = equippedItems.firstOrNull { it.rarity == Rarity.EPIC }
        val relicItem = equippedItems.firstOrNull { it.rarity == Rarity.RELIC }
        val normalCarrierItems = equippedItems.filter { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }
        var nextNormalCarrierIndex = 0
        val sublimationsByItem = mutableMapOf<Equipment, MutableList<Sublimation>>()
        for ((sub, subVar) in subModel.subVars) {
            // A cumulable NORMAL sub can be socketed multiple times: its copy count is the base var plus its copy
            // vars, and each copy lands on its OWN distinct carrier. Greedy carrier pick: any equipped ≥3-socket
            // item hosts a normal sub identically (stats come from the sub, not the item), and the model's aggregate
            // normal-carrier capacity constraint guarantees enough distinct carriers — objective-neutral. Epic/relic
            // are single-copy (never cumulable).
            val copies =
                when (sub.rarity) {
                    SublimationRarity.NORMAL ->
                        (valueOf(subVar) + subModel.copyVars[sub].orEmpty().sumOf { valueOf(it) }).toInt()
                    else -> if (valueOf(subVar) > 0L) 1 else 0
                }
            repeat(copies) {
                val carrier =
                    when (sub.rarity) {
                        SublimationRarity.NORMAL -> normalCarrierItems.getOrNull(nextNormalCarrierIndex++)
                        SublimationRarity.EPIC -> epicItem
                        SublimationRarity.RELIC -> relicItem
                    }
                if (carrier != null) sublimationsByItem.getOrPut(carrier) { mutableListOf() }.add(sub)
            }
        }

        return BuildCombination(equippedItems, optimizedSkills, runes, sublimationsByItem, resolvedPassives(params))
    }

    // Splits each skill's contribution into fixed / percent terms keyed by characteristic, mirroring
    // CharacteristicValues. The Major "% Inflicted Damage" aptitude lands in fixed[DAMAGE_INFLICTED];
    // only the max-damage objective reads that stat, so it stays inert in the most-masteries / precision
    // modes — exactly like the scorer. See the NOTE in computeCharacteristicsValues.
    internal fun buildSkillTerms(skillVars: Map<SkillCharacteristic, IntVar>): SkillTerms {
        val fixed = mutableMapOf<Characteristic, MutableList<Term>>()
        val percent = mutableMapOf<Characteristic, MutableList<Term>>()

        fun addTerm(
            char: Characteristic?,
            variable: IntVar,
            unitValue: Int,
            unitType: UnitType,
        ) {
            if (char == null || unitValue == 0) return
            val target = if (unitType == UnitType.FIXED) fixed else percent
            target.getOrPut(char) { mutableListOf() }.add(Term(variable, unitValue.toLong()))
        }

        for ((skill, variable) in skillVars) {
            when (skill) {
                is SkillCharacteristic.PairedCharacteristic -> {
                    addTerm(skill.first.characteristic, variable, skill.first.unitValue, skill.first.unitType)
                    addTerm(skill.second.characteristic, variable, skill.second.unitValue, skill.second.unitType)
                }

                else -> addTerm(skill.characteristic, variable, skill.unitValue, skill.unitType)
            }
        }

        return SkillTerms(
            fixed = fixed.mapValues { it.value.toList() },
            percent = percent.mapValues { it.value.toList() }
        )
    }

    internal fun Equipment.valueFor(char: Characteristic): Int {
        val base = characteristics[char] ?: 0
        return when (char) {
            Characteristic.ACTION_POINT -> base + (characteristics[Characteristic.MAX_ACTION_POINT] ?: 0)
            Characteristic.MOVEMENT_POINT -> base + (characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0)
            Characteristic.WAKFU_POINT -> base + (characteristics[Characteristic.MAX_WAKFU_POINTS] ?: 0)
            else -> base
        }
    }

    private fun String.toIdentifier(): String =
        lowercase()
            .replace(" ", "_")
            .replace("-", "_")

    internal fun CpModel.sumVar(
        name: String,
        vars: List<IntVar>,
        min: Long,
        max: Long,
    ): IntVar {
        if (vars.isEmpty()) return newConstant(0L)
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, LinearExpr.sum(vars.toTypedArray()))
        return sumVar
    }

    internal fun CpModel.sumVar(
        name: String,
        terms: List<Term>,
        constant: Long,
        min: Long,
        max: Long,
    ): IntVar {
        if (terms.isEmpty()) return newConstant(constant)
        val builder = LinearExpr.newBuilder().add(constant)
        terms.forEach { builder.addTerm(it.variable, it.coefficient) }
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, builder.build())
        return sumVar
    }

    private fun CpModel.applyPercent(
        value: IntVar,
        percent: IntVar,
        name: String,
    ): IntVar {
        val product = newIntVar(-PRODUCT_ABS_MAX, PRODUCT_ABS_MAX, "${name}_prod")
        addMultiplicationEquality(product, arrayOf(value, percent))

        val quotient = newIntVar(-(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1, "${name}_quot")
        addDivisionEquality(quotient, product, newConstant(100L))

        val remainder = newIntVar(-99, 99, "${name}_rem")
        addModuloEquality(remainder, product, 100L)

        val inc = newBoolVar("${name}_inc")
        addGreaterOrEqual(remainder, 50).onlyEnforceIf(inc)
        addLessOrEqual(remainder, 49).onlyEnforceIf(inc.not())

        val dec = newBoolVar("${name}_dec")
        addLessOrEqual(remainder, -51).onlyEnforceIf(dec)
        addGreaterOrEqual(remainder, -50).onlyEnforceIf(dec.not())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(inc, 1)
                .addTerm(dec, 1)
                .build(),
            1
        )

        val rounded = newIntVar(-(PRODUCT_ABS_MAX / 100) - 2, (PRODUCT_ABS_MAX / 100) + 2, "${name}_rounded")
        addEquality(
            rounded,
            LinearExpr
                .newBuilder()
                .addTerm(quotient, 1)
                .addTerm(inc, 1)
                .addTerm(dec, -1)
                .build()
        )

        val withPercent = newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, name)
        addEquality(
            withPercent,
            LinearExpr
                .newBuilder()
                .addTerm(value, 1)
                .addTerm(rounded, 1)
                .build()
        )
        return withPercent
    }

    private fun CpModel.maxVar(
        value: IntVar,
        minValue: Long,
        maxValue: Long,
        name: String,
    ): IntVar {
        val maxVar = newIntVar(minValue, maxValue, name)
        addMaxEquality(maxVar, arrayOf(value, newConstant(minValue)))
        return maxVar
    }

    /** clamp(value, low, high) as an IntVar with domain [low, high]. */
    internal fun CpModel.clampVar(
        value: IntVar,
        low: Long,
        high: Long,
        name: String,
    ): IntVar {
        val lowered = newIntVar(low, CLAMP_INTERMEDIATE_MAX, "${name}Lo")
        addMaxEquality(lowered, arrayOf(value, newConstant(low)))
        val clamped = newIntVar(low, high, name)
        addMinEquality(clamped, arrayOf(lowered, newConstant(high)))
        return clamped
    }

    private fun CpModel.bucketedIndex(
        totalActualScore: IntVar,
        totalExpectedScore: Long,
    ): Pair<IntVar, Int> {
        if (totalExpectedScore <= MAX_POWER_TABLE_INDEX) {
            return totalActualScore to totalExpectedScore.toInt()
        }

        val bucketSize = ceil(totalExpectedScore.toDouble() / MAX_POWER_TABLE_INDEX.toDouble()).toLong()
        val maxIndex = ((totalExpectedScore + bucketSize - 1) / bucketSize).toInt()
        val bucketVar = newIntVar(0, maxIndex.toLong(), "scoreBucket")
        addDivisionEquality(bucketVar, totalActualScore, newConstant(bucketSize))
        return bucketVar to maxIndex
    }

    private fun buildPowerTable(
        maxIndex: Long,
        maxMasteryAbs: Long,
    ): PowerTable {
        val maxMultiplierTarget = MAX_PENALTY_MULTIPLIER
        val maxPow = BigInteger.valueOf(maxIndex).pow(6)
        val powScale =
            if (maxPow > BigInteger.valueOf(maxMultiplierTarget)) {
                maxPow.divide(BigInteger.valueOf(maxMultiplierTarget))
            } else {
                BigInteger.ONE
            }

        val table =
            LongArray(maxIndex.toInt() + 1) { index ->
                BigInteger
                    .valueOf(index.toLong())
                    .pow(6)
                    .divide(powScale)
                    .toLong()
            }

        return PowerTable(
            values = table,
            maxValue = table.last()
        )
    }

    /**
     * Gentle power table for the survivability soft-floor: `index^SURVIVABILITY_PENALTY_POWER`, scaled so
     * the top bucket equals [MAX_SURVIVABILITY_MULTIPLIER]. Like [buildPowerTable] but with the much
     * smaller power-2 exponent, so the implied damage tax for missing the EHP floor stays mild (a build at
     * half the floor keeps ~1/4 of its score from this factor) instead of the near-veto a power-6 imposes.
     */
    private fun buildGentlePowerTable(maxIndex: Long): PowerTable {
        if (maxIndex <= 0) return PowerTable(longArrayOf(MAX_SURVIVABILITY_MULTIPLIER), MAX_SURVIVABILITY_MULTIPLIER)
        val maxPow = BigInteger.valueOf(maxIndex).pow(SURVIVABILITY_PENALTY_POWER)
        val target = BigInteger.valueOf(MAX_SURVIVABILITY_MULTIPLIER)
        val powScale = if (maxPow > target) maxPow.divide(target) else BigInteger.ONE

        val table =
            LongArray(maxIndex.toInt() + 1) { index ->
                BigInteger
                    .valueOf(index.toLong())
                    .pow(SURVIVABILITY_PENALTY_POWER)
                    .divide(powScale)
                    .toLong()
            }
        return PowerTable(values = table, maxValue = table.last().coerceAtLeast(1L))
    }

    private fun safeMultiply(
        a: Long,
        b: Long,
    ): Long {
        val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val maxSafe = BigInteger.valueOf(Long.MAX_VALUE / 2)
        return if (product > maxSafe) (Long.MAX_VALUE / 2) else product.toLong()
    }

    /** Interval product of two reachable ranges (all four corner products; handles mixed signs). */
    internal fun mulRange(
        a: LongRange,
        b: LongRange,
    ): LongRange {
        val corners =
            longArrayOf(
                a.first * b.first,
                a.first * b.last,
                a.last * b.first,
                a.last * b.last
            )
        return corners.min()..corners.max()
    }

    internal fun ceilDivPositive(
        numerator: Long,
        denominator: Long,
    ): Long {
        require(numerator >= 0L) { "ceilDivPositive numerator must be non-negative: $numerator" }
        require(denominator > 0L) { "ceilDivPositive denominator must be positive: $denominator" }
        return if (numerator == 0L) 0L else 1L + (numerator - 1L) / denominator
    }

    private fun deterministicTimeFrom(responseStats: String): Double =
        Regex("""deterministic_time:\s*([0-9.Ee+-]+)""")
            .find(responseStats)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: Double.NaN

    private fun longStatFrom(
        responseStats: String,
        key: String,
    ): Long =
        Regex("""(?m)^\s*$key:\s*([0-9]+)""")
            .find(responseStats)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L

    private fun orderEquipments(equipmentsByItemType: Map<ItemType, List<Equipment>>): List<Equipment> =
        ItemType.entries
            .flatMap { type ->
                equipmentsByItemType[type].orEmpty().sortedBy { it.equipmentId }
            }.distinctBy { it.equipmentId }
}

/**
 * The conceptual "M-feeding" elemental-mastery stat set for a max-damage [scenario]: the generic
 * +all-elements mastery, the spell element's own mastery, the range-band mastery, plus the conditional
 * back / berserk / healing masteries. This is the OBJECTIVE / domination set — it INCLUDES the specific
 * element mastery.
 *
 * The rune sites deliberately use DIFFERENT sets and must NOT route through this helper: runes have only a
 * single GENERIC elemental-mastery rune (no per-element rune exists), so the rune-model mastery set omits
 * the specific element mastery, and `relevantRuneStats` additionally adds `MASTERY_CRITICAL`. Folding the
 * element mastery into those would inject a non-existent per-element rune and corrupt the rune model.
 */
internal fun scenarioMasteryStats(scenario: DamageScenario): List<Characteristic> =
    buildList {
        add(Characteristic.MASTERY_ELEMENTARY)
        add(scenario.element.masteryCharacteristic)
        add(scenario.rangeBand.masteryCharacteristic)
        if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
        if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) add(Characteristic.MASTERY_HEALING)
    }
