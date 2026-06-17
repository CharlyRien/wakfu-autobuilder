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
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
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
    private const val MASTERY_SCORE_ABS_MAX = 100_000_000L
    private const val MAX_POWER_TABLE_INDEX = 2_000
    private const val MAX_PENALTY_MULTIPLIER = 1_000_000L
    private const val MAX_SUBLIMATIONS = 10L // Wakfu: at most 10 sublimations on a build (incl. ≤1 epic, ≤1 relic).
    private const val NORMAL_SUB_SOCKET_COST = 3L // a normal sublimation occupies 3 sockets on its carrier item.

    // Out-of-combat hardcaps (Wakfu): the equipped sheet can't exceed these. In-combat bonuses — including
    // start-of-combat sublimations — may go beyond, so the cap is on the PRE-sublimation value.
    private const val MAX_OUT_OF_COMBAT_AP = 16L
    private const val MAX_OUT_OF_COMBAT_MP = 8L
    private const val MAX_OUT_OF_COMBAT_WP = 20L
    private const val MIN_OUT_OF_COMBAT_CRIT = -9L // negative-crit gear is condition-limited to ≥ −9% total.

    // Bounds for the max-damage objective's nonlinear terms. Masteries / DI are clamped into these
    // (well above any real build) so the CP-SAT multiplication variables keep small, stable domains.
    private const val DAMAGE_MASTERY_MAX = 100_000L
    private const val DAMAGE_DI_MAX = 5_000L
    private const val DAMAGE_DI_FLOOR = 50L
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
     * Only elemental / resistance targets pull in the per-item random-element modelling that makes
     * the full late-game pool intractable; everything else solves to a proven global optimum on the
     * full pool, so we keep the prefilter (which trades global optimality for tractability) opt-in.
     */
    private fun needsItemPrefilter(targetStats: TargetStats): Boolean = targetStats.masteryElementsWanted.isNotEmpty() || targetStats.resistanceElementsWanted.isNotEmpty()

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
    )

    fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, sublimations, tuning = null)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = optimize(params, equipmentsByItemType, emptyList(), emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> =
        callbackFlow {
            withContext(Dispatchers.IO) {
                val built = buildModel(params, equipmentsByItemType, runes, sublimations)
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
    )

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
    ): BuiltModel {
        val model = CpModel()

        // Full pool gives the provable *global* optimum and stays tractable for most queries.
        // Only elemental/resistance targets activate the heavy random-element modelling that
        // explodes on the full late-game pool, so we prefilter exactly (and only) those cases.
        val pool =
            if (needsItemPrefilter(params.targetStats)) {
                prefilterRelevantEquipments(equipmentsByItemType, params)
            } else {
                equipmentsByItemType
            }
        val allEquips = orderEquipments(pool)
        val equipVars = model.createEquipmentVariables(allEquips)
        val skillVars = model.createSkillVariables(params.character.characterSkills)
        val runeModel = model.createRuneModel(params, allEquips, equipVars, runes)
        val subModel = model.createSublimationModel(params, allEquips, equipVars, sublimations)
        model.addNormalSublimationSocketBudget(allEquips, equipVars, runeModel, subModel)

        model.addBuildValidityConstraints(allEquips, equipVars)

        val objective =
            when (params.scoreComputationMode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                    model.buildMostMasteriesObjective(params, allEquips, equipVars, skillVars, runeModel, subModel)

                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                    model.buildPrecisionObjective(params, allEquips, equipVars, skillVars, runeModel, subModel)

                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                    model.buildMaxDamageObjective(params, allEquips, equipVars, skillVars, runeModel, subModel)
            }
        model.maximize(objective)

        return BuiltModel(model, objective, allEquips, equipVars, skillVars, runeModel, subModel)
    }

    /**
     * Test-only: assemble the model exactly like [optimize] (via [buildModel]) and return the **maximized
     * objective value**, requiring a deterministic [SolverTuning] and a proven `OPTIMAL` status. Needed because
     * the streamed [GeneticAlgorithmResult.matchPercentage] is computed by the single-element scorer and so
     * cannot read the bi-element objective for an interior split — see the Lot 2 tests in WakfuBuildSolverTest.
     */
    internal fun maxDamageObjectiveValueForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
    ): Long {
        val built = buildModel(params, equipmentsByItemType, emptyList(), emptyList())
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL) { "expected OPTIMAL, got $status" }
        return solver.objectiveValue().toLong()
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
                val skillVar = newIntVar(0, skill.maxPointsAssignable.toLong(), varName)
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

        val runeVars = mutableMapOf<Equipment, Map<Characteristic, IntVar>>()
        for (equip in allEquips) {
            val slots = equip.maxShardSlots
            if (slots <= 0) continue
            val perStat = runeStats.associateWith { stat -> newIntVar(0, slots.toLong(), "rune_${equip.equipmentId}_${stat.name}") }
            // Sockets only count when the item is equipped: Σ runeCount ≤ slots · selected (linear).
            val capExpr = LinearExpr.newBuilder()
            perStat.values.forEach { capExpr.addTerm(it, 1L) }
            capExpr.addTerm(equipVars.getValue(equip), -slots.toLong())
            addLessOrEqual(capExpr.build(), 0L)
            runeVars[equip] = perStat
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
        return RuneModel(runeByCharacteristic, runeVars)
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
            SublimationConditionType.BLOCK_AT_LEAST,
            SublimationConditionType.RANGE_AT_MOST,
            SublimationConditionType.RANGE_AT_LEAST,
            SublimationConditionType.RANGE_EXACT,
            SublimationConditionType.DODGE_LT_PCT_OF_LEVEL,
            SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
        )

    /** A solver-choosable sub the engine can correctly model in this request's mode/scenario. */
    private fun isModelableSublimation(
        sub: Sublimation,
        params: WakfuBestBuildParams,
    ): Boolean {
        if (!sub.solverChoosable) return false
        // Conversions are handled by a dedicated path; static-conditionals need a supported condition.
        when (sub.kind) {
            SublimationKind.CONVERSION -> if (sub.conversion == null) return false
            SublimationKind.STATIC_CONDITIONAL ->
                if (sub.condition == null || sub.condition!!.type !in SUPPORTED_SUB_CONDITIONS) return false
            SublimationKind.FLAT -> {}
            SublimationKind.COMBAT_CONDITIONAL -> return false
        }
        // It must be able to contribute *something* in this mode/scenario.
        val hasUsableEffect = sub.effects.any { scenarioGateMatches(it.scenarioGate, params) }
        return hasUsableEffect || sub.conversion != null
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
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return false
        val s = params.damageScenario
        gate.rangeBand?.let { if (s.rangeBand.name != it) return false }
        gate.orientation?.let { if (s.orientation.name != it) return false }
        if (gate.berserk == true && !s.berserk) return false
        if (gate.ranged == true && s.rangeBand.name != "DISTANCE") return false
        gate.minCharacterLevel?.let { if (params.character.level < it) return false }
        return true
    }

    /**
     * Models the chosen/forced sublimations. Each modeled sub gets a [SublimationModel.subVars] boolean.
     * Epic/relic subs are gated to an equipped epic/relic item — their dedicated slot comes from that carrier
     * ([gateSublimationsOnCarrierItems]); a normal sub is assigned to one ≥3-socket carrier item and consumes
     * 3 of its sockets ([addNormalSublimationSocketBudget]), so it can't share sockets across items. At most 10
     * sublimations per build. Socket colours stay optimistic/re-rollable (no per-item colour data); the chosen
     * carrier + pattern are surfaced in the GUI. Effect contributions fold into the stat term loop by
     * [StatBuilder]; forced subs apply unconditionally (the user takes responsibility).
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

        // Tie each NORMAL sub to exactly one carrier item with ≥3 sockets (y[sub,item]=1 ⇒ that item hosts it).
        // The sub then consumes 3 of that item's sockets (addNormalSublimationSocketBudget) — i.e. it changes
        // that item's enchantment loadout. Epic/relic subs need no socket carrier (gated on the rarity item above).
        val normalSubs = subVars.keys.filter { it.rarity == SublimationRarity.NORMAL }
        val carrierItems = allEquips.filter { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }
        val carrierVars = LinkedHashMap<Sublimation, Map<Equipment, IntVar>>()
        for (sub in normalSubs) {
            val perItem = carrierItems.associateWith { item -> newBoolVar("subCarrier_${sub.stateId}_${item.equipmentId}") }
            carrierVars[sub] = perItem
            // Chosen ⇔ assigned to exactly one carrier; a carrier must itself be equipped.
            val assigned = LinearExpr.newBuilder()
            perItem.values.forEach { assigned.addTerm(it, 1L) }
            addEquality(assigned.build(), subVars.getValue(sub))
            for ((item, y) in perItem) addLessOrEqual(y, equipVars.getValue(item))
        }
        // A single ≥3-socket item physically hosts at most one 3-socket normal sublimation.
        for (item in carrierItems) {
            val hostsOnItem = normalSubs.mapNotNull { carrierVars[it]?.get(item) }
            if (hostsOnItem.size > 1) addLessOrEqual(LinearExpr.sum(hostsOnItem.toTypedArray()), 1L)
        }

        return SublimationModel(subVars, forcedSubs.toSet(), params.character.level, carrierVars)
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
     * Objective for "most masteries" mode: maximize only the *requested* masteries under the
     * required-stat constraints. There is deliberately no tie-breaker that fills otherwise-empty
     * slots, so a slot whose items cannot improve any requested stat is left empty in the proven
     * optimum. This is why, e.g., an item set asking only for distance mastery + AP/MP/HP comes
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

        val masteryScore = statBuilder.finalMasteryScore(targetStats, targetCharacteristics)

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
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
    ): IntVar {
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars, runeModel, subModel)
        statBuilder.applyOutOfCombatCaps()
        // External-loop AP probe: pin the build to exactly N AP so each breakpoint can be evaluated.
        params.maxDamageApTarget?.let { addEquality(statBuilder.actionPointVar(), newConstant(it.toLong())) }
        // Bi-element (Lot 2): when a split is given, optimize the element PAIR (the split + total AP are pinned,
        // outer-loop constants); otherwise the single/boss per-element objective. Both return a var on the SAME
        // [DAMAGE_PERTURN_ABS_MAX] domain, so the external max over (mono OR bi, AP, split) is comparable.
        val damageScore =
            params.maxDamageBiElement?.let { bi ->
                val totalAp =
                    requireNotNull(params.maxDamageApTarget) { "bi-element requires a pinned total AP (maxDamageApTarget)" }
                statBuilder.perTurnDamageScoreBiElement(
                    bi.first,
                    bi.second,
                    bi.apSplitOnFirst,
                    totalAp,
                    params.damageScenario,
                    params.character.clazz
                )
            } ?: statBuilder.perTurnDamageScore(params.damageScenario, params.character.clazz)
        // Survivability soft-floor (opt-in): gently tax the damage score when the build's effective-HP
        // proxy is below the floor, BEFORE the hard-target penalty. Folding it into the core score (rather
        // than chaining a second multiply onto the already-near-Long.MAX/2 penalized objective) keeps the
        // objective on the same DAMAGE_PERTURN_ABS_MAX domain, so applyConstraintPenalty's bounds are
        // untouched and the external max over (mono | bi, AP, split) stays comparable.
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
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
    ): IntVar {
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars, runeModel, subModel)
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
        scope: ProducerScope<GeneticAlgorithmResult<BuildCombination>>,
        tuning: SolverTuning?,
    ) {
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        if (tuning == null) {
            // Production: a parallel portfolio (CP-SAT's equivalent of the GA's parallel scoring),
            // bounded by the user's wall-clock search duration. Presolve is capped because full
            // presolve on this objective never terminates within that budget; the prefiltered
            // (small) model keeps even a light presolve effective.
            //
            // We deliberately leave one core for the host: CP-SAT spawns this many *native* threads
            // and pins them at 100%, which otherwise starves the Compose render thread / EDT and
            // makes the GUI window visibly freeze during a search. One fewer worker is a negligible
            // throughput loss next to a responsive UI (and keeps the terminal responsive for the CLI).
            solver.parameters.maxPresolveIterations = 1
            solver.parameters.maxTimeInSeconds = params.searchDuration.inWholeSeconds.toDouble()
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
                override fun onSolutionCallback() {
                    // The native solve blocks the IO thread, so coroutine cancellation can't interrupt
                    // it directly; stopping the search here (next time a solution is found) is the
                    // CP-SAT idiom that lets the GUI's cancel actually end the work.
                    if (!scope.isActive) {
                        stopSearch()
                        return
                    }

                    val combination = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { value(it) }
                    val actualScore = scoreFor(params, combination)

                    val progress = ((System.currentTimeMillis() - startTime).toDouble() / params.searchDuration.inWholeMilliseconds.toDouble() * 100).toInt()
                    scope.trySend(GeneticAlgorithmResult(combination, actualScore, progress.coerceAtMost(100)))
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
                        GeneticAlgorithmResult(
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
                        val count = valueOf(runeVar).toInt()
                        val rune = runeModel.runeByCharacteristic[stat]
                        if (count > 0 && rune != null) List(count) { rune } else emptyList()
                    }
                }.filterValues { it.isNotEmpty() }

        // Sublimations keyed by carrier item: a normal sub by its assignment var, an epic/relic sub by the
        // equipped epic/relic item (whose dedicated slot hosts it). This is what the GUI renders per item.
        val epicItem = equippedItems.firstOrNull { it.rarity == Rarity.EPIC }
        val relicItem = equippedItems.firstOrNull { it.rarity == Rarity.RELIC }
        val sublimationsByItem = mutableMapOf<Equipment, MutableList<Sublimation>>()
        for ((sub, subVar) in subModel.subVars) {
            if (valueOf(subVar) <= 0L) continue
            val carrier =
                when (sub.rarity) {
                    SublimationRarity.NORMAL ->
                        subModel.carrierVars[sub]
                            ?.entries
                            ?.firstOrNull { valueOf(it.value) > 0L }
                            ?.key
                    SublimationRarity.EPIC -> epicItem
                    SublimationRarity.RELIC -> relicItem
                }
            if (carrier != null) sublimationsByItem.getOrPut(carrier) { mutableListOf() }.add(sub)
        }

        return BuildCombination(equippedItems, optimizedSkills, runes, sublimationsByItem, resolvedPassives(params))
    }

    private class StatBuilder(
        private val model: CpModel,
        private val params: WakfuBestBuildParams,
        private val allEquips: List<Equipment>,
        private val equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        private val runeModel: RuneModel,
        private val subModel: SublimationModel,
    ) {
        private val baseValues = params.character.baseCharacteristicValues
        private val skillTerms = buildSkillTerms(skillVars)

        private val prePercentCache = mutableMapOf<Characteristic, IntVar>()
        private val preSubCache = mutableMapOf<Characteristic, IntVar>()
        private val actualCache = mutableMapOf<Characteristic, IntVar>()
        private val elementCache = mutableMapOf<Pair<Characteristic, List<Characteristic>>, Map<Characteristic, IntVar>>()
        private val appliesVarCache = mutableMapOf<Sublimation, IntVar>()

        // Sublimation stat contributions folded into the term loop, grouped by the (AP/MP/WP-folded)
        // characteristic they feed. Built eagerly so prePercentStat sees them; conversions are excluded
        // here and applied by [conversionContributions]. Conditions reify against [preSubStat] (the
        // pre-sublimation stat value) to keep the constraint network acyclic.
        private val subTermsByStat: Map<Characteristic, List<Term>> = buildSublimationTerms()

        // The selected passives' flat stats ([Passive.flatStats] — the extractor's permanent + unconditional
        // + flat + positive subset, safe to fold for ANY passive), added as constants (a passive is a fixed
        // player choice, not a solver variable). The conditional/triggered part of a passive (combat state
        // the static solver can't see) is not modeled; the full loadout still rides on the build for display.
        // Grouped by the AP/MP/WP-folded stat, like [subTermsByStat].
        private val passiveTermsByStat: Map<Characteristic, List<Term>> = buildPassiveTerms()

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

        fun finalMasteryScore(
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

            // Fold the generic "+all elements" stat into every wanted element (matching the scorer's
            // computeCharacteristicsValues), but take the minimum over only the elements the user
            // actually asked for: a co-requested MASTERY_ELEMENTARY no longer forces balancing the
            // off-elements. minElements is always a subset of foldElements — see
            // TargetStats.masteryElementsToMinimize.
            val foldElements = targetStats.masteryElementsWanted.keys.toList()
            val minElements = targetStats.masteryElementsToMinimize

            val lowestElementMastery =
                if (minElements.isEmpty()) {
                    model.newConstant(0L)
                } else {
                    val elementVars = elementMasteryVars(foldElements)
                    val minVar = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "minElementMastery")
                    model.addMinEquality(minVar, minElements.map { elementVars.getValue(it) }.toTypedArray())
                    minVar
                }

            val total = model.newIntVar(-MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX, "masteryScore")
            val sumExpr =
                LinearExpr
                    .newBuilder()
                    .addTerm(nonElemSum, 1)
                    .addTerm(negativePenalty, 1)
                    .addTerm(lowestElementMastery, 1)
                    .build()
            model.addEquality(total, sumExpr)
            return total
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
            model.addLessOrEqual(preSubStat(Characteristic.MOVEMENT_POINT), MAX_OUT_OF_COMBAT_MP)
            model.addLessOrEqual(preSubStat(Characteristic.WAKFU_POINT), MAX_OUT_OF_COMBAT_WP)
            // Negative-crit gear is condition-limited: the sheet can't drop below −9% Critical Hit.
            model.addGreaterOrEqual(preSubStat(Characteristic.CRITICAL_HIT), MIN_OUT_OF_COMBAT_CRIT)
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
        ): IntVar {
            val apVar = model.clampVar(actualStat(Characteristic.ACTION_POINT), 0L, MAX_ROTATION_AP, "rotationAp")
            val perElementDamage =
                scenario.candidateElements().mapNotNull { (element, resistance) ->
                    val spells =
                        SpellCatalog.damageSpells(clazz).filter {
                            it.element ==
                                me.chosante.common.SpellElement
                                    .valueOf(element.name)
                        }
                    val table = SpellRotationOptimizer.baseThroughputTable(spells, MAX_ROTATION_AP.toInt())
                    if (table.all { it == 0L }) return@mapNotNull null

                    if (table.max() > PER_TURN_THROUGHPUT_MAX) {
                        System.err.println(
                            "WARN: per-turn throughput for ${element.name} (${table.max()}) exceeds the " +
                                "CP-SAT cap PER_TURN_THROUGHPUT_MAX=$PER_TURN_THROUGHPUT_MAX; the cap is binding and " +
                                "distorts the max-damage objective — raise it for this dataset."
                        )
                    }
                    val clampedTable = LongArray(table.size) { table[it].coerceAtMost(PER_TURN_THROUGHPUT_MAX) }
                    val throughput = model.newIntVar(0L, clampedTable.max(), "throughput_${element.name}")
                    model.addElement(apVar, clampedTable, throughput)

                    // raw = throughput · (perHit ÷ PERHIT_DOWNSCALE) — the per-hit core scaled down to a
                    // modest domain before the multiplication, so the product stays small for CP-SAT.
                    val perHit = perHitDamageScore(scenarioElementMasteryVar(element.masteryCharacteristic), element.masteryCharacteristic.name, scenario)
                    val perHitScaled = model.newIntVar(0L, PERHIT_SCALED_MAX, "perHitScaled_${element.name}")
                    model.addDivisionEquality(perHitScaled, perHit, model.newConstant(PERHIT_DOWNSCALE))
                    val raw = model.newIntVar(0L, ROTATION_RAW_MAX, "rotRaw_${element.name}")
                    model.addMultiplicationEquality(raw, arrayOf(throughput, perHitScaled))

                    // Fold in the boss's per-element resistance, then scale down once into the penalty's range.
                    val resFactor = (100L - resistance).coerceIn(RES_FACTOR_MIN, RES_FACTOR_MAX)
                    val rawWithRes = model.newIntVar(0L, ROTATION_RAW_RES_MAX, "rotRawRes_${element.name}")
                    model.addEquality(rawWithRes, LinearExpr.term(raw, resFactor))
                    val damage = model.newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "rotDamage_${element.name}")
                    model.addDivisionEquality(damage, rawWithRes, model.newConstant(FINAL_DOWNSCALE))
                    damage
                }

            return when (perElementDamage.size) {
                0 -> model.newConstant(0L)
                1 -> perElementDamage.single()
                else -> {
                    val best = model.newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "rotBestElement")
                    model.addMaxEquality(best, perElementDamage.toTypedArray())
                    best
                }
            }
        }

        /**
         * Spell/boss-aware per-turn damage for a BI-ELEMENT split (Lot 2): the pair [elementA]+[elementB] with
         * [apOnA] AP routed to [elementA]'s rotation and `[totalAp] − [apOnA]` to [elementB]'s. The split and total
         * AP are **outer-loop constants** (from [WakfuBestBuildParams.maxDamageBiElement] + `maxDamageApTarget`),
         * so each element's throughput `T_e[ap]` is injected as a literal and the objective is
         * `Σ_e resFactor_e · T_e[ap_e] · perHit_e` — purely **linear** (no spell-count × mastery product; contrast
         * the mono [perTurnDamageScore], whose `addElement` + `addMultiplicationEquality` IS that bilinear term).
         *
         * **Scale-identical to [perTurnDamageScore]**: each element reuses the exact mono pipeline
         * (perHit → ÷PERHIT_DOWNSCALE → ×T → ×resFactor → ÷FINAL_DOWNSCALE), so at a degenerate split
         * (`apOnA = 0` or `= totalAp`) the other element's `T` is 0, it drops, and the result reproduces the mono
         * objective for the surviving element exactly ⇒ bi ≥ mono (domination). The two per-element damages are
         * summed then **clamped to [DAMAGE_PERTURN_ABS_MAX]**: the sum's natural bound is 2×, but clamping keeps the
         * objective on the mono domain (so the external `max` over mono/bi is sound) AND keeps the constraint-penalty
         * multiply overflow-safe (see [applyConstraintPenalty]); the clamp is physically non-binding (the cap is
         * orders of magnitude above any real per-turn value).
         *
         * The **single** [elementVars] call over both mastery characteristics is the double-count fix (Lot 2b):
         * generic "+all elements" folds in full onto each element's own damage and `*_RANDOM_ELEMENT` lines split
         * across the pair (`effectiveCount = min(count, 2)`) — two singleton calls would add generic mastery to
         * both cores and assign a 1-random line in full to each. Crit / DI / range / rear are build-global and
         * shared by both cores (read inside [perHitDamageScore]) — correctly NOT split.
         */
        fun perTurnDamageScoreBiElement(
            elementA: me.chosante.autobuilder.domain.SpellElement,
            elementB: me.chosante.autobuilder.domain.SpellElement,
            apOnA: Int,
            totalAp: Int,
            scenario: DamageScenario,
            clazz: me.chosante.common.CharacterClass,
        ): IntVar {
            require(elementA != elementB) { "bi-element requires two distinct elements" }
            val pair = listOf(elementA, elementB)
            val resByElement = scenario.candidateElements().toMap()
            val apByElement = mapOf(elementA to apOnA, elementB to (totalAp - apOnA))

            // (Lot 2b) ONE elementVars call over both elements' masteries — see the KDoc. The cache key
            // (MASTERY_ELEMENTARY, [eA, eB]) is distinct from the mono singleton keys, and the mono/bi paths are
            // mutually exclusive within a solve, so there is no cache collision.
            val masteryVars =
                elementVars(
                    wantedElements = pair.map { it.masteryCharacteristic },
                    genericCharacteristic = Characteristic.MASTERY_ELEMENTARY,
                    targets = pair.associate { it.masteryCharacteristic to 1 },
                    randomByCount = MASTERY_RANDOM_BY_COUNT
                )

            val perElementDamage =
                pair.mapNotNull { element ->
                    // A pair element absent from the scenario's resistances degenerates to mono (don't invent a
                    // neutral resistance — mirrors candidateElements()).
                    val resistance = resByElement[element] ?: return@mapNotNull null
                    val ap = apByElement.getValue(element).coerceIn(0, MAX_ROTATION_AP.toInt())
                    val spells =
                        SpellCatalog.damageSpells(clazz).filter {
                            it.element ==
                                me.chosante.common.SpellElement
                                    .valueOf(element.name)
                        }
                    val table = SpellRotationOptimizer.baseThroughputTable(spells, MAX_ROTATION_AP.toInt())
                    val throughput = table[ap].coerceAtMost(PER_TURN_THROUGHPUT_MAX) // same clamp as mono clampedTable
                    if (throughput == 0L) return@mapNotNull null // dead element (no spells) or a=0 slice ⇒ contributes 0

                    val s = "${element.name}_bi"
                    val perHit = perHitDamageScore(masteryVars.getValue(element.masteryCharacteristic), s, scenario)
                    val perHitScaled = model.newIntVar(0L, PERHIT_SCALED_MAX, "perHitScaled_$s")
                    model.addDivisionEquality(perHitScaled, perHit, model.newConstant(PERHIT_DOWNSCALE))

                    // raw = T · perHitScaled — T is an outer-loop CONSTANT, so this is LINEAR (no variable×variable).
                    val raw = model.newIntVar(0L, ROTATION_RAW_MAX, "rotRaw_$s")
                    model.addEquality(raw, LinearExpr.term(perHitScaled, throughput))

                    val resFactor = (100L - resistance).coerceIn(RES_FACTOR_MIN, RES_FACTOR_MAX)
                    val rawWithRes = model.newIntVar(0L, ROTATION_RAW_RES_MAX, "rotRawRes_$s")
                    model.addEquality(rawWithRes, LinearExpr.term(raw, resFactor))
                    val damage = model.newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "rotDamage_$s")
                    model.addDivisionEquality(damage, rawWithRes, model.newConstant(FINAL_DOWNSCALE))
                    damage
                }

            return when (perElementDamage.size) {
                0 -> model.newConstant(0L)
                1 -> perElementDamage.single() // a pair with one dead element degenerates to mono (exact, no clamp)
                else -> {
                    val sum = model.sumVar("rotBiSum", perElementDamage, 0L, 2L * DAMAGE_PERTURN_ABS_MAX)
                    val total = model.newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "rotBiTotal")
                    model.addMinEquality(total, arrayOf(sum, model.newConstant(DAMAGE_PERTURN_ABS_MAX)))
                    total
                }
            }
        }

        /**
         * Build-dependent per-hit core `D · Graw` for an element whose folded elemental-mastery var is
         * [elementMasteryVar] (see [perTurnDamageScore]). Taking the mastery var as a parameter — rather
         * than computing it internally — lets the bi-element path feed both cores from a *single*
         * [elementVars] call over the element pair, so generic "+all elements" and random-element mastery
         * are folded once per element rather than double-counted. All masteries / DI / crit are clamped
         * into the damage bounds so the two CP-SAT multiplications stay on small, stable integer domains.
         * Var names carry [suffix] so per-element cores never collide.
         */
        private fun perHitDamageScore(
            elementMasteryVar: IntVar,
            suffix: String,
            scenario: DamageScenario,
        ): IntVar {
            val s = suffix
            val masteryTerms = mutableListOf<Term>()
            masteryTerms.add(Term(elementMasteryVar, 1L))
            masteryTerms.add(Term(actualStat(scenario.rangeBand.masteryCharacteristic), 1L))
            if (scenario.orientation.grantsRearMastery) masteryTerms.add(Term(actualStat(Characteristic.MASTERY_BACK), 1L))
            if (scenario.berserk) masteryTerms.add(Term(actualStat(Characteristic.MASTERY_BERSERK), 1L))
            if (scenario.healing) masteryTerms.add(Term(actualStat(Characteristic.MASTERY_HEALING), 1L))

            // M = clamp(100 + ΣMastery, 0, MAX); criticalMastery and DI clamped likewise.
            val preM = model.sumVar("dmgPreM_$s", masteryTerms, 100L, -CLAMP_INTERMEDIATE_MAX, CLAMP_INTERMEDIATE_MAX)
            val m = model.clampVar(preM, 0L, DAMAGE_MASTERY_MAX, "dmgM_$s")
            val criticalMastery = model.clampVar(actualStat(Characteristic.MASTERY_CRITICAL), 0L, DAMAGE_MASTERY_MAX, "dmgCriticalMastery_$s")
            val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
            val crit = model.clampVar(actualStat(Characteristic.CRITICAL_HIT), 0L, critCap, "dmgCrit_$s")
            val di = model.clampVar(actualStat(Characteristic.DAMAGE_INFLICTED), -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "dmgDI_$s")
            val d = model.sumVar("dmgD_$s", listOf(Term(di, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)

            // diff = M + 5·criticalMastery ; term = crit · diff ; Graw = 400·M + term.
            val diff = model.sumVar("dmgDiff_$s", listOf(Term(m, 1L), Term(criticalMastery, 5L)), 0L, 0L, DAMAGE_MASTERY_MAX * 6)
            val term = model.newIntVar(0L, 100L * DAMAGE_MASTERY_MAX * 6, "dmgCritTerm_$s")
            model.addMultiplicationEquality(term, arrayOf(crit, diff))
            val graw = model.sumVar("dmgGraw_$s", listOf(Term(m, 400L), Term(term, 1L)), 0L, 0L, DAMAGE_GRAW_MAX)

            val damageScore = model.newIntVar(0L, DAMAGE_SCORE_ABS_MAX, "dmgScore_$s")
            model.addMultiplicationEquality(damageScore, arrayOf(d, graw))
            return damageScore
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
                        model.sumVar(
                            name = "pre_${element.name}",
                            terms =
                                listOf(
                                    Term(baseElement, 1L),
                                    Term(genericBase, 1L)
                                ),
                            constant = 0L,
                            min = -STAT_WITH_PERCENT_ABS_MAX,
                            max = STAT_WITH_PERCENT_ABS_MAX
                        )
                    }

                val prePercentElements =
                    applyGreedyRandom(wantedElements, baseElements, targets, buildRandomEntries(randomByCount))

                prePercentElements.mapValues { (element, preElement) ->
                    val percentTerms = skillTerms.percent[element].orEmpty()
                    if (percentTerms.isEmpty()) {
                        preElement
                    } else {
                        val percent =
                            model.sumVar(
                                name = "pct_${element.name}",
                                terms = percentTerms,
                                constant = 0L,
                                min = -PERCENT_ABS_MAX,
                                max = PERCENT_ABS_MAX
                            )
                        model.applyPercent(preElement, percent, "stat_${element.name}")
                    }
                }
            }
        }

        private fun applyGreedyRandom(
            wantedElements: List<Characteristic>,
            baseElements: Map<Characteristic, IntVar>,
            targets: Map<Characteristic, Int>,
            randomEntries: List<RandomEntry>,
        ): Map<Characteristic, IntVar> {
            if (wantedElements.isEmpty()) return baseElements
            if (targets.isEmpty()) return baseElements
            if (randomEntries.isEmpty()) return baseElements

            val priorities =
                wantedElements
                    .mapIndexed { index, element ->
                        element to (wantedElements.size - index)
                    }.toMap()
            val priorityScale = 10L
            val elementCount = wantedElements.size

            var current = baseElements
            randomEntries.forEachIndexed { index, entry ->
                val effectiveCount = min(entry.count, elementCount)
                if (effectiveCount == 0) return@forEachIndexed

                if (effectiveCount == elementCount) {
                    current =
                        wantedElements.associateWith { element ->
                            val next =
                                model.newIntVar(
                                    -STAT_WITH_PERCENT_ABS_MAX,
                                    STAT_WITH_PERCENT_ABS_MAX,
                                    "rand_${index}_${element.name}"
                                )
                            model.addEquality(
                                next,
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(current.getValue(element), 1)
                                    .addTerm(entry.equipVar, entry.value.toLong())
                                    .build()
                            )
                            next
                        }
                    return@forEachIndexed
                }

                val assigns =
                    wantedElements.associateWith { element ->
                        model.newBoolVar("assign_${index}_${entry.nameSuffix}_${element.name}")
                    }

                model.addEquality(
                    LinearExpr.sum(assigns.values.toTypedArray()),
                    LinearExpr.term(entry.equipVar, effectiveCount.toLong())
                )

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

                current =
                    wantedElements.associateWith { element ->
                        val next =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX,
                                STAT_WITH_PERCENT_ABS_MAX,
                                "rand_${index}_${element.name}"
                            )
                        model.addEquality(
                            next,
                            LinearExpr
                                .newBuilder()
                                .addTerm(current.getValue(element), 1)
                                .addTerm(assigns.getValue(element), entry.value.toLong())
                                .build()
                        )
                        next
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
                    val percent =
                        model.sumVar(
                            name = "pct_${char.name}",
                            terms = percentTerms,
                            constant = 0L,
                            min = -PERCENT_ABS_MAX,
                            max = PERCENT_ABS_MAX
                        )
                    model.applyPercent(pre, percent, "stat_${char.name}")
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
            runeModel.runeByCharacteristic[char]?.let { rune ->
                for ((equip, perStat) in runeModel.runeVars) {
                    val runeVar = perStat[char] ?: continue
                    // Rune level is capped by the carrier ITEM's level, not the character's (fix 36918746).
                    val coefficient = rune.valueOn(equip.itemType, equip.level).toLong()
                    if (coefficient != 0L) {
                        terms.add(Term(runeVar, coefficient))
                    }
                }
            }

            terms.addAll(skillTerms.fixed[char].orEmpty())
            val base = baseValues[char]?.toLong() ?: 0L
            return terms to base
        }

        private fun prePercentStat(char: Characteristic): IntVar =
            prePercentCache.getOrPut(char) {
                val (terms, base) = baseTermsFor(char)
                // Sublimation contributions fold in exactly like item/rune stats (FLAT always, STATIC
                // under a reified condition, CONVERSION moving value between two stats).
                terms.addAll(subTermsByStat[char].orEmpty())
                // Selected passives' flat stats fold in the same way (always-on constants).
                terms.addAll(passiveTermsByStat[char].orEmpty())
                model.sumVar("pre_${char.name}", terms, base, -STAT_ABS_MAX, STAT_ABS_MAX)
            }

        /** Pre-sublimation actual value of [char] (item + rune + skill + base), used to reify conditions. */
        private fun preSubStat(char: Characteristic): IntVar =
            preSubCache.getOrPut(char) {
                val (terms, base) = baseTermsFor(char)
                model.sumVar("preSub_${char.name}", terms, base, -STAT_ABS_MAX, STAT_ABS_MAX)
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
                        .add(Term(model.newConstant(value.toLong()), 1L))
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
                    val moved = model.newIntVar(0L, STAT_ABS_MAX, "subConvMoved_${sub.stateId}")
                    model.addMultiplicationEquality(moved, arrayOf(raw, applies))
                    map.getOrPut(effectiveStat(conv.to)) { mutableListOf() }.add(Term(moved, 1L))
                    map.getOrPut(effectiveStat(conv.from)) { mutableListOf() }.add(Term(moved, -1L))
                    continue
                }
                for (effect in sub.effects) {
                    if (!scenarioGateMatches(effect.scenarioGate, params)) continue
                    map
                        .getOrPut(effectiveStat(effect.characteristic)) { mutableListOf() }
                        .add(Term(applies, effect.value.toLong()))
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

        /** A reified boolean for a supported [SublimationCondition] over the pre-sublimation build stats. */
        private fun reifyCondition(cond: me.chosante.common.SublimationCondition): IntVar {
            val n = (cond.value ?: 0).toLong()
            val tag = "subCond_${cond.type}_${n}_${appliesVarCache.size}"
            return when (cond.type) {
                SublimationConditionType.AP_AT_MOST -> reifyLe(preSubStat(Characteristic.ACTION_POINT), n, tag)
                SublimationConditionType.AP_AT_LEAST -> reifyGe(preSubStat(Characteristic.ACTION_POINT), n, tag)
                SublimationConditionType.AP_EXACT ->
                    and(
                        reifyLe(preSubStat(Characteristic.ACTION_POINT), n, "${tag}_le"),
                        reifyGe(preSubStat(Characteristic.ACTION_POINT), n, "${tag}_ge"),
                        tag
                    )
                SublimationConditionType.CRIT_AT_MOST -> reifyLe(preSubStat(Characteristic.CRITICAL_HIT), n, tag)
                SublimationConditionType.CRIT_AT_LEAST -> reifyGe(preSubStat(Characteristic.CRITICAL_HIT), n, tag)
                SublimationConditionType.BLOCK_AT_LEAST -> reifyGe(preSubStat(Characteristic.BLOCK_PERCENTAGE), n, tag)
                SublimationConditionType.RANGE_AT_MOST -> reifyLe(preSubStat(Characteristic.RANGE), n, tag)
                SublimationConditionType.RANGE_AT_LEAST -> reifyGe(preSubStat(Characteristic.RANGE), n, tag)
                SublimationConditionType.RANGE_EXACT ->
                    and(
                        reifyLe(preSubStat(Characteristic.RANGE), n, "${tag}_le"),
                        reifyGe(preSubStat(Characteristic.RANGE), n, "${tag}_ge"),
                        tag
                    )
                SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> {
                    val threshold = (n * subModel.characterLevel) / 100L
                    reifyLe(preSubStat(Characteristic.DODGE), threshold - 1, tag)
                }
                SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> {
                    val sum =
                        model.sumVar(
                            "secMast_$tag",
                            listOf(preSubStat(Characteristic.MASTERY_MELEE), preSubStat(Characteristic.MASTERY_DISTANCE)),
                            -STAT_ABS_MAX,
                            STAT_ABS_MAX
                        )
                    reifyLe(sum, n, tag)
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
            val scaled = model.newIntVar(-PRODUCT_ABS_MAX, PRODUCT_ABS_MAX, "${name}_scaled")
            model.addEquality(scaled, LinearExpr.term(value, percent.toLong()))
            val quotient = model.newIntVar(-(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1, "${name}_q")
            model.addDivisionEquality(quotient, scaled, model.newConstant(100L))
            val positive = model.newIntVar(0L, STAT_ABS_MAX, "${name}_pos")
            model.addMaxEquality(positive, arrayOf(quotient, model.newConstant(0L)))
            return positive
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
    ) {
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
        // Per NORMAL sublimation: a per-carrier-item bool (1 ⇒ that item hosts the sub). Empty for epic/relic.
        val carrierVars: Map<Sublimation, Map<Equipment, IntVar>> = emptyMap(),
    ) {
        companion object {
            val EMPTY = SublimationModel(emptyMap(), emptySet(), 0)
        }
    }

    /**
     * Best-achievable normal-sublimation ↔ rune socket coupling (research P7): a normal sub reserves 3
     * socket slots that can no longer hold runes, so `Σ runeCount + 3·Σ normalSubChosen ≤ Σ sockets of
     * equipped items`. This is a global capacity budget (no per-slot colour / doubling pinning), matching
     * the engine's existing optimistic re-rollable-colours rune model.
     */
    private fun CpModel.addNormalSublimationSocketBudget(
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
    ) {
        val normalSubs = subModel.subVars.keys.filter { it.rarity == SublimationRarity.NORMAL }
        if (normalSubs.isEmpty()) return
        // Per item: (runes socketed in it) + 3·(normal subs hosted on it) ≤ its sockets, when equipped. This
        // ties each sub's 3 sockets to ONE carrier and shares the carrier's sockets with its runes, so hosting
        // a sub reduces that item's rune capacity (no borrowing sockets across items). A normal sub with no
        // ≥3-socket carrier is already forced off by the assignment equality in createSublimationModel.
        for (item in allEquips) {
            val runeVarsForItem = runeModel.runeVars[item]?.values.orEmpty()
            val subHostsForItem = normalSubs.mapNotNull { subModel.carrierVars[it]?.get(item) }
            if (runeVarsForItem.isEmpty() && subHostsForItem.isEmpty()) continue
            val budget = LinearExpr.newBuilder()
            runeVarsForItem.forEach { budget.addTerm(it, 1L) }
            subHostsForItem.forEach { budget.addTerm(it, NORMAL_SUB_SOCKET_COST) }
            budget.addTerm(equipVars.getValue(item), -item.maxShardSlots.toLong())
            addLessOrEqual(budget.build(), 0L)
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

    private fun orderEquipments(equipmentsByItemType: Map<ItemType, List<Equipment>>): List<Equipment> =
        ItemType.entries
            .flatMap { type ->
                equipmentsByItemType[type].orEmpty().sortedBy { it.equipmentId }
            }.distinctBy { it.equipmentId }
}
