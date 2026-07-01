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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.firesInMostMasteries
import me.chosante.autobuilder.domain.perElementDiMastery
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationKind
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

object WakfuBuildSolver {
    private val logger = KotlinLogging.logger {}

    private const val STAT_ABS_MAX = 10_000_000L
    private const val PERCENT_ABS_MAX = 10_000L
    private const val PRODUCT_ABS_MAX = STAT_ABS_MAX * PERCENT_ABS_MAX
    private const val STAT_WITH_PERCENT_ABS_MAX = STAT_ABS_MAX + (PRODUCT_ABS_MAX / 100) + 10

    // MASTERY_SCORE_ABS_MAX is shared with the re-scorer — see ScoreComputationMode.kt.
    private const val MAX_POWER_TABLE_INDEX = 2_000
    private const val MAX_PENALTY_MULTIPLIER = 1_000_000L
    private const val MAX_SUBLIMATIONS = 10L // Wakfu: at most 10 sublimations on a build (incl. ≤1 epic, ≤1 relic).
    private const val NORMAL_SUB_SOCKET_COST = 3L // a normal sublimation needs a 3-socket carrier for its ordered colour pattern.

    // Out-of-combat hardcaps (Wakfu): the equipped sheet can't exceed these. In-combat bonuses — including
    // start-of-combat sublimations — may go beyond, so the cap is on the PRE-sublimation value.
    private const val MAX_OUT_OF_COMBAT_AP = 16L
    private const val MAX_OUT_OF_COMBAT_MP = 8L
    private const val MAX_OUT_OF_COMBAT_WP = 20L
    private const val MIN_OUT_OF_COMBAT_CRIT = -9L // negative-crit gear is condition-limited to ≥ −9% total.

    // Bounds for the max-damage objective's nonlinear terms. Masteries / DI are clamped into these
    // (well above any real build) so the CP-SAT multiplication variables keep small, stable domains.
    // DAMAGE_DI_FLOOR / DAMAGE_DI_MAX are shared with the re-scorers — see ScoreComputationMode.kt.
    private const val DAMAGE_MASTERY_MAX = 100_000L
    private const val CLAMP_INTERMEDIATE_MAX = 8_000_000_000L
    private const val DAMAGE_GRAW_MAX = 400L * DAMAGE_MASTERY_MAX + 100L * (DAMAGE_MASTERY_MAX * 6)
    private const val DAMAGE_SCORE_ABS_MAX = (100L + DAMAGE_DI_MAX) * DAMAGE_GRAW_MAX

    // Spell-aware / boss-aware per-turn damage (max-damage mode only). The per-turn value is
    // `(throughput × perHit) × resFactor`, scaled to keep every CP-SAT variable domain modest (≤ ~6e13,
    // well inside int64 so presolve never overflows) while preserving ranking resolution. The per-hit
    // core is first divided by [PERHIT_DOWNSCALE] (keeps ~5M levels — fine even for low-level builds),
    // then the `× resFactor` product is divided by [FINAL_DOWNSCALE] so the value — and then the
    // power-6 constraint penalty (× MAX_PENALTY_MULTIPLIER) — stays under Long.MAX/2.
    private const val MAX_ROTATION_AP = 20L

    // Min wall-clock gap between intermediate best-so-far emissions. Each emission re-runs the heavy
    // solutionToBuild + scoreFor (a knapsack rotation in max-damage) ON the native solve thread, stealing
    // cycles from search/proof. Intermediate snapshots are pure progress — re-rendering the in-flight build
    // more than ~twice a second has no UX value — so coalescing to one per 500ms returns those cycles to the
    // solver without affecting the result: the FINAL build is recomputed unconditionally after solve() and
    // delivered via a guaranteed (suspending) send, so throttling intermediates can never drop or reorder it.
    private const val INTERMEDIATE_EMIT_THROTTLE_MS = 500L
    private const val PER_TURN_THROUGHPUT_MAX = 60_000L
    private const val RES_FACTOR_MIN = 10L // res capped at +90% → factor ≥ 10
    private const val RES_FACTOR_MAX = 200L // weakness floored at −100% → factor ≤ 200
    private const val PERHIT_DOWNSCALE = 100_000L
    private const val PERHIT_SCALED_MAX = DAMAGE_SCORE_ABS_MAX / PERHIT_DOWNSCALE + 1 // ≈ 5.1e6
    private const val ROTATION_RAW_MAX = PER_TURN_THROUGHPUT_MAX * PERHIT_SCALED_MAX // ≈ 3.06e11
    private const val ROTATION_RAW_RES_MAX = ROTATION_RAW_MAX * RES_FACTOR_MAX // ≈ 6.12e13
    private const val FINAL_DOWNSCALE = 20L
    private const val DAMAGE_PERTURN_ABS_MAX = ROTATION_RAW_RES_MAX / FINAL_DOWNSCALE // ≈ 3.06e12

    // Survivability soft-floor (Lot 5, opt-in). The effective-HP proxy EHP ≈ HP·(100+avgResist)/100 is
    // bucketed against the floor and feeds a GENTLE power-2 penalty (vs the power-6 used for hard AP/MP
    // targets) so missing the floor only *nudges* the damage objective — never dominates it. Resistance
    // is averaged over the 4 elements and capped at EHP_AVG_RESIST_CAP (Wakfu's soft resist ceiling), so
    // one extreme element can't inflate the proxy. EHP_MAX bounds the proxy's CP-SAT domain (HP·1.8); it
    // is far above any real build.
    private const val EHP_HP_MAX = 1_000_000L
    private const val EHP_AVG_RESIST_CAP = 80L
    private const val EHP_MAX = EHP_HP_MAX * (100L + EHP_AVG_RESIST_CAP) / 100L
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

    private val NON_ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    private val NEGATIVE_MASTERY_PENALTY =
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
    private fun TargetStats.scaledWeight(targetStat: TargetStat): Long = (weight(targetStat) * WEIGHT_SCALE).roundToLong()

