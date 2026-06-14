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
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import java.math.BigDecimal
import java.math.BigInteger
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
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, tuning = null)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> = optimize(params, equipmentsByItemType, emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: SolverTuning?,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> =
        callbackFlow {
            withContext(Dispatchers.IO) {
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

                model.addBuildValidityConstraints(allEquips, equipVars)

                val objective =
                    when (params.scoreComputationMode) {
                        ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                            model.buildMostMasteriesObjective(params, allEquips, equipVars, skillVars, runeModel)

                        ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                            model.buildPrecisionObjective(params, allEquips, equipVars, skillVars, runeModel)
                    }
                model.maximize(objective)

                executeSolverAndEmitResults(model, params, allEquips, equipVars, skillVars, runeModel, this@callbackFlow, tuning)
            }
            close()
            awaitClose { }
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
        if (!params.useRunes || runes.isEmpty()) return RuneModel.EMPTY
        val runeByCharacteristic = runes.associateBy { it.characteristic }
        val runeStats = relevantRuneStats(params, runeByCharacteristic.keys)
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
        return RuneModel(runeByCharacteristic, runeVars, params.character.level)
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
        return result.intersect(runeCharacteristics)
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
    ): IntVar {
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars, runeModel)
        val targetStats = params.targetStats
        val targetCharacteristics = targetStats.map { it.characteristic }.toSet()

        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)

        val masteryScore = statBuilder.finalMasteryScore(targetStats, targetCharacteristics)

        if (requiredTargets.isEmpty()) {
            return masteryScore
        }

        val totalActualScore = statBuilder.totalActualScore(requiredTargets, totalExpectedScore, targetStats)
        val totalActualScoreForPenalty = maxVar(totalActualScore, 1L, totalExpectedScore, "totalActualScoreForPenalty")

        val (indexVar, maxIndex) = bucketedIndex(totalActualScoreForPenalty, totalExpectedScore)
        val powerTable = buildPowerTable(maxIndex.toLong(), MASTERY_SCORE_ABS_MAX)

        val multiplier = newIntVar(0, powerTable.maxValue, "penaltyMultiplier")
        addElement(indexVar, powerTable.values, multiplier)

        val maxObjective = safeMultiply(MASTERY_SCORE_ABS_MAX, powerTable.maxValue)
        val objectiveBound = maxObjective.coerceAtMost(Long.MAX_VALUE / 2)
        val primaryObjective = newIntVar(-objectiveBound, objectiveBound, "objectiveScore")
        addMultiplicationEquality(primaryObjective, masteryScore, multiplier)

        // Lexicographic tie-breaker: among builds the primary objective ranks equally, prefer the one
        // that exceeds the required targets the most (weighted by the same per-constraint priorities).
        // This is what makes the solver spend otherwise objective-neutral skill points into HP/CC%
        // (and, among ties, pick gear that overshoots) instead of leaving them unused — free in-game
        // value the player would always take. It can never trade a maximized-mastery point for
        // overshoot; see [withOvershootTieBreaker].
        val overshoot = statBuilder.overshootScore(requiredTargets, totalExpectedScore, targetStats)
        return withOvershootTieBreaker(primaryObjective, objectiveBound, overshoot, totalExpectedScore)
    }

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
    ): IntVar {
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars, runeModel)
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
        }

    private suspend fun executeSolverAndEmitResults(
        model: CpModel,
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
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
            solver.parameters.numSearchWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
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

                    val combination = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel) { value(it) }
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
                val finalComb = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel) { solver.value(it) }
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

        return BuildCombination(equippedItems, optimizedSkills, runes)
    }

    private class StatBuilder(
        private val model: CpModel,
        private val params: WakfuBestBuildParams,
        private val allEquips: List<Equipment>,
        private val equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        private val runeModel: RuneModel,
    ) {
        private val baseValues = params.character.baseCharacteristicValues
        private val skillTerms = buildSkillTerms(skillVars)

        private val prePercentCache = mutableMapOf<Characteristic, IntVar>()
        private val actualCache = mutableMapOf<Characteristic, IntVar>()
        private val elementCache = mutableMapOf<Pair<Characteristic, List<Characteristic>>, Map<Characteristic, IntVar>>()

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

        private fun prePercentStat(char: Characteristic): IntVar =
            prePercentCache.getOrPut(char) {
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
                        val coefficient = rune.valueOn(equip.itemType, runeModel.characterLevel).toLong()
                        if (coefficient != 0L) {
                            terms.add(Term(runeVar, coefficient))
                        }
                    }
                }

                terms.addAll(skillTerms.fixed[char].orEmpty())

                val base = baseValues[char]?.toLong() ?: 0L
                model.sumVar("pre_${char.name}", terms, base, -STAT_ABS_MAX, STAT_ABS_MAX)
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
        val characterLevel: Int,
    ) {
        companion object {
            val EMPTY = RuneModel(emptyMap(), emptyMap(), 0)
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
    // CharacteristicValues. The Major "% Inflicted Damage" aptitude lands in percent[MASTERY_ELEMENTARY]
    // and — like in the scorer — never reaches the score (mastery is read through the specific element
    // keys, which carry no percent). Kept inert on purpose; see the NOTE in computeCharacteristicsValues.
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