    private val ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        )

    private val ELEMENTARY_RESISTANCES =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE,
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND
        )

    // Upper bound for the "exceed the target once everything is met" tie-breaker. Far above any
    // realistic scaled overflow, so the clamp never triggers in practice while keeping the
    // lexicographic objective (hit targets first, then maximise overflow) inside Long range.
    private const val PRECISION_OVERFLOW_BOUND = 1_000_000_000L

    private val RANDOM_RESISTANCES =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
        )

    // Per-element random lines paired with how many distinct elements each rolls onto. Used to fold
    // random masteries/resistances into specific elements exactly as the scorers do.
    private val MASTERY_RANDOM_BY_COUNT =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    private val RESISTANCE_RANDOM_BY_COUNT =
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
    private fun needsItemPrefilter(targetStats: TargetStats): Boolean = targetStats.masteryElementsWanted.size > 1 || targetStats.resistanceElementsWanted.size > 1

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
    ): Flow<SolverResult<BuildCombination>> =
        callbackFlow {
            withContext(Dispatchers.IO) {
                // Domination runs only on the production (wall-clock) path: tuning == null. The deterministic
                // test path keeps the full pool so existing tests are untouched; the soundness lock toggles it.
                val built =
                    buildModel(
                        params,
                        equipmentsByItemType,
                        runes,
                        sublimations,
                        applyDomination = tuning == null,
                        maxDamageExperiment = tuning?.maxDamageExperiment ?: MaxDamageExperimentConfig.DEFAULT
                    )
                executeSolverAndEmitResults(
                    built.model,
                    params,
                    built.allEquips,
                    built.equipVars,
                    built.skillVars,
                    built.runeModel,
                    built.subModel,
                    this@callbackFlow,
                    tuning
                )
            }
            close()
            awaitClose { }
        }

    private class BuiltModel(
        val model: CpModel,
        val objective: IntVar,
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
    )

    /**
     * The characteristics to PIN to equality in the domination relation for these params — or `null` to not
     * apply the pre-filter at all. An empty set means full domination (no condition to respect).
     *
     * Applies to **all three modes** — each maximizes an objective that is **monotone non-decreasing in every
     * characteristic** (more of any stat is never worse), so an item beaten on every stat is never needed:
     *  - max-damage: `throughput × perHit`, both ≥ 0;
     *  - precision: a sum of `min(actual, target)` terms — capped at the target, so overshoot is *neutral*, not
     *    penalized (my earlier "penalizes overshoot" claim was wrong);
     *  - most-masteries: `masteryScore × penaltyMultiplier`, the penalty CAPPED at the target (shortfall only)
     *    with overshoot rewarded by a tie-breaker. The product is monotone *where it matters*: the optimum
     *    always has `masteryScore ≥ 0` (the empty build already scores objective ≥ 0, so a negative-mastery
     *    build is strictly worse), and for `masteryScore ≥ 0` the dominance swap `(m+Δm)(μ+Δμ) ≥ mμ` holds.
     *
     * A **conditional** sublimation makes some stats non-monotone: a build may keep a weaker (e.g. low-secondary)
     * item *specifically* to satisfy a cap like `SECONDARY_MASTERIES_AT_MOST`, which domination would remove.
     * Rather than gate off, we pin **every stat a dangerous (≤ / exact / parity) condition reads** to equality,
     * so the swap can't move that stat's build sum and no sub can flip — while domination still fires across
     * every stat no condition touches. A `≥`-type condition stays satisfied under a `≥` swap on a beneficial
     * choosable sub, so it needs no pin. Returns `null` (gate off) for a forced item / rune-carrier, a forced
     * conditional sub (unknown effect direction), or a condition that compares two build stats / is categorical
     * and can't be reduced to a stat pin.
     */
    internal data class DominationShape(
        val pinned: Set<Characteristic>,
        val compared: Set<Characteristic>? = null,
        // Stats where LOWER is better for the swap proof, so a dominator must be `≤` (not `≥`). Used for the three
        // non-scenario elemental masteries when a best-element concentration sub (Elemental Concentration) is
        // choosable: in a single-element solve they do nothing for the scored element and only risk flipping which
        // element is "strongest", so more of them is never beneficial — see the swap proof in the sub's decode.
        val minimized: Set<Characteristic> = emptySet(),
    )

    internal fun dominationShape(
        params: WakfuBestBuildParams,
        sublimations: List<Sublimation>,
    ): DominationShape? {
        if (params.forcedItems.isNotEmpty() || params.forcedRunesByItem.isNotEmpty()) return null
        val forcedNames = params.forcedSublimations.map { it.lowercase() }.toSet()
        val pinned = mutableSetOf<Characteristic>()
        val conditionStats = mutableSetOf<Characteristic>()
        val subStats = mutableSetOf<Characteristic>()
        for (sub in sublimations) {
            val choosable = sub.solverChoosable && params.useSublimations
            val forced = sub.name.fr.lowercase() in forcedNames || sub.name.en.lowercase() in forcedNames
            if (!choosable && !forced) continue
            sub.effects
                .filter { scenarioGateMatches(it.scenarioGate, params) }
                .forEach { subStats += effectiveStatForDomination(it.characteristic) }
            sub.conversion?.let { conversion ->
                subStats += effectiveStatForDomination(conversion.from)
                subStats += effectiveStatForDomination(conversion.to)
            }
            val condition = sub.condition ?: continue
            if (forced) return null // forced conditional sub: unknown effect direction ⇒ can't pin soundly
            when (condition.type) {
                SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT, SublimationConditionType.AP_ODD -> {
                    pinned += Characteristic.ACTION_POINT
                    conditionStats += Characteristic.ACTION_POINT
                }
                SublimationConditionType.CRIT_AT_MOST -> {
                    pinned += Characteristic.CRITICAL_HIT
                    conditionStats += Characteristic.CRITICAL_HIT
                }
                SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> {
                    pinned += Characteristic.MASTERY_CRITICAL
                    conditionStats += Characteristic.MASTERY_CRITICAL
                }
                SublimationConditionType.RANGE_AT_MOST, SublimationConditionType.RANGE_EXACT -> {
                    pinned += Characteristic.RANGE
                    conditionStats += Characteristic.RANGE
                }
                SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> {
                    pinned += Characteristic.DODGE
                    conditionStats += Characteristic.DODGE
                }
                SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> {
                    pinned += SECONDARY_MASTERY_CHARACTERISTICS
                    conditionStats += SECONDARY_MASTERY_CHARACTERISTICS
                }
                // ≥-type: a ≥ swap on a beneficial choosable sub keeps the condition satisfied ⇒ no pin needed.
                SublimationConditionType.AP_AT_LEAST -> conditionStats += Characteristic.ACTION_POINT
                SublimationConditionType.CRIT_AT_LEAST -> conditionStats += Characteristic.CRITICAL_HIT
                SublimationConditionType.BLOCK_AT_LEAST -> conditionStats += Characteristic.BLOCK_PERCENTAGE
                SublimationConditionType.RANGE_AT_LEAST -> conditionStats += Characteristic.RANGE
                // Compares two build stats / categorical / slot-based / unknown ⇒ can't reduce to a stat pin ⇒ gate off.
                SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR, SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING,
                SublimationConditionType.WEAPON_TYPE_EQUIPPED, SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED,
                SublimationConditionType.OTHER,
                -> return null
            }
        }
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            return DominationShape(pinned)
        }

        // Max-damage does not care about every sheet stat. Comparing only stats that can affect the objective,
        // out-of-combat caps, sublimation conditions/effects/conversions, or random-element folding makes
        // domination much sharper while preserving the swap proof.
        val scenario = params.damageScenario
        val compared =
            buildSet {
                add(Characteristic.ACTION_POINT)
                add(Characteristic.MOVEMENT_POINT)
                add(Characteristic.WAKFU_POINT)
                add(Characteristic.CRITICAL_HIT)
                add(Characteristic.DAMAGE_INFLICTED)
                add(Characteristic.MASTERY_CRITICAL)
                addAll(scenarioMasteryStats(scenario))
                addAll(MASTERY_RANDOM_BY_COUNT.map { it.first })
                addAll(params.targetStats.map { it.characteristic })
                addAll(conditionStats)
                addAll(subStats)
                // When the survivability soft-floor is active the objective ALSO depends on effective-HP — HP and
                // the four elemental resistances (plus their generic / random-element sources), via
                // [StatBuilder.effectiveHpVar]. Those are not damage stats, so without comparing them a
                // higher-damage / lower-EHP item would dominate and evict the item a floor-clearing build needs,
                // pruning the true (survivability-constrained) optimum. Add them so per-slot domination stays
                // optimum-preserving when the floor is on.
                if (scenario.survivabilityFloor && scenario.minEffectiveHp > 0) {
                    add(Characteristic.HP)
                    addAll(ELEMENTARY_RESISTANCES)
                    add(Characteristic.RESISTANCE_ELEMENTARY)
                    addAll(RANDOM_RESISTANCES)
                }
            }
        // The equipped sheet has hard upper caps on these stats before in-combat sublimations. Pinning them
        // prevents a domination swap from replacing a cap-safe item with a stronger but cap-breaking one.
        pinned += Characteristic.ACTION_POINT
        pinned += Characteristic.MOVEMENT_POINT
        pinned += Characteristic.WAKFU_POINT

        // Best-element concentration (Elemental Concentration) breaks item domination's monotonicity: more OFF-scenario
        // elemental mastery can COST the "+DI when your element is strongest" bonus. In a single-element solve those
        // masteries do nothing for the scored element, so a dominator having MORE of them is never beneficial — mark
        // them MINIMIZED (dominator must be ≤). Sound and cheap (3 extra compared stats). If one is also a beneficial
        // target the two directions can't be reconciled by a pin, so gate domination off for that rare request.
        val ecChoosable = sublimations.any { it.bestElementConcentration != null && it.solverChoosable && params.useSublimations }
        val minimized = mutableSetOf<Characteristic>()
        if (ecChoosable && scenario.candidateElements().size == 1) {
            val offElements = SpellElement.entries.map { it.masteryCharacteristic } - scenario.element.masteryCharacteristic
            if (offElements.any { it in compared }) return null
            minimized += offElements
        }
        return DominationShape(pinned, compared + minimized, minimized)
    }

    private fun effectiveStatForDomination(char: Characteristic): Characteristic =
        when (char) {
            Characteristic.MAX_ACTION_POINT -> Characteristic.ACTION_POINT
            Characteristic.MAX_MOVEMENT_POINT -> Characteristic.MOVEMENT_POINT
            Characteristic.MAX_WAKFU_POINTS -> Characteristic.WAKFU_POINT
            else -> char
        }

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

    /** Apply [dominatedWithin] per slot — RING keeps 2 (two are co-equippable, distinct); every other slot 1. */
    private fun filterDominatedPool(
        pool: Map<ItemType, List<Equipment>>,
        pinned: Set<Characteristic>,
        compared: Set<Characteristic>? = null,
        minimized: Set<Characteristic> = emptySet(),
    ): Map<ItemType, List<Equipment>> = pool.mapValues { (slot, items) -> dominatedWithin(items, if (slot == ItemType.RING) 2 else 1, pinned, compared, minimized) }

    /**
     * Keep only items NOT dominated by ≥[k] others in the same slot (k = slot capacity). B is removable iff
     * ≥k items A satisfy `A ≽ B`, with a deterministic tie-break (A strictly better, OR equal and lower id ⇒
     * exactly one of a set of identical items is kept). RING needs k=2 because one dominator may already be
     * worn in the other ring slot — see the proof in `docs/SOLVER_PERFORMANCE.md`.
     *
     * `A ≽ B` (A can replace B in any build of a monotone mode with no loss, no extra scarce-rarity budget,
     * and no conditional-sublimation flip):
     *  - `A.maxShardSlots ≥ B` — ≥ rune capacity AND sub-carrier eligibility (sockets are a colour-agnostic
     *    count in this model), so any rune/sub on B fits A;
     *  - **(A epic ⇒ B epic) ∧ (A relic ⇒ B relic)** — the swap never RAISES the build's ≤1-epic / ≤1-relic
     *    count, so an EPIC never dominates a non-epic (keeping the non-epic may be what frees the epic budget
     *    for a stronger epic elsewhere — the one case a naive stats-only filter gets wrong);
     *  - `A.characteristics ≥ B` on EVERY characteristic, AND **`A == B` on every [pinned] stat** — so every
     *    monotone objective term / ≥-type condition is still ≥, and every pinned ≤/exact/parity condition keeps
     *    its exact truth value (its build sum is unchanged by the swap).
     */
    private fun dominatedWithin(
        items: List<Equipment>,
        k: Int,
        pinned: Set<Characteristic>,
        compared: Set<Characteristic>?,
        minimized: Set<Characteristic> = emptySet(),
    ): List<Equipment> =
        items.filter { b ->
            items.count { a ->
                a !== b &&
                    a.dominates(b, pinned, compared, minimized) &&
                    (!b.dominates(a, pinned, compared, minimized) || a.equipmentId < b.equipmentId)
            } < k
        }

    private fun Equipment.dominates(
        other: Equipment,
        pinned: Set<Characteristic>,
        compared: Set<Characteristic>?,
        minimized: Set<Characteristic> = emptySet(),
    ): Boolean {
        if (maxShardSlots < other.maxShardSlots) return false
        if (rarity == Rarity.EPIC && other.rarity != Rarity.EPIC) return false
        if (rarity == Rarity.RELIC && other.rarity != Rarity.RELIC) return false
        val chars = compared ?: (characteristics.keys + other.characteristics.keys)
        return chars.all { c ->
            val mine = characteristics.getOrDefault(c, 0)
            val theirs = other.characteristics.getOrDefault(c, 0)
            when {
                c in pinned -> mine == theirs
                c in minimized -> mine <= theirs
                else -> mine >= theirs
            }
        }
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
        // Test seam: when true, the max-damage build also runs [certifyMaxPerHitAtAp] for every AP cell and
        // stores the resulting objectives in [BuiltModel.certifierObjectivesForTest] (single-element only).
        certifyAllApForTest: Boolean = false,
    ): BuiltModel {
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
        val pool = if (activeDomination != null) filterDominatedPool(basePool, activeDomination.pinned, activeDomination.compared, activeDomination.minimized) else basePool
        val allEquips = orderEquipments(pool)
        val equipVars = model.createEquipmentVariables(allEquips)
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
        val subModel = model.createSublimationModel(params, allEquips, equipVars, sublimations)
        // A normal sublimation does NOT reserve rune sockets. Golden runes (colour-agnostic) form its ordered
        // colour pattern AND still carry their stat — doubling where the item favours that colour — so a carrier
        // keeps a full set of runes alongside the sub. Carrier eligibility (≥3-socket item) and the
        // ≤1-normal-sub-per-item cap live in createSublimationModel; rune capacity (Σ runes ≤ sockets) lives in
        // createRuneModel. The two no longer share a socket budget.

        model.addBuildValidityConstraints(allEquips, equipVars)
        model.addForcedItemsEquippedConstraints(params, allEquips, equipVars)

        var maxDamageTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var precisionTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var certifierObjectives: Map<Int, Long> = emptyMap()
        val objective =
            when (params.scoreComputationMode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                    model.buildMostMasteriesObjective(params, allEquips, equipVars, skillVars, runeModel, subModel)

                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> {
                    // Declare the precision stat chain on its reachable domains (like max-damage), instead of
                    // the loose 10M guard: every reach is a sound superset of the attainable value (locked by
                    // [precisionVarBoundsForTest]), so the optimum is unchanged while presolve / the LP
                    // relaxation work on tight bounds. tightDomains=false reproduces the loose reference build.
                    val statBuilder = StatBuilder(model, params, allEquips, equipVars, skillVars, runeModel, subModel, tight = tightDomains)
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
                            certifyForTest = certifyAllApForTest
                        )
                    val obj = model.buildMaxDamageObjective(params, statBuilder, maxDamageObjectiveCutoff)
                    maxDamageTracked = statBuilder.tracker.tracked()
                    certifierObjectives = statBuilder.certifierObjectivesForTest
                    obj
                }
            }
        model.maximize(objective)

        return BuiltModel(model, objective, allEquips, equipVars, skillVars, runeModel, subModel, maxDamageTracked, precisionTracked, certifierObjectives)
    }

    /** Configures a deterministic, machine-reproducible max-damage solver (full presolve + level-2 linearization). */
    private fun deterministicMaxDamageSolver(tuning: SolverTuning): CpSolver {
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.linearizationLevel = 2
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
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
    ): MaxDamageTimedProfile {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = applyDomination,
                maxDamageExperiment = experiment,
                maxDamageObjectiveCutoff = objectiveCutoff
            )
        objectiveCutoff?.let { built.model.addGreaterOrEqual(built.objective, it) }
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
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
            hasSolution = hasSolution
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
    ): List<MaxDamageVarBound> {
        val built = buildModel(params, equipmentsByItemType, runes, sublimations, tightDomains = false, maxDamageExperiment = tuning.maxDamageExperiment)
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

    private fun skillVariableCaps(characterSkills: CharacterSkills): Map<SkillCharacteristic, Long> {
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
     * Models runes as extra per-item allocatable stats. For each socketable equipped item and each
     * requested rune-coverable stat, an integer var counts how many runes of that stat sit in the
     * item's sockets; the per-item sum is capped at the item's socket count and forced to 0 when the
     * item is not equipped. The rune *value* per (stat, item slot, character level) is a constant
     * (best-achievable: max rune level + WakForge doubling), so runes plug straight into the stat term
     * loop in [StatBuilder.prePercentStat] and need no special-casing in the objective or scorer.
     */
    private fun CpModel.createRuneModel(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        runes: List<RuneType>,
        allowRuneFold: Boolean,
        // Stats a dangerous (≤/exact/parity) conditional sub reads (null = un-analyzable / forced). A modeled
        // rune feeding one of these makes most-masteries exact-fill unsound — see [fillSockets] below.
        subPinnedStats: Set<Characteristic>?,
        // Test seam: force the rune cap back to `≤` (no exact fill), for the exact-fill==≤ soundness lock.
        forceRuneLeq: Boolean,
    ): RuneModel {
        if (runes.isEmpty()) return RuneModel.EMPTY
        val runeById = runes.associateBy { it.id }
        val runeByCharacteristic = runes.associateBy { it.characteristic }

        // Global forced runes (CLI --forced-runes): "≥1 rune of this stat socketed somewhere".
        val forcedNames = params.forcedRunes.map { it.lowercase() }.toSet()
        val globalForcedRuneStats =
            runes
                .filter { it.name.fr.lowercase() in forcedNames || it.name.en.lowercase() in forcedNames }
                .map { it.characteristic }
                .toSet()

        // Per-item forced runes (GUI): pin a multiset of rune ids onto a specific carrier item. Keyed by
        // the item's French name (like forcedItems); resolve each id to its characteristic and count the
        // required runes per characteristic.
        val perItemForced: Map<String, Map<Characteristic, Int>> =
            params.forcedRunesByItem
                .mapKeys { (name, _) -> name.lowercase() }
                .mapValues { (_, ids) ->
                    ids
                        .mapNotNull { runeById[it]?.characteristic }
                        .groupingBy { it }
                        .eachCount()
                }.filterValues { it.isNotEmpty() }
        val perItemForcedStats = perItemForced.values.flatMapTo(mutableSetOf()) { it.keys }

        val forcedRuneStats = globalForcedRuneStats + perItemForcedStats
        // Auto-fill runes only when enabled; forced runes are modeled regardless of that toggle.
        if (!params.useRunes && forcedRuneStats.isEmpty()) return RuneModel.EMPTY

        val runeStats =
            (if (params.useRunes) relevantRuneStats(params, runeByCharacteristic.keys) else emptySet()) + forcedRuneStats
        if (runeStats.isEmpty()) return RuneModel.EMPTY

        // Exact socket fill — pin `Σ runeCount = slots·selected` (instead of `≤`) so the proven optimum never
        // leaves a socket empty. It removes every never-optimal "underfill" assignment from the integer search
        // (a pure search-space cut that helps the proof close) AND fixes the "fewer than max runes" builds.
        //  - MAX-DAMAGE: the generic elemental-mastery rune is NOT a secondary mastery and only ever raises the
        //    damage objective, so it backfills any socket without tripping a secondary/AP/crit/range "at most" sub
        //    condition, and overshooting a required target is unpenalised (the score caps actual at target —
        //    coerceAtMost in FindMaxDamageScoring). So filling is always free. (Unchanged.)
        //  - MOST-MASTERIES: the objective is monotone non-decreasing in every modeled rune stat — masteries,
        //    shortfall-only required targets, the DI fold, the min-over-elements — so underfill is never optimal.
        //    The only risk is a dangerous (≤/exact/parity) conditional sub: forcing fill could push the stat it
        //    reads over the cap and flip the sub off. But the per-stat distribution is free (mixing preserved),
        //    so as long as ONE modeled rune stat is *not* read by a dangerous condition, every socket can be
        //    filled with that "safe filler" rune (HP, elemental, …) — monotone-beneficial and breaks no sub — so
        //    exact-fill keeps the optimum. It is unsound only when EVERY modeled rune stat is dangerous (a
        //    self-contradictory request: the sole rune-relevant stat is itself the capped one). [subPinnedStats]
        //    is the dangerous set (null ⇒ un-analyzable/forced ⇒ stay safe with `≤`). Locked sound by an
        //    adversarial soundness review + the exact-fill==≤ optimum test. The single-type FOLD below stays
        //    max-damage-only, so most-masteries keeps the integer-count model (intra-item rune MIXING preserved).
        val maxDamageFreeFill =
            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
                Characteristic.MASTERY_ELEMENTARY in runeStats
        val mostMasteriesExactFill =
            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                subPinnedStats != null &&
                runeStats.any { it !in subPinnedStats }
        val fillSockets = !forceRuneLeq && (maxDamageFreeFill || mostMasteriesExactFill)
        // Single-type-per-item rune FOLD (max-damage, no forced runes, no secondary-cap>0 sub in play —
        // [allowRuneFold]): because a rune's value is uniform across an item's sockets (doubling is per item
        // SLOT, not per socket — see RuneType.valueOn), filling an item entirely with its single best-value
        // type is ≥ any mix, so mixing types within ONE item is freedom the optimum never uses. Modelling each
        // item's choice as ONE boolean pick per type (Σ pick = selected) instead of an integer count 0..slots
        // collapses the rune search to binary decisions — far easier for CP-SAT to PROVE — with the SAME
        // reachable stat contributions (a pick contributes slots·coeff; see baseTermsFor + the 0..1 leaf seed).
        // The solver still chooses the type per item (build-dependent: mastery vs crit-mastery, elemental vs a
        // secondary for the secondary=0 subs) and items still differ — only the never-optimal intra-item mix is
        // dropped. Gated to where it is provably sound; forced runes / secondary-cap>0 subs keep the count model.
        val singleTypePerItem = allowRuneFold && maxDamageFreeFill && forcedRuneStats.isEmpty()
        val maxDamageMasteryRuneStats =
            if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                buildSet {
                    val scenario = params.damageScenario
                    // Intentionally NOT scenarioMasteryStats(): runes route all elemental mastery through the
                    // single GENERIC elemental-mastery rune (no per-element rune exists), so the specific
                    // element mastery is deliberately omitted here.
                    add(Characteristic.MASTERY_ELEMENTARY)
                    add(scenario.rangeBand.masteryCharacteristic)
                    if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
                    if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
                    if (scenario.healing) add(Characteristic.MASTERY_HEALING)
                }
            } else {
                emptySet()
            }
        val maxDamageRuneChoiceCollapse =
            singleTypePerItem &&
                params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
                runeStats.all { it == Characteristic.MASTERY_CRITICAL || it in maxDamageMasteryRuneStats }
        val runeTypeByVar = HashMap<IntVar, RuneType>()
        val coefficientByVar = HashMap<IntVar, Long>()
        val extraTerms = mutableMapOf<Characteristic, MutableList<Term>>()
        val suppressedBy = HashMap<Pair<Equipment, Characteristic>, IntVar>()
        val runeVars = mutableMapOf<Equipment, Map<Characteristic, IntVar>>()
        for (equip in allEquips) {
            val slots = equip.maxShardSlots
            if (slots <= 0) continue
            if (maxDamageRuneChoiceCollapse) {
                // Pure max-damage only cares about two rune effects:
                //  - M-feeding mastery (elemental/range/back/berserk/healing all enter the same M sum);
                //  - critical mastery.
                // Pick the best M-feeding rune for this carrier, then keep the crit-mastery alternative only
                // when its carrier-specific value is larger. If crit's value is ≤ the M rune's value, M
                // dominates it for every crit rate in [0,100] because dGraw/dM = 400+crit ≥ 5*crit = dGraw/dK.
                // NOTE: this dominance is tied to perHitDamageScore's exact Graw coefficients (400·M, 5·K); if
                // those ever change, re-verify the inequality before trusting this fold.
                val choices = LinkedHashMap<Characteristic, Pair<RuneType, Long>>()
                val bestMasteryRune =
                    maxDamageMasteryRuneStats
                        .mapNotNull { runeByCharacteristic[it] }
                        .map { it to it.valueOn(equip.itemType, equip.level).toLong() }
                        .maxByOrNull { it.second }
                if (bestMasteryRune != null) {
                    choices[params.damageScenario.rangeBand.masteryCharacteristic] = bestMasteryRune
                }
                runeByCharacteristic[Characteristic.MASTERY_CRITICAL]?.let { critRune ->
                    val critValue = critRune.valueOn(equip.itemType, equip.level).toLong()
                    val masteryValue = bestMasteryRune?.second ?: 0L
                    if (critValue > masteryValue) {
                        choices[Characteristic.MASTERY_CRITICAL] = critRune to critValue
                    }
                }
                if (choices.isEmpty()) continue

                val perStat =
                    if (choices.size == 1) {
                        // The single surviving choice is forced whenever the item is equipped: substitute the
                        // equipment variable directly and skip a redundant rune bool + equality.
                        mapOf(choices.keys.single() to equipVars.getValue(equip))
                    } else if (
                        choices.size == 2 &&
                        choices.containsKey(params.damageScenario.rangeBand.masteryCharacteristic) &&
                        choices.containsKey(Characteristic.MASTERY_CRITICAL)
                    ) {
                        val masteryStat = params.damageScenario.rangeBand.masteryCharacteristic
                        val masteryChoice = choices.getValue(masteryStat)
                        val critVar = newBoolVar("runePick_${equip.equipmentId}_${Characteristic.MASTERY_CRITICAL.name}")
                        addLessOrEqual(critVar, equipVars.getValue(equip))
                        // M is the default rune on the equipment var; choosing crit suppresses that default.
                        extraTerms
                            .getOrPut(masteryStat) { mutableListOf() }
                            .add(Term(critVar, -masteryChoice.second * slots.toLong()))
                        suppressedBy[equip to masteryStat] = critVar
                        mapOf(
                            masteryStat to equipVars.getValue(equip),
                            Characteristic.MASTERY_CRITICAL to critVar
                        )
                    } else {
                        val vars = choices.keys.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
                        val pickExpr = LinearExpr.newBuilder()
                        vars.values.forEach { pickExpr.addTerm(it, 1L) }
                        pickExpr.addTerm(equipVars.getValue(equip), -1L)
                        addEquality(pickExpr.build(), 0L)
                        vars
                    }
                for ((stat, choice) in choices) {
                    val v = perStat.getValue(stat)
                    runeTypeByVar[v] = choice.first
                    coefficientByVar[v] = choice.second
                }
                runeVars[equip] = perStat
            } else if (singleTypePerItem) {
                // One boolean per type; exactly one type fills all the item's sockets when equipped, none otherwise.
                val perStat = runeStats.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
                val pickExpr = LinearExpr.newBuilder()
                perStat.values.forEach { pickExpr.addTerm(it, 1L) }
                pickExpr.addTerm(equipVars.getValue(equip), -1L)
                addEquality(pickExpr.build(), 0L)
                runeVars[equip] = perStat
            } else {
                val perStat = runeStats.associateWith { stat -> newIntVar(0, slots.toLong(), "rune_${equip.equipmentId}_${stat.name}") }
                // Sockets only count when the item is equipped: Σ runeCount {= max-damage | ≤ other modes} slots·selected.
                val capExpr = LinearExpr.newBuilder()
                perStat.values.forEach { capExpr.addTerm(it, 1L) }
                capExpr.addTerm(equipVars.getValue(equip), -slots.toLong())
                if (fillSockets) addEquality(capExpr.build(), 0L) else addLessOrEqual(capExpr.build(), 0L)
                runeVars[equip] = perStat
            }
        }
        // Global forced runes must be socketed at least once across the build.
        for (stat in globalForcedRuneStats) {
            val countExpr = LinearExpr.newBuilder()
            var any = false
            for ((_, perStat) in runeVars) {
                perStat[stat]?.let {
                    countExpr.addTerm(it, 1L)
                    any = true
                }
            }
            if (any) addGreaterOrEqual(countExpr.build(), 1L)
        }
        // Per-item forced runes: for each named carrier, the rune-count var(s) for the equipped item
        // matching that name must reach the required count. We sum over every same-named candidate (only
        // one can be equipped, and a non-equipped item's rune vars are pinned to 0 by the socket cap), so
        // this both pins the runes onto that item AND forces one such item to be equipped.
        for ((name, byCharacteristic) in perItemForced) {
            val matching = allEquips.filter { it.name.fr.lowercase() == name && it.maxShardSlots > 0 }
            if (matching.isEmpty()) continue
            for ((stat, count) in byCharacteristic) {
                val countExpr = LinearExpr.newBuilder()
                var any = false
                for (equip in matching) {
                    runeVars[equip]?.get(stat)?.let {
                        countExpr.addTerm(it, 1L)
                        any = true
                    }
                }
                if (any) addGreaterOrEqual(countExpr.build(), count.toLong())
            }
        }
        return RuneModel(runeByCharacteristic, runeVars, singleTypePerItem, runeTypeByVar, coefficientByVar, extraTerms, suppressedBy)
    }

    /**
     * The rune-coverable stats worth modelling for this request: requested stats that have a rune.
     * Elemental masteries (specific or generic) all route to the single generic elemental-mastery rune
     * (there is no per-element mastery rune); the aggregate resistance request expands to the four
     * per-element resistance runes. Mirrors the elemental folding the scorers/solver already do.
     */
    private fun relevantRuneStats(
        params: WakfuBestBuildParams,
        runeCharacteristics: Set<Characteristic>,
    ): Set<Characteristic> {
        val result = mutableSetOf<Characteristic>()
        for (targetStat in params.targetStats) {
            when (val characteristic = targetStat.characteristic) {
                Characteristic.MASTERY_ELEMENTARY, in ELEMENTARY_MASTERIES -> result.add(Characteristic.MASTERY_ELEMENTARY)
                Characteristic.RESISTANCE_ELEMENTARY -> result.addAll(ELEMENTARY_RESISTANCES)
                else -> result.add(characteristic)
            }
        }
        // Max-damage mode socket-fills the masteries that drive the scenario's damage, even when they
        // are not in targetStats (which there only carry hard AP/MP/range/… constraints).
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            val scenario = params.damageScenario
            // Intentionally NOT scenarioMasteryStats(): the rune-relevant set omits the specific element
            // mastery (generic-rune routing) and adds MASTERY_CRITICAL (a crit rune does exist).
            result.add(Characteristic.MASTERY_ELEMENTARY)
            result.add(scenario.rangeBand.masteryCharacteristic)
            result.add(Characteristic.MASTERY_CRITICAL)
            if (scenario.orientation.grantsRearMastery) result.add(Characteristic.MASTERY_BACK)
            if (scenario.berserk) result.add(Characteristic.MASTERY_BERSERK)
            if (scenario.healing) result.add(Characteristic.MASTERY_HEALING)
        }
        return result.intersect(runeCharacteristics)
    }

    /** Static-conditional sublimation conditions the solver can reify against build stats (research §4a). */
    private val SUPPORTED_SUB_CONDITIONS =
        setOf(
            SublimationConditionType.AP_AT_MOST,
            SublimationConditionType.AP_AT_LEAST,
            SublimationConditionType.AP_EXACT,
            SublimationConditionType.CRIT_AT_MOST,
            SublimationConditionType.CRIT_AT_LEAST,
            SublimationConditionType.CRITICAL_MASTERY_AT_MOST,
            SublimationConditionType.BLOCK_AT_LEAST,
            SublimationConditionType.RANGE_AT_MOST,
            SublimationConditionType.RANGE_AT_LEAST,
            SublimationConditionType.RANGE_EXACT,
            SublimationConditionType.DODGE_LT_PCT_OF_LEVEL,
            SublimationConditionType.SECONDARY_MASTERIES_AT_MOST,
            SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED
        )

    /** A solver-choosable sub the engine can correctly model in this request's mode/scenario. */
    private fun isModelableSublimation(
        sub: Sublimation,
        params: WakfuBestBuildParams,
    ): Boolean {
        if (!sub.solverChoosable) return false
        // Conversions are handled by a dedicated path; static-conditionals need a supported condition.
        when (sub.kind) {
            // A conversion applies via appliesVar, which only enforces a SUPPORTED condition — a conversion carrying
            // an UNsupported condition would move stats unconditionally (over-credit), so it must stay forced-only.
            SublimationKind.CONVERSION ->
                if (sub.conversion == null || (sub.condition != null && sub.condition!!.type !in SUPPORTED_SUB_CONDITIONS)) return false
            SublimationKind.STATIC_CONDITIONAL ->
                if (sub.condition == null || sub.condition!!.type !in SUPPORTED_SUB_CONDITIONS) return false

            SublimationKind.FLAT -> {}
            SublimationKind.COMBAT_CONDITIONAL -> return false
        }
        // Backstop for the degenerate most-masteries/precision request with NO maximizable mastery to protect
        // (only AP/MP/HP/range targets). There the DI fold `mastery × (1 + DI/100)` is structurally 0 — nothing
        // for the DI factor to multiply — so a damage-reducing sub would otherwise be free for the overshoot
        // tie-breaker to grab (the old Enutrof failure mode for required-only requests). When ANY maximizable
        // mastery is requested the fold governs DI on merit, so this never fires. Max-damage models DI directly.
        if (dropsDamageWithNoMasteryToProtect(sub, params)) return false
        // Best-element concentration (Elemental Concentration): its sound model — "+DI gated so the scenario element
        // is the build's strongest" — needs a SINGLE fixed damage element to protect. That holds in max-damage with
        // one candidate element (MaxDamageSearch enumerates one element per solve). Elsewhere (multi-element / boss
        // max-damage, most-masteries, precision) it stays forced-input-only — a clean follow-up.
        if (sub.bestElementConcentration != null) {
            return params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
                params.damageScenario.candidateElements().size == 1
        }
        // It must be able to contribute *something* in this mode/scenario. A perStatStep ramp (Featherweight)
        // contributes via a build-stat-driven term (no flat effect), so it counts too.
        val hasUsableEffect = sub.effects.any { scenarioGateMatches(it.scenarioGate, params) }
        return hasUsableEffect || sub.conversion != null || sub.perStatStep != null
    }

    /**
     * True when [sub] reduces % Damage Inflicted in a non-max-damage request that maximizes **no** mastery,
     * so the DI fold can't weigh it and it must not be auto-chosen (it could only ever cut real damage). When a
     * maximizable mastery IS requested, [StatBuilder.diAdjustedPerElementMasteryScore] already prices DI in, so a −DI sub
     * is taken only when its mastery gain outweighs the loss — and this returns false.
     */
    private fun dropsDamageWithNoMasteryToProtect(
        sub: Sublimation,
        params: WakfuBestBuildParams,
    ): Boolean {
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return false
        if (params.targetStats.any { it.characteristic.isMaximizableMastery() }) return false
        val netDamageInflicted =
            sub.effects
                .filter { it.characteristic == Characteristic.DAMAGE_INFLICTED }
                .sumOf { it.magnitudeAtLevel(params.character.level) }
        return netDamageInflicted < 0
    }

    /**
     * Whether a scenario-gated effect can fire for this request. Gates are damage-scenario specific, so
     * a gated effect only counts in max-damage mode when the configured [DamageScenario] matches. Area is
     * not modeled by [DamageScenario]; it is treated as satisfiable (best-achievable). Ungated effects
     * always count.
     */
    private fun scenarioGateMatches(
        gate: me.chosante.common.ScenarioGate?,
        params: WakfuBestBuildParams,
    ): Boolean {
        if (gate == null) return true
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            // Outside max-damage there is no attack scenario, so scenario gates don't fire — except a pure element
            // gate in a mono-element most-masteries request (the build is single-element, so a "+% <element> damage"
            // sub boosts all of its damage). Orientation/berserk/range gates stay max-damage-only.
            return params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                gate.firesInMostMasteries(params.targetStats.masteryElementsWanted.keys)
        }
        val s = params.damageScenario
        gate.rangeBand?.let { if (s.rangeBand.name != it) return false }
        gate.orientation?.let { if (s.orientation.name != it) return false }
        gate.element?.let { if (s.element.name != it) return false }
        if (gate.berserk == true && !s.berserk) return false
        if (gate.ranged == true && s.rangeBand.name != "DISTANCE") return false
        gate.minCharacterLevel?.let { if (params.character.level < it) return false }
        return true
    }

    /**
     * Models the chosen/forced sublimations. Each modeled sub gets a [SublimationModel.subVars] boolean.
     * Epic/relic subs are gated to an equipped epic/relic item — their dedicated slot comes from that carrier
     * ([gateSublimationsOnCarrierItems]). Normal subs all share the same carrier eligibility in this optimistic
     * model (any equipped item with ≥3 sockets, colours rerollable), so carrier assignment collapses to one
     * aggregate capacity constraint: `selected normal subs ≤ equipped normal carriers`. That avoids a huge
     * `(sub × carrier item)` boolean grid while preserving feasibility; [solutionToBuild] assigns selected
     * normal subs greedily to equipped carriers for display. Normal subs do NOT consume rune sockets — golden
     * runes (colour-agnostic) form their ordered colour pattern while still carrying their stat, so a carrier
     * keeps a full set of runes alongside the sub. At most 10 sublimations per build. Effect contributions fold
     * into the stat term loop by [StatBuilder]; forced subs apply unconditionally (the user takes responsibility).
     *
     * A sub is only ever *chosen* when it improves the active objective. In max-damage mode that includes its
     * DI and scenario-gated effects; in most-masteries / precision modes (which don't maximize damage) a sub is
     * taken only when it raises a requested mastery or helps meet a required target — DI-only subs pay off
     * solely in max-damage mode.
     */
    private fun CpModel.createSublimationModel(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        sublimations: List<Sublimation>,
    ): SublimationModel {
        if (sublimations.isEmpty()) return SublimationModel.EMPTY
        val forcedNames = params.forcedSublimations.map { it.lowercase() }.toSet()
        val forcedSubs =
            sublimations.filter { it.name.fr.lowercase() in forcedNames || it.name.en.lowercase() in forcedNames }
        val choosableSubs =
            if (!params.useSublimations) {
                emptyList()
            } else {
                sublimations.filter { it !in forcedSubs && isModelableSublimation(it, params) }
            }
        if (forcedSubs.isEmpty() && choosableSubs.isEmpty()) return SublimationModel.EMPTY

        val subVars = LinkedHashMap<Sublimation, IntVar>()
        for (sub in forcedSubs) {
            val v = newBoolVar("subForced_${sub.stateId}")
            addEquality(v, 1L)
            subVars[sub] = v
        }
        for (sub in choosableSubs) {
            subVars[sub] = newBoolVar("sub_${sub.stateId}")
        }

        // Epic / relic sublimations can ONLY be applied to an epic / relic ITEM — the dedicated sub slot
        // comes FROM the carrier item (Wakfu: "epic Sublimations can only be applied to epic items, relic
        // only to relic items"). So Σ epicSub ≤ Σ epicItems and Σ relicSub ≤ Σ relicItems, modeled as
        // (Σ sub − Σ carrier ≤ 0). This also caps each sub at ≤1 since epic/relic items are themselves ≤1
        // (addBuildValidityConstraints). Forcing such a sub therefore forces its carrier item to be
        // equipped; with no carrier in the pool the request is correctly infeasible (it cannot be hosted).
        gateSublimationsOnCarrierItems(subVars, allEquips, equipVars, SublimationRarity.EPIC, Rarity.EPIC)
        gateSublimationsOnCarrierItems(subVars, allEquips, equipVars, SublimationRarity.RELIC, Rarity.RELIC)

        // Total cap: at most 10 sublimations on a build.
        addLessOrEqual(LinearExpr.sum(subVars.values.toTypedArray()), MAX_SUBLIMATIONS)

        // Normal subs only need aggregate carrier capacity: all modeled normal subs can go on any equipped
        // ≥3-socket item, and every such item hosts at most one normal sub. The individual carrier choice is
        // objective-neutral, so a bipartite assignment grid is unnecessary; total selected ≤ total carriers is
        // both necessary and sufficient for this complete bipartite matching.
        val normalSubs = subVars.keys.filter { it.rarity == SublimationRarity.NORMAL }
        val carrierItems = allEquips.filter { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }
        if (normalSubs.isNotEmpty()) {
            val capacity = LinearExpr.newBuilder()
            normalSubs.forEach { capacity.addTerm(subVars.getValue(it), 1L) }
            carrierItems.forEach { capacity.addTerm(equipVars.getValue(it), -1L) }
            addLessOrEqual(capacity.build(), 0L)
        }

        return SublimationModel(subVars, forcedSubs.toSet(), params.character.level)
    }

    /** Σ(subs of [subRarity]) ≤ Σ(equipped items of [itemRarity]): an epic/relic sub's slot comes from its carrier item. */
    private fun CpModel.gateSublimationsOnCarrierItems(
        subVars: Map<Sublimation, IntVar>,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        subRarity: SublimationRarity,
        itemRarity: Rarity,
    ) {
        val subsOfRarity = subVars.filterKeys { it.rarity == subRarity }.values
        if (subsOfRarity.isEmpty()) return
        val gate = LinearExpr.newBuilder()
        subsOfRarity.forEach { gate.addTerm(it, 1L) }
        allEquips.filter { it.rarity == itemRarity }.forEach { gate.addTerm(equipVars.getValue(it), -1L) }
        addLessOrEqual(gate.build(), 0L)
    }

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
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars, runeModel, subModel)
        statBuilder.applyOutOfCombatCaps()
        val targetStats = params.targetStats
        val targetCharacteristics = targetStats.map { it.characteristic }.toSet()

        // Damage-faithful proxy: maximized mastery sum × (1 + DI/100). Mirrored exactly by the re-scorer
        // (FindMostMasteriesFromInputScoring) so the solver optimum and the scored optimum stay in lockstep.
        val masteryScore = statBuilder.diAdjustedPerElementMasteryScore(targetStats, targetCharacteristics)

        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        val penalized = applyConstraintPenalty(params, statBuilder, masteryScore, MASTERY_SCORE_ABS_MAX)
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
     * Objective for "max-damage" mode: maximize expected damage for the requested [DamageScenario]
     * (Wakfu's exact formula, see [FindMaxDamageScoring]). The build-dependent core is the product
     * `D · Graw` with `D = 100 + ΣDI`, `Graw = 400·M + crit·(M + 5·criticalMastery)` and
     * `M = 100 + ΣMastery` — derived so that `D·Graw ∝ E[dmg]` (the scenario's constant Base /
     * orientation / resistance factors are dropped since they scale every build equally). Required
     * AP/MP/range/… targets are then enforced with the same shortfall penalty as most-masteries mode.
     * Unlike most-masteries this has no overshoot tie-breaker: the damage objective already strongly
     * differentiates builds, so there is no large class of objective-ties left to refine.
     */
    private fun CpModel.buildMaxDamageObjective(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
        objectiveCutoff: Long? = null,
    ): IntVar {
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
        return applyConstraintPenalty(params, statBuilder, survivableScore, DAMAGE_PERTURN_ABS_MAX).objective
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

    private data class PenalizedObjective(
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
        scope: ProducerScope<SolverResult<BuildCombination>>,
        tuning: SolverTuning?,
    ) {
        val solver = CpSolver()
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

                    // Throttle the heavy rescore: building + scoring every improving solution on the solve
                    // thread starves the search. Snapshots are best-effort progress (trySend, consumers keep
                    // only the last), so skipping some is invisible — the proven final build is emitted
                    // separately and unconditionally below. The first solution always passes (lastEmitMs = 0).
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs < INTERMEDIATE_EMIT_THROTTLE_MS) return
                    lastEmitMs = now

                    val combination = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { value(it) }
                    val actualScore = scoreFor(params, combination)

                    val progress = ((now - startTime).toDouble() / params.searchDuration.inWholeMilliseconds.toDouble() * 100).toInt()
                    scope.trySend(SolverResult(combination, actualScore, progress.coerceAtMost(100)))
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
                            isOptimal = status == com.google.ortools.sat.CpSolverStatus.OPTIMAL
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
    private fun resolvedPassives(params: WakfuBestBuildParams): List<Passive> {
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
            if (valueOf(subVar) <= 0L) continue
            val carrier =
                when (sub.rarity) {
                    // Greedy carrier pick: any equipped ≥3-socket item hosts a normal sub identically (the sub's
                    // stats come from the sub, not the item), and the model's aggregate normal-carrier capacity
                    // constraint guarantees enough distinct carriers — so this assignment is objective-neutral.
                    SublimationRarity.NORMAL -> normalCarrierItems.getOrNull(nextNormalCarrierIndex++)

                    SublimationRarity.EPIC -> epicItem
                    SublimationRarity.RELIC -> relicItem
                }
            if (carrier != null) sublimationsByItem.getOrPut(carrier) { mutableListOf() }.add(sub)
        }

        return BuildCombination(equippedItems, optimizedSkills, runes, sublimationsByItem, resolvedPassives(params))
    }

    /**
     * Tracks the **reachable** [LongRange] of each CP-SAT variable on the max-damage objective chain, so
     * every intermediate var — and therefore every McCormick product envelope — is declared with a domain
     * sized to what a real build can reach, NOT the "safe huge" `*_ABS_MAX` guards. Those guards made the
     * LP relaxation worthless (`best_bound ≈ 90× objective`, never closing) and forced a pure-branching
     * proof that timed out; reachable domains shrink every product box so CP-SAT certifies the incumbent
     * and returns `OPTIMAL`. See `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`.
     *
     * Each builder over-estimates its output range from its inputs' ranges (sound interval arithmetic);
     * an **under-estimate would silently cut the optimum**, so the arithmetic must never undershoot
     * (locked by [maxDamageVarBoundsForTest]). The old `*_ABS_MAX` constants survive only as int64
     * overflow guards via [decl]'s `coerceIn`.
     *
     * [tight] = false records the same reachable ranges but declares vars with the loose guard instead —
     * the reference model the soundness test solves against (loose domains let a solved value exceed a
     * buggy tight bound, which is exactly how an under-estimate is detected). It is also what every
     * non-max-damage objective uses, so those modes keep byte-identical (guard-sized) domains.
     */
    private class DomainTracker(
        val tight: Boolean,
    ) {
        private val ranges = LinkedHashMap<IntVar, LongRange>()
        private val names = HashMap<IntVar, String>()

        /** Seed a leaf var (equip/skill/rune/sub bool or a constant) with its exact domain. */
        fun seed(
            v: IntVar,
            range: LongRange,
            name: String,
        ): IntVar {
            ranges[v] = range
            names[v] = name
            return v
        }

        /** Record a derived var's reachable range (used by downstream [of] lookups and the soundness test). */
        fun record(
            v: IntVar,
            range: LongRange,
            name: String,
        ): IntVar {
            ranges[v] = range
            names[v] = name
            return v
        }

        /**
         * Reachable range of [v]. Untracked vars fall back to the stat-level guard `±STAT_ABS_MAX`, which is
         * a sound over-estimate for every var that can appear as a stat-sum term (item/rune/skill/sub values
         * are all within it); product operands on the damage chain are always explicitly tracked, so the
         * fallback never under-bounds a large intermediate.
         */
        fun of(v: IntVar): LongRange = ranges[v] ?: (-STAT_ABS_MAX..STAT_ABS_MAX)

        /** The `[lo, hi]` to actually declare a var with: the reachable range (clamped into the guard) when [tight], else the guard. */
        fun decl(
            reach: LongRange,
            guardLo: Long,
            guardHi: Long,
        ): Pair<Long, Long> =
            if (tight) {
                reach.first.coerceIn(guardLo, guardHi) to reach.last.coerceIn(guardLo, guardHi)
            } else {
                guardLo to guardHi
            }

        /** Every tracked var with its name and reachable range — read back by the soundness test. */
        fun tracked(): List<Triple<IntVar, String, LongRange>> = ranges.entries.map { Triple(it.key, names[it.key] ?: "?", it.value) }
    }

    private class StatBuilder(
        private val model: CpModel,
        private val params: WakfuBestBuildParams,
        private val allEquips: List<Equipment>,
        private val equipVars: Map<Equipment, IntVar>,
        private val skillVars: Map<SkillCharacteristic, IntVar>,
        private val runeModel: RuneModel,
        private val subModel: SublimationModel,
        // Reachable-domain tracking for the max-damage objective chain. [tight] = true (max-damage only)
        // declares each chain var sized to its reachable range; false reproduces the loose guard domains
        // (every other mode, and the soundness-test reference). See [DomainTracker].
        tight: Boolean = false,
        private val maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
        // Test seam: when true, [perTurnDamageScore] certifies the exact per-AP-cell max objective for every
        // AP into [certifierObjectivesForTest] (single-element only). See [certifyMaxPerHitAtAp].
        private val certifyForTest: Boolean = false,
    ) {
        val tracker = DomainTracker(tight)

        // Test seam (see [certifyForTest]): AP cell → certifier objective, or -1 where the certifier bails.
        val certifierObjectivesForTest = linkedMapOf<Int, Long>()

        private val baseValues = params.character.baseCharacteristicValues
        private val skillTerms = buildSkillTerms(skillVars)
        private val skillCaps = skillVariableCaps(params.character.characterSkills)

        // Reverse lookup of item-gated vars (equipment picks and their rune vars) → carrier item, so a stat
        // sum's reachable bound can be computed PER mutually-exclusive slot (one item per slot, two rings)
        // instead of summing every candidate. Runes need this too: a rune var can only fire when its carrier
        // item is equipped, so counting every socketable candidate at once recreates the old huge domains.
        private val carrierByVar: Map<IntVar, Equipment> =
            buildMap {
                equipVars.forEach { (equip, v) -> put(v, equip) }
                runeModel.runeVars.forEach { (equip, perStat) ->
                    perStat.values.forEach { put(it, equip) }
                }
            }
        private val subByVar: Map<IntVar, Sublimation> = subModel.subVars.entries.associate { (sub, v) -> v to sub }

        init {
            // Seed every leaf variable's exact domain so the interval arithmetic can propagate from them.
            equipVars.forEach { (equip, v) -> tracker.seed(v, 0L..1L, "equip_${equip.equipmentId}") }
            skillVars.forEach { (skill, v) -> tracker.seed(v, 0L..skillCaps.getValue(skill), "skill_${skill.name}") }
            runeModel.runeVars.forEach { (equip, perStat) ->
                // Fold ⇒ each var is a boolean PICK (0..1, contributes slots·coeff); count model ⇒ 0..slots.
                val runeHi = if (runeModel.singleTypePerItem) 1L else equip.maxShardSlots.toLong()
                perStat.forEach { (stat, v) -> tracker.seed(v, 0L..runeHi, "rune_${equip.equipmentId}_${stat.name}") }
            }
            subModel.subVars.forEach { (sub, v) -> tracker.seed(v, 0L..1L, "sub_${sub.stateId}") }
        }

        // ---- Domain-tracked variable builders (the max-damage objective chain) ----------------------
        // Each mirrors a plain CpModel helper but declares the new var from its inputs' reachable ranges
        // (via [DomainTracker]) and records its own range for downstream propagation. With tracker.tight =
        // false they declare the exact same guard-sized domains as the untracked helpers, so non-max-damage
        // objectives are unchanged.

        /** A constant, seeded as the singleton range `v..v`. */
        private fun tConst(v: Long): IntVar = tracker.seed(model.newConstant(v), v..v, "const_$v")

        /** Reachable range of a stat sum `constant + Σ coeff·var`, **per mutually-exclusive slot** for item terms. */
        private fun reachableSumDomain(
            terms: List<Term>,
            constant: Long,
        ): LongRange {
            var lo = constant
            var nonCarrierHi = constant
            // Item-gated terms: one item per slot (two per RING). Aggregate all terms for the same carrier
            // first (e.g. the item's own stat plus its rune contribution), then bound each itemType by the
            // best carrier range instead of summing every candidate. This is a sound over-estimate (it even
            // allows 2H+1H+off-hand together, which the validity rules forbid) and keeps rune-heavy stats on
            // real build-sized domains.
            val rangesByCarrier = LinkedHashMap<Equipment, LongRange>()
            val rangesBySub = LinkedHashMap<Sublimation, LongRange>()
            for (term in terms) {
                val equip = carrierByVar[term.variable]
                if (equip != null) {
                    val d = tracker.of(term.variable)
                    val scaled =
                        if (term.coefficient >= 0) {
                            d.first * term.coefficient..d.last * term.coefficient
                        } else {
                            d.last * term.coefficient..d.first * term.coefficient
                        }
                    val current = rangesByCarrier[equip] ?: (0L..0L)
                    rangesByCarrier[equip] = current.first + scaled.first..current.last + scaled.last
                } else {
                    val sub = subByVar[term.variable]
                    val d = tracker.of(term.variable)
                    val scaled = if (term.coefficient >= 0) d.first * term.coefficient..d.last * term.coefficient else d.last * term.coefficient..d.first * term.coefficient
                    if (sub != null) {
                        val current = rangesBySub[sub] ?: (0L..0L)
                        rangesBySub[sub] = current.first + scaled.first..current.last + scaled.last
                    } else {
                        lo += scaled.first
                        nonCarrierHi += scaled.last
                    }
                }
            }
            val rangesByType =
                rangesByCarrier
                    .entries
                    .groupBy({ it.key.itemType }, { it.key to it.value })
            for ((type, ranges) in rangesByType) {
                val limit = if (type == ItemType.RING) 2L else 1L
                lo += limit * minOf(0L, ranges.minOf { it.second.first })
            }
            for (range in rangesBySub.values) {
                lo += minOf(0L, range.first)
            }
            val hi = nonCarrierHi + rarityAwareUpper(rangesByType) + sublimationAwareUpper(rangesBySub)
            return lo..hi
        }

        private fun sublimationAwareUpper(rangesBySub: Map<Sublimation, LongRange>): Long {
            data class State(
                val count: Int,
                val epic: Int,
                val relic: Int,
            )

            fun rarityCounts(sub: Sublimation): Pair<Int, Int> =
                when (sub.rarity) {
                    SublimationRarity.EPIC -> 1 to 0
                    SublimationRarity.RELIC -> 0 to 1
                    else -> 0 to 0
                }

            var dp = mapOf(State(0, 0, 0) to 0L)
            for ((sub, range) in rangesBySub) {
                val value = range.last
                if (value <= 0L) continue
                val (epicCost, relicCost) = rarityCounts(sub)
                val next = dp.toMutableMap()
                for ((state, current) in dp) {
                    val count = state.count + 1
                    val epic = state.epic + epicCost
                    val relic = state.relic + relicCost
                    if (count <= MAX_SUBLIMATIONS.toInt() && epic <= 1 && relic <= 1) {
                        val candidate = State(count, epic, relic)
                        next[candidate] = maxOf(next[candidate] ?: Long.MIN_VALUE, current + value)
                    }
                }
                dp = next
            }
            return dp.values.maxOrNull()?.coerceAtLeast(0L) ?: 0L
        }

        /**
         * Upper bound for item-gated terms that respects the global ≤1 EPIC / ≤1 RELIC build budgets. The
         * equality constraints still enforce exact item choices; this only tightens the declared reachable
         * domain used by product big-Ms. Slot feasibility stays an over-estimate (notably weapon combinations
         * and same-name rings), but we stop granting every slot its own epic/relic fantasy item.
         */
        private fun rarityAwareUpper(rangesByType: Map<ItemType, List<Pair<Equipment, LongRange>>>): Long {
            data class Option(
                val value: Long,
                val epic: Int,
                val relic: Int,
            )

            fun counts(equip: Equipment): Pair<Int, Int> =
                when (equip.rarity) {
                    Rarity.EPIC -> 1 to 0
                    Rarity.RELIC -> 0 to 1
                    else -> 0 to 0
                }

            fun topByRarity(entries: List<Pair<Equipment, LongRange>>): List<Option> {
                val best = HashMap<Pair<Int, Int>, Option>()
                for ((equip, range) in entries) {
                    val value = range.last
                    if (value <= 0L) continue
                    val (epic, relic) = counts(equip)
                    val key = epic to relic
                    if (value > (best[key]?.value ?: Long.MIN_VALUE)) best[key] = Option(value, epic, relic)
                }
                return listOf(Option(0L, 0, 0)) + best.values
            }

            fun ringOptions(entries: List<Pair<Equipment, LongRange>>): List<Option> {
                val byRarity = HashMap<Pair<Int, Int>, MutableList<Pair<Equipment, Long>>>()
                for ((equip, range) in entries) {
                    val value = range.last
                    if (value <= 0L) continue
                    byRarity
                        .getOrPut(counts(equip)) { mutableListOf() }
                        .add(equip to value)
                }
                val top =
                    byRarity.mapValues { (_, values) ->
                        values
                            .sortedByDescending { it.second }
                            .take(2)
                    }
                val options = mutableListOf(Option(0L, 0, 0))
                for ((state, values) in top) {
                    values.firstOrNull()?.let { options.add(Option(it.second, state.first, state.second)) }
                }

                fun addPair(
                    leftState: Pair<Int, Int>,
                    rightState: Pair<Int, Int>,
                ) {
                    val left = top[leftState].orEmpty()
                    val right = top[rightState].orEmpty()
                    for ((leftEquip, leftValue) in left) {
                        for ((rightEquip, rightValue) in right) {
                            if (leftEquip == rightEquip) continue
                            if (maxDamageExperiment.sameNameRingBound && leftEquip.name.fr.equals(rightEquip.name.fr, ignoreCase = true)) continue
                            val epic = leftState.first + rightState.first
                            val relic = leftState.second + rightState.second
                            if (epic <= 1 && relic <= 1) options.add(Option(leftValue + rightValue, epic, relic))
                        }
                    }
                }

                val normal = 0 to 0
                val epic = 1 to 0
                val relic = 0 to 1
                addPair(normal, normal)
                addPair(normal, epic)
                addPair(normal, relic)
                addPair(epic, relic)
                return options
            }

            fun combineOptions(
                left: List<Option>,
                right: List<Option>,
            ): List<Option> {
                val options = mutableListOf<Option>()
                for (a in left) {
                    for (b in right) {
                        val epic = a.epic + b.epic
                        val relic = a.relic + b.relic
                        if (epic <= 1 && relic <= 1) options.add(Option(a.value + b.value, epic, relic))
                    }
                }
                return options
            }

            fun weaponOptions(): List<Option> {
                val oneHanded = topByRarity(rangesByType[ItemType.ONE_HANDED_WEAPONS].orEmpty())
                val offHand = topByRarity(rangesByType[ItemType.OFF_HAND_WEAPONS].orEmpty())
                val twoHanded = topByRarity(rangesByType[ItemType.TWO_HANDED_WEAPONS].orEmpty())
                // Wakfu weapon rules: either a 2H weapon, or a 1H/off-hand pair (including either side alone).
                return combineOptions(oneHanded, offHand) + twoHanded
            }

            val weaponTypes = setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS, ItemType.TWO_HANDED_WEAPONS)
            val optionSets =
                buildList {
                    for ((type, entries) in rangesByType) {
                        if (type in weaponTypes) continue
                        add(if (type == ItemType.RING) ringOptions(entries) else topByRarity(entries))
                    }
                    if (weaponTypes.any { it in rangesByType }) add(weaponOptions())
                }

            var dp = Array(2) { LongArray(2) { Long.MIN_VALUE / 4 } }
            dp[0][0] = 0L
            for (options in optionSets) {
                val next = Array(2) { LongArray(2) { Long.MIN_VALUE / 4 } }
                for (usedEpic in 0..1) {
                    for (usedRelic in 0..1) {
                        val current = dp[usedEpic][usedRelic]
                        if (current <= Long.MIN_VALUE / 8) continue
                        for (option in options) {
                            val epic = usedEpic + option.epic
                            val relic = usedRelic + option.relic
                            if (epic <= 1 && relic <= 1) next[epic][relic] = maxOf(next[epic][relic], current + option.value)
                        }
                    }
                }
                dp = next
            }
            return dp.maxOf { row -> row.maxOrNull() ?: 0L }.coerceAtLeast(0L)
        }

        /** Stat sum with a precomputed reachable [reach]; declares tight-or-guard and records [reach]. */
        private fun tSum(
            name: String,
            terms: List<Term>,
            constant: Long,
            reach: LongRange,
            guardLo: Long,
            guardHi: Long,
        ): IntVar {
            if (terms.isEmpty()) return tConst(constant)
            val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
            val v = model.newIntVar(lo, hi, name)
            val builder = LinearExpr.newBuilder().add(constant)
            terms.forEach { builder.addTerm(it.variable, it.coefficient) }
            model.addEquality(v, builder.build())
            return tracker.record(v, reach, name)
        }

        /** Stat sum whose reachable range is the naive interval sum of its (already-tracked) terms. */
        private fun tSumNaive(
            name: String,
            terms: List<Term>,
            constant: Long,
            guardLo: Long,
            guardHi: Long,
        ): IntVar {
            var reach = constant..constant
            for (term in terms) {
                val d = tracker.of(term.variable)
                val scaled = if (term.coefficient >= 0) d.first * term.coefficient..d.last * term.coefficient else d.last * term.coefficient..d.first * term.coefficient
                reach = reach.first + scaled.first..reach.last + scaled.last
            }
            return tSum(name, terms, constant, reach, guardLo, guardHi)
        }

        /** clamp(value, low, high): reachable range = the value's range clamped into `[low, high]`. */
        private fun tClamp(
            value: IntVar,
            low: Long,
            high: Long,
            name: String,
        ): IntVar {
            val v = tracker.of(value)
            val loweredReach = maxOf(low, v.first)..maxOf(low, v.last).coerceAtMost(CLAMP_INTERMEDIATE_MAX)
            val (lLo, lHi) = tracker.decl(loweredReach, low, CLAMP_INTERMEDIATE_MAX)
            val lowered = model.newIntVar(lLo, lHi, "${name}Lo")
            model.addMaxEquality(lowered, arrayOf(value, model.newConstant(low)))
            val clampedReach = v.first.coerceIn(low, high)..v.last.coerceIn(low, high)
            val (cLo, cHi) = tracker.decl(clampedReach, low, high)
            val clamped = model.newIntVar(cLo, cHi, name)
            model.addMinEquality(clamped, arrayOf(lowered, model.newConstant(high)))
            return tracker.record(clamped, clampedReach, name)
        }

        /** z = x·y, declared from the interval product of the operands' reachable ranges. */
        private fun tMul(
            name: String,
            x: IntVar,
            y: IntVar,
            guardLo: Long,
            guardHi: Long,
        ): IntVar {
            val reach = mulRange(tracker.of(x), tracker.of(y))
            val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
            val z = model.newIntVar(lo, hi, name)
            model.addMultiplicationEquality(z, arrayOf(x, y))
            return tracker.record(z, reach, name)
        }

        /**
         * z = x·b for a boolean [selector]. In tight production builds the big-M constants use [x]'s tracked
         * reachable range; in the loose reference build they use [xGuard] so the soundness probe is not itself
         * constrained by a possibly-underestimated tracked range.
         */
        private fun tBoolGate(
            name: String,
            x: IntVar,
            selector: IntVar,
            xGuard: LongRange,
        ): IntVar {
            val xReach = tracker.of(x)
            val reach = minOf(0L, xReach.first)..maxOf(0L, xReach.last)
            val guard = minOf(0L, xGuard.first)..maxOf(0L, xGuard.last)
            val (lo, hi) = tracker.decl(reach, guard.first, guard.last)
            val z = model.newIntVar(lo, hi, name)

            val m = if (tracker.tight) xReach else xGuard
            val lower = m.first
            val upper = m.last

            // z <= U·b
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(z, 1L)
                    .addTerm(selector, -upper)
                    .build(),
                0L
            )
            // z >= L·b
            model.addGreaterOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(z, 1L)
                    .addTerm(selector, -lower)
                    .build(),
                0L
            )
            // z <= x - L·(1-b)
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(z, 1L)
                    .addTerm(x, -1L)
                    .addTerm(selector, -lower)
                    .build(),
                -lower
            )
            // z >= x - U·(1-b)
            model.addGreaterOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(z, 1L)
                    .addTerm(x, -1L)
                    .addTerm(selector, -upper)
                    .build(),
                -upper
            )

            return tracker.record(z, reach, name)
        }

        /** Creates an exact one-hot encoding of [value] over the inclusive integer range [low, high]. */
        private fun tValueSelectors(
            name: String,
            value: IntVar,
            low: Long,
            high: Long,
        ): List<Pair<Long, IntVar>> {
            require(low <= high) { "empty selector domain for $name: $low..$high" }
            val selectors =
                (low..high).map { v ->
                    val selector = model.newBoolVar("${name}_$v")
                    tracker.seed(selector, 0L..1L, "${name}_$v")
                    v to selector
                }

            model.addEquality(LinearExpr.sum(selectors.map { it.second }.toTypedArray()), 1L)

            val valueExpr = LinearExpr.newBuilder()
            for ((v, selector) in selectors) {
                if (v != 0L) valueExpr.addTerm(selector, v)
            }
            model.addEquality(value, valueExpr.build())
            return selectors
        }

        /**
         * Exact replacement for table[index]·factor. The index is one-hot encoded over the table positions and
         * [factor] is gated behind the selected AP value, avoiding a generic product between an element lookup
         * and the per-hit score.
         */
        private fun tTableProduct(
            name: String,
            index: IntVar,
            table: LongArray,
            factor: IntVar,
            factorGuard: LongRange,
            productGuard: LongRange,
        ): IntVar {
            val selectors = tValueSelectors("${name}_idx", index, 0L, (table.size - 1).toLong())
            val factorReach = tracker.of(factor)
            val values = table.flatMap { throughput -> listOf(throughput * factorReach.first, throughput * factorReach.last) }
            val reach = values.minOrNull()!!..values.maxOrNull()!!
            val terms = mutableListOf<Term>()

            selectors.forEach { (indexValue, selector) ->
                val throughput = table[indexValue.toInt()]
                if (throughput == 0L) return@forEach
                val gated = tBoolGate("${name}_gate_$indexValue", factor, selector, factorGuard)
                terms.add(Term(gated, throughput))
            }

            return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
        }

        /** Exact replacement for `(offset + valueRange.first) * factor` using a binary expansion of [offset]. */
        private fun tBinaryOffsetProduct(
            name: String,
            offset: IntVar,
            valueRange: LongRange,
            factor: IntVar,
            factorGuard: LongRange,
            productGuard: LongRange,
        ): IntVar {
            val span = valueRange.last - valueRange.first
            require(span >= 0L) { "empty binary product range for $name: $valueRange" }
            val factorReach = tracker.of(factor)
            val reach = mulRange(valueRange, factorReach)
            if (span == 0L) {
                return tSum(name, listOf(Term(factor, valueRange.first)), 0L, reach, productGuard.first, productGuard.last)
            }

            val bits = mutableListOf<Pair<Long, IntVar>>()
            var weight = 1L
            while (weight <= span) {
                val bit = model.newBoolVar("${name}_bit_$weight")
                tracker.seed(bit, 0L..1L, "${name}_bit_$weight")
                bits.add(weight to bit)
                if (weight > Long.MAX_VALUE / 2L) break
                weight *= 2L
            }

            val offsetExpr = LinearExpr.newBuilder()
            bits.forEach { (bitWeight, bit) -> offsetExpr.addTerm(bit, bitWeight) }
            model.addEquality(offset, offsetExpr.build())

            val terms = mutableListOf<Term>()
            if (valueRange.first != 0L) terms.add(Term(factor, valueRange.first))
            bits.forEach { (bitWeight, bit) ->
                val gated = tBoolGate("${name}_gate_$bitWeight", factor, bit, factorGuard)
                terms.add(Term(gated, bitWeight))
            }
            return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
        }

        /**
         * Exact source expansion for `(100 + DI) * factor`, when DI is not clamped and every variable DI source
         * is boolean. Returns null when that proof obligation is not met, so callers can fall back to a generic
         * exact product encoding.
         */
        private fun tSourceExpandedDamageInflictedProduct(
            name: String,
            factor: IntVar,
            factorGuard: LongRange,
            productGuard: LongRange,
        ): IntVar? {
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
            val diReach = reachableSumDomain(diTerms, diBase)
            if (diReach.first < -DAMAGE_DI_FLOOR || diReach.last > DAMAGE_DI_MAX) return null

            var constant = 100L + diBase
            val terms = mutableListOf<Term>()
            for ((index, term) in diTerms.withIndex()) {
                val d = tracker.of(term.variable)
                if (d.first == d.last) {
                    constant += d.first * term.coefficient
                    continue
                }
                if (d.first != 0L || d.last != 1L) return null
                val gated = tBoolGate("${name}_diSrc_$index", factor, term.variable, factorGuard)
                terms.add(Term(gated, term.coefficient))
            }
            if (constant != 0L) terms.add(Term(factor, constant))

            val factorReach = tracker.of(factor)
            val reach = mulRange(100L + diReach.first..100L + diReach.last, factorReach)
            return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
        }

        /** q = num / divisor (truncated, divisor > 0), declared from num's range divided by [divisor]. */
        private fun tDiv(
            name: String,
            num: IntVar,
            divisor: Long,
            guardLo: Long,
            guardHi: Long,
        ): IntVar {
            val n = tracker.of(num)
            val reach = n.first / divisor..n.last / divisor // truncation toward zero matches CP-SAT integer division
            val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
            val q = model.newIntVar(lo, hi, name)
            model.addDivisionEquality(q, num, model.newConstant(divisor))
            return tracker.record(q, reach, name)
        }

        /** target = table[index]: reachable range = `[min(table), max(table)]`. */
        private fun tElement(
            name: String,
            index: IntVar,
            table: LongArray,
        ): IntVar {
            val reach = table.min()..table.max()
            val (lo, hi) = tracker.decl(reach, 0L, table.max().coerceAtLeast(0L))
            val target = model.newIntVar(lo, hi, name)
            model.addElement(index, table, target)
            return tracker.record(target, reach, name)
        }

        /** withPercent = value + round(value·percent / 100): mirrors [applyPercent], tightening every var. */
        private fun tPercent(
            value: IntVar,
            percent: IntVar,
            name: String,
        ): IntVar {
            val productReach = mulRange(tracker.of(value), tracker.of(percent))
            val (pLo, pHi) = tracker.decl(productReach, -PRODUCT_ABS_MAX, PRODUCT_ABS_MAX)
            val product = model.newIntVar(pLo, pHi, "${name}_prod")
            model.addMultiplicationEquality(product, arrayOf(value, percent))

            val quotientReach = productReach.first / 100..productReach.last / 100
            val (qLo, qHi) = tracker.decl(quotientReach, -(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1)
            val quotient = model.newIntVar(qLo, qHi, "${name}_quot")
            model.addDivisionEquality(quotient, product, model.newConstant(100L))

            val remainder = model.newIntVar(-99, 99, "${name}_rem")
            model.addModuloEquality(remainder, product, 100L)

            val inc = model.newBoolVar("${name}_inc")
            model.addGreaterOrEqual(remainder, 50).onlyEnforceIf(inc)
            model.addLessOrEqual(remainder, 49).onlyEnforceIf(inc.not())

            val dec = model.newBoolVar("${name}_dec")
            model.addLessOrEqual(remainder, -51).onlyEnforceIf(dec)
            model.addGreaterOrEqual(remainder, -50).onlyEnforceIf(dec.not())

            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(inc, 1)
                    .addTerm(dec, 1)
                    .build(),
                1
            )

            val roundedReach = quotientReach.first - 1..quotientReach.last + 1
            val (rLo, rHi) = tracker.decl(roundedReach, -(PRODUCT_ABS_MAX / 100) - 2, (PRODUCT_ABS_MAX / 100) + 2)
            val rounded = model.newIntVar(rLo, rHi, "${name}_rounded")
            model.addEquality(
                rounded,
                LinearExpr
                    .newBuilder()
                    .addTerm(quotient, 1)
                    .addTerm(inc, 1)
                    .addTerm(dec, -1)
                    .build()
            )

            val withReach = tracker.of(value).first + roundedReach.first..tracker.of(value).last + roundedReach.last
            val (wLo, wHi) = tracker.decl(withReach, -STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX)
            val withPercent = model.newIntVar(wLo, wHi, name)
            model.addEquality(
                withPercent,
                LinearExpr
                    .newBuilder()
                    .addTerm(value, 1)
                    .addTerm(rounded, 1)
                    .build()
            )
            return tracker.record(withPercent, withReach, name)
        }

        private val prePercentCache = mutableMapOf<Characteristic, IntVar>()
        private val preSubCache = mutableMapOf<Characteristic, IntVar>()
        private val preCombatCache = mutableMapOf<Characteristic, IntVar>()

        // Per-solve memo for the (scenario-pure) max-damage pre-mastery term list. damagePreMasteryTerms is
        // called ~3× per perHitDamageScore (once via damagePreMastery, twice via damageMasteryCriticalReach)
        // and rebuilds the full per-equipment random-mastery fold each time. The result depends only on the
        // scenario and references already-created vars (it mints none), so caching it is value-identical. The
        // cached LinearTermSum.terms list is read-only at every consumer; do not mutate it in place.
        private val damagePreMasteryTermsCache = mutableMapOf<DamageScenario, LinearTermSum?>()
        private val actualCache = mutableMapOf<Characteristic, IntVar>()
        private val elementCache = mutableMapOf<Pair<Characteristic, List<Characteristic>>, Map<Characteristic, IntVar>>()
        private val appliesVarCache = mutableMapOf<Sublimation, IntVar>()

        // The PERMANENT (out-of-combat / character-sheet) sublimation contributions — the subset of FLAT-sub
        // effects flagged [SublimationEffect.appliesBeforeCombat]. These are present BEFORE combat starts, so
        // they (and only they) feed build-static start-of-combat conditions via [preCombatStat]: a permanent
        // +crit (Influence II) counts toward another sub's CRIT_AT_MOST, while a start-of-combat / conditional
        // +crit (Secondary Devastation II, Ambition) does not. Gated by the raw subVar (FLAT subs have no
        // condition), so referencing it from [reifyCondition] never recurses through [appliesVar]. Built BEFORE
        // [subTermsByStat] because that map's init reifies conditions, which read [preCombatStat] → this map.
        private val permanentSubTermsByStat: Map<Characteristic, List<Term>> = buildPermanentSubTerms()

        // Per-element DI sub contributions (Brûlure/Gel/Tellurisme/Ventilation) routed by their OWN element's
        // mastery, in most-masteries mode only — kept OUT of the global DAMAGE_INFLICTED so a "+12% fire damage"
        // sub multiplies only the fire damage line of [diAdjustedPerElementMasteryScore], not water's. Populated
        // as a side effect of [buildSublimationTerms]; declared before it so it is initialized first (empty).
        private val elementDiTermsByMastery = mutableMapOf<Characteristic, MutableList<Term>>()

        // Per-sub reified "scenario element is the build's strongest" boolean for best-element concentration subs
        // (Elemental Concentration). Declared BEFORE [subTermsByStat] because that map's eager init
        // ([buildSublimationTerms]) reads it via [scenarioElementStrongestVar].
        private val bestElementStrongestCache = HashMap<Int, IntVar>()

        // Sublimation stat contributions folded into the term loop, grouped by the (AP/MP/WP-folded)
        // characteristic they feed. Built eagerly so prePercentStat sees them; conversions are excluded
        // here and applied by [conversionContributions]. Conditions reify against [preCombatStat] (base +
        // items + runes + skills + PERMANENT subs) to keep the constraint network acyclic.
        private val subTermsByStat: Map<Characteristic, List<Term>> = buildSublimationTerms()

        // The selected passives' flat stats ([Passive.flatStats] — the extractor's permanent + unconditional
        // + flat + positive subset, safe to fold for ANY passive), added as constants (a passive is a fixed
        // player choice, not a solver variable). The conditional/triggered part of a passive (combat state
        // the static solver can't see) is not modeled; the full loadout still rides on the build for display.
        // Grouped by the AP/MP/WP-folded stat, like [subTermsByStat].
        private val passiveTermsByStat: Map<Characteristic, List<Term>> = buildPassiveTerms()

        // Featherweight-style "per <source> above threshold, +<target> (capped)" ramps ([Sublimation.perStatStep]),
        // grouped by their target stat. Their magnitude depends on a build VARIABLE (the source stat), so unlike a
        // flat sub effect they can't be a constant term — [prePercentTermsFor] adds a memoized reified clamped var
        // ([perStatStepGatedVar]) built from actualStat(source). source ≠ target (e.g. MP vs DI), so building it
        // never re-enters the target stat's own term loop. COMBAT_CONDITIONAL subs are skipped (like the flat loop).
        private val perStatStepSpecsByTarget: Map<Characteristic, List<Sublimation>> =
            subModel.subVars.keys
                .filter { it.kind != SublimationKind.COMBAT_CONDITIONAL && it.perStatStep != null }
                .groupBy { effectiveStat(it.perStatStep!!.target) }
        private val perStatStepVarCache = HashMap<Int, IntVar>()

        fun totalActualScore(
            requiredTargets: List<TargetStat>,
            totalExpectedScore: Long,
            targetStats: TargetStats,
        ): IntVar {
            // Score each constraint as weight * clamp(actual, -target, target). Clamping *before*
            // multiplying by the (fixed-point) weight keeps the weighted domain tight (~target*weight)
            // instead of STAT_WITH_PERCENT_ABS_MAX*weight, which otherwise blows the integer domains up
            // and makes the model intractable on the full item set. The low clamp at -target is
            // faithful to the scorer: totalActualScore is floored at 1 before the penalty ratio, so a
            // constraint dragged below -target already maxes out the penalty either way.
            val contributions =
                requiredTargets.map { targetStat ->
                    val actual = requiredActualStat(targetStat.characteristic)
                    val weight = targetStats.scaledWeight(targetStat)
                    val target = targetStat.target.toLong()
                    val name = targetStat.characteristic.name

                    val cappedAtTarget = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, target.coerceAtLeast(0), "capAt_$name")
                    model.addMinEquality(cappedAtTarget, arrayOf(actual, model.newConstant(target)))

                    val clamped = model.newIntVar(-target.coerceAtLeast(0), target.coerceAtLeast(0), "clamp_$name")
                    model.addMaxEquality(clamped, arrayOf(cappedAtTarget, model.newConstant(-target)))

                    val expectedScore = target * weight
                    val contribution = model.newIntVar(-expectedScore, expectedScore, "contrib_$name")
                    model.addEquality(contribution, LinearExpr.term(clamped, weight))
                    contribution
                }

            return model.sumVar("totalActualScore", contributions, -totalExpectedScore, totalExpectedScore)
        }

        /**
         * Weighted amount by which a build *exceeds* its required targets — the raw input to the
         * lexicographic overshoot tie-breaker (see [WakfuBuildSolver.withOvershootTieBreaker]). Mirrors
         * [totalActualScore] (same `requiredActualStat` values, same per-constraint weights), but scores
         * `weight * clamp(actual - target, 0, target)`: only the part above the target, and capped at
         * one extra target's worth so the whole sum stays ≤ [totalExpectedScore] and no single stat can
         * dominate. The shared weights mean leftover skill points flow to the highest-priority stat
         * exactly like the constraints themselves decide, with no separate distribution policy to encode.
         */
        fun overshootScore(
            requiredTargets: List<TargetStat>,
            totalExpectedScore: Long,
            targetStats: TargetStats,
        ): IntVar {
            val contributions =
                requiredTargets.map { targetStat ->
                    val actual = requiredActualStat(targetStat.characteristic)
                    val weight = targetStats.scaledWeight(targetStat)
                    val target = targetStat.target.toLong().coerceAtLeast(0)
                    val name = targetStat.characteristic.name

                    val excess = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "excess_$name")
                    model.addEquality(
                        excess,
                        LinearExpr
                            .newBuilder()
                            .addTerm(actual, 1)
                            .add(-target)
                            .build()
                    )
                    val cappedExcess = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, target, "excessCap_$name")
                    model.addMinEquality(cappedExcess, arrayOf(excess, model.newConstant(target)))
                    val positiveExcess = model.newIntVar(0, target, "excessPos_$name")
                    model.addMaxEquality(positiveExcess, arrayOf(cappedExcess, model.newConstant(0L)))

                    val contribution = model.newIntVar(0, target * weight, "overshoot_$name")
                    model.addEquality(contribution, LinearExpr.term(positiveExcess, weight))
                    contribution
                }

            return model.sumVar("overshootScore", contributions, 0, totalExpectedScore)
        }

        /**
         * Models [FindClosestBuildFromInputScoring] as a CP-SAT objective: every requested stat
         * scores min(weight * actual, weight * target); the aggregate elementary stats average the
         * four elements. Two refinements mirror the scorer: a target-0 stat that ends up negative
         * halves the score, and once every target is fully met the build that exceeds the targets
         * the most ranks higher (lexicographic capped-then-overflow objective).
         */
        fun precisionScore(targetStats: TargetStats): IntVar {
            val capped = mutableListOf<IntVar>()
            val uncapped = mutableListOf<IntVar>()
            var totalExpected = 0L

            for (targetStat in targetStats) {
                val weight = targetStats.scaledWeight(targetStat)
                if (weight == 0L) continue
                val expected = targetStat.target.toLong() * weight
                val name = targetStat.characteristic.name
                val (cappedVar, uncappedVar) =
                    when (targetStat.characteristic) {
                        Characteristic.MASTERY_ELEMENTARY ->
                            averagedContribution(elementMasteryVars(ELEMENTARY_MASTERIES).values.toList(), weight, expected, name)

                        Characteristic.RESISTANCE_ELEMENTARY ->
                            averagedContribution(elementResistanceVars(ELEMENTARY_RESISTANCES).values.toList(), weight, expected, name)

                        else -> cappedContribution(foldedElementalStat(targetStat.characteristic), weight, expected, name)
                    }
                capped.add(cappedVar)
                uncapped.add(uncappedVar)
                totalExpected += expected
            }

            val totalExpectedScore = totalExpected.coerceAtLeast(1L)
            val maxWeight = (targetStats.maxOfOrNull { targetStats.scaledWeight(it) } ?: 1L).coerceAtLeast(1L)
            val bound = STAT_WITH_PERCENT_ABS_MAX * maxWeight * targetStats.size.coerceAtLeast(1)

            val cappedSum = model.sumVar("precisionCapped", capped, -bound, totalExpectedScore)
            val uncappedSum = model.sumVar("precisionUncapped", uncapped, -bound, bound)
            val penalizedCapped = negativeTargetPenalty(targetStats, cappedSum, -bound, totalExpectedScore)

            // overflow = how far the build exceeds the targets; always >= 0 because each capped term
            // is <= its uncapped term. It is only rewarded once every target is met (see fullyMet).
            val rawOverflow = model.newIntVar(0, 2 * bound, "precisionRawOverflow")
            model.addEquality(
                rawOverflow,
                LinearExpr
                    .newBuilder()
                    .addTerm(uncappedSum, 1)
                    .addTerm(cappedSum, -1)
                    .build()
            )
            val clampedOverflow = model.newIntVar(0, PRECISION_OVERFLOW_BOUND, "precisionOverflow")
            model.addMinEquality(clampedOverflow, arrayOf(rawOverflow, model.newConstant(PRECISION_OVERFLOW_BOUND)))

            val fullyMet = model.newBoolVar("precisionFullyMet")
            model.addGreaterOrEqual(penalizedCapped, totalExpectedScore).onlyEnforceIf(fullyMet)
            model.addLessOrEqual(penalizedCapped, totalExpectedScore - 1).onlyEnforceIf(fullyMet.not())

            val bonus = model.newIntVar(0, PRECISION_OVERFLOW_BOUND, "precisionBonus")
            model.addEquality(bonus, clampedOverflow).onlyEnforceIf(fullyMet)
            model.addEquality(bonus, 0L).onlyEnforceIf(fullyMet.not())

            val objective = model.newIntVar(-bound, totalExpectedScore + PRECISION_OVERFLOW_BOUND, "precisionObjective")
            model.addEquality(
                objective,
                LinearExpr
                    .newBuilder()
                    .addTerm(penalizedCapped, 1)
                    .addTerm(bonus, 1)
                    .build()
            )
            return objective
        }

        private fun cappedContribution(
            actual: IntVar,
            weight: Long,
            expected: Long,
            name: String,
        ): Pair<IntVar, IntVar> {
            val span = STAT_WITH_PERCENT_ABS_MAX * weight
            val weighted = model.newIntVar(-span, span, "precWeighted_$name")
            model.addEquality(weighted, LinearExpr.term(actual, weight))
            val cappedVar = model.newIntVar(-span, expected, "precCapped_$name")
            model.addMinEquality(cappedVar, arrayOf(weighted, model.newConstant(expected)))
            return cappedVar to weighted
        }

        private fun averagedContribution(
            elementActuals: List<IntVar>,
            weight: Long,
            expected: Long,
            name: String,
        ): Pair<IntVar, IntVar> {
            val cappedElements = mutableListOf<IntVar>()
            val uncappedElements = mutableListOf<IntVar>()
            elementActuals.forEachIndexed { index, element ->
                val (cappedVar, uncappedVar) = cappedContribution(element, weight, expected, "${name}_$index")
                cappedElements.add(cappedVar)
                uncappedElements.add(uncappedVar)
            }
            val count = elementActuals.size.coerceAtLeast(1)
            val span = STAT_WITH_PERCENT_ABS_MAX * weight * count
            val cappedSum = model.sumVar("precElemCapped_$name", cappedElements, -span, expected * count)
            val uncappedSum = model.sumVar("precElemUncapped_$name", uncappedElements, -span, span)
            val cappedAvg = model.newIntVar(-span, expected, "precElemCappedAvg_$name")
            model.addDivisionEquality(cappedAvg, cappedSum, model.newConstant(count.toLong()))
            val uncappedAvg = model.newIntVar(-span, span, "precElemUncappedAvg_$name")
            model.addDivisionEquality(uncappedAvg, uncappedSum, model.newConstant(count.toLong()))
            return cappedAvg to uncappedAvg
        }

        private fun negativeTargetPenalty(
            targetStats: TargetStats,
            cappedSum: IntVar,
            low: Long,
            high: Long,
        ): IntVar {
            val zeroTargets =
                targetStats.filter {
                    it.target == 0 &&
                        it.characteristic != Characteristic.MASTERY_ELEMENTARY &&
                        it.characteristic != Characteristic.RESISTANCE_ELEMENTARY
                }
            if (zeroTargets.isEmpty()) return cappedSum

            val flagsSum = LinearExpr.newBuilder()
            for (targetStat in zeroTargets) {
                val actual = actualStat(targetStat.characteristic)
                val isNegative = model.newBoolVar("precNeg_${targetStat.characteristic.name}")
                model.addLessOrEqual(actual, -1L).onlyEnforceIf(isNegative)
                model.addGreaterOrEqual(actual, 0L).onlyEnforceIf(isNegative.not())
                flagsSum.addTerm(isNegative, 1)
            }
            val anyNegative = model.newBoolVar("precAnyNegativeTarget0")
            val flags = flagsSum.build()
            model.addGreaterOrEqual(flags, 1L).onlyEnforceIf(anyNegative)
            model.addLessOrEqual(flags, 0L).onlyEnforceIf(anyNegative.not())

            val halved = model.newIntVar(low, high, "precHalvedCapped")
            model.addDivisionEquality(halved, cappedSum, model.newConstant(2L))

            val penalized = model.newIntVar(low, high, "precPenalizedCapped")
            model.addEquality(penalized, cappedSum).onlyEnforceIf(anyNegative.not())
            model.addEquality(penalized, halved).onlyEnforceIf(anyNegative)
            return penalized
        }

        /**
         * The "most-masteries" objective: maximize the requested masteries scaled by a damage-faithful
         * `(1 + DI/100)` factor (the same factor the max-damage objective and the real hit formula use). The
         * previous single GLOBAL fold becomes a **per-element** fold, so a per-element "+X% <element> damage"
         * sublimation (Brûlure/Gel/Tellurisme/Ventilation) multiplies ONLY its own element's damage line:
         *
         *  - BRANCH A (no element requested — `minElements` empty): one product `clamp(nonElemNeg,≥0) × (100 +
         *    globalDI) / 100`. Byte-identical (in value) to the old `finalMasteryScore × diAdjustedMasteryScore`
         *    (where `lowestElementMastery == 0`). The common distance/melee-only request stays on this path.
         *  - BRANCH B (element(s) requested): `min over e in minElements of clamp(nonElemNeg + mastery_e, ≥0) ×
         *    (100 + clamp(globalDI + elementDI_e, [-FLOOR,MAX])) / 100`. Each element's OWN per-element DI sits
         *    inside ITS factor; the weakest element governs (the existing balance philosophy). With no per-element
         *    DI all `factor_e` are equal, so by monotonicity `min_e floor(g(m_e)) == floor(g(min_e m_e))` ⇒ value
         *    reduces to BRANCH A — no-element / no-sub requests are unchanged.
         *
         * The combined DI is clamped as ONE sum `clamp(globalDI + elementDI_e)`, mirroring the old mono path
         * (which folded the per-element +12 into the global DAMAGE_INFLICTED bucket). The re-scorer
         * (FindMostMasteriesFromInputScoring) mirrors this exactly, including the per-element integer truncation.
         *
         * Each product clamps `mastery ≥ 0` before the multiply (the factor `100 + DI` is positive, so multiplying
         * a negative mastery would invert the DI incentive), divides by 100 (floor), and clamps onto
         * `[0, MASTERY_SCORE_ABS_MAX]`, so the result keeps the same domain as before ⇒ every downstream penalty /
         * overshoot bound is unchanged.
         */
        fun diAdjustedPerElementMasteryScore(
            targetStats: TargetStats,
            targetCharacteristics: Set<Characteristic>,
        ): IntVar {
            val nonElementaries =
                targetStats
                    .filter { it.characteristic in NON_ELEMENTARY_MASTERIES }
                    .map { actualStat(it.characteristic) }
            val nonElemSum = model.sumVar("nonElemMastery", nonElementaries, -MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX)

            val negativePenaltyVars =
                NEGATIVE_MASTERY_PENALTY
                    .filter { it !in targetCharacteristics }
                    .map { char ->
                        val actual = actualStat(char)
                        val negativePart = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, 0, "neg_${char.name}")
                        model.addMinEquality(negativePart, arrayOf(actual, model.newConstant(0L)))
                        negativePart
                    }
            val negativePenalty = model.sumVar("negMasteryPenalty", negativePenaltyVars, -MASTERY_SCORE_ABS_MAX, 0)

            // The element-independent base shared by every per-element damage line: non-element masteries
            // (distance/melee/crit/…) + the negative-mastery penalty.
            val nonElemNeg =
                model.sumVar(
                    "mmNonElemNeg",
                    listOf(Term(nonElemSum, 1L), Term(negativePenalty, 1L)),
                    0L,
                    -MASTERY_SCORE_ABS_MAX,
                    MASTERY_SCORE_ABS_MAX
                )

            val globalDi = model.clampVar(actualStat(Characteristic.DAMAGE_INFLICTED), -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "mmDI")
            val productBound = MASTERY_SCORE_ABS_MAX * (100L + DAMAGE_DI_MAX)

            // The shared `clamp(mastery,≥0) × factor / 100 → clamp` kernel (one bilinear product).
            fun diProduct(
                masteryTier: IntVar,
                diFactor: IntVar,
                tag: String,
            ): IntVar {
                val nonNeg = model.clampVar(masteryTier, 0L, MASTERY_SCORE_ABS_MAX, "mmNN_$tag")
                val product = model.newIntVar(0L, productBound, "mmProd_$tag")
                model.addMultiplicationEquality(product, arrayOf(nonNeg, diFactor))
                val scaled = model.newIntVar(0L, productBound / 100L, "mmScaled_$tag")
                model.addDivisionEquality(scaled, product, model.newConstant(100L))
                return model.clampVar(scaled, 0L, MASTERY_SCORE_ABS_MAX, "mmAdj_$tag")
            }

            val minElements = targetStats.masteryElementsToMinimize
            if (minElements.isEmpty()) {
                // BRANCH A: no element requested ⇒ one product on (nonElemNeg × globalFactor).
                val factor = model.sumVar("mmDiFactor", listOf(Term(globalDi, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)
                return diProduct(nonElemNeg, factor, "global")
            }

            // BRANCH B: per-element products (each with its own element DI inside its factor), then weakest-min.
            val foldElements = targetStats.masteryElementsWanted.keys.toList()
            val elementVars = elementMasteryVars(foldElements)
            val perElementAdj =
                minElements.associateWith { e ->
                    val tier =
                        model.sumVar(
                            "mmTier_${e.name}",
                            listOf(Term(nonElemNeg, 1L), Term(elementVars.getValue(e), 1L)),
                            0L,
                            -MASTERY_SCORE_ABS_MAX,
                            MASTERY_SCORE_ABS_MAX
                        )
                    // combinedDi = clamp(globalDI + elementDI_e, [-FLOOR, MAX]) — ONE clamp of the sum (mirrors mono).
                    val combinedDi =
                        model.clampVar(
                            model.sumVar(
                                "mmDiSum_${e.name}",
                                listOf(Term(globalDi, 1L), Term(elementDiVar(e), 1L)),
                                0L,
                                -DAMAGE_DI_FLOOR,
                                DAMAGE_DI_MAX + DAMAGE_DI_MAX
                            ),
                            -DAMAGE_DI_FLOOR,
                            DAMAGE_DI_MAX,
                            "mmDiClamp_${e.name}"
                        )
                    val factor =
                        model.sumVar("mmDiFactor_${e.name}", listOf(Term(combinedDi, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)
                    diProduct(tier, factor, e.name)
                }
            val result = model.newIntVar(0L, MASTERY_SCORE_ABS_MAX, "mmDiAdjustedMin")
            model.addMinEquality(result, minElements.map { perElementAdj.getValue(it) }.toTypedArray())
            return result
        }

        // The build's resolved Action Points variable (base + gear + skills), for the external-loop AP probe.
        fun actionPointVar(): IntVar = actualStat(Characteristic.ACTION_POINT)

        /**
         * Monotonic **effective-HP proxy** for the survivability soft-floor (Lot 5):
         * `EHP ≈ HP · (100 + avgResist) / 100`, with `avgResist` the average of the four elemental
         * resistances clamped to `[0, EHP_AVG_RESIST_CAP]`. This is a *linear* CP-SAT expression — exact
         * `1/(1 − res)` damage mitigation is non-linear and cannot be modeled here — so it ranks builds
         * by survivability (more HP and more resist both raise it) without being a true effective-HP.
         * Resistance is averaged (not summed) so a single high element can't masquerade as overall
         * tankiness; the cap mirrors Wakfu's soft resistance ceiling and keeps the proxy honest against
         * a few extreme resist rolls. Each element is read through [foldedElementalStat] so the generic
         * "+all elements" resistance and random-resistance gear count exactly as they do elsewhere.
         */
        fun effectiveHpVar(): IntVar {
            val hp = model.clampVar(actualStat(Characteristic.HP), 0L, EHP_HP_MAX, "ehpHp")
            val resSum =
                model.sumVar(
                    "ehpResSum",
                    ELEMENTARY_RESISTANCES.map { foldedElementalStat(it) },
                    -STAT_WITH_PERCENT_ABS_MAX,
                    STAT_WITH_PERCENT_ABS_MAX
                )
            val avgResist = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "ehpAvgRes")
            model.addDivisionEquality(avgResist, resSum, model.newConstant(ELEMENTARY_RESISTANCES.size.toLong()))
            val avgResistClamped = model.clampVar(avgResist, 0L, EHP_AVG_RESIST_CAP, "ehpAvgResClamped")

            // factor = 100 + avgResist ∈ [100, 100 + cap]; EHP = HP · factor / 100.
            val factor = model.sumVar("ehpFactor", listOf(Term(avgResistClamped, 1L)), 100L, 100L, 100L + EHP_AVG_RESIST_CAP)
            val product = model.newIntVar(0L, EHP_HP_MAX * (100L + EHP_AVG_RESIST_CAP), "ehpProduct")
            model.addMultiplicationEquality(product, arrayOf(hp, factor))
            val ehp = model.newIntVar(0L, EHP_MAX, "ehp")
            model.addDivisionEquality(ehp, product, model.newConstant(100L))
            return ehp
        }

        /**
         * Out-of-combat hardcaps: the equipped sheet — gear + skills + runes, i.e. the PRE-sublimation value,
         * since sublimations activate at start of combat — cannot exceed 16 AP / 8 MP / 20 WP. In-combat
         * bonuses (those subs, active spells, …) may push past these; they aren't part of the equipped build.
         */
        fun applyOutOfCombatCaps() {
            model.addLessOrEqual(preSubStat(Characteristic.ACTION_POINT), MAX_OUT_OF_COMBAT_AP)
            if (maxDamageExperiment.apCeiling) {
                model.addLessOrEqual(actualStat(Characteristic.ACTION_POINT), actualActionPointCeiling())
            }
            model.addLessOrEqual(preSubStat(Characteristic.MOVEMENT_POINT), MAX_OUT_OF_COMBAT_MP)
            model.addLessOrEqual(preSubStat(Characteristic.WAKFU_POINT), MAX_OUT_OF_COMBAT_WP)
            // Negative-crit gear is condition-limited: the sheet can't drop below −9% Critical Hit.
            model.addGreaterOrEqual(preSubStat(Characteristic.CRITICAL_HIT), MIN_OUT_OF_COMBAT_CRIT)
        }

        private fun actualActionPointCeiling(): Long =
            (
                MAX_OUT_OF_COMBAT_AP +
                    positiveSublimationUpper(Characteristic.ACTION_POINT) +
                    positiveUpper(passiveTermsByStat[Characteristic.ACTION_POINT].orEmpty())
            ).coerceIn(0L, MAX_ROTATION_AP)

        private fun positiveSublimationUpper(char: Characteristic): Long {
            val rangesBySub = LinkedHashMap<Sublimation, LongRange>()
            var other = 0L
            for (term in subTermsByStat[char].orEmpty()) {
                val d = tracker.of(term.variable)
                val scaled =
                    if (term.coefficient >= 0) {
                        d.first * term.coefficient..d.last * term.coefficient
                    } else {
                        d.last * term.coefficient..d.first * term.coefficient
                    }
                val sub = subByVar[term.variable]
                if (sub != null) {
                    val current = rangesBySub[sub] ?: (0L..0L)
                    rangesBySub[sub] = current.first + scaled.first..current.last + scaled.last
                } else {
                    other += scaled.last.coerceAtLeast(0L)
                }
            }
            return other + sublimationAwareUpper(rangesBySub)
        }

        private fun positiveUpper(terms: List<Term>): Long =
            terms.sumOf { term ->
                val d = tracker.of(term.variable)
                val scaled =
                    if (term.coefficient >= 0) {
                        d.first * term.coefficient..d.last * term.coefficient
                    } else {
                        d.last * term.coefficient..d.first * term.coefficient
                    }
                scaled.last.coerceAtLeast(0L)
            }

        /**
         * Spell-aware / boss-aware per-turn damage objective for [scenario] (max-damage mode only).
         *
         * For each candidate element (one fixed element, or all four when boss-aware — see
         * [DamageScenario.candidateElements]) that [clazz] actually has spells in, the value is
         * `throughput_e × perHit_e × resFactor_e`:
         *  - `perHit_e` is the existing per-hit core `D · Graw` for that element ([perHitDamageScore]);
         *  - `throughput_e` is the build-independent best base-damage castable with the build's AP — a
         *    precomputed knapsack table ([SpellRotationOptimizer.baseThroughputTable]) looked up by the
         *    AP variable. Element gating is intrinsic: a class with no spells in `e` has an all-zero
         *    table and contributes nothing;
         *  - `resFactor_e = (100 − res_e)` folds in the boss's per-element resistance (a weakness
         *    `res_e < 0` amplifies it), so the `max` over elements picks the best **playable** element
         *    given both the boss profile and the class kit — the joint equipment + element + rotation
         *    optimum. The per-hit score is divided by [DMG_DOWNSCALE] to keep the product in Long range.
         */
        fun perTurnDamageScore(
            scenario: DamageScenario,
            clazz: me.chosante.common.CharacterClass,
            objectiveCutoff: Long? = null,
        ): IntVar {
            val maxRotationAp = if (maxDamageExperiment.apCeiling) actualActionPointCeiling() else MAX_ROTATION_AP
            val apVar = tClamp(actualStat(Characteristic.ACTION_POINT), 0L, maxRotationAp, "rotationAp")
            val candidateElements = scenario.candidateElements()
            val directCutoff =
                objectiveCutoff
                    ?.takeIf { it > 0L && candidateElements.size == 1 && !maxDamageExperiment.perHitOnlyObjective }
            val perElementDamage =
                candidateElements.mapNotNull { (element, resistance) ->
                    val spells =
                        SpellCatalog.damageSpells(clazz).filter {
                            it.element ==
                                me.chosante.common.SpellElement
                                    .valueOf(element.name)
                        }
                    val table = SpellRotationOptimizer.baseThroughputTable(spells, maxRotationAp.toInt(), params.character.level)
                    if (table.all { it == 0L }) return@mapNotNull null

                    if (table.max() > PER_TURN_THROUGHPUT_MAX) {
                        System.err.println(
                            "WARN: per-turn throughput for ${element.name} (${table.max()}) exceeds the " +
                                "CP-SAT cap PER_TURN_THROUGHPUT_MAX=$PER_TURN_THROUGHPUT_MAX; the cap is binding and " +
                                "distorts the max-damage objective — raise it for this dataset."
                        )
                    }
                    val clampedTable = LongArray(table.size) { table[it].coerceAtMost(PER_TURN_THROUGHPUT_MAX) }
                    // raw = throughput[AP] · (perHit ÷ PERHIT_DOWNSCALE). The AP lookup has only 21 possible
                    // values, so encode it as a selector-gated product instead of a generic multiplication.
                    // Fold in the boss's per-element resistance, then scale down once into the penalty's range.
                    val resFactor = (100L - resistance).coerceIn(RES_FACTOR_MIN, RES_FACTOR_MAX)
                    // dGrawCutoff: push the per-turn floor down to the per-hit product `score = D·Graw`, so its step
                    // can reason per-D-value (see [perHitDamageScore]). Same exact chain as the raw/perHit floors
                    // below: raw ≥ rawLower ⟹ perHitScaled ≥ ⌈rawLower/throughput⌉ ⟹ perHit ≥ that · DOWNSCALE.
                    val dGrawCutoffLowerBound: Long? =
                        if (maxDamageExperiment.dGrawCutoff && directCutoff != null) {
                            params.maxDamageApTarget
                                ?.takeIf { it in clampedTable.indices }
                                ?.let { apTarget ->
                                    val throughput = clampedTable[apTarget]
                                    if (throughput > 0L) {
                                        val rawLower = ceilDivPositive(directCutoff * FINAL_DOWNSCALE, resFactor)
                                        ceilDivPositive(rawLower, throughput) * PERHIT_DOWNSCALE
                                    } else {
                                        null
                                    }
                                }
                        } else {
                            null
                        }
                    val perHit =
                        perHitDamageScore(
                            scenarioElementMasteryVar(element.masteryCharacteristic),
                            element.masteryCharacteristic.name,
                            scenario,
                            dGrawCutoffLowerBound
                        )
                    // Joint AM-GM product bound on perHit = D·Graw (sound for any build; tightens the McCormick
                    // independent-max looseness). Single-element only, where the scenario mastery IS this element's.
                    if (maxDamageExperiment.dGrawJointBound && candidateElements.size == 1) {
                        maxPerHitProductBound(scenario)?.let { model.addLessOrEqual(perHit, it) }
                    }
                    if (System.getenv("WAKFU_MAX_DAMAGE_DEBUG_JOINT") == "1" && candidateElements.size == 1) {
                        params.maxDamageApTarget?.let { apCapacitatedProbe(scenario, it) }
                    }
                    if (System.getenv("WAKFU_MAX_DAMAGE_INNER_FRONTIER") == "1" && candidateElements.size == 1) {
                        params.maxDamageApTarget?.let {
                            val t0 = System.nanoTime()
                            innerFrontierPrototype(scenario, it)
                            System.err.println("INNER_FRONTIER_MS ap=$it ms=${(System.nanoTime() - t0) / 1_000_000}")
                        }
                    }
                    if (System.getenv("WAKFU_MAX_DAMAGE_CERTIFIER") == "1" && candidateElements.size == 1) {
                        params.maxDamageApTarget?.takeIf { it in clampedTable.indices }?.let { ap ->
                            val t0 = System.nanoTime()
                            val maxPerHit = certifyMaxPerHitAtAp(scenario, ap)
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            val obj =
                                if (maxPerHit == Long.MAX_VALUE) {
                                    -1L
                                } else {
                                    clampedTable[ap] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE
                                }
                            // Compare `objective` against the CP-SAT cell-max from a paired AP-pinned solve; the
                            // pre-2026-06-27 lvl-245 AP16 baseline (14,003,760 via Measure III + Influence II) is
                            // stale after the sublimation pre-combat-condition fix — re-establish before pinning.
                            System.err.println("CERTIFIER ap=$ap maxPerHit=$maxPerHit objective=$obj ms=$ms")
                        }
                    }
                    if (certifyForTest && candidateElements.size == 1) {
                        for (apCell in clampedTable.indices) {
                            val maxPerHit = certifyMaxPerHitAtAp(scenario, apCell)
                            certifierObjectivesForTest[apCell] =
                                if (maxPerHit == Long.MAX_VALUE) {
                                    -1L
                                } else {
                                    clampedTable[apCell] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE
                                }
                        }
                    }
                    if (maxDamageExperiment.perHitOnlyObjective) return@mapNotNull perHit
                    val perHitScaled = tDiv("perHitScaled_${element.name}", perHit, PERHIT_DOWNSCALE, 0L, PERHIT_SCALED_MAX)
                    val raw =
                        tTableProduct(
                            "rotRaw_${element.name}",
                            apVar,
                            clampedTable,
                            perHitScaled,
                            0L..PERHIT_SCALED_MAX,
                            0L..ROTATION_RAW_MAX
                        )
                    if (maxDamageExperiment.perApRotRawCut) {
                        maxRotRawForElement(scenario, clampedTable, PERHIT_SCALED_MAX)?.let { model.addLessOrEqual(raw, it) }
                    }

                    if (directCutoff != null) {
                        val rawLower = ceilDivPositive(directCutoff * FINAL_DOWNSCALE, resFactor)
                        model.addGreaterOrEqual(raw, rawLower)
                        params.maxDamageApTarget?.takeIf { it in clampedTable.indices }?.let { apTarget ->
                            val throughput = clampedTable[apTarget]
                            if (throughput > 0L) {
                                val perHitScaledLower = ceilDivPositive(rawLower, throughput)
                                model.addGreaterOrEqual(perHitScaled, perHitScaledLower)
                                model.addGreaterOrEqual(perHit, perHitScaledLower * PERHIT_DOWNSCALE)
                            }
                        }
                    }
                    val rawWithRes = tSumNaive("rotRawRes_${element.name}", listOf(Term(raw, resFactor)), 0L, 0L, ROTATION_RAW_RES_MAX)
                    tDiv("rotDamage_${element.name}", rawWithRes, FINAL_DOWNSCALE, 0L, DAMAGE_PERTURN_ABS_MAX)
                }

            // The turn plays the single best PLAYABLE element against the boss — `max over elements`, NOT a sum
            // (an AP split across elements was measured UNPROVABLE at every arity — 2 terms still FEASIBLE at 240
            // det-time, 3 terms 311s FEASIBLE — for only a ~1.4% unproven gain; multi-element value is captured by
            // element SELECTION, this max). NOTE: a single candidate (size == 1) proves in seconds, but the
            // in-model `max` over several candidates does NOT prove on the full pool (562s FEASIBLE) — so the
            // production boss path (MaxDamageSearch) instead solves each candidate element SEPARATELY (single
            // candidate ⇒ provable) and takes the max externally. This in-model `max` is the correct but
            // slower-to-prove fallback for direct/small-pool callers. See docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md.
            return when (perElementDamage.size) {
                0 -> model.newConstant(0L)
                1 -> perElementDamage.single()
                else -> {
                    val reach = 0L..perElementDamage.maxOf { tracker.of(it).last }
                    val (lo, hi) = tracker.decl(reach, 0L, DAMAGE_PERTURN_ABS_MAX)
                    val best = model.newIntVar(lo, hi, "rotBestElement")
                    model.addMaxEquality(best, perElementDamage.toTypedArray())
                    tracker.record(best, reach, "rotBestElement")
                }
            }
        }

        /**
         * Build-dependent per-hit core `D · Graw` for an element whose folded elemental-mastery var is
         * [elementMasteryVar] (see [perTurnDamageScore]). Taking the mastery var as a parameter — rather than
         * computing it internally — lets the caller fold generic "+all elements" and random-element mastery onto
         * the element once. All masteries / DI / crit are clamped into the damage bounds so the two CP-SAT
         * multiplications stay on small, stable integer domains. Var names carry [suffix] so per-element cores
         * never collide.
         */
        private fun perHitDamageScore(
            elementMasteryVar: IntVar,
            suffix: String,
            scenario: DamageScenario,
            dGrawCutoffLowerBound: Long? = null,
        ): IntVar {
            val s = suffix
            val preM = damagePreMastery(s, elementMasteryVar, scenario)
            val m = tClamp(preM, 0L, DAMAGE_MASTERY_MAX, "dmgM_$s")
            val criticalMastery = tClamp(actualStat(Characteristic.MASTERY_CRITICAL), 0L, DAMAGE_MASTERY_MAX, "dmgCriticalMastery_$s")
            val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
            val crit = tClamp(actualStat(Characteristic.CRITICAL_HIT), 0L, critCap, "dmgCrit_$s")
            val di = tClamp(actualStat(Characteristic.DAMAGE_INFLICTED), -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "dmgDI_$s")
            val d = tSumNaive("dmgD_$s", listOf(Term(di, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)

            // diff = M + 5·criticalMastery ; term = crit · diff ; Graw = 400·M + term.
            // Crit has a tiny non-negative integer domain (0..100), so an exact selector can turn the nested
            // crit product into a sum of boolean-gated tight diff copies.
            val diffReach = damageMasteryCriticalReach(scenario, masteryWeight = 1L, criticalMasteryWeight = 5L, guardHi = DAMAGE_MASTERY_MAX * 6)
            val diff = tSum("dmgDiff_$s", listOf(Term(m, 1L), Term(criticalMastery, 5L)), 0L, diffReach, 0L, DAMAGE_MASTERY_MAX * 6)
            val critTable = LongArray(critCap.toInt() + 1) { it.toLong() }
            val term =
                when (maxDamageExperiment.critProduct) {
                    CritProductMode.TABLE ->
                        tTableProduct("dmgCritTerm_$s", crit, critTable, diff, 0L..DAMAGE_MASTERY_MAX * 6, 0L..100L * DAMAGE_MASTERY_MAX * 6)

                    CritProductMode.GENERIC ->
                        tMul("dmgCritTerm_$s", crit, diff, 0L, 100L * DAMAGE_MASTERY_MAX * 6)

                    // Binary-expand crit (0..critCap) into ~7 gated diff copies — the d-binary trick on the
                    // crit one-hot. Distinct from GENERIC (a raw tMul, which was measured slower).
                    CritProductMode.BINARY ->
                        tBinaryOffsetProduct("dmgCritTerm_$s", crit, 0L..critCap, diff, 0L..DAMAGE_MASTERY_MAX * 6, 0L..100L * DAMAGE_MASTERY_MAX * 6)
                }
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(term, 1L)
                    .addTerm(diff, -critCap)
                    .build(),
                0L
            )
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(term, 1L)
                    .addTerm(crit, -(DAMAGE_MASTERY_MAX * 6))
                    .build(),
                0L
            )
            val grawReach =
                damageMasteryCriticalReach(
                    scenario,
                    masteryWeight = 400L + critCap,
                    criticalMasteryWeight = 5L * critCap,
                    guardHi = DAMAGE_GRAW_MAX
                )
            // Graw = 400·M + crit·(M + 5·criticalMastery) with M = 100 + ΣMastery. Because M folds in the base
            // 100, Graw already equals 40000·(the per-hit multiplier of FindMaxDamageScoring.expectedDamage): the
            // 400·M term carries the base hit (400·100 = 40000) AND the flat crit bonus (crit·100 from term), so
            // `D·Graw` is exactly proportional to the real per-hit. (An earlier `grawFull = Graw + 40000 + 100·crit`
            // double-counted both and distorted the ranking — reverted.)
            val graw = tSum("dmgGraw_$s", listOf(Term(m, 400L), Term(term, 1L)), 0L, grawReach, 0L, DAMAGE_GRAW_MAX)

            val dReach = tracker.of(d)
            val dOffset =
                tSumNaive(
                    "dmgDOffset_$s",
                    listOf(Term(d, 1L)),
                    -dReach.first,
                    0L,
                    dReach.last - dReach.first
                )
            val dTable = LongArray((dReach.last - dReach.first + 1).toInt()) { dReach.first + it }
            val score =
                when (maxDamageExperiment.dProduct) {
                    DProductMode.TABLE ->
                        tTableProduct("dmgScore_$s", dOffset, dTable, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)

                    DProductMode.BINARY ->
                        tBinaryOffsetProduct("dmgScore_$s", dOffset, dReach, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)

                    DProductMode.SOURCE_DI ->
                        tSourceExpandedDamageInflictedProduct("dmgScore_$s", graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)
                            ?: tTableProduct("dmgScore_$s", dOffset, dTable, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)
                }
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(score, 1L)
                    .addTerm(graw, -dReach.last)
                    .build(),
                0L
            )
            val grawDomain = tracker.of(graw)
            model.addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(score, 1L)
                    .addTerm(graw, -dReach.first)
                    .addTerm(d, -grawDomain.last)
                    .build(),
                -dReach.first * grawDomain.last
            )

            // Cutoff (incumbent-optimality proof) ONLY: a sound lower bound `tScore` on the per-hit product
            // score = D·Graw. CP-SAT's product relaxation lets a fractional (D high)×(Graw high) meet tScore even
            // when no integer build does — high DI gear costs mastery, so it lowers Graw. Add the EXACT per-D-value
            // disjunction `(D==v) ⟹ Graw ≥ ⌈tScore/v⌉` (only over the band where it bites), plus the cheap box
            // projections `D ≥ ⌈tScore/Grawmax⌉` and `Graw ≥ ⌈tScore/Dmax⌉`. Every constraint is implied by
            // score ≥ tScore (so it never removes a feasible build), but they engage the integer item-coupling the
            // bilinear McCormick envelope misses. Fires only when dGrawCutoff is set AND a cutoff bound was derived,
            // so the normal (no-cutoff) objective — and its exactness tests — are untouched.
            if (dGrawCutoffLowerBound != null && maxDamageExperiment.dGrawCutoff) {
                val tScore = dGrawCutoffLowerBound
                val grawDom = tracker.of(graw)
                model.addGreaterOrEqual(score, tScore)
                if (grawDom.last > 0L) model.addGreaterOrEqual(d, ceilDivPositive(tScore, grawDom.last))
                if (dReach.last > 0L) model.addGreaterOrEqual(graw, ceilDivPositive(tScore, dReach.last))
                for (v in maxOf(dReach.first, 1L)..dReach.last) {
                    val grawMin = ceilDivPositive(tScore, v)
                    // Below the band the bound is free (already implied); above it `D==v` is impossible and is
                    // already pruned by the `D ≥ ⌈tScore/Grawmax⌉` box cut, so only reify the middle.
                    if (grawMin <= grawDom.first || grawMin > grawDom.last) continue
                    val isV = model.newBoolVar("dGrawCut_${suffix}_$v")
                    model.addEquality(d, v).onlyEnforceIf(isV)
                    model.addDifferent(d, model.newConstant(v)).onlyEnforceIf(isV.not())
                    model.addGreaterOrEqual(graw, grawMin).onlyEnforceIf(isV)
                }
            }
            return score
        }

        /**
         * Sound upper bound on `raw = throughput[AP] · perHitScaled`, tightening the AP-vs-mastery slack: a
         * build with high AP (more throughput) spends item slots on AP gear, which lowers mastery (so perHit),
         * but the table-product McCormick lets `throughput[high AP]` pair with `perHit[high mastery]`. Since DI
         * and mastery do NOT compete (the per-hit `D·graw` is already tight), `perHit` at AP=a is bounded by
         * `D_max · grawLinMaxAtAp(a)` with `grawLin = 500·M + 500·K ≥ graw`. The per-AP mastery max comes from a
         * Lagrangian relaxation: for any μ, `max{grawLin : AP=a} ≤ max_x(grawLin(x) − μ·AP(x)) + μ·a`, and the
         * inner max is a SINGLE [reachableSumDomain] over the μ-weighted `(grawLin − μ·AP)` terms (so all the
         * ring/rarity/sublimation coupling comes for free). min over a μ grid ⇒ sound per-AP bound. The cut is
         * `raw ≤ max_a throughput[a] · floor(D_max·grawLinUB(a) / PERHIT_DOWNSCALE)`. Null ⇒ cannot tighten.
         */
        private fun maxRotRawForElement(
            scenario: DamageScenario,
            clampedTable: LongArray,
            perHitScaledMax: Long,
        ): Long? {
            val mastery = damagePreMasteryTerms(scenario) ?: return null
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return null
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
            if (skillTerms.percent[Characteristic.ACTION_POINT].orEmpty().isNotEmpty()) return null
            val (critTerms, critBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
            val (apTerms, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)

            val grawLinTerms =
                mastery.terms.map { Term(it.variable, it.coefficient * 500L) } +
                    critTerms.map { Term(it.variable, it.coefficient * 500L) }
            val grawLinConst = 500L * mastery.constant + 500L * critBase
            val dHi = reachableSumDomain(diTerms, 100L + diBase).last.coerceAtLeast(1L)

            val grawLinGlobalHi = reachableSumDomain(grawLinTerms, grawLinConst).last.coerceAtLeast(0L)
            if (grawLinGlobalHi <= 0L) return 0L
            val apSpan = (reachableSumDomain(apTerms, apBase).last - apBase).coerceAtLeast(1L)
            val muBase = (grawLinGlobalHi / apSpan).coerceAtLeast(1L) // ≈ grawLin gained per AP point
            val muGrid =
                listOf(0L, muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4, muBase * 8)
                    .filter { it >= 0L }
                    .distinct()
            val gByMu =
                muGrid.map { mu ->
                    val combined = grawLinTerms + apTerms.map { Term(it.variable, -mu * it.coefficient) }
                    mu to reachableSumDomain(combined, grawLinConst - mu * apBase).last
                }

            var maxRotRaw = 0L
            for (a in clampedTable.indices) {
                if (clampedTable[a] == 0L) continue
                val grawLinUBa = gByMu.minOf { (mu, g) -> g + mu * a }.coerceIn(0L, grawLinGlobalHi)
                val perHitScaledUBa = clampedProductQuotient(dHi, grawLinUBa, PERHIT_DOWNSCALE, perHitScaledMax)
                maxRotRaw = maxOf(maxRotRaw, clampedTable[a] * perHitScaledUBa)
            }
            return maxRotRaw
        }

        /**
         * Sound CONSTANT upper bound on the per-hit product `score = D · Graw` (D = 100+DI, Graw ≤ grawLin =
         * 500·M + 500·K). The McCormick relaxation of the product bounds it by the INDEPENDENT maxes `Dmax ·
         * grawLinMax` — loose because high DI and high mastery COMPETE for the same slots (the best-DI item in a
         * slot is rarely the best-mastery one). For any weight μ>0 the joint reachable bound `μ·D + grawLin ≤
         * C(μ)` is a SINGLE [reachableSumDomain] over the μ-weighted `(μ·DI ∪ grawLin)` terms (so all the per-slot
         * / ring / rarity / sublimation competition is captured exactly), and by AM-GM `D·grawLin ≤ C(μ)²/(4μ)`.
         * The min over a μ grid is a sound upper bound on `score` for EVERY build — valid for the normal objective,
         * not only the cutoff proof. Null ⇒ a %-skill term makes the slot decomposition unsound (cannot tighten).
         */
        private fun maxPerHitProductBound(scenario: DamageScenario): Long? {
            val mastery = damagePreMasteryTerms(scenario) ?: return null
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return null
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
            val (critTerms, critBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)

            // graw = 400·M + crit·(M + 5·critM) with crit ≤ critCap, so the tightest crit-free upper bound is
            // (400+critCap)·M + 5·critCap·critM (NOT 500·M + 500·critM, which assumed crit ≤ 100 — far too loose
            // when the scenario caps crit well below 100).
            val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
            val masteryCoef = 400L + critCap
            val critCoef = 5L * critCap
            val grawLinTerms =
                mastery.terms.map { Term(it.variable, it.coefficient * masteryCoef) } +
                    critTerms.map { Term(it.variable, it.coefficient * critCoef) }
            val grawLinConst = masteryCoef * mastery.constant + critCoef * critBase
            val dBase = 100L + diBase
            val dHi = reachableSumDomain(diTerms, dBase).last.coerceAtLeast(1L)
            val grawLinHi = reachableSumDomain(grawLinTerms, grawLinConst).last.coerceAtLeast(1L)

            // The AM-GM bound is tightest when μ·D ≈ grawLin, i.e. μ ≈ grawLin / D; grid around it.
            val muBase = (grawLinHi / dHi).coerceAtLeast(1L)
            val muGrid = listOf(muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4).filter { it > 0L }.distinct()
            val independent = dHi * grawLinHi
            var best = independent // independent-max fallback (always valid; the cut only helps if a μ beats it)
            for (mu in muGrid) {
                val combined = diTerms.map { Term(it.variable, mu * it.coefficient) } + grawLinTerms
                val cMu = reachableSumDomain(combined, mu * dBase + grawLinConst).last
                // Clamp the AM-GM bound `cMu² / (4μ)` to the running [best] (its only role is to lower the min),
                // computed EXACTLY: a Double `cMu * cMu` loses precision past 2^53 and could round this hard
                // bound below the true per-hit max, cutting the optimum.
                best = clampedProductQuotient(cMu, cMu, 4L * mu, best)
            }
            if (System.getenv("WAKFU_MAX_DAMAGE_DEBUG_JOINT") == "1") {
                System.err.println(
                    "JOINT_BOUND_DEBUG critCap=$critCap dHi=$dHi grawLinHi=$grawLinHi independent=$independent " +
                        "jointU=$best ratio=${"%.4f".format(best.toDouble() / independent)}"
                )
            }
            return best
        }

        /** Per carrier (item), the summed max contribution of [terms] (positive coef ⇒ item's high value). */
        private fun perCarrierContribution(terms: List<Term>): LinkedHashMap<Equipment, Long> {
            val byCarrier = LinkedHashMap<Equipment, Long>()
            for (term in terms) {
                val equip = carrierByVar[term.variable] ?: continue
                val d = tracker.of(term.variable)
                val contrib = if (term.coefficient >= 0) d.last * term.coefficient else d.first * term.coefficient
                byCarrier[equip] = (byCarrier[equip] ?: 0L) + contrib
            }
            return byCarrier
        }

        /** Per sublimation, the summed max contribution of [terms]. */
        private fun perSubContribution(terms: List<Term>): LinkedHashMap<Sublimation, Long> {
            val bySub = LinkedHashMap<Sublimation, Long>()
            for (term in terms) {
                val sub = subByVar[term.variable] ?: continue
                val d = tracker.of(term.variable)
                val contrib = if (term.coefficient >= 0) d.last * term.coefficient else d.first * term.coefficient
                bySub[sub] = (bySub[sub] ?: 0L) + contrib
            }
            return bySub
        }

        /**
         * Per sublimation, the EXACT contribution of [terms] when the sub is SELECTED (its 0/1 var = 1) — i.e.
         * the raw coefficient sum, which (unlike [perSubContribution]) keeps NEGATIVE effects such as Carapace's
         * MAX_ACTION_POINT −2. The certifier always either takes a sub whole or not at all, so this is the value
         * to fold in; the max-form would silently zero a negative AP/stat and hide that lever.
         */
        private fun perSubValue(terms: List<Term>): LinkedHashMap<Sublimation, Long> {
            val bySub = LinkedHashMap<Sublimation, Long>()
            for (term in terms) {
                val sub = subByVar[term.variable] ?: continue
                bySub[sub] = (bySub[sub] ?: 0L) + term.coefficient
            }
            return bySub
        }

        /** The summed max contribution of [terms] that come from neither an item nor a sublimation (skills). */
        private fun nonCarrierNonSubContribution(terms: List<Term>): Long {
            var sum = 0L
            for (term in terms) {
                if (carrierByVar[term.variable] != null || subByVar[term.variable] != null) continue
                val d = tracker.of(term.variable)
                sum += if (term.coefficient >= 0) d.last * term.coefficient else d.first * term.coefficient
            }
            return sum
        }

        /**
         * Knapsack DP over carrier slots (ring ≤2 as two pseudo-slots) + sublimations (≤MAX_SUBLIMATIONS), AP as the
         * capacity axis. Returns dp[a] = max Σ value over builds whose item+sub AP totals EXACTLY a, for a in
         * 0..[axisMax]. Skips epic/relic rarity caps (probe). Lets the caller read the exact AP band a build with a
         * given total AP must occupy.
         */
        private fun apAxisValueDp(
            valueByCarrier: Map<Equipment, Long>,
            apByCarrier: Map<Equipment, Long>,
            valueBySub: Map<Sublimation, Long>,
            apBySub: Map<Sublimation, Long>,
            axisMax: Int,
        ): LongArray {
            data class Opt(
                val value: Long,
                val ap: Int,
            )
            val slots = mutableListOf<List<Opt>>()
            for ((type, equips) in valueByCarrier.keys.groupBy { it.itemType }) {
                val bestByAp = HashMap<Int, Long>()
                for (e in equips) {
                    val v = valueByCarrier[e] ?: 0L
                    if (v <= 0L) continue
                    val ap = (apByCarrier[e] ?: 0L).toInt().coerceIn(0, axisMax)
                    bestByAp[ap] = maxOf(bestByAp[ap] ?: Long.MIN_VALUE, v)
                }
                val opts = listOf(Opt(0L, 0)) + bestByAp.map { Opt(it.value, it.key) }
                repeat(if (type == ItemType.RING) 2 else 1) { slots.add(opts) }
            }

            var dp = LongArray(axisMax + 1) { if (it == 0) 0L else Long.MIN_VALUE }

            fun apply(opts: List<Opt>) {
                val nd = dp.copyOf()
                for (a in 0..axisMax) {
                    if (dp[a] == Long.MIN_VALUE) continue
                    for (o in opts) {
                        if (o.value == 0L && o.ap == 0) continue
                        val na = a + o.ap
                        if (na > axisMax) continue
                        if (dp[a] + o.value > nd[na]) nd[na] = dp[a] + o.value
                    }
                }
                dp = nd
            }
            for (slotOpts in slots) apply(slotOpts)
            // Sublimations: 0/1 each, ≤MAX_SUBLIMATIONS — track count as a second axis only while applying.
            val subList = valueBySub.entries.filter { (it.value) > 0L }
            if (subList.isNotEmpty()) {
                val maxSubs = MAX_SUBLIMATIONS.toInt()
                var dpk = Array(maxSubs + 1) { k -> if (k == 0) dp else LongArray(axisMax + 1) { Long.MIN_VALUE } }
                for ((sub, value) in subList) {
                    val ap = (apBySub[sub] ?: 0L).toInt().coerceIn(0, axisMax)
                    val nd = Array(maxSubs + 1) { k -> dpk[k].copyOf() }
                    for (k in 0 until maxSubs) {
                        for (a in 0..axisMax) {
                            if (dpk[k][a] == Long.MIN_VALUE) continue
                            val na = a + ap
                            if (na > axisMax) continue
                            if (dpk[k][a] + value > nd[k + 1][na]) nd[k + 1][na] = dpk[k][a] + value
                        }
                    }
                    dpk = nd
                }
                val merged = LongArray(axisMax + 1) { Long.MIN_VALUE }
                for (k in 0..maxSubs) {
                    for (a in 0..axisMax) merged[a] = maxOf(merged[a], dpk[k][a])
                }
                dp = merged
            }
            return dp
        }

        /**
         * Sharper debug probe: includes ALL value + AP sources (items, sublimations, skills) and the EXACT total-AP
         * band a build must occupy. Reaching AP=[apTarget] forces item+sub AP into [apTarget − apBase − maxSkillAP,
         * apTarget − apBase] (skills fill the rest, capped by their scarce Major points). Compares the AM-GM
         * product bound with NO AP band vs WITH the band. Skips epic/relic rarity caps, so absolute numbers are
         * over-estimates — but the band-vs-noband RATIO answers whether exact-AP-equality is the lever.
         */
        private fun apCapacitatedProbe(
            scenario: DamageScenario,
            apTarget: Int,
        ) {
            val mastery = damagePreMasteryTerms(scenario) ?: return
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return
            val (critTerms, critBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
            val (apTerms, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)
            val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
            val masteryCoef = 400L + critCap
            val critCoef = 5L * critCap
            val grawLinTerms =
                mastery.terms.map { Term(it.variable, it.coefficient * masteryCoef) } +
                    critTerms.map { Term(it.variable, it.coefficient * critCoef) }
            val grawLinConst = masteryCoef * mastery.constant + critCoef * critBase
            val dBase = 100L + diBase

            val apByCarrier = perCarrierContribution(apTerms)
            val apBySub = perSubContribution(apTerms)
            val maxSkillAp = nonCarrierNonSubContribution(apTerms).coerceAtLeast(0L)
            val axisMax = 60
            val apHigh = (apTarget - apBase).toInt()
            val apLow = (apTarget - apBase - maxSkillAp).toInt().coerceAtLeast(0)
            if (apHigh < 0) {
                System.err.println("AP_DP_PROBE2 ap=$apTarget apBase=$apBase apHigh<0 (base exceeds target)")
                return
            }
            val bandHi = apHigh.coerceAtMost(axisMax)

            val dHi = reachableSumDomain(diTerms, dBase).last.coerceAtLeast(1L)
            val grawLinHi = reachableSumDomain(grawLinTerms, grawLinConst).last.coerceAtLeast(1L)
            val independent = dHi * grawLinHi
            val muBase = (grawLinHi / dHi).coerceAtLeast(1L)
            val muGrid = listOf(muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4).filter { it > 0L }.distinct()

            var bestNoBand = independent
            var bestBand = independent
            for (mu in muGrid) {
                val valueTerms = diTerms.map { Term(it.variable, mu * it.coefficient) } + grawLinTerms
                val base = mu * dBase + grawLinConst + nonCarrierNonSubContribution(valueTerms)
                val dp = apAxisValueDp(perCarrierContribution(valueTerms), apByCarrier, perSubContribution(valueTerms), apBySub, axisMax)
                val noBand = dp.filter { it != Long.MIN_VALUE }.maxOrNull() ?: 0L
                val band = (apLow..bandHi).map { dp[it] }.filter { it != Long.MIN_VALUE }.maxOrNull() ?: 0L
                val cNoBand = base + noBand
                val cBand = base + band
                bestNoBand = minOf(bestNoBand, cNoBand * cNoBand / (4L * mu))
                bestBand = minOf(bestBand, cBand * cBand / (4L * mu))
            }
            System.err.println(
                "AP_DP_PROBE2 ap=$apTarget apBase=$apBase maxSkillAp=$maxSkillAp band=[$apLow,$bandHi] " +
                    "independent=$independent noBandBound=$bestNoBand bandBound=$bestBand " +
                    "bandVsNoBand=${"%.4f".format(bestBand.toDouble() / bestNoBand)} bandVsIndep=${"%.4f".format(bestBand.toDouble() / independent)}"
            )
        }

        /** A 2-D Pareto frontier of (DI, graw) pairs — both maximized. Kept sorted by DI desc, graw strictly asc. */
        private class Frontier {
            // di descending; graw strictly increasing across the kept points (the non-dominated staircase).
            val pts = ArrayList<LongArray>()

            fun add(
                di: Long,
                graw: Long,
            ) {
                // dominated by an existing point?
                for (p in pts) if (p[0] >= di && p[1] >= graw) return
                pts.removeAll { it[0] <= di && it[1] <= graw }
                pts.add(longArrayOf(di, graw))
            }

            fun copy(): Frontier {
                val f = Frontier()
                for (p in pts) f.pts.add(p.copyOf())
                return f
            }
        }

        /**
         * EXACT certifier (stage 1: subs + crit step-1 + (DI,graw) frontier; weapons as separate slots ⇒ SOUND
         * upper bound, slightly loose pending the weapon/rarity tightening). Returns the exact-or-over max perHit
         * = max D·Graw over builds with total AP == [apTarget]. Long.MAX_VALUE ⇒ cannot certify this scenario
         * (percent skills present) — caller falls back to CP-SAT. Single-element only.
         */
        private fun certifyMaxPerHitAtAp(
            scenario: DamageScenario,
            apTarget: Int,
        ): Long {
            val mastery = damagePreMasteryTerms(scenario) ?: return Long.MAX_VALUE
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return Long.MAX_VALUE
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return Long.MAX_VALUE
            if (skillTerms.percent[Characteristic.ACTION_POINT].orEmpty().isNotEmpty()) return Long.MAX_VALUE
            if (skillTerms.percent[Characteristic.CRITICAL_HIT].orEmpty().isNotEmpty()) return Long.MAX_VALUE
            // Conversion subs flow through a `moved` variable [subByVar] cannot attribute (it would leak
            // into the skills constant, unconditional + un-gated); forced subs must apply unconditionally
            // and bind their carrier. The certifier models neither — bail so the caller uses CP-SAT.
            if (subModel.subVars.keys.any { it.kind == SublimationKind.CONVERSION }) return Long.MAX_VALUE
            if (subModel.forced.isNotEmpty()) return Long.MAX_VALUE
            // Best-element-concentration / per-stat-step subs (added after this certifier was written) are NOT
            // bailed: their Damage-Inflicted enters [diTerms] and is folded at each term's MAX contribution
            // below, so the certified value stays a SOUND upper bound (looser, never an under-estimate). Tighten
            // + add covering tests before wiring the certifier into the production proof (see
            // docs/code-review-followups.md).
            val (critMTerms, critMBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
            val (apTerms, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)
            val (critTerms, critBase) = prePercentTermsFor(Characteristic.CRITICAL_HIT)

            val diI = perCarrierContribution(diTerms)
            val mI = perCarrierContribution(mastery.terms)
            val cmI = perCarrierContribution(critMTerms)
            val apI = perCarrierContribution(apTerms)
            val crI = perCarrierContribution(critTerms)
            val diS = perSubValue(diTerms)
            val mS = perSubValue(mastery.terms)
            val cmS = perSubValue(critMTerms)
            val apS = perSubValue(apTerms)
            val crS = perSubValue(critTerms)
            // Skill points are a per-branch SHARED pool (addSkillConstraints: Σ branch points ≤ pool), so the
            // certifier cannot fold each objective skill stat at its individual max — that over-allocates the
            // pool (e.g. Luck's 61 pts can't be 20 crit AND 61 critM at once). Split skill contributions into
            // the PASSIVE constant part (base + always-on passives) which stays a constant, and the SKILL-VAR
            // part which is folded into the DP per branch as a pseudo-slot (below) so the allocation is solved
            // jointly with items/subs. mastery/critM may also reach the constant via passives — kept general.
            val skillVarSet = skillVars.values.toSet()

            fun passivePart(terms: List<Term>): Long {
                var sum = 0L
                for (t in terms) {
                    if (carrierByVar[t.variable] != null || subByVar[t.variable] != null || t.variable in skillVarSet) continue
                    val d = tracker.of(t.variable)
                    sum += if (t.coefficient >= 0) d.last * t.coefficient else d.first * t.coefficient
                }
                return sum
            }
            val mConst = mastery.constant + passivePart(mastery.terms)
            val diConst = diBase + passivePart(diTerms)
            val critMConst = critMBase + passivePart(critMTerms)
            val critConst = critBase + passivePart(critTerms)
            val apConst = apBase + passivePart(apTerms)

            // Per objective-relevant skill var: its per-point contribution to crit / ap / di / mastery / critM
            // (read from the model's own term coefficients), and the points it can take (its cap ∧ the pool).
            data class SkillVarInfo(
                val crit: Int,
                val ap: Int,
                val di: Long,
                val m: Long,
                val critM: Long,
                val cap: Int,
            )

            fun skillCoef(
                terms: List<Term>,
                v: IntVar,
            ): Long = terms.filter { it.variable == v }.sumOf { it.coefficient }

            fun branchInfos(
                chars: List<SkillCharacteristic>,
                pool: Int,
            ): List<SkillVarInfo> =
                chars.mapNotNull { ch ->
                    val v = skillVars[ch] ?: return@mapNotNull null
                    val crit = skillCoef(critTerms, v)
                    val ap = skillCoef(apTerms, v)
                    val di = skillCoef(diTerms, v)
                    val m = skillCoef(mastery.terms, v)
                    val cm = skillCoef(critMTerms, v)
                    if (crit == 0L && ap == 0L && di == 0L && m == 0L && cm == 0L) {
                        null
                    } else {
                        SkillVarInfo(crit.toInt(), ap.toInt(), di, m, cm, minOf(ch.maxPointsAssignable, pool))
                    }
                }

            val cs = params.character.characterSkills
            val skillBranches =
                listOf(cs.strength, cs.intelligence, cs.agility, cs.luck, cs.major)
                    .mapNotNull { b ->
                        branchInfos(b.getCharacteristics(), b.maxPointsToAssign)
                            .takeIf { it.isNotEmpty() }
                            ?.let { b.maxPointsToAssign to it }
                    }
            // The per-branch knapsack assumes ≤1 crit var and ≤1 ap var, and no var mixing a cost dimension
            // (crit/ap) with anything else. True for the current skill tree; bail otherwise (sound fallback).
            for ((_, infos) in skillBranches) {
                if (infos.count { it.crit != 0 } > 1 || infos.count { it.ap != 0 } > 1) return Long.MAX_VALUE
                if (infos.any { (it.crit != 0 || it.ap != 0) && (it.di != 0L || it.m != 0L || it.critM != 0L) }) {
                    return Long.MAX_VALUE
                }
                if (infos.any { it.crit != 0 && it.ap != 0 }) return Long.MAX_VALUE
            }

            // Raw stat tuple per carrier / sub: (di, m, critM, ap, crit, epic, relic). graw filled per crit c.
            data class Raw(
                val di: Long,
                val m: Long,
                val critM: Long,
                val ap: Int,
                val crit: Int,
                val epic: Int,
                val relic: Int,
            )

            fun raw(e: Equipment) =
                Raw(
                    diI[e] ?: 0,
                    mI[e] ?: 0,
                    cmI[e] ?: 0,
                    (apI[e] ?: 0).toInt().coerceAtLeast(0),
                    (crI[e] ?: 0).toInt().coerceAtLeast(0),
                    if (e.rarity ==
                        Rarity.EPIC
                    ) {
                        1
                    } else {
                        0
                    },
                    if (e.rarity == Rarity.RELIC) 1 else 0
                )

            fun rawSub(s: Sublimation) =
                Raw(
                    diS[s] ?: 0,
                    mS[s] ?: 0,
                    cmS[s] ?: 0,
                    (apS[s] ?: 0).toInt(),
                    (crS[s] ?: 0).toInt(),
                    if (s.rarity ==
                        SublimationRarity.EPIC
                    ) {
                        1
                    } else {
                        0
                    },
                    if (s.rarity == SublimationRarity.RELIC) 1 else 0
                )

            // Max-damage runes fold to ONE type per item (maxDamageRuneChoiceCollapse): the best M-feeding
            // mastery rune OR the critical-mastery rune — never both. perCarrierContribution counted BOTH
            // (mI has the mastery rune, cmI the critM rune), so split a dual-rune item into two Raw options
            // (mastery-rune-on / critM-rune-on); the DP keeps whichever wins at each crit rate c. Items with a
            // single always-on rune (or none) keep their single Raw. Bail on rune models we cannot mirror.
            val rangeBandMasteryChar = scenario.rangeBand.masteryCharacteristic
            if (runeModel.runeVars.isNotEmpty()) {
                if (!runeModel.singleTypePerItem) return Long.MAX_VALUE
                val allowed = setOf(rangeBandMasteryChar, Characteristic.MASTERY_CRITICAL)
                if (runeModel.runeVars.any { (_, perStat) -> perStat.keys.any { it !in allowed } }) return Long.MAX_VALUE
            }

            fun rawOptions(e: Equipment): List<Raw> {
                val base = raw(e)
                runeModel.runeVars[e]?.get(Characteristic.MASTERY_CRITICAL) ?: return listOf(base)
                val slots = e.maxShardSlots.toLong()
                val runeMastery = runeModel.coefficientFor(e, rangeBandMasteryChar) * slots
                val runeCritM = runeModel.coefficientFor(e, Characteristic.MASTERY_CRITICAL) * slots
                // base counts BOTH runes; strip the one not chosen for each option.
                return listOf(base.copy(critM = base.critM - runeCritM), base.copy(m = base.m - runeMastery))
            }

            val itemEquips = (diI.keys + mI.keys + cmI.keys + apI.keys + crI.keys).distinct()
            // Equipment vars are boolean, so the build equips TWO DISTINCT rings (Σ ringVar ≤ 2). Applying the
            // ring cells twice would let the certifier double the single best ring — keep rings per-equip and
            // pick the top-2 DISTINCT rings per cost cell below. Other slots flatten to their rune options.
            val itemsByType =
                itemEquips
                    .filter { it.itemType != ItemType.RING }
                    .groupBy { it.itemType }
                    .mapValues { (_, equips) -> equips.flatMap { rawOptions(it) } }
            val ringOptionsByEquip = itemEquips.filter { it.itemType == ItemType.RING }.map { rawOptions(it) }
            val subEntries =
                (diS.keys + mS.keys + cmS.keys + apS.keys + crS.keys)
                    .distinct()
                    .map { it to rawSub(it) }

            val critCap =
                scenario.critCapPercent
                    .toLong()
                    .coerceIn(0L, 100L)
                    .toInt()
            // The crit DP dimension is item crit = c − critConst, enumerated over total crit c ∈ 0..critCap, so
            // it assumes critCap ≥ critConst (the always-on base+passive crit). If the scenario caps usable crit
            // BELOW the base (critCapPercent < base crit — never the production default of 100, but the CLI/GUI
            // can lower it), every c < critConst is skipped and the DP would under-count to 0. Bail to CP-SAT.
            if (critCap < critConst) return Long.MAX_VALUE
            val apHigh = (apTarget - apConst).toInt()
            if (apHigh < 0) return Long.MAX_VALUE
            val subCap = System.getenv("WAKFU_MAX_DAMAGE_CERT_SUBCAP")?.toIntOrNull() ?: MAX_SUBLIMATIONS.toInt()

            // --- Sublimation couplings (mirror createSublimationModel) -------------------------------
            // Subs touch DI / crit% / AP / mastery (no current choosable sub gives mastery, but a FLAT one
            // would fold into graw via grawOf — kept fully general). Couplings vs the old free 0/1 pool:
            //  • epic/relic subs need an equipped epic/relic ITEM (Σ subRarity ≤ Σ itemRarity); modeled as a
            //    dedicated slot requiring state itemRarity≥1 — they do NOT consume the item's own ≤1 cap.
            //  • a NORMAL sub needs a distinct ≥3-socket carrier item (Σ normalSub ≤ Σ carriers); vacuous
            //    when there are ≥ that many carrier slots (the build hosts a damage-irrelevant carrier in
            //    any otherwise-spent slot) — guarded, else bail.
            //  • conditions read PRE-sub build stats. AP/CRIT are tracked, so gated EXACTLY; a defensive
            //    condition a max-damage build never satisfies (secMast≤0 when the objective stacks a
            //    secondary mastery, so satisfying it strips M to its elemental part; block≥40) is dropped
            //    (sound, locked by ==CP-SAT). Conditions a damage build naturally meets are credited.
            // Crit reaches a total `c` from items (the tracked DP dimension) OR pure-crit subs (a free
            // budget). The graw-max build sources crit from subs first, so pre-sub crit = c − subCrit;
            // tracking ITEM crit lets every CRIT_AT_MOST condition be gated exactly per state.
            val objectiveSecondaryOverlap =
                scenarioMasteryStats(scenario).any { it in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }

            fun structurallyDropped(sub: Sublimation): Boolean {
                val cond = sub.condition ?: return false
                return when (cond.type) {
                    SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
                        (cond.value ?: 0) <= 0 && objectiveSecondaryOverlap
                    SublimationConditionType.BLOCK_AT_LEAST -> true
                    else -> false
                }
            }

            val keptSubs = subEntries.filter { !structurallyDropped(it.first) }

            // Pure-crit / pure-AP subs (a crit%-only or AP-only effect) form free BUDGETS that fill the gap
            // between the build's PRE-sub crit/AP (the tracked DP dimensions) and the pinned total; every other
            // kept sub is a frontier transition (di + graw). The budgets make CRIT_AT_MOST / AP_AT_MOST exact
            // per state (pre-sub crit = critConst + critDim; pre-sub AP = apConst + apDim). Vivacity is +1 AP,
            // Carapace −2 AP (lets the build over-equip AP gear, the budget pulls the total back to the pin). A
            // kept sub mixing crit% or AP with di/mastery would need an extra source axis — bail instead.
            fun isPureCrit(r: Raw) = r.crit > 0 && r.di == 0L && r.m == 0L && r.critM == 0L && r.ap == 0

            fun isPureAp(r: Raw) = r.ap != 0 && r.di == 0L && r.m == 0L && r.critM == 0L && r.crit == 0
            if (keptSubs.any { (it.second.crit != 0 || it.second.ap != 0) && !isPureCrit(it.second) && !isPureAp(it.second) }) {
                return Long.MAX_VALUE
            }
            val critBudgetSubs = keptSubs.filter { isPureCrit(it.second) }
            val apBudgetSubs = keptSubs.filter { isPureAp(it.second) }

            fun isTransition(r: Raw) = !isPureCrit(r) && !isPureAp(r)
            val normalTransitionSubs =
                keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.NORMAL }
            val epicSubs = keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.EPIC }
            val relicSubs = keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.RELIC }

            val maxSubCrit = critBudgetSubs.sumOf { it.second.crit.toLong() }
            // Sub AP ranges over [minSubAp, maxSubAp]; items + skills supply the pre-sub AP dimension and the
            // budget fills the gap to the pin, so apDim ranges [apHigh − maxSubAp, apHigh − minSubAp].
            val maxSubAp = apBudgetSubs.sumOf { maxOf(0, it.second.ap) }
            val minSubAp = apBudgetSubs.sumOf { minOf(0, it.second.ap) }
            val apCeil = apHigh - minSubAp
            val apFloor = (apHigh - maxSubAp).coerceAtLeast(0)

            // Pre-combat-condition split (mirrors [preCombatStat] / [permanentSubTermsByStat]): only PERMANENT
            // (appliesBeforeCombat) sub crit/AP shows on the character sheet, so only it feeds a build-static
            // start-of-combat condition. A start-of-combat crit budget sub (Secondary Devastation II, +7) still
            // reaches the in-combat crit `c` but does NOT raise the pre-combat crit a CRIT_AT_MOST reads; a
            // permanent one (Influence II, +15) does. So at a state the pre-combat crit can be pushed as low as
            // max(itemCrit, c − Σstart) (hide the rest behind start subs) or as high as min(c, itemCrit + Σperm).
            val permCritByVar = permanentSubTermsByStat[Characteristic.CRITICAL_HIT].orEmpty().associate { it.variable to it.coefficient }
            val permApByVar = permanentSubTermsByStat[Characteristic.ACTION_POINT].orEmpty().associate { it.variable to it.coefficient }

            fun permCritOf(entry: Pair<Sublimation, Raw>) = subModel.subVars[entry.first]?.let { permCritByVar[it] } ?: 0L

            fun permApOf(entry: Pair<Sublimation, Raw>) = subModel.subVars[entry.first]?.let { permApByVar[it] } ?: 0L
            val maxStartCrit = critBudgetSubs.sumOf { (it.second.crit - permCritOf(it)).coerceAtLeast(0L) }
            val maxPermCrit = critBudgetSubs.sumOf { permCritOf(it).coerceAtLeast(0L) }
            val maxStartAp = apBudgetSubs.sumOf { (it.second.ap - permApOf(it)).coerceAtLeast(0L) }
            val maxPermAp = apBudgetSubs.sumOf { permApOf(it).coerceAtLeast(0L) }

            // Socket capacity: a NORMAL sub needs a distinct ≥3-socket carrier slot. Vacuous when there are
            // ≥ that many carrier slots; otherwise the constraint could bind and we bail to CP-SAT.
            val carrierSlotCount =
                allEquips.groupBy { it.itemType }.entries.sumOf { (type, candidates) ->
                    if (candidates.none { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }) {
                        0
                    } else if (type == ItemType.RING) {
                        2
                    } else {
                        1
                    }
                }
            val normalKeptCount = keptSubs.count { it.first.rarity == SublimationRarity.NORMAL }
            if (normalKeptCount > carrierSlotCount) return Long.MAX_VALUE
            if (keptSubs.size > subCap) return Long.MAX_VALUE

            // Whether [sub]'s condition can hold at a DP state with item crit/AP `preCrit`/`preAp` and total
            // in-combat crit `c`. The condition reads the PRE-COMBAT (character-sheet) crit/AP — base + item +
            // rune + skill + PERMANENT subs — NOT the start-of-combat budget (see [preCombatStat]). AT_MOST can
            // hide crit behind start-of-combat subs (min pre-combat = max(itemCrit, c − Σstart)); AT_LEAST can
            // stack permanent subs (max pre-combat = min(c, itemCrit + Σperm)). AP has no start-of-combat source
            // (Vivacity / Carapace are permanent), so pre-combat AP == the pinned total apTarget. Conditions on
            // stats the certifier does not track fall through to `true`.
            fun subAllowedAt(
                sub: Sublimation,
                preCrit: Int,
                preAp: Int,
                c: Int,
            ): Boolean {
                val cond = sub.condition ?: return true
                val n = (cond.value ?: 0).toLong()
                val preCombatCritMin = maxOf(critConst + preCrit, c - maxStartCrit)
                val preCombatCritMax = minOf(c.toLong(), critConst + preCrit + maxPermCrit)
                val preCombatApMin = maxOf(apConst + preAp, apTarget - maxStartAp)
                val preCombatApMax = minOf(apTarget.toLong(), apConst + preAp + maxPermAp)
                return when (cond.type) {
                    SublimationConditionType.AP_AT_MOST -> preCombatApMin <= n
                    SublimationConditionType.AP_AT_LEAST -> preCombatApMax >= n
                    SublimationConditionType.AP_EXACT -> preCombatApMin <= n && n <= preCombatApMax
                    SublimationConditionType.CRIT_AT_MOST -> preCombatCritMin <= n
                    SublimationConditionType.CRIT_AT_LEAST -> preCombatCritMax >= n
                    else -> true
                }
            }

            if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") {
                System.err.println(
                    "CERT_DEBUG ap=$apTarget apBase=$apBase apConst=$apConst apHigh=$apHigh critCap=$critCap " +
                        "critConst=$critConst diConst=$diConst critMConst=$critMConst mConst=$mConst " +
                        "branches=${skillBranches.map { it.first }}"
                )
                for ((sub, _) in subModel.subVars) {
                    val di = diS[sub] ?: 0L
                    val m = mS[sub] ?: 0L
                    val cm = cmS[sub] ?: 0L
                    val ap = apS[sub] ?: 0L
                    val cr = crS[sub] ?: 0L
                    if (di == 0L && m == 0L && cm == 0L && ap == 0L && cr == 0L) continue
                    System.err.println(
                        "CERT_DEBUG_SUB id=${sub.stateId} '${sub.name.en}' rarity=${sub.rarity} kind=${sub.kind} " +
                            "cond=${sub.condition?.type}/${sub.condition?.value} forced=${sub in subModel.forced} " +
                            "di=$di m=$m critM=$cm ap=$ap crit=$cr"
                    )
                }
                val socketByType =
                    allEquips.groupBy { it.itemType }.mapValues { (_, es) ->
                        "${es.count { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }}/${es.size}"
                    }
                System.err.println("CERT_DEBUG_SOCKETS perType=$socketByType")
                val branches =
                    mapOf(
                        "STR" to params.character.characterSkills.strength,
                        "INT" to params.character.characterSkills.intelligence,
                        "AGI" to params.character.characterSkills.agility,
                        "LUCK" to params.character.characterSkills.luck,
                        "MAJOR" to params.character.characterSkills.major
                    )

                fun coef(
                    terms: List<Term>,
                    v: IntVar,
                ) = terms.filter { it.variable == v }.sumOf { it.coefficient }
                for ((bn, branch) in branches) {
                    System.err.println("CERT_DEBUG_BRANCH $bn pool=${branch.maxPointsToAssign}")
                    for (ch in branch.getCharacteristics()) {
                        val v = skillVars[ch] ?: continue
                        val cm = coef(mastery.terms, v)
                        val ccm = coef(critMTerms, v)
                        val ccr = coef(critTerms, v)
                        val cap = coef(apTerms, v)
                        val cdi = coef(diTerms, v)
                        if (cm == 0L && ccm == 0L && ccr == 0L && cap == 0L && cdi == 0L) continue
                        System.err.println(
                            "CERT_DEBUG_SKILL $bn ${ch.characteristic} cap=${ch.maxPointsAssignable} " +
                                "m=$cm critM=$ccm crit=$ccr ap=$cap di=$cdi"
                        )
                    }
                }
                val epicItems = allEquips.count { it.rarity == Rarity.EPIC }
                val relicItems = allEquips.count { it.rarity == Rarity.RELIC }
                System.err.println("CERT_DEBUG_RARITY epicItems=$epicItems relicItems=$relicItems")
            }

            var best = 0L
            var bestDbg = ""
            for (c in 0..critCap) {
                // The crit dimension is the build's PRE-sub crit (base+passive folded into critConst): items,
                // runes and folded skill (Luck) crit. Pure-crit subs fill the gap to the total crit c.
                val critItemHigh = (c - critConst).toInt()
                if (critItemHigh < 0) continue
                val critItemLow = (critItemHigh - maxSubCrit.toInt()).coerceAtLeast(0)
                val grawConst = (400L + c) * mConst + 5L * c * critMConst
                val dConst = 100L + diConst

                fun grawOf(r: Raw) = (400L + c) * r.m + 5L * c * r.critM
                // state key packs (ap, crit, epic, relic, subCount). epic/relic in {0,1}, subCount in 0..subCap.
                val critDim = critItemHigh + 1

                fun key(
                    ap: Int,
                    crit: Int,
                    epic: Int,
                    relic: Int,
                    n: Int,
                ) = ((((ap.toLong() * critDim + crit) * 2 + epic) * 2 + relic) * (subCap + 1)) + n

                var dp = HashMap<Long, Frontier>()
                dp[key(0, 0, 0, 0, 0)] = Frontier().also { it.add(0, 0) }

                // Reduce a slot's items to a Frontier per (ap,crit,epic,relic) cost bucket — collapses ~470 raw
                // items to a handful of cost cells, the key speedup. Then one DP transition per cost cell.
                fun perCost(options: List<Raw>): Map<Int, Frontier> {
                    val m = HashMap<Int, Frontier>()
                    for (r in options) {
                        if (r.ap > apCeil || r.crit > critItemHigh) continue
                        val g = grawOf(r)
                        if (r.di <= 0 && g <= 0 && r.epic + r.relic == 0) continue
                        val ck = ((r.ap * critDim + r.crit) * 2 + r.epic) * 2 + r.relic
                        m.getOrPut(ck) { Frontier() }.add(r.di, g)
                    }
                    return m
                }

                fun applyCells(cells: Map<Int, Frontier>) {
                    val nd = HashMap<Long, Frontier>()
                    for ((k, fr) in dp) nd.getOrPut(k) { Frontier() }.also { for (p in fr.pts) it.add(p[0], p[1]) }
                    for ((k, fr) in dp) {
                        val n0 = (k % (subCap + 1)).toInt()
                        var rest = k / (subCap + 1)
                        val relic0 = (rest % 2).toInt()
                        rest /= 2
                        val epic0 = (rest % 2).toInt()
                        rest /= 2
                        val crit0 = (rest % critDim).toInt()
                        val ap0 = (rest / critDim).toInt()
                        for ((ck, cfr) in cells) {
                            var cr = ck
                            val rrelic = cr % 2
                            cr /= 2
                            val repic = cr % 2
                            cr /= 2
                            val rcrit = cr % critDim
                            val rap = cr / critDim
                            val ap1 = ap0 + rap
                            val crit1 = crit0 + rcrit
                            val epic1 = epic0 + repic
                            val relic1 = relic0 + rrelic
                            if (ap1 > apCeil || crit1 > critItemHigh || epic1 > 1 || relic1 > 1) continue
                            val tgt = nd.getOrPut(key(ap1, crit1, epic1, relic1, n0)) { Frontier() }
                            for (p in fr.pts) for (q in cfr.pts) tgt.add(p[0] + q[0], p[1] + q[1])
                        }
                    }
                    dp = nd
                }

                // Item slots: weapons grouped (1H+offhand OR 2H) like rarityAwareUpper.weaponOptions.
                val weaponTypes = setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS, ItemType.TWO_HANDED_WEAPONS)
                for ((type, entries) in itemsByType) {
                    if (type in weaponTypes) continue
                    applyCells(perCost(entries))
                }
                if (weaponTypes.any { it in itemsByType }) {
                    val oneH = itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()
                    val offH = itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()
                    val twoH = itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty()
                    // 1H/off-hand pair (each side optional) plus the 2H alternative, as ONE combined slot.
                    val pair = mutableListOf(Raw(0, 0, 0, 0, 0, 0, 0))
                    val oneOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + oneH
                    val offOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + offH
                    val combined = mutableListOf<Raw>()
                    for (a in oneOpts) {
                        for (b in offOpts) {
                            if (a.epic + b.epic > 1 || a.relic + b.relic > 1) continue
                            combined.add(Raw(a.di + b.di, a.m + b.m, a.critM + b.critM, a.ap + b.ap, a.crit + b.crit, a.epic + b.epic, a.relic + b.relic))
                        }
                    }
                    pair.clear()
                    pair.addAll(combined + twoH)
                    applyCells(perCost(pair))
                }

                // Ring slot = TWO DISTINCT rings. Per ring take its best rune option's graw at this crit rate,
                // keep the top-2 graw per cost cell, then offer every 1-ring and 2-distinct-ring combination as
                // one slot (the same ring is never paired with itself; a same-cost pair uses best + 2nd-best).
                if (ringOptionsByEquip.isNotEmpty()) {
                    val top2 = HashMap<Int, LongArray>()
                    for (opts in ringOptionsByEquip) {
                        var bestG = Long.MIN_VALUE
                        var bestCk = -1
                        for (r in opts) {
                            if (r.ap > apCeil || r.crit > critItemHigh) continue
                            val g = grawOf(r)
                            if (g > bestG) {
                                bestG = g
                                bestCk = ((r.ap * critDim + r.crit) * 2 + r.epic) * 2 + r.relic
                            }
                        }
                        if (bestCk < 0 || bestG <= 0) continue
                        val cur = top2.getOrPut(bestCk) { longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE) }
                        if (bestG > cur[0]) {
                            cur[1] = cur[0]
                            cur[0] = bestG
                        } else if (bestG > cur[1]) {
                            cur[1] = bestG
                        }
                    }
                    val cks = top2.keys.toList()
                    val ringCells = HashMap<Int, Frontier>()

                    fun decodeAp(ck: Int) = (ck / 2 / 2) / critDim

                    fun decodeCrit(ck: Int) = (ck / 2 / 2) % critDim

                    fun decodeEpic(ck: Int) = (ck / 2) % 2

                    fun decodeRelic(ck: Int) = ck % 2
                    for (i in cks.indices) {
                        ringCells.getOrPut(cks[i]) { Frontier() }.add(0, top2.getValue(cks[i])[0]) // one ring
                        for (j in i until cks.size) {
                            val a = cks[i]
                            val b = cks[j]
                            val graw =
                                if (a == b) {
                                    val g = top2.getValue(a)
                                    if (g[1] == Long.MIN_VALUE) continue
                                    g[0] + g[1]
                                } else {
                                    top2.getValue(a)[0] + top2.getValue(b)[0]
                                }
                            val ap = decodeAp(a) + decodeAp(b)
                            val crit = decodeCrit(a) + decodeCrit(b)
                            val epic = decodeEpic(a) + decodeEpic(b)
                            val relic = decodeRelic(a) + decodeRelic(b)
                            if (ap > apCeil || crit > critItemHigh || epic > 1 || relic > 1) continue
                            ringCells.getOrPut(((ap * critDim + crit) * 2 + epic) * 2 + relic) { Frontier() }.add(0, graw)
                        }
                    }
                    applyCells(ringCells)
                }

                // Skill branches as pseudo-slots: enumerate the pool allocation between the single crit var
                // (→ crit dimension) and ap var (→ ap dimension), with the remaining points giving the best
                // (di, graw) frontier — di vars on the frontier, mastery/critM filled greedily by graw-per-point.
                // This solves the skill allocation jointly with items/subs under the real per-branch pool cap.
                fun branchCells(
                    infos: List<SkillVarInfo>,
                    pool: Int,
                ): Map<Int, Frontier> {
                    val critVar = infos.firstOrNull { it.crit != 0 }
                    val apVar = infos.firstOrNull { it.ap != 0 }
                    val diVars = infos.filter { it.di != 0L }.sortedByDescending { it.di }
                    val grawVars = infos.filter { it.di == 0L && it.crit == 0 && it.ap == 0 }

                    fun fillGraw(points: Int): Long {
                        var rem = points
                        var g = 0L
                        for (gv in grawVars.sortedByDescending { (400L + c) * it.m + 5L * c * it.critM }) {
                            if (rem <= 0) break
                            val per = (400L + c) * gv.m + 5L * c * gv.critM
                            if (per <= 0) continue
                            val take = minOf(rem, gv.cap)
                            g += take * per
                            rem -= take
                        }
                        return g
                    }
                    val diCapTotal = diVars.sumOf { it.cap }
                    val cells = HashMap<Int, Frontier>()
                    val critCap = critVar?.let { minOf(it.cap, pool) } ?: 0
                    val apCap = apVar?.let { minOf(it.cap, pool) } ?: 0
                    for (ccPts in 0..critCap) {
                        val crit = ccPts * (critVar?.crit ?: 0)
                        if (crit > critItemHigh) break
                        for (apPts in 0..minOf(apCap, pool - ccPts)) {
                            val ap = apPts * (apVar?.ap ?: 0)
                            if (ap > apCeil) break
                            val rem0 = pool - ccPts - apPts
                            for (d in 0..minOf(rem0, diCapTotal)) {
                                var di = 0L
                                var left = d
                                for (dv in diVars) {
                                    val take = minOf(left, dv.cap)
                                    di += take * dv.di
                                    left -= take
                                    if (left <= 0) break
                                }
                                val graw = fillGraw(rem0 - d)
                                val ck = (ap * critDim + crit) * 2 * 2
                                cells.getOrPut(ck) { Frontier() }.add(di, graw)
                            }
                        }
                    }
                    return cells
                }
                for ((pool, infos) in skillBranches) applyCells(branchCells(infos, pool))

                // Normal transition subs: 0/1 each, count ≤ subCap (state's n field). crit==0 (pure-crit
                // subs are the budget, not transitions) so they never move the item-crit dimension. They
                // never consume the item epic/relic budget; mastery/critM (if any) fold into graw.
                for ((sub, r) in normalTransitionSubs) {
                    if (r.ap > apCeil) continue
                    val g = grawOf(r)
                    val nd = HashMap<Long, Frontier>()
                    for ((k, fr) in dp) nd.getOrPut(k) { Frontier() }.also { for (p in fr.pts) it.add(p[0], p[1]) }
                    for ((k, fr) in dp) {
                        val n0 = (k % (subCap + 1)).toInt()
                        if (n0 >= subCap) continue
                        var rest = k / (subCap + 1)
                        val relic0 = (rest % 2).toInt()
                        rest /= 2
                        val epic0 = (rest % 2).toInt()
                        rest /= 2
                        val crit0 = (rest % critDim).toInt()
                        val ap0 = (rest / critDim).toInt()
                        if (!subAllowedAt(sub, crit0, ap0, c)) continue
                        val ap1 = ap0 + r.ap
                        if (ap1 < 0 || ap1 > apCeil) continue
                        val tgt = nd.getOrPut(key(ap1, crit0, epic0, relic0, n0 + 1)) { Frontier() }
                        for (p in fr.pts) tgt.add(p[0] + r.di, p[1] + g)
                    }
                    dp = nd
                }

                // Epic/relic sub slot: at most ONE, hosted on an equipped epic/relic ITEM (state rarity ≥1).
                // It does NOT increment the item rarity count (Σ subRarity ≤ Σ itemRarity, they coexist).
                // Per-state condition gate uses the state's item crit so CRIT_AT_MOST is exact.
                fun applyRaritySub(
                    options: List<Pair<Sublimation, Raw>>,
                    epic: Boolean,
                ) {
                    if (options.isEmpty()) return
                    val nd = HashMap<Long, Frontier>()
                    for ((k, fr) in dp) nd.getOrPut(k) { Frontier() }.also { for (p in fr.pts) it.add(p[0], p[1]) }
                    for ((k, fr) in dp) {
                        val n0 = (k % (subCap + 1)).toInt()
                        if (n0 >= subCap) continue
                        var rest = k / (subCap + 1)
                        val relic0 = (rest % 2).toInt()
                        rest /= 2
                        val epic0 = (rest % 2).toInt()
                        rest /= 2
                        val crit0 = (rest % critDim).toInt()
                        val ap0 = (rest / critDim).toInt()
                        if (epic && epic0 < 1) continue
                        if (!epic && relic0 < 1) continue
                        for ((sub, r) in options) {
                            if (!subAllowedAt(sub, crit0, ap0, c)) continue
                            val ap1 = ap0 + r.ap
                            if (ap1 < 0 || ap1 > apCeil) continue
                            val tgt = nd.getOrPut(key(ap1, crit0, epic0, relic0, n0 + 1)) { Frontier() }
                            for (p in fr.pts) tgt.add(p[0] + r.di, p[1] + grawOf(r))
                        }
                    }
                    dp = nd
                }
                applyRaritySub(epicSubs, epic = true)
                applyRaritySub(relicSubs, epic = false)

                for ((k, fr) in dp) {
                    var rest = k / (subCap + 1)
                    rest /= 2
                    rest /= 2
                    val crit = (rest % critDim).toInt()
                    val ap = (rest / critDim).toInt()
                    // apDim is PRE-sub AP; the sub-AP budget fills the gap to the pin ⇒ apDim ∈ [apFloor, apCeil].
                    if (ap < apFloor || ap > apCeil || crit < critItemLow) continue
                    for (p in fr.pts) {
                        val prod = (dConst + p[0]) * (grawConst + p[1])
                        if (prod > best) {
                            best = prod
                            if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") {
                                bestDbg = "c=$c ap=$ap critDim$crit di=${dConst + p[0]} graw=${grawConst + p[1]}"
                            }
                        }
                    }
                }
            }
            if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") System.err.println("CERT_DEBUG_BEST $bestDbg")
            return best
        }

        private fun innerFrontierPrototype(
            scenario: DamageScenario,
            apTarget: Int,
        ) {
            val mastery = damagePreMasteryTerms(scenario) ?: return
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return
            if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return
            val (critMTerms, critMBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
            val (apTerms, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)
            val (critTerms, critBase) = prePercentTermsFor(Characteristic.CRITICAL_HIT)

            val diByItem = perCarrierContribution(diTerms)
            val mByItem = perCarrierContribution(mastery.terms)
            val critMByItem = perCarrierContribution(critMTerms)
            val apByItem = perCarrierContribution(apTerms)
            val critByItem = perCarrierContribution(critTerms)
            val skillDi = nonCarrierNonSubContribution(diTerms)
            val skillM = nonCarrierNonSubContribution(mastery.terms)
            val skillCritM = nonCarrierNonSubContribution(critMTerms)
            val maxSkillAp = nonCarrierNonSubContribution(apTerms).coerceAtLeast(0L).toInt()
            val maxSkillCrit = nonCarrierNonSubContribution(critTerms).coerceAtLeast(0L).toInt()

            data class ItemContrib(
                val di: Long,
                val m: Long,
                val critM: Long,
                val ap: Int,
                val crit: Int,
            )
            val items = (diByItem.keys + mByItem.keys + critMByItem.keys + apByItem.keys + critByItem.keys).distinct()
            val byType =
                items.groupBy({ it.itemType }) {
                    it to
                        ItemContrib(
                            diByItem[it] ?: 0,
                            mByItem[it] ?: 0,
                            critMByItem[it] ?: 0,
                            (apByItem[it] ?: 0).toInt().coerceAtLeast(0),
                            (critByItem[it] ?: 0).toInt().coerceAtLeast(0)
                        )
                }

            val apHigh = (apTarget - apBase).toInt()
            if (apHigh < 0) return
            val apLow = (apHigh - maxSkillAp).coerceAtLeast(0)

            run {
                val withDi = items.count { (diByItem[it] ?: 0) > 0 }
                val withM = items.count { (mByItem[it] ?: 0) > 0 }
                val withCrit = items.count { (critByItem[it] ?: 0) > 0 }
                val naiveMaxM =
                    byType.entries.sumOf { (type, entries) ->
                        val best = entries.maxOf { (_, ic) -> ic.m }.coerceAtLeast(0)
                        if (type == ItemType.RING) 2 * best else best
                    }
                val perTypeM =
                    byType.entries.joinToString(",") { (type, entries) ->
                        "$type:${entries.maxOf { (_, ic) -> ic.m }.coerceAtLeast(0)}"
                    }
                System.err.println(
                    "INNER_FRONTIER_DIAG items=${items.size} withDi=$withDi withM=$withM withCrit=$withCrit " +
                        "skillM=$skillM naiveMaxM(+mConst${mastery.constant})=${naiveMaxM + mastery.constant} optimumFireM≈3890 perType[$perTypeM]"
                )
            }
            var globalMax = 0L
            var globalMaxFrontier = 0
            var bestC = -1
            val critGrid = (0..100 step 2).toList()
            for (c in critGrid) {
                val critItemHigh = (c - critBase).toInt()
                if (critItemHigh < 0) continue
                val critItemLow = (critItemHigh - maxSkillCrit).coerceAtLeast(0)
                val grawConst = (400L + c) * (mastery.constant + skillM) + 5L * c * (critMBase + skillCritM)
                val dConst = 100L + diBase + skillDi
                // DP state key = ap*(critItemHigh+1)+crit ; value = Frontier
                var dp = HashMap<Int, Frontier>()
                dp[0] = Frontier().also { it.add(0, 0) }
                val critDim = critItemHigh + 1

                fun key(
                    ap: Int,
                    crit: Int,
                ) = ap * critDim + crit
                for ((type, entries) in byType) {
                    // reduce: best (di,graw) per (ap,crit) cost for this slot
                    val bestPerCost = HashMap<Int, Frontier>()
                    for ((_, ic) in entries) {
                        if (ic.ap > apHigh || ic.crit > critItemHigh) continue
                        val g = (400L + c) * ic.m + 5L * c * ic.critM
                        if (ic.di <= 0 && g <= 0) continue
                        bestPerCost.getOrPut(key(ic.ap, ic.crit)) { Frontier() }.add(ic.di, g)
                    }
                    val picks = if (type == ItemType.RING) 2 else 1
                    repeat(picks) {
                        val nd = HashMap<Int, Frontier>()
                        for ((k, fr) in dp) {
                            val ap0 = k / critDim
                            val crit0 = k % critDim
                            // skip (take nothing in this slot)
                            nd.getOrPut(k) { Frontier() }.also { for (p in fr.pts) it.add(p[0], p[1]) }
                            for ((ck, cfr) in bestPerCost) {
                                val ap1 = ap0 + ck / critDim
                                val crit1 = crit0 + ck % critDim
                                if (ap1 > apHigh || crit1 > critItemHigh) continue
                                val tgt = nd.getOrPut(key(ap1, crit1)) { Frontier() }
                                for (p in fr.pts) for (q in cfr.pts) tgt.add(p[0] + q[0], p[1] + q[1])
                            }
                        }
                        dp = nd
                    }
                }
                var maxProd = 0L
                var maxFr = 0
                for ((k, fr) in dp) {
                    val ap = k / critDim
                    val crit = k % critDim
                    maxFr = maxOf(maxFr, fr.pts.size)
                    if (ap < apLow || crit < critItemLow) continue
                    for (p in fr.pts) {
                        val prod = (dConst + p[0]) * (grawConst + p[1])
                        if (prod > maxProd) maxProd = prod
                    }
                }
                globalMaxFrontier = maxOf(globalMaxFrontier, maxFr)
                if (maxProd > globalMax) {
                    globalMax = maxProd
                    bestC = c
                }
            }
            System.err.println(
                "INNER_FRONTIER ap=$apTarget bestCrit=$bestC maxPerHit=$globalMax maxFrontierSize=$globalMaxFrontier " +
                    "(carrier+skill, no subs)"
            )
        }

        private data class LinearTermSum(
            val terms: MutableList<Term>,
            val constant: Long,
        )

        /**
         * `M = 100 + ΣMastery` for the max-damage formula, built as ONE slot-aware linear sum. Summing the
         * already-aggregated elemental and secondary mastery vars loses the carrier correlation: the fire
         * bound can pick the best fire item in a slot while the distance bound picks a different best-distance
         * item in that same slot. Building the direct term list lets [reachableSumDomain] score each candidate
         * item by its combined fire + generic + random + distance contribution and then keep only the best
         * item per slot (two rings), which is the sound bound CP-SAT needs to close the damage proof.
         */
        private fun damagePreMastery(
            suffix: String,
            fallbackElementMasteryVar: IntVar,
            scenario: DamageScenario,
        ): IntVar {
            val direct = damagePreMasteryTerms(scenario)
            if (direct != null) {
                return tSum(
                    "dmgPreM_$suffix",
                    direct.terms,
                    direct.constant,
                    reachableSumDomain(direct.terms, direct.constant),
                    -CLAMP_INTERMEDIATE_MAX,
                    CLAMP_INTERMEDIATE_MAX
                )
            }

            val terms = mutableListOf(Term(fallbackElementMasteryVar, 1L), Term(actualStat(scenario.rangeBand.masteryCharacteristic), 1L))
            if (scenario.orientation.grantsRearMastery) terms.add(Term(actualStat(Characteristic.MASTERY_BACK), 1L))
            if (scenario.berserk) terms.add(Term(actualStat(Characteristic.MASTERY_BERSERK), 1L))
            if (scenario.healing) terms.add(Term(actualStat(Characteristic.MASTERY_HEALING), 1L))
            return tSumNaive("dmgPreM_$suffix", terms, 100L, -CLAMP_INTERMEDIATE_MAX, CLAMP_INTERMEDIATE_MAX)
        }

        private fun damagePreMasteryTerms(scenario: DamageScenario): LinearTermSum? = damagePreMasteryTermsCache.getOrPut(scenario) { computeDamagePreMasteryTerms(scenario) }

        private fun computeDamagePreMasteryTerms(scenario: DamageScenario): LinearTermSum? {
            val directStats = scenarioMasteryStats(scenario).distinct()

            // No current mastery skill is percent-based. If a future data/model change adds one, preserve
            // exactness by falling back to the older var-sum path for that stat instead of silently skipping
            // the percent application.
            if (directStats.any { skillTerms.percent[it].orEmpty().isNotEmpty() }) {
                return null
            }

            val terms = mutableListOf<Term>()
            var constant = 100L
            for (stat in directStats) {
                val (statTerms, statBase) = prePercentTermsFor(stat)
                terms.addAll(statTerms)
                constant += statBase
            }

            for (equip in allEquips) {
                val equipVar = equipVars.getValue(equip)
                for ((randomCharacteristic, count) in MASTERY_RANDOM_BY_COUNT) {
                    if (min(count, 1) == 0) continue
                    val value = equip.characteristics[randomCharacteristic] ?: 0
                    if (value != 0) terms.add(Term(equipVar, value.toLong()))
                }
            }

            return LinearTermSum(terms, constant)
        }

        private fun damageMasteryCriticalReach(
            scenario: DamageScenario,
            masteryWeight: Long,
            criticalMasteryWeight: Long,
            guardHi: Long,
        ): LongRange {
            val mastery = damagePreMasteryTerms(scenario) ?: return 0L..guardHi
            if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return 0L..guardHi

            val (criticalTerms, criticalBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
            val terms = mutableListOf<Term>()
            mastery.terms.forEach { terms.add(Term(it.variable, it.coefficient * masteryWeight)) }
            criticalTerms.forEach { terms.add(Term(it.variable, it.coefficient * criticalMasteryWeight)) }
            val constant = mastery.constant * masteryWeight + criticalBase * criticalMasteryWeight

            val masteryReach = reachableSumDomain(mastery.terms, mastery.constant)
            val criticalReach = reachableSumDomain(criticalTerms, criticalBase)
            val clampSlack = masteryWeight * -minOf(0L, masteryReach.first) + criticalMasteryWeight * -minOf(0L, criticalReach.first)
            val hi = (reachableSumDomain(terms, constant).last + clampSlack).coerceAtLeast(0L).coerceAtMost(guardHi)
            return 0L..hi
        }

        /**
         * Elemental mastery for the scenario's spell element, with generic "+all elements" mastery and
         * random-element lines folded in onto that single element — matching how [FindMaxDamageScoring]
         * (via computeCharacteristicsValues with that one wanted element) resolves it.
         */
        private fun scenarioElementMasteryVar(element: Characteristic): IntVar =
            elementVars(
                wantedElements = listOf(element),
                genericCharacteristic = Characteristic.MASTERY_ELEMENTARY,
                targets = mapOf(element to 1),
                randomByCount = MASTERY_RANDOM_BY_COUNT
            ).getValue(element)

        private fun elementMasteryVars(wantedElements: List<Characteristic>): Map<Characteristic, IntVar> =
            elementVars(
                wantedElements = wantedElements,
                genericCharacteristic = Characteristic.MASTERY_ELEMENTARY,
                targets = params.targetStats.masteryElementsWanted,
                randomByCount = MASTERY_RANDOM_BY_COUNT
            )

        private fun elementResistanceVars(wantedElements: List<Characteristic>): Map<Characteristic, IntVar> =
            elementVars(
                wantedElements = wantedElements,
                genericCharacteristic = Characteristic.RESISTANCE_ELEMENTARY,
                targets = params.targetStats.resistanceElementsWanted,
                randomByCount = RESISTANCE_RANDOM_BY_COUNT
            )

        /**
         * Actual per-element value of an elemental mastery/resistance with the generic "+all elements"
         * stat folded in — the way the scorers compute it (see `currentStatSpecificElements`). Falls
         * back to the plain [actualStat] for any non-elemental characteristic. Routing single-element
         * targets through here is what lets the solver see generic-mastery / generic-resistance gear
         * and the Major aptitudes (which carry [Characteristic.MASTERY_ELEMENTARY] /
         * [Characteristic.RESISTANCE_ELEMENTARY]); without it those contributions were invisible and
         * the matching items/aptitudes were never selected.
         */
        private fun foldedElementalStat(characteristic: Characteristic): IntVar =
            when (characteristic) {
                in ELEMENTARY_MASTERIES -> elementMasteryVars(listOf(characteristic)).getValue(characteristic)
                in ELEMENTARY_RESISTANCES -> elementResistanceVars(listOf(characteristic)).getValue(characteristic)
                else -> actualStat(characteristic)
            }

        /**
         * Actual value of a *required* (most-masteries) target. Same as [foldedElementalStat], except
         * the aggregate [Characteristic.RESISTANCE_ELEMENTARY] resolves to the **minimum** of the four
         * folded elemental resistances — the scorer stores that aggregate as the min of the elements
         * (see `computeCharacteristicsValues`), so the constraint must use the min too.
         */
        private fun requiredActualStat(characteristic: Characteristic): IntVar =
            if (characteristic == Characteristic.RESISTANCE_ELEMENTARY) {
                val elementResistances = elementResistanceVars(ELEMENTARY_RESISTANCES)
                val minVar = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "minElementResistance")
                model.addMinEquality(minVar, elementResistances.values.toTypedArray())
                minVar
            } else {
                foldedElementalStat(characteristic)
            }

        /**
         * Builds, for each requested element, an [IntVar] equal to that element's own stat plus the
         * generic "+all elements" stat ([genericCharacteristic]), with random-element lines greedily
         * assigned and percent skills applied — mirroring `computeCharacteristicsValues`. Works for
         * both elemental masteries and elemental resistances.
         */
        private fun elementVars(
            wantedElements: List<Characteristic>,
            genericCharacteristic: Characteristic,
            targets: Map<Characteristic, Int>,
            randomByCount: List<Pair<Characteristic, Int>>,
        ): Map<Characteristic, IntVar> {
            val key = genericCharacteristic to wantedElements.toList()
            return elementCache.getOrPut(key) {
                val genericBase = prePercentStat(genericCharacteristic)
                val baseElements =
                    wantedElements.associateWith { element ->
                        val baseElement = prePercentStat(element)
                        // baseElement + genericBase: both are per-slot-tight, so their interval sum is too.
                        tSumNaive(
                            name = "pre_${element.name}",
                            terms = listOf(Term(baseElement, 1L), Term(genericBase, 1L)),
                            constant = 0L,
                            guardLo = -STAT_WITH_PERCENT_ABS_MAX,
                            guardHi = STAT_WITH_PERCENT_ABS_MAX
                        )
                    }

                // For a monotone objective in the element vars the per-roll greedy ordering is an expensive,
                // provably-suboptimal heuristic, so we let CP-SAT pick the random assignment FREELY (only the
                // cardinality constraint) — the objective drives it to the true optimum — which also removes the
                // O(elements²) ordering that exploded the multi-element pool. The scorer mirrors this exactly.
                //  - most-masteries: the objective is a MIN (mastery always; aggregate resistance when
                //    RESISTANCE_ELEMENTARY is requested) ⇒ exact max-min scorer ([assignMaxMinMasteryRandomValues]).
                //  - precision: the objective is the capped sum (both mastery and resistance) ⇒ exact max-capped
                //    scorer ([assignMaxCappedMasteryRandomValues]).
                // max-damage stays greedy (the objective plays a single element ⇒ assignment is degenerate anyway).
                val freeAssignment =
                    when (params.scoreComputationMode) {
                        ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                            genericCharacteristic == Characteristic.MASTERY_ELEMENTARY ||
                                (
                                    genericCharacteristic == Characteristic.RESISTANCE_ELEMENTARY &&
                                        params.targetStats.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }
                                )

                        ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> true
                        ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> false
                    }
                val prePercentElements =
                    applyGreedyRandom(wantedElements, baseElements, targets, buildRandomEntries(randomByCount), freeAssignment)

                prePercentElements.mapValues { (element, preElement) ->
                    val percentTerms = skillTerms.percent[element].orEmpty()
                    if (percentTerms.isEmpty()) {
                        preElement
                    } else {
                        val percent = tSumNaive("pct_${element.name}", percentTerms, 0L, -PERCENT_ABS_MAX, PERCENT_ABS_MAX)
                        tPercent(preElement, percent, "stat_${element.name}")
                    }
                }
            }
        }

        private fun applyGreedyRandom(
            wantedElements: List<Characteristic>,
            baseElements: Map<Characteristic, IntVar>,
            targets: Map<Characteristic, Int>,
            randomEntries: List<RandomEntry>,
            // When true (most-masteries min objective): pick the per-roll element assignment FREELY — only the
            // cardinality constraint (exactly `count` elements when equipped) — and let the maximized min drive
            // CP-SAT to the optimal assignment. This drops the O(elements²) reified ordering that forces the
            // suboptimal deficit-greedy and explodes the multi-element pool. When false: the original greedy.
            freeAssignment: Boolean,
        ): Map<Characteristic, IntVar> {
            if (wantedElements.isEmpty()) return baseElements
            if (targets.isEmpty()) return baseElements
            if (randomEntries.isEmpty()) return baseElements

            if (wantedElements.size == 1) {
                val element = wantedElements.single()
                val terms = mutableListOf(Term(baseElements.getValue(element), 1L))
                randomEntries
                    .filter { min(it.count, 1) > 0 }
                    .groupingBy { it.equipVar }
                    .fold(0L) { acc, entry -> acc + entry.value.toLong() }
                    .forEach { (equipVar, value) ->
                        if (value != 0L) terms.add(Term(equipVar, value))
                    }
                return mapOf(
                    element to
                        tSum(
                            "rand_${element.name}",
                            terms,
                            0L,
                            reachableSumDomain(terms, 0L),
                            -STAT_WITH_PERCENT_ABS_MAX,
                            STAT_WITH_PERCENT_ABS_MAX
                        )
                )
            }

            val priorityScale = 10L
            val elementCount = wantedElements.size

            var current = baseElements
            randomEntries.forEachIndexed { index, entry ->
                val effectiveCount = min(entry.count, elementCount)
                if (effectiveCount == 0) return@forEachIndexed

                if (effectiveCount == elementCount) {
                    current =
                        wantedElements.associateWith { element ->
                            tSumNaive(
                                "rand_${index}_${element.name}",
                                listOf(Term(current.getValue(element), 1L), Term(entry.equipVar, entry.value.toLong())),
                                0L,
                                -STAT_WITH_PERCENT_ABS_MAX,
                                STAT_WITH_PERCENT_ABS_MAX
                            )
                        }
                    return@forEachIndexed
                }

                val assigns =
                    wantedElements.associateWith { element ->
                        val tag = "assign_${index}_${entry.nameSuffix}_${element.name}"
                        model.newBoolVar(tag).also { tracker.seed(it, 0L..1L, tag) }
                    }

                // Cardinality: exactly `effectiveCount` of the wanted elements get this roll, and only when equipped.
                model.addEquality(
                    LinearExpr.sum(assigns.values.toTypedArray()),
                    LinearExpr.term(entry.equipVar, effectiveCount.toLong())
                )

                if (!freeAssignment) {
                    // Force the chosen elements to be the highest-deficit ones (replicates the scorer's greedy).
                    val priorities = wantedElements.mapIndexed { i, element -> element to (wantedElements.size - i) }.toMap()
                    val adjustedDeficits =
                        wantedElements.associateWith { element ->
                            val currentValue = current.getValue(element)
                            val target = targets.getValue(element).toLong()
                            val deficit =
                                model.newIntVar(
                                    -STAT_WITH_PERCENT_ABS_MAX - 10_000L,
                                    STAT_WITH_PERCENT_ABS_MAX + 10_000L,
                                    "def_${index}_${element.name}"
                                )
                            model.addEquality(
                                deficit,
                                LinearExpr
                                    .newBuilder()
                                    .add(target)
                                    .addTerm(currentValue, -1)
                                    .build()
                            )

                            val adjusted =
                                model.newIntVar(
                                    -STAT_WITH_PERCENT_ABS_MAX * priorityScale,
                                    STAT_WITH_PERCENT_ABS_MAX * priorityScale + priorityScale,
                                    "adj_${index}_${element.name}"
                                )
                            model.addEquality(
                                adjusted,
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(deficit, priorityScale)
                                    .add(priorities.getValue(element).toLong())
                                    .build()
                            )
                            adjusted
                        }

                    for (i in wantedElements) {
                        for (j in wantedElements) {
                            if (i == j) continue
                            model
                                .addGreaterOrEqual(adjustedDeficits.getValue(i), adjustedDeficits.getValue(j))
                                .onlyEnforceIf(arrayOf(assigns.getValue(i), assigns.getValue(j).not()))
                        }
                    }
                }

                current =
                    wantedElements.associateWith { element ->
                        tSumNaive(
                            "rand_${index}_${element.name}",
                            listOf(Term(current.getValue(element), 1L), Term(assigns.getValue(element), entry.value.toLong())),
                            0L,
                            -STAT_WITH_PERCENT_ABS_MAX,
                            STAT_WITH_PERCENT_ABS_MAX
                        )
                    }
            }

            return current
        }

        private fun buildRandomEntries(randomByCount: List<Pair<Characteristic, Int>>): List<RandomEntry> {
            // Grouped by element-count (1s, then 2s, then 3s) to preserve the original assignment order.
            val entriesByCount = randomByCount.map { mutableListOf<RandomEntry>() }

            for (equip in allEquips) {
                val equipVar = equipVars.getValue(equip)
                randomByCount.forEachIndexed { groupIndex, (randomCharacteristic, count) ->
                    val value = equip.characteristics[randomCharacteristic] ?: 0
                    if (value != 0) {
                        entriesByCount[groupIndex].add(RandomEntry(equipVar, value, count, "${count}_${equip.equipmentId}"))
                    }
                }
            }

            return entriesByCount.flatten()
        }

        private fun actualStat(char: Characteristic): IntVar =
            actualCache.getOrPut(char) {
                val pre = prePercentStat(char)
                val percentTerms = skillTerms.percent[char].orEmpty()
                if (percentTerms.isEmpty()) {
                    pre
                } else {
                    val percent = tSumNaive("pct_${char.name}", percentTerms, 0L, -PERCENT_ABS_MAX, PERCENT_ABS_MAX)
                    tPercent(pre, percent, "stat_${char.name}")
                }
            }

        /** Item + rune + fixed-skill terms (and base) for [char], excluding sublimations. */
        private fun baseTermsFor(char: Characteristic): Pair<MutableList<Term>, Long> {
            val terms = mutableListOf<Term>()

            for (equip in allEquips) {
                val value = equip.valueFor(char)
                if (value != 0) {
                    terms.add(Term(equipVars.getValue(equip), value.toLong()))
                }
            }

            // Runes contribute exactly like item stats: a constant value per socketed rune of this
            // stat (max rune level + WakForge doubling on favoured slots), times the per-item rune
            // count var. The socket-cap / equipped-only constraints live on those vars (createRuneModel).
            for ((equip, perStat) in runeModel.runeVars) {
                val runeVar = perStat[char] ?: continue
                // Rune level is capped by the carrier ITEM's level, not the character's (fix 36918746).
                val coefficient = runeModel.coefficientFor(equip, char)
                // Fold model: runeVar is a boolean PICK — one pick fills all `slots` sockets, so it
                // contributes slots·coeff. Count model: runeVar is the count and contributes coeff each.
                val multiplier = if (runeModel.singleTypePerItem) equip.maxShardSlots.toLong() else 1L
                if (coefficient != 0L) {
                    terms.add(Term(runeVar, coefficient * multiplier))
                }
            }
            terms.addAll(runeModel.extraTerms[char].orEmpty())

            terms.addAll(skillTerms.fixed[char].orEmpty())
            val base = baseValues[char]?.toLong() ?: 0L
            return terms to base
        }

        private fun prePercentTermsFor(char: Characteristic): Pair<MutableList<Term>, Long> {
            val (terms, base) = baseTermsFor(char)
            // Sublimation contributions fold in exactly like item/rune stats (FLAT always, STATIC
            // under a reified condition, CONVERSION moving value between two stats).
            terms.addAll(subTermsByStat[char].orEmpty())
            // Selected passives' flat stats fold in the same way (always-on constants).
            terms.addAll(passiveTermsByStat[char].orEmpty())
            // Featherweight-style ramps that TARGET this stat: a build-static clamp(perStep·(source−threshold), 0,
            // cap), gated on the sub. source ≠ target (MP vs DI), so actualStat(source) inside never re-enters here.
            for (sub in perStatStepSpecsByTarget[char].orEmpty()) {
                terms.add(Term(perStatStepGatedVar(sub), 1L))
            }
            return terms to base
        }

        private fun prePercentStat(char: Characteristic): IntVar =
            prePercentCache.getOrPut(char) {
                val (terms, base) = prePercentTermsFor(char)
                tSum("pre_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
            }

        /**
         * Pre-sublimation actual value of [char] (item + rune + skill + base, NO subs) — the base for a
         * CONVERSION's `from` stat and the out-of-combat AP/MP/WP/crit caps. Start-of-combat **conditions** read
         * [preCombatStat] instead (which adds the permanent subs).
         */
        private fun preSubStat(char: Characteristic): IntVar =
            preSubCache.getOrPut(char) {
                val (terms, base) = baseTermsFor(char)
                tSum("preSub_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
            }

        /**
         * Pre-combat (character-sheet) value of [char]: `preSubStat` + the PERMANENT sublimation contributions
         * ([permanentSubTermsByStat]). This is exactly what a build-static start-of-combat condition is evaluated
         * against — a permanent +crit (Influence II) IS part of the pre-combat crit and so can push the build past
         * a CRIT_AT_MOST, whereas a start-of-combat / conditional +crit (Secondary Devastation II, Ambition) is
         * excluded (it is applied *at* combat start, after the condition is read; a conditional sub must never
         * feed its own condition). Acyclic: permanent terms are gated by the raw `subVar`, never a reified bool.
         */
        private fun preCombatStat(char: Characteristic): IntVar =
            preCombatCache.getOrPut(char) {
                val (terms, base) = baseTermsFor(char)
                terms.addAll(permanentSubTermsByStat[char].orEmpty())
                tSum("preCombat_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
            }

        /** AP/MP/WP folding: a `MAX_*` sublimation effect feeds the corresponding usable stat. */
        private fun effectiveStat(char: Characteristic): Characteristic =
            when (char) {
                Characteristic.MAX_ACTION_POINT -> Characteristic.ACTION_POINT
                Characteristic.MAX_MOVEMENT_POINT -> Characteristic.MOVEMENT_POINT
                Characteristic.MAX_WAKFU_POINTS -> Characteristic.WAKFU_POINT
                else -> char
            }

        /** Constant flat-stat contributions of the selected passives (see [resolvedPassives]). */
        private fun buildPassiveTerms(): Map<Characteristic, List<Term>> {
            val map = mutableMapOf<Characteristic, MutableList<Term>>()
            for (passive in resolvedPassives(params)) {
                for ((characteristic, value) in passive.flatStats) {
                    map
                        .getOrPut(effectiveStat(characteristic)) { mutableListOf() }
                        .add(Term(tConst(value.toLong()), 1L))
                }
            }
            return map
        }

        private fun buildSublimationTerms(): Map<Characteristic, List<Term>> {
            val map = mutableMapOf<Characteristic, MutableList<Term>>()
            for ((sub, _) in subModel.subVars) {
                // Combat-conditional subs (only ever forced) reserve their slot/sockets but their
                // situational effects are not auto-credited to the build (could be penalties / unmet).
                if (sub.kind == SublimationKind.COMBAT_CONDITIONAL) continue
                val applies = appliesVar(sub)
                if (sub.kind == SublimationKind.CONVERSION) {
                    val conv = sub.conversion ?: continue
                    // moved = clamp(percent% of the pre-sub `from` stat, >=0), zeroed when not applied.
                    val raw = percentOf(preSubStat(conv.from), conv.percent, "subConv_${sub.stateId}")
                    val moved = tBoolGate("subConvMoved_${sub.stateId}", raw, applies, 0L..STAT_ABS_MAX)
                    map.getOrPut(effectiveStat(conv.to)) { mutableListOf() }.add(Term(moved, 1L))
                    map.getOrPut(effectiveStat(conv.from)) { mutableListOf() }.add(Term(moved, -1L))
                    continue
                }
                val bec = sub.bestElementConcentration
                if (bec != null) {
                    // Elemental Concentration: +damageInflictedBonus% Damage Inflicted, sound-gated so the max-damage
                    // scenario element is the build's strongest — constrain `subVar ≤ strongest`. When it is NOT
                    // strongest the in-game −penalty lands on the scored element and makes the sub strictly worse, so
                    // such a build is never optimal: the guard excludes only dominated builds and never over-credits
                    // the DI. (Only reachable in max-damage single-element — see isModelableSublimation.)
                    model.addLessOrEqual(subModel.subVars.getValue(sub), scenarioElementStrongestVar(sub))
                    map.getOrPut(Characteristic.DAMAGE_INFLICTED) { mutableListOf() }.add(Term(applies, bec.damageInflictedBonus.toLong()))
                    continue
                }
                for (effect in sub.effects) {
                    if (!scenarioGateMatches(effect.scenarioGate, params)) continue
                    val magnitude = effect.magnitudeAtLevel(subModel.characterLevel).toLong()
                    // Per-element DI in most-masteries: route into the element's OWN bucket so it only multiplies
                    // that element's damage fold (NOT the global DI). Other modes (max-damage) keep it global —
                    // there the single scenario element IS the global DI, so the existing routing is correct.
                    val diMastery = effect.scenarioGate?.perElementDiMastery()
                    if (diMastery != null &&
                        effect.characteristic == Characteristic.DAMAGE_INFLICTED &&
                        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                    ) {
                        elementDiTermsByMastery.getOrPut(diMastery) { mutableListOf() }.add(Term(applies, magnitude))
                        continue
                    }
                    map.getOrPut(effectiveStat(effect.characteristic)) { mutableListOf() }.add(Term(applies, magnitude))
                }
            }
            return map
        }

        /**
         * The per-element % Damage Inflicted routed to [mastery]'s element (sum of the chosen Brûlure/Gel/… subs
         * for that element), 0 when none — read only inside that element's own factor in
         * [diAdjustedPerElementMasteryScore]. Most-masteries only; empty in other modes.
         */
        private fun elementDiVar(mastery: Characteristic): IntVar = model.sumVar("mmElemDI_${mastery.name}", elementDiTermsByMastery[mastery].orEmpty(), 0L, 0L, DAMAGE_DI_MAX)

        /**
         * The reified [Sublimation.perStatStep] contribution of [sub] to its target stat:
         * `clamp(perStep·(actualStat(source) − threshold), 0, cap)`, gated to 0 when the sub is not chosen.
         * Memoized per sub. Built lazily (on the first [prePercentTermsFor] of the target, during objective build),
         * so `actualStat(source)` is ready; source ≠ target keeps it acyclic (it never re-enters the target loop).
         */
        private fun perStatStepGatedVar(sub: Sublimation): IntVar =
            perStatStepVarCache.getOrPut(sub.stateId) {
                val spec = sub.perStatStep!!
                // scaled = perStep·source − perStep·threshold  (= perStep·(source − threshold)); clamp to [0, cap].
                val scaled =
                    tSumNaive(
                        "fwScaled_${sub.stateId}",
                        listOf(Term(actualStat(spec.source), spec.perStep.toLong())),
                        -(spec.perStep.toLong() * spec.threshold),
                        -CLAMP_INTERMEDIATE_MAX,
                        CLAMP_INTERMEDIATE_MAX
                    )
                val clamped = tClamp(scaled, 0L, spec.cap.toLong(), "fwClamp_${sub.stateId}")
                tBoolGate("fwGate_${sub.stateId}", clamped, appliesVar(sub), 0L..spec.cap.toLong())
            }

        /**
         * Reified boolean: the max-damage SCENARIO element is (weakly) the build's strongest element — its pre-combat
         * elemental mastery ≥ every other element's. Gates [Sublimation.bestElementConcentration] (Elemental
         * Concentration): only then does the −penalty spare the scored element, so crediting the +DI is exact. The
         * generic "+all elements" mastery is common to all four and cancels in the comparison, so the per-element
         * [preCombatStat] suffices (and matches the re-scorer, which compares the same per-element pre-combat totals).
         */
        private fun scenarioElementStrongestVar(sub: Sublimation): IntVar =
            bestElementStrongestCache.getOrPut(sub.stateId) {
                val scenarioMastery = params.damageScenario.element.masteryCharacteristic
                val others = SpellElement.entries.map { it.masteryCharacteristic }.filter { it != scenarioMastery }
                val maxOther = model.newIntVar(-STAT_ABS_MAX, STAT_ABS_MAX, "becMaxOther_${sub.stateId}")
                model.addMaxEquality(maxOther, others.map { preCombatStat(it) }.toTypedArray())
                // reify (scenario elemental mastery − maxOther ≥ 0) ⇔ scenario element weakly strongest.
                val diff =
                    model.sumVar(
                        "becDiff_${sub.stateId}",
                        listOf(Term(preCombatStat(scenarioMastery), 1L), Term(maxOther, -1L)),
                        0L,
                        -2 * STAT_ABS_MAX,
                        2 * STAT_ABS_MAX
                    )
                reifyGe(diff, 0L, "becStrongest_${sub.stateId}")
            }

        /**
         * The PERMANENT (before-combat) sublimation contributions, grouped by the AP/MP/WP-folded stat — only
         * effects flagged [SublimationEffect.appliesBeforeCombat]. These feed [preCombatStat], the value a
         * start-of-combat condition reads. Gated by the raw `subVar` (these effects live only on condition-less
         * FLAT subs, so `subVar == appliesVar`), which keeps [reifyCondition] → [preCombatStat] acyclic: it
         * never pulls in a conditional sub's own (or any other sub's) reified-condition variable.
         */
        private fun buildPermanentSubTerms(): Map<Characteristic, List<Term>> {
            val map = mutableMapOf<Characteristic, MutableList<Term>>()
            for ((sub, subVar) in subModel.subVars) {
                if (sub.kind == SublimationKind.COMBAT_CONDITIONAL || sub.kind == SublimationKind.CONVERSION) continue
                for (effect in sub.effects) {
                    if (!effect.appliesBeforeCombat) continue
                    if (!scenarioGateMatches(effect.scenarioGate, params)) continue
                    map
                        .getOrPut(effectiveStat(effect.characteristic)) { mutableListOf() }
                        .add(Term(subVar, effect.magnitudeAtLevel(subModel.characterLevel).toLong()))
                }
            }
            return map
        }

        /**
         * Boolean that gates a sub's contributions — always its `subVar`. For a solver-chosen
         * STATIC_CONDITIONAL/CONVERSION sub with a supported condition we additionally constrain
         * `subVar ≤ condHolds`, so the solver may only choose the sub when it arranges the build to
         * satisfy the condition (this is what makes it trade stats to unlock lucrative conditions, and
         * means a chosen sub's effect always applies). Forced subs apply unconditionally.
         */
        private fun appliesVar(sub: Sublimation): IntVar =
            appliesVarCache.getOrPut(sub) {
                val subVar = subModel.subVars.getValue(sub)
                val cond = sub.condition
                if (sub !in subModel.forced && cond != null && cond.type in SUPPORTED_SUB_CONDITIONS) {
                    model.addLessOrEqual(subVar, reifyCondition(cond))
                }
                subVar
            }

        private fun reifyLe(
            value: IntVar,
            n: Long,
            tag: String,
        ): IntVar {
            val b = model.newBoolVar(tag)
            model.addLessOrEqual(value, n).onlyEnforceIf(b)
            model.addGreaterOrEqual(value, n + 1).onlyEnforceIf(b.not())
            return b
        }

        private fun reifyGe(
            value: IntVar,
            n: Long,
            tag: String,
        ): IntVar {
            val b = model.newBoolVar(tag)
            model.addGreaterOrEqual(value, n).onlyEnforceIf(b)
            model.addLessOrEqual(value, n - 1).onlyEnforceIf(b.not())
            return b
        }

        private fun and(
            a: IntVar,
            b: IntVar,
            tag: String,
        ): IntVar {
            val out = model.newBoolVar(tag)
            model.addMultiplicationEquality(out, arrayOf(a, b))
            return out
        }

        /**
         * A reified boolean for a supported [SublimationCondition], evaluated against the **pre-combat**
         * (character-sheet) build stats — [preCombatStat], i.e. base + items + runes + skills + permanent subs.
         * Start-of-combat / conditional sub effects are deliberately excluded (see [preCombatStat]).
         */
        private fun reifyCondition(cond: me.chosante.common.SublimationCondition): IntVar {
            val n = (cond.value ?: 0).toLong()
            val tag = "subCond_${cond.type}_${n}_${appliesVarCache.size}"
            return when (cond.type) {
                SublimationConditionType.AP_AT_MOST -> reifyLe(preCombatStat(Characteristic.ACTION_POINT), n, tag)
                SublimationConditionType.AP_AT_LEAST -> reifyGe(preCombatStat(Characteristic.ACTION_POINT), n, tag)
                SublimationConditionType.AP_EXACT ->
                    and(
                        reifyLe(preCombatStat(Characteristic.ACTION_POINT), n, "${tag}_le"),
                        reifyGe(preCombatStat(Characteristic.ACTION_POINT), n, "${tag}_ge"),
                        tag
                    )

                SublimationConditionType.CRIT_AT_MOST -> reifyLe(preCombatStat(Characteristic.CRITICAL_HIT), n, tag)
                SublimationConditionType.CRIT_AT_LEAST -> reifyGe(preCombatStat(Characteristic.CRITICAL_HIT), n, tag)
                SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> reifyLe(preCombatStat(Characteristic.MASTERY_CRITICAL), n, tag)
                SublimationConditionType.BLOCK_AT_LEAST -> reifyGe(preCombatStat(Characteristic.BLOCK_PERCENTAGE), n, tag)
                SublimationConditionType.RANGE_AT_MOST -> reifyLe(preCombatStat(Characteristic.RANGE), n, tag)
                SublimationConditionType.RANGE_AT_LEAST -> reifyGe(preCombatStat(Characteristic.RANGE), n, tag)
                SublimationConditionType.RANGE_EXACT ->
                    and(
                        reifyLe(preCombatStat(Characteristic.RANGE), n, "${tag}_le"),
                        reifyGe(preCombatStat(Characteristic.RANGE), n, "${tag}_ge"),
                        tag
                    )

                SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> {
                    val threshold = (n * subModel.characterLevel) / 100L
                    reifyLe(preCombatStat(Characteristic.DODGE), threshold - 1, tag)
                }

                SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> {
                    val sum =
                        model.sumVar(
                            "secMast_$tag",
                            me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
                                .map { preCombatStat(it) },
                            -STAT_ABS_MAX,
                            STAT_ABS_MAX
                        )
                    reifyLe(sum, n, tag)
                }

                SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED -> {
                    // Holds iff the build equips no off-hand and no two-handed weapon: the sum of those slots'
                    // pick vars (each 0/1) is 0. Empty pool ⇒ trivially satisfiable.
                    val picks =
                        allEquips
                            .filter { it.itemType == ItemType.OFF_HAND_WEAPONS || it.itemType == ItemType.TWO_HANDED_WEAPONS }
                            .map { equipVars.getValue(it) }
                    if (picks.isEmpty()) {
                        model.newConstant(1L)
                    } else {
                        reifyLe(model.sumVar("offOr2H_$tag", picks, 0L, picks.size.toLong()), 0L, tag)
                    }
                }

                else -> model.newConstant(1L) // unsupported -> treated as always-on (best-achievable)
            }
        }

        /** Non-negative `percent`% of [value] (integer-floored), as a fresh variable. */
        private fun percentOf(
            value: IntVar,
            percent: Int,
            name: String,
        ): IntVar {
            val scaledReach = mulRange(tracker.of(value), percent.toLong()..percent.toLong())
            val (sLo, sHi) = tracker.decl(scaledReach, -PRODUCT_ABS_MAX, PRODUCT_ABS_MAX)
            val scaled = model.newIntVar(sLo, sHi, "${name}_scaled")
            model.addEquality(scaled, LinearExpr.term(value, percent.toLong()))
            tracker.record(scaled, scaledReach, "${name}_scaled")

            val quotientReach = scaledReach.first / 100..scaledReach.last / 100
            val (qLo, qHi) = tracker.decl(quotientReach, -(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1)
            val quotient = model.newIntVar(qLo, qHi, "${name}_q")
            model.addDivisionEquality(quotient, scaled, model.newConstant(100L))
            tracker.record(quotient, quotientReach, "${name}_q")

            val positiveReach = maxOf(0L, quotientReach.first)..maxOf(0L, quotientReach.last)
            val (pLo, pHi) = tracker.decl(positiveReach, 0L, STAT_ABS_MAX)
            val positive = model.newIntVar(pLo, pHi, "${name}_pos")
            model.addMaxEquality(positive, arrayOf(quotient, model.newConstant(0L)))
            return tracker.record(positive, positiveReach, "${name}_pos")
        }
    }

    private data class Term(
        val variable: IntVar,
        val coefficient: Long,
    )

    private data class RandomEntry(
        val equipVar: IntVar,
        val value: Int,
        val count: Int,
        val nameSuffix: String,
    )

    /**
     * Per-search rune modelling: the rune for each covered [Characteristic], the per-(item, stat) count
     * variables (only for socketable items), and the character level the rune values were computed for.
     * [EMPTY] means runes are disabled or no requested stat has a rune.
     */
    private class RuneModel(
        val runeByCharacteristic: Map<Characteristic, RuneType>,
        val runeVars: Map<Equipment, Map<Characteristic, IntVar>>,
        /**
         * True ⇒ [runeVars] are boolean single-type PICKS (one chosen type fills every socket of the item)
         * rather than per-stat counts, so a pick contributes `slots·coeff` and its leaf domain is 0..1. See
         * the rune fold in [createRuneModel].
         */
        val singleTypePerItem: Boolean = false,
        private val runeTypeByVar: Map<IntVar, RuneType> = emptyMap(),
        private val coefficientByVar: Map<IntVar, Long> = emptyMap(),
        val extraTerms: Map<Characteristic, List<Term>> = emptyMap(),
        private val suppressedBy: Map<Pair<Equipment, Characteristic>, IntVar> = emptyMap(),
    ) {
        fun runeTypeFor(
            variable: IntVar,
            characteristic: Characteristic,
        ): RuneType? = runeTypeByVar[variable] ?: runeByCharacteristic[characteristic]

        fun coefficientFor(
            equip: Equipment,
            characteristic: Characteristic,
        ): Long {
            val variable = runeVars[equip]?.get(characteristic) ?: return 0L
            return coefficientByVar[variable]
                ?: runeByCharacteristic[characteristic]?.valueOn(equip.itemType, equip.level)?.toLong()
                ?: 0L
        }

        fun isSuppressed(
            equip: Equipment,
            characteristic: Characteristic,
            valueOf: (IntVar) -> Long,
        ): Boolean = suppressedBy[equip to characteristic]?.let { valueOf(it) > 0L } == true

        companion object {
            val EMPTY = RuneModel(emptyMap(), emptyMap())
        }
    }

    /**
     * Per-search sublimation modelling: the chosen/forced boolean for each modeled sub, the set of subs
     * the user forced (applied unconditionally), and the character level. [EMPTY] means no sub is modeled.
     */
    private class SublimationModel(
        val subVars: Map<Sublimation, IntVar>,
        val forced: Set<Sublimation>,
        val characterLevel: Int,
    ) {
        companion object {
            val EMPTY = SublimationModel(emptyMap(), emptySet(), 0)
        }
    }

    private data class SkillTerms(
        val fixed: Map<Characteristic, List<Term>>,
        val percent: Map<Characteristic, List<Term>>,
    )

    private data class PowerTable(
        val values: LongArray,
        val maxValue: Long,
    )

    // Splits each skill's contribution into fixed / percent terms keyed by characteristic, mirroring
    // CharacteristicValues. The Major "% Inflicted Damage" aptitude lands in fixed[DAMAGE_INFLICTED];
    // only the max-damage objective reads that stat, so it stays inert in the most-masteries / precision
    // modes — exactly like the scorer. See the NOTE in computeCharacteristicsValues.
    private fun buildSkillTerms(skillVars: Map<SkillCharacteristic, IntVar>): SkillTerms {
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

    private fun Equipment.valueFor(char: Characteristic): Int {
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

    private fun CpModel.sumVar(
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

    private fun CpModel.sumVar(
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
    private fun CpModel.clampVar(
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
    private fun mulRange(
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

    private fun ceilDivPositive(
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
private fun scenarioMasteryStats(scenario: DamageScenario): List<Characteristic> =
    buildList {
        add(Characteristic.MASTERY_ELEMENTARY)
        add(scenario.element.masteryCharacteristic)
        add(scenario.rangeBand.masteryCharacteristic)
        if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
        if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) add(Characteristic.MASTERY_HEALING)
    }
