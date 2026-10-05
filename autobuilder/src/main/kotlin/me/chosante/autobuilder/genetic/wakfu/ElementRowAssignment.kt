package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.util.BitSet
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

// The PER-ELEMENT-ROW random-element fold, shared by the CP-SAT solver ([StatBuilder]) and the re-scorers
// ([computeCharacteristicsValues]). It lives outside [WakfuBuildSolver] on purpose: the scorers must never touch that
// object, whose first use loads OR-Tools natively.

/**
 * Fixed-point scale of a target row's weight. The scorers weight each target by a Double = (100 / target) ×
 * userDefinedWeight, which is almost always < 1 for high targets (HP 2000 → 0.05): truncated to a Long it collapsed to 0
 * and silently dropped HP and any target > 100 from the objective. The solver therefore carries the weight in
 * fixed-point (× this scale), which keeps both the per-target 100 / target normalization and userDefinedWeight; the same
 * scale applies to the expected and the actual score, so the success ratio that drives the penalty is unchanged.
 */
internal const val TARGET_WEIGHT_SCALE = 1_000L

/**
 * [TargetStats.weight] in fixed point — THE integer weight of a target row: the solver's (`WakfuBuildSolver.scaledWeight`
 * delegates here) and the per-element-row objective's ([ElementRowObjective]), so the two can never drift.
 */
internal fun TargetStats.fixedPointWeight(targetStat: TargetStat): Long = (weight(targetStat) * TARGET_WEIGHT_SCALE).roundToLong()

/** The two elemental families a request targets per element (fire, water, earth, air) or all at once (the aggregate row). */
enum class ElementFamily(
    internal val aggregate: Characteristic,
    /** The four elements in the solver's canonical fold order: `WakfuBuildSolver.ELEMENTARY_MASTERIES` / `_RESISTANCES` ARE these lists. */
    internal val elements: List<Characteristic>,
    /** The family's random-element lines, each with how many distinct elements its roll lands on. */
    internal val randomByCount: List<Pair<Characteristic, Int>>,
) {
    MASTERY(
        Characteristic.MASTERY_ELEMENTARY,
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        ),
        listOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )
    ),
    RESISTANCE(
        Characteristic.RESISTANCE_ELEMENTARY,
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE,
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND
        ),
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )
    ),
    ;

    /** The family's wanted elements → target: all four at the aggregate's target when it is requested, else the per-element rows. */
    internal fun wanted(targetStats: TargetStats): Map<Characteristic, Int> =
        when (this) {
            MASTERY -> targetStats.masteryElementsWanted
            RESISTANCE -> targetStats.resistanceElementsWanted
        }
}

/**
 * Whether this request reads [family] through PER-ELEMENT rows (fire resistance, water resistance, …) over more than one
 * wanted element, with the random-element rolls placed FREELY. Then the solver builds ONE joint fold of the family (each
 * roll lands on exactly `min(k, wanted)` distinct wanted elements, chosen to maximize the objective — never on every
 * element at once, as the old one-row-at-a-time fold credited it) and every row reads it; the scorers place that family's
 * rolls with [ElementRowObjective], the exact optimum of the same objective.
 *
 *  - most-masteries: the RESISTANCE rows (required targets). Its elemental MASTERY rows are the maximized core, which
 *    places its rolls by the max-min ([assignMaxMinMasteryRandomValues]), not per row.
 *  - precision: both families.
 *  - max-damage: the RESISTANCE rows. The scenario's element mastery is a single-element fold.
 *
 * Only once a per-element row has a target: a 0-valued row weighs nothing (no penalty share, no overshoot, no hard
 * constraint, no capped term), so without one the family keeps its earlier placements — the aggregate row's own exact one
 * (the GUI's default "wind resistance 0" row beside "all resistances" in precision / max-damage), or single-element folds.
 * (The solver's fold beside an aggregate row is the aggregate's joint one, which such a 0-valued row now reads too; nothing
 * weighs it there, and precision's halving keeps reading its unfolded stat.) Within a jointly read family, a 0-valued row
 * still matters in precision, whose halving reads the joint fold for it: see [ElementRowObjective.placeKeepingZeroTargetRows].
 * A family with one wanted element keeps its single-element fold, which credits every roll in full — exact there.
 */
internal fun TargetStats.readsJointPerElementRows(
    family: ElementFamily,
    mode: ScoreComputationMode,
): Boolean {
    if (family.wanted(this).size < 2) return false
    if (none { it.characteristic in family.elements && it.target > 0 }) return false
    return when (mode) {
        ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT -> family == ElementFamily.RESISTANCE
        ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> true
        ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> family == ElementFamily.RESISTANCE
    }
}

/**
 * The per-element-row objectives of a request (see [readsJointPerElementRows]): what [computeCharacteristicsValues]
 * places each family's random-element rolls with. Built by [elementRowObjectives].
 */
class ElementRowObjectives internal constructor(
    internal val mastery: ElementRowObjective?,
    internal val resistance: ElementRowObjective?,
    // The whole request: precision's halving decision reads every row ([precisionModelObjective]).
    internal val targetStats: TargetStats,
)

/**
 * The per-element-row objectives of this request under [mode], or null when no family is read through per-element rows
 * over more than one element (every family then keeps its legacy per-mode assignment). Pass it to
 * [computeCharacteristicsValues] wherever a scored request's stats are resolved — the scorers, the max-damage penalty
 * reads and the GUI's stat grid — so they all place the rolls exactly where the solver did.
 */
fun TargetStats.elementRowObjectives(mode: ScoreComputationMode): ElementRowObjectives? {
    val mastery = ElementRowObjective.of(this, ElementFamily.MASTERY, mode)
    val resistance = ElementRowObjective.of(this, ElementFamily.RESISTANCE, mode)
    return if (mastery == null && resistance == null) null else ElementRowObjectives(mastery, resistance, this)
}

/**
 * What the solver's PRECISION objective (`StatBuilder.precisionScore`) reads for a build whose every stat is [stats], in its
 * integer units — a mirror the scorers use to take the placement the solver itself takes where one family cannot decide
 * alone (the halving, see [ElementRowObjective.placeKeepingZeroTargetRows]):
 *  - the capped sum `Σ min(W·read, W·t)` of the rows with a weight (an aggregate row averages its four elements with the
 *    solver's truncating division), HALVED (truncated) while some row of target 0 reads below 0 — the joint fold's value for
 *    a jointly read family's row, else the solver's unfolded value ([unfoldedStats] for an elemental row: its own lines only,
 *    without the "+all elements" lines and the random rolls), the stat itself for any other row;
 *  - plus, once that reaches the expected total (every target met, no halving), the overflow `Σ W·read − capped sum`.
 */
internal fun precisionModelObjective(
    targetStats: TargetStats,
    stats: Map<Characteristic, Int>,
    unfoldedStats: Map<Characteristic, Int>,
): Long {
    var capped = 0L
    var uncapped = 0L
    var expected = 0L
    for (row in targetStats) {
        val weight = targetStats.fixedPointWeight(row)
        if (weight == 0L) continue
        val rowExpected = row.target.toLong() * weight
        val family = ElementFamily.entries.firstOrNull { it.aggregate == row.characteristic }
        if (family != null) {
            var familyCapped = 0L
            var familyUncapped = 0L
            for (element in family.elements) {
                val value = (stats[element] ?: 0).toLong()
                familyCapped += minOf(weight * value, rowExpected)
                familyUncapped += weight * value
            }
            capped += familyCapped / family.elements.size
            uncapped += familyUncapped / family.elements.size
        } else {
            val value = (stats[row.characteristic] ?: 0).toLong()
            capped += minOf(weight * value, rowExpected)
            uncapped += weight * value
        }
        expected += rowExpected
    }
    val halved =
        targetStats.any { row ->
            if (row.target != 0 || ElementFamily.entries.any { it.aggregate == row.characteristic }) return@any false
            val family = ElementFamily.entries.firstOrNull { row.characteristic in it.elements }
            val read =
                when {
                    family == null -> stats[row.characteristic]
                    targetStats.readsJointPerElementRows(family, ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) -> stats[row.characteristic]
                    else -> unfoldedStats[row.characteristic]
                } ?: 0
            read < 0
        }
    val penalized = if (halved) capped / 2 else capped
    val bonus = if (penalized >= expected.coerceAtLeast(1L)) minOf(maxOf(uncapped - capped, 0L), PRECISION_OVERFLOW_BOUND) else 0L
    return penalized + bonus
}

/**
 * The EXACT random-element roll assignment for a family read through per-element rows ([readsJointPerElementRows]): the
 * optimum, over every way to put each roll on `min(k, n)` distinct wanted elements, of the solver's own objective for
 * those rows, in the solver's integer units (weights = [fixedPointWeight], the solver's `scaledWeight`), compared
 * lexicographically:
 *
 *  1. [primary] — the family's share of what the objective maximizes first:
 *     - most-masteries / max-damage: the required-target penalty total `Σ W · clamp(read, −t, t)` of the family's rows
 *       (`StatBuilder.totalActualScore`; the aggregate row reads the min of the four). The penalty multiplier — the
 *       solver's bucketed power-6 table and the scorers' continuous `(100 / success%)⁶` — is non-decreasing in it, and
 *       resistance feeds neither the mastery core nor the damage, so the larger the better in both engines. On the hard
 *       leg it is maximal iff every row is met (`Σ W·t`), so "the solver says the targets are met" ⇔ "this assignment
 *       meets them" (within the search's [NODE_BUDGET], see there).
 *     - precision: the capped sum `Σ min(W · read, W · t)` (`StatBuilder.precisionScore`; the aggregate row averages its
 *       four capped elements with the solver's truncating division by 4). A row of target 0 weighs nothing there, but the
 *       solver halves its whole objective while one reads below 0 — a trade against the rest of the request, so the
 *       scorers also ask [placeKeepingZeroTargetRows] and let [precisionModelObjective] pick on the whole build.
 *  2. [zeroWeightMet] — most-masteries / max-damage: how many rows with a target but a 0 weight are met. The penalty
 *     ignores them but the hard leg requires them; with every weight positive (the GUI's priorities are 1..5) a primary
 *     optimum already meets every reachable row, so this only matters for a 0 weight.
 *  3. [secondary] — the solver's tie-breaker, on the plateau where every weighed row is met: the most-masteries overshoot
 *     `Σ W · clamp(read − t, 0, t)` (every hard-leg result sits there), precision's uncapped sum (its bonus once every
 *     target is met); nothing below that plateau, nor in max-damage.
 *
 * Ties keep the first assignment the search meets; children are tried best-greedy-gain first, then onto the lowest
 * elements, so a tie reads as the balanced spread a player expects. Exact by branch and bound, two passes (first only the
 * completions that can reach the root's primary bound — very often the optimum — then, if none does, all of them), with
 * admissible bounds: a separable concave relaxation of the remaining rolls over the polymatroid "each roll at most once per
 * element", tightened by the subset sums the remaining rolls can actually form; the aggregate's minimum bounded jointly with
 * the rows (a concave search over the level it ends at); on the all-met plateau, the mass each element still needs to stay
 * met counted as spent; every bound rounded down to the lattice the objective lives on (the weights' gcd). Plus a memo of
 * visited states, capped past every threshold and up to the permutations of interchangeable elements. The result is the
 * optimum, not an estimate — locked against exhaustive enumeration ([ElementRowAssignmentTest]) — whenever the search ends
 * within its [NODE_BUDGET], which no real build comes near (see there for the one synthetic shape that can, and what is kept
 * then). The bounds need non-negative weights and targets; a CLI-only negative weight or target turns pruning off (still
 * exact within the budget, just slower).
 *
 * Known semantics shared with the solver (not differences between the two): a roll always lands on `min(k, n)` WANTED
 * elements, also a negative one (the game would let a player put it on an unwanted element); the primary keeps the
 * solver's lower clamp at `−t`, which only differs from the scorers' unclamped penalty total when an element's resistance
 * sits below minus its target.
 */
internal class ElementRowObjective private constructor(
    private val mode: ScoreComputationMode,
    /** The family's wanted elements (at least 2) in canonical order; every roll lands on `min(k, elements.size)` of them. */
    val elements: List<Characteristic>,
    private val rowElement: IntArray,
    private val rowTarget: LongArray,
    private val rowWeight: LongArray,
    private val hasAggregate: Boolean,
    private val aggregateTarget: Long,
    private val aggregateWeight: Long,
) {
    private val precision = mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
    private val n = elements.size

    // The bounds assume every term is non-decreasing in every element, i.e. non-negative weights and targets.
    private val prunable =
        rowWeight.all { it >= 0L } &&
            rowTarget.all { it >= 0L } &&
            (!hasAggregate || (aggregateWeight >= 0L && aggregateTarget >= 0L))

    /**
     * Precision only: the elements of the family's rows of target 0 — the GUI's default "air resistance 0" beside a fire
     * resistance target makes {fire, air} one jointly read family. Such a row weighs nothing, but precision HALVES the whole
     * objective while one of them reads below 0 (`StatBuilder.negativeTargetPenalty`, which reads the joint fold for them),
     * a request-wide effect no per-family objective can weigh alone: [placeKeepingZeroTargetRows] gives the best placement
     * that keeps them all ≥ 0, and [precisionModelObjective] decides between the two on the whole build.
     */
    private val zeroTargetElements: IntArray =
        if (mode != ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
            IntArray(0)
        } else {
            rowElement.indices
                .filter { rowTarget[it] == 0L }
                .map { rowElement[it] }
                .distinct()
                .sorted()
                .toIntArray()
        }

    /** Whether this family has rows of target 0 whose sign precision's halving reads (see [zeroTargetElements]). */
    internal val hasZeroTargetRows: Boolean get() = zeroTargetElements.isNotEmpty()

    // The lattice the bounds round down to. The values are integers and every term is a weight times an integer, so in
    // most-masteries / max-damage the family's primary and secondary are multiples of the weights' gcd — at least 10, the
    // weights being 100 / target at 2 decimals, in thousandths — and so is anything they can reach. Precision's bounds work
    // on 4 × the objective (the aggregate's truncating /4): 4 × the rows plus the aggregate's sum is a multiple of
    // gcd(4 × the rows' weights, the aggregate's weight). A bound rounded down to it stays sound, and a gap smaller than one
    // step is closed at once — without it, a bound a few units above an optimum it cannot reach (the integer placements
    // fall just short of the relaxation's) kept millions of nodes open.
    private val boundStep: Long =
        run {
            var rows = 0L
            for (w in rowWeight) rows = gcd(rows, abs(w))
            val aggregate = if (hasAggregate) abs(aggregateWeight) else 0L
            gcd(if (precision) 4L * rows else rows, aggregate).coerceAtLeast(1L)
        }

    private fun roundDownToStep(bound: Long): Long = if (bound == Long.MIN_VALUE || bound == Long.MAX_VALUE) bound else Math.floorDiv(bound, boundStep) * boundStep

    // Interchangeable elements (the same rows with the same targets and weights): the objective is symmetric under
    // permuting them, so the visited-state memo keys on their sorted values.
    private val symmetryClass: IntArray =
        run {
            val profiles =
                (0 until n).map { e ->
                    rowElement.indices
                        .filter { rowElement[it] == e }
                        .map { rowTarget[it] to rowWeight[it] }
                        .sortedWith(compareBy({ it.first }, { it.second }))
                }
            IntArray(n) { e -> (0 until n).first { profiles[it] == profiles[e] } }
        }

    /** The family's share of the solver's primary objective (solver units) for the per-element values [r]. */
    fun primary(r: IntArray): Long {
        var p = 0L
        if (precision) {
            for (i in rowElement.indices) p += minOf(rowWeight[i] * r[rowElement[i]], rowWeight[i] * rowTarget[i])
            if (hasAggregate) {
                var y = 0L
                for (v in r) y += minOf(aggregateWeight * v, aggregateWeight * aggregateTarget)
                p += y / r.size
            }
        } else {
            for (i in rowElement.indices) p += rowWeight[i] * maxOf(minOf(r[rowElement[i]].toLong(), rowTarget[i]), -rowTarget[i])
            if (hasAggregate) p += aggregateWeight * maxOf(minOf(r.min().toLong(), aggregateTarget), -aggregateTarget)
        }
        return p
    }

    /** Rows with a target the penalty does not weigh (weight 0) that [r] meets — the hard leg still requires them. */
    fun zeroWeightMet(r: IntArray): Int {
        if (precision) return 0
        var met = 0
        for (i in rowElement.indices) if (rowWeight[i] == 0L && rowTarget[i] > 0L && r[rowElement[i]] >= rowTarget[i]) met++
        if (hasAggregate && aggregateWeight == 0L && aggregateTarget > 0L && r.min() >= aggregateTarget) met++
        return met
    }

    /**
     * The family's primary once every row it weighs is met — its most: `Σ W·t` (+ the aggregate's `W·T`), each row's term
     * being at most its share of the solver's expected total. Precision's overflow bonus only exists at this value.
     */
    private val allMetPrimary: Long =
        run {
            var p = 0L
            for (i in rowElement.indices) p += rowWeight[i] * rowTarget[i]
            if (hasAggregate) p += aggregateWeight * aggregateTarget
            p
        }

    /**
     * The family's share of the solver's tie-breaker (solver units) for [r], on the plateau where every weighed row is met
     * ([allMetPrimary]) — 0 below it:
     *  - most-masteries: the overshoot `Σ W · clamp(read − t, 0, t)`. Every hard-leg result sits on that plateau, where the
     *    solver maximizes it exactly. Below it (a soft-leg build whose targets are out of reach) the solver ranks placements
     *    by its BUCKETED penalty first, coarser than [primary], so its overshoot choice has no exact counterpart here, and the
     *    score does not read it.
     *  - precision: the uncapped sum, which its objective only rewards once every target OF THE REQUEST is met
     *    (`precisionScore`'s `fullyMet` bonus) — the score above 100 %, where the solver maximizes it exactly. While another
     *    row is short the solver is indifferent to it (as is the score, capped below 100 %): it then only picks which of the
     *    solver's equally good placements is shown.
     *  - max-damage: nothing.
     */
    fun secondary(r: IntArray): Long {
        if (mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE || primary(r) != allMetPrimary) return 0L
        if (precision) return uncapped(r)
        var s = 0L
        for (i in rowElement.indices) {
            val t = rowTarget[i].coerceAtLeast(0L)
            s += rowWeight[i] * maxOf(minOf(r[rowElement[i]] - t, t), 0L)
        }
        if (hasAggregate) {
            val t = aggregateTarget.coerceAtLeast(0L)
            s += aggregateWeight * maxOf(minOf(r.min() - t, t), 0L)
        }
        return s
    }

    /** Precision's uncapped sum `Σ W·read` (the aggregate averaged with the solver's truncating division by 4) — linear in [r]. */
    private fun uncapped(r: IntArray): Long {
        var s = 0L
        for (i in rowElement.indices) s += rowWeight[i] * r[rowElement[i]]
        if (hasAggregate) {
            var y = 0L
            for (v in r) y += aggregateWeight * v
            s += y / r.size
        }
        return s
    }

    /**
     * Places the [rolls] (`value` on `count` random elements) onto the [current] per-element values (keys ⊇ [elements]) at
     * the optimum described on the class (exact within [NODE_BUDGET]), and returns every element's final value. Every roll
     * is placed, and the result depends neither on the order of the rolls nor on that of the request's rows.
     */
    fun assign(
        rolls: List<Pair<Int, Int>>,
        current: Map<Characteristic, Int>,
    ): Map<Characteristic, Int> = place(rolls, current).values

    /**
     * A placement [place] returned: every element's final [values] (read-only — the memo shares it), the search [nodes] it took,
     * and whether it is [exact] — the proven optimum, i.e. the search ended within its node budget (else the best placement it
     * met, see [NODE_BUDGET]).
     */
    internal class Placement(
        val values: Map<Characteristic, Int>,
        val nodes: Long,
        val exact: Boolean,
    )

    /** [assign], with what the search took ([Placement]); [nodeBudget] = [NODE_BUDGET] in production. */
    internal fun place(
        rolls: List<Pair<Int, Int>>,
        current: Map<Characteristic, Int>,
        nodeBudget: Long = NODE_BUDGET,
    ): Placement = checkNotNull(search(rolls, current, nodeBudget, keepZeroTargetRows = false))

    /**
     * Precision: the same optimum restricted to the placements that keep every row of target 0 at 0 or more
     * ([zeroTargetElements]) — null when none does. Every roll is placed.
     */
    internal fun placeKeepingZeroTargetRows(
        rolls: List<Pair<Int, Int>>,
        current: Map<Characteristic, Int>,
        nodeBudget: Long = NODE_BUDGET,
    ): Placement? = search(rolls, current, nodeBudget, keepZeroTargetRows = true)

    private fun search(
        rolls: List<Pair<Int, Int>>,
        current: Map<Characteristic, Int>,
        nodeBudget: Long,
        keepZeroTargetRows: Boolean,
    ): Placement? {
        val start = IntArray(n) { current[elements[it]] ?: 0 }
        val negative = mutableListOf<Roll>()
        val positive = mutableListOf<Roll>()
        for ((value, count) in rolls) {
            val cover = minOf(count, n)
            if (value == 0 || cover <= 0) continue
            when {
                // A roll on at least as many elements as are wanted lands on all of them: no choice to make.
                cover == n -> for (e in 0 until n) start[e] += value
                value < 0 -> negative += Roll(value, cover)
                else -> positive += Roll(value, cover)
            }
        }
        // Negative rolls first (none remain past them, so the memo may then merge states past every threshold); then the
        // biggest mass first, which tightens the bounds early (equal rolls end up adjacent: the memo folds their orders).
        val order = compareByDescending<Roll> { it.value.toLong() * it.cover }.thenByDescending { it.value }.thenBy { it.cover }
        val choice = negative.sortedWith(order) + positive.sortedWith(order)
        val constrained = keepZeroTargetRows && hasZeroTargetRows
        // The same build is read several times over (its score, the stats column, the max-damage penalty, each emission of a
        // search) and a loaded build is read on the UI thread: an identical search returns the placement it already found.
        val key = PlacementKey(signature, start.toList(), choice.map { it.value to it.cover }, nodeBudget, constrained)
        synchronized(PLACEMENTS) { if (PLACEMENTS.containsKey(key)) return PLACEMENTS[key] }
        val placement = solve(choice, negative.size, start, nodeBudget, constrained, rolls.size)
        synchronized(PLACEMENTS) { PLACEMENTS[key] = placement }
        return placement
    }

    private fun solve(
        choice: List<Roll>,
        negativeCount: Int,
        start: IntArray,
        nodeBudget: Long,
        constrained: Boolean,
        rollCount: Int,
    ): Placement? {
        val search = Search(choice, negativeCount, start, nodeBudget, constrained)
        val best = search.run() ?: return null
        val result = LinkedHashMap<Characteristic, Int>()
        elements.forEachIndexed { e, element -> result[element] = best[e] }
        if (!search.exact) {
            // Rate-limited (the 1st, 2nd, 4th, 8th… time in this JVM): the scorers may call this for every build they read.
            val hits = BUDGET_HITS.incrementAndGet()
            if (hits and (hits - 1) == 0L) {
                val gap = if (search.rootBound == Long.MAX_VALUE) "" else ", its primary within ${search.rootBound - primary(best)} solver units of the optimum"
                logger.warn {
                    "Per-element-row placement of $rollCount random-element lines over $elements stopped at its $nodeBudget-node budget " +
                        "(hit $hits time(s) so far): the best placement found is kept$gap."
                }
            }
        }
        // Read-only: the memo hands this very placement to every later identical search, so no caller may write into it.
        return Placement(Collections.unmodifiableMap(result), search.nodes, search.exact)
    }

    // Everything a placement depends on besides its values and rolls: the objective itself (rebuilt for each request read).
    private val signature: List<Any> =
        listOf(mode, elements, rowElement.toList(), rowTarget.toList(), rowWeight.toList(), hasAggregate, aggregateTarget, aggregateWeight)

    /** What a placement depends on — a pure function of it, so [PLACEMENTS] may hand an earlier result back. */
    private data class PlacementKey(
        val signature: List<Any>,
        val start: List<Int>,
        val choice: List<Pair<Int, Int>>,
        val nodeBudget: Long,
        val keepZeroTargetRows: Boolean,
    )

    private class Roll(
        val value: Int,
        val cover: Int,
    )

    /** Unwinds a search pass that reached its node budget (no stack trace: it is control flow). */
    private class BudgetExhausted : RuntimeException(null, null, false, false)

    private class StateKey(
        val depth: Int,
        val values: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is StateKey && other.depth == depth && other.values.contentEquals(values)

        override fun hashCode(): Int = 31 * depth + values.contentHashCode()
    }

    // Past this value of an element, no term of the objective changes any more (positive rolls only raise it): most-masteries
    // reads up to twice a target (its overshoot), max-damage and precision's primary up to the target. Precision's secondary
    // is linear in every element and is carried by the memo as a value instead. Long.MAX_VALUE = never cap.
    private val memoCap: LongArray =
        LongArray(n) { e ->
            val scale = if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) 2L else 1L
            var cap = Long.MIN_VALUE
            for (i in rowElement.indices) if (rowElement[i] == e) cap = maxOf(cap, scale * rowTarget[i])
            if (hasAggregate) cap = maxOf(cap, scale * aggregateTarget)
            if (cap == Long.MIN_VALUE || !prunable) Long.MAX_VALUE else cap.coerceAtLeast(0L)
        }

    /**
     * One branch-and-bound over [choice] (the rolls that leave a choice; its first [negativeCount] are the negative ones),
     * from the per-element values [start]. With [keepZeroTargets], only the placements keeping every [zeroTargetElements]
     * element ≥ 0 count (see [placeKeepingZeroTargetRows]).
     */
    private inner class Search(
        choice: List<Roll>,
        private val negativeCount: Int,
        start: IntArray,
        private val nodeBudget: Long,
        private val keepZeroTargets: Boolean,
    ) {
        private val depths = choice.size
        private val rollValue = IntArray(depths) { choice[it].value }
        private val rollCover = IntArray(depths) { choice[it].cover }
        private val startValues = start.copyOf()
        private val current = start.copyOf()
        private val best = start.copyOf()
        private var bestP = Long.MIN_VALUE
        private var bestM = Int.MIN_VALUE
        private var bestS = Long.MIN_VALUE
        private var found = false
        private val combos: Array<Array<IntArray>> = Array(n + 1) { indexCombinations(n, it).toTypedArray() }

        // capacity[d][k]: the most mass the positive rolls from depth d on can put on any k elements together — Σ value ·
        // min(k, cover), a roll landing at most once per element. A polymatroid rank (concave in k), so the steepest-first
        // greedy of [maxGain] is the exact optimum of the relaxation it bounds. Negative rolls only lower the
        // (non-decreasing) terms, so the upper bounds leave them out.
        private val capacity = Array(depths + 1) { LongArray(n + 1) }

        // subsetSums[d]: every mass one element can receive from the positive rolls from depth d on (each at most once).
        private val subsetSums = Array(depths + 1) { BitSet() }

        // The memo: a visited state (depth + values, capped past [memoCap] once no negative roll remains, sorted within the
        // classes of interchangeable elements) → precision's secondary so far (ignored in the other modes).
        private val visited = HashMap<StateKey, Long>()

        // Per-node scratch: the search is single-threaded, its hot path allocation-free.
        private val maxIntervals = 2 * (rowElement.size + n)
        private val intervalElement = IntArray(maxIntervals)
        private val intervalSlope = LongArray(maxIntervals)
        private val intervalFrom = LongArray(maxIntervals)
        private val intervalTo = LongArray(maxIntervals)
        private var intervalCount = 0
        private val breakpoints = LongArray(2 * maxIntervals + 1)
        private val segmentElement = IntArray(2 * maxIntervals + 1)
        private val segmentSlope = LongArray(2 * maxIntervals + 1)
        private val segmentLength = LongArray(2 * maxIntervals + 1)
        private val taken = LongArray(n)
        private val scratch = LongArray(n)
        private val childOrder = Array(depths) { IntArray(MAX_CHILDREN) }
        private val childP = LongArray(MAX_CHILDREN)
        private val childM = IntArray(MAX_CHILDREN)
        private val childS = LongArray(MAX_CHILDREN)
        private val childLow = LongArray(MAX_CHILDREN)

        /** Search nodes visited, over both passes. */
        var nodes = 0L
            private set

        /** Whether [run]'s result is the proven optimum: a pass completed within the node budget (see [run]). */
        var exact = false
            private set

        /** The root's primary bound (Long.MAX_VALUE when the bounds are off): how far an inexact result can be from the optimum. */
        var rootBound = Long.MAX_VALUE
            private set

        // The node count the running pass may reach: past it, [descend] unwinds with [BudgetExhausted].
        private var passLimit = Long.MAX_VALUE

        init {
            for (d in depths - 1 downTo 0) {
                val v = rollValue[d].toLong()
                for (k in 0..n) capacity[d][k] = capacity[d + 1][k] + if (v > 0L) v * minOf(k, rollCover[d]) else 0L
            }
            subsetSums[depths].set(0)
            for (d in depths - 1 downTo 0) {
                val v = rollValue[d]
                val below = subsetSums[d + 1]
                val sums = below.clone() as BitSet
                if (v > 0) {
                    var bit = below.nextSetBit(0)
                    while (bit >= 0) {
                        sums.set(bit + v)
                        bit = below.nextSetBit(bit + 1)
                    }
                }
                subsetSums[d] = sums
            }
        }

        // Pass 1's aspiration: a node whose primary bound falls below it is skipped (Long.MIN_VALUE = none, pass 2).
        private var aspiration = Long.MIN_VALUE

        /**
         * The best placement, at most [nodeBudget] nodes in: exact ([exact]) when a pass completes in time. Pass 1 gets half the
         * budget, so pass 2 — whose first dive is never pruned — always reaches a complete placement. Null only with
         * [keepZeroTargets], when no placement met within the budget keeps every row of target 0 at 0 or more.
         */
        fun run(): IntArray? {
            if (keepZeroTargets && !zeroTargetNeedsFit(0)) {
                exact = true
                return null
            }
            if (prunable && depths > 0) {
                // Pass 1: the root's primary bound is very often the optimum itself, and a search for completions that can
                // still REACH it skips everything a weak first leaf would leave open. Reached ⇒ primary-optimal, and the pass
                // already ranked every such leaf by the met count and the secondary: exact, done.
                rootBound = minOf(primaryUpper(0, Long.MIN_VALUE), allMetPrimary)
                aspiration = rootBound
                passLimit = nodeBudget / 2
                if (completes() && found && bestP >= aspiration) {
                    exact = true
                    return best
                }
                // Not reached (or out of its budget): pass 2 is the plain search, from pass 1's best leaf (if any); its memo
                // restarts, as pass 1 left subtrees unexplored under a stricter threshold.
                aspiration = Long.MIN_VALUE
                visited.clear()
            }
            // (Never fewer nodes than a first dive takes, which no bound prunes before a leaf is found.)
            passLimit = maxOf(nodeBudget, nodes + depths + 1)
            exact = completes()
            return if (found) best else null
        }

        /** Runs one pass of [descend] from the root; false when it ran out of its [passLimit]. */
        private fun completes(): Boolean =
            try {
                descend(0, Long.MAX_VALUE)
                true
            } catch (_: BudgetExhausted) {
                // Unwound mid-path: back to the root's values for the next pass.
                startValues.copyInto(current)
                false
            }

        private fun descend(
            depth: Int,
            inheritedUpper: Long,
        ) {
            if (++nodes > passLimit) throw BudgetExhausted()
            if (depth == depths) {
                if (keepZeroTargets && zeroTargetElements.any { current[it] < 0 }) return
                val p = primary(current)
                val m = zeroWeightMet(current)
                val s = secondary(current)
                if (!found || p > bestP || (p == bestP && (m > bestM || (m == bestM && s > bestS)))) {
                    found = true
                    bestP = p
                    bestM = m
                    bestS = s
                    current.copyInto(best)
                }
                return
            }
            // No completion can lift every row of target 0 back to 0: nothing below counts (and [zeroNeed] is now this node's).
            if (keepZeroTargets && !zeroTargetNeedsFit(depth)) return
            var pUpper = inheritedUpper
            if (prunable && (found || aspiration != Long.MIN_VALUE)) {
                // Every ancestor's bound also bounds this subtree: keep the tightest (the hull bound alone is not monotone).
                pUpper = minOf(primaryUpper(depth, maxOf(aspiration, if (found) bestP else Long.MIN_VALUE)), inheritedUpper, allMetPrimary)
                if (pUpper < aspiration) return
                if (found && cannotBeatBest(depth, pUpper)) return
            }
            // An equivalent state already searched (same depth, same values up to the thresholds and up to interchangeable
            // elements) has completions of the same objective values — precision's linear secondary aside, which the memo
            // compares — all already compared with a best that only grew since.
            val key = stateKey(depth)
            // Precision's secondary is linear in the values: equal futures add equal amounts, so the larger sum so far wins.
            val pathValue = if (precision) uncapped(current) else 0L
            val seen = visited[key]
            if (seen != null && pathValue <= seen) return
            if (seen != null || visited.size < MEMO_LIMIT) visited[key] = pathValue
            val options = combos[rollCover[depth]]
            val order = orderChildren(depth, options)
            val value = rollValue[depth]
            for (j in options.indices) {
                val combo = options[order[j]]
                for (e in combo) current[e] += value
                descend(depth + 1, pUpper)
                for (e in combo) current[e] -= value
            }
        }

        /** The memo key: the depth and the values — capped once no negative roll remains — sorted within each symmetry class. */
        private fun stateKey(depth: Int): StateKey {
            val capped = depth >= negativeCount
            val key = IntArray(n) { e -> if (capped) minOf(current[e].toLong(), memoCap[e]).toInt() else current[e] }
            for (cls in 0 until n) {
                for (e in 0 until n) {
                    if (symmetryClass[e] != cls) continue
                    for (f in e + 1 until n) {
                        if (symmetryClass[f] == cls && key[f] < key[e]) {
                            val swap = key[e]
                            key[e] = key[f]
                            key[f] = swap
                        }
                    }
                }
            }
            return StateKey(depth, key)
        }

        /** The order to try this node's children in: best one-roll gain (primary, met, secondary) first, then onto the lowest elements. */
        private fun orderChildren(
            depth: Int,
            options: Array<IntArray>,
        ): IntArray {
            val order = childOrder[depth]
            val value = rollValue[depth]
            for (j in options.indices) {
                val combo = options[j]
                var low = 0L
                for (e in combo) {
                    low += current[e]
                    current[e] += value
                }
                childP[j] = primary(current)
                childM[j] = zeroWeightMet(current)
                childS[j] = secondary(current)
                childLow[j] = low
                for (e in combo) current[e] -= value
                // Stable insertion by (primary ↓, met ↓, secondary ↓, values below ↑).
                var at = j
                while (at > 0 && before(j, order[at - 1])) {
                    order[at] = order[at - 1]
                    at--
                }
                order[at] = j
            }
            return order
        }

        private fun before(
            a: Int,
            b: Int,
        ): Boolean =
            when {
                childP[a] != childP[b] -> childP[a] > childP[b]
                childM[a] != childM[b] -> childM[a] > childM[b]
                childS[a] != childS[b] -> childS[a] > childS[b]
                else -> childLow[a] < childLow[b]
            }

        /** Whether no completion of this node can strictly beat the best leaf: its (primary, met, secondary) upper bounds are ≤ it, lexicographically. */
        private fun cannotBeatBest(
            depth: Int,
            pUpper: Long,
        ): Boolean {
            if (pUpper != bestP) return pUpper < bestP
            val mUpper = zeroWeightMetUpper(depth)
            if (mUpper != bestM) return mUpper < bestM
            return secondaryUpper(depth) <= bestS
        }

        private fun primaryUpper(
            depth: Int,
            prunesBelow: Long,
        ): Long {
            intervalCount = 0
            if (precision) {
                // 4·P = 4·Σ rows + 4·trunc(Y / 4), Y the aggregate's capped sum: ≤ 4·Σ rows + Y once Y cannot end negative (the
                // truncation is then a floor) — past the negative rolls Y only grows — else + 3 (a negative Y truncates up).
                // Keeping the rows of target 0 at 0 or more, each such element first takes the mass [zeroNeed] it needs to get
                // there (filled for this node): the terms are read at the lifted values, that mass counted as spent, and above it
                // the gain is a plain interval (what an element takes past a forced part is no subset sum of the rolls).
                var scaled = 0L
                var aggregateSum = 0L
                for (i in rowElement.indices) {
                    val e = rowElement[i]
                    val r = current[e].toLong() + liftOf(e)
                    scaled += 4L * minOf(rowWeight[i] * r, rowWeight[i] * rowTarget[i])
                    addGainPiece(depth, e, 4L * rowWeight[i], rowTarget[i] - r)
                }
                if (hasAggregate) {
                    for (e in 0 until n) {
                        val r = current[e].toLong() + liftOf(e)
                        aggregateSum += minOf(aggregateWeight * r, aggregateWeight * aggregateTarget)
                        addGainPiece(depth, e, aggregateWeight, aggregateTarget - r)
                    }
                }
                val slack = if (!hasAggregate || (depth >= negativeCount && aggregateSum >= 0L)) 0L else 3L
                val gain = maxGain(depth, if (keepZeroTargets) zeroNeed else null)
                return Math.floorDiv(roundDownToStep(scaled + aggregateSum + gain) + slack, 4L)
            }
            var upper = 0L
            for (i in rowElement.indices) {
                val r = current[rowElement[i]].toLong()
                val t = rowTarget[i]
                upper += rowWeight[i] * maxOf(minOf(r, t), -t)
                // clamp(r + x, −t, t) − clamp(r, −t, t) ≤ min(x, t − max(r, −t)).
                addRoomPiece(depth, rowElement[i], rowWeight[i], t - maxOf(r, -t))
            }
            upper += maxGain(depth)
            if (hasAggregate) {
                upper += aggregateWeight * maxOf(minOf(minLevelUpper(depth), aggregateTarget), -aggregateTarget)
                // The rows and the aggregate's minimum draw on the same mass, which the two bounds above count twice.
                // (Only when the cheap bound does not already prune: it costs a few dozen levels.)
                if (rowElement.isNotEmpty() && depth >= negativeCount && upper >= prunesBelow) upper = minOf(upper, aggregateLevelUpper(depth))
            }
            return roundDownToStep(upper)
        }

        // [levelValue]'s per-element lower bounds: the mass that lifts each element to the level.
        private val levelNeed = LongArray(n)

        /**
         * Most-masteries / max-damage with the aggregate row beside per-element rows: a joint bound. Fixing the final minimum at
         * a level L forces every element up to L; the aggregate then reads `W·clamp(L, −T, T)` and the rows gain at most their
         * best under those lower bounds ([levelValue]). A completion whose minimum is L is bounded by that value, and its
         * minimum lies between the current one (no negative roll remains) and the water-fill level:
         *  - below −T the aggregate's term is flat while the rows' bound only shrinks as the lower bounds rise, so the current
         *    minimum bounds that whole stretch;
         *  - from −T on, the value is concave in L — the rows' bound is a concave program's value under a convex shift of its
         *    lower bounds, `min(L, T)` is concave — and past T it can only fall: a ternary search over `[max(low, −T),
         *    min(fill, T)]` finds its maximum.
         */
        private fun aggregateLevelUpper(depth: Int): Long {
            var low = Long.MAX_VALUE
            for (e in 0 until n) low = minOf(low, current[e].toLong())
            val high = maxOf(low, minOf(minLevelUpper(depth), maxOf(low, aggregateTarget)))
            var best = levelValue(depth, low)
            var a = maxOf(low, -aggregateTarget)
            var b = high
            if (a > b) return best
            while (b - a > 2) {
                val m1 = a + (b - a) / 3
                val m2 = b - (b - a) / 3
                if (levelValue(depth, m1) < levelValue(depth, m2)) a = m1 + 1 else b = m2
            }
            var level = a
            while (level <= b) {
                best = maxOf(best, levelValue(depth, level))
                level++
            }
            return best
        }

        /** [aggregateLevelUpper]'s bound for a final minimum of [level] (Long.MIN_VALUE when the rolls cannot lift every element there). */
        private fun levelValue(
            depth: Int,
            level: Long,
        ): Long {
            for (e in 0 until n) levelNeed[e] = maxOf(0L, level - current[e])
            // The k largest lifts together must fit the k-set capacity.
            for (e in 0 until n) {
                var at = e
                while (at > 0 && scratch[at - 1] < levelNeed[e]) {
                    scratch[at] = scratch[at - 1]
                    at--
                }
                scratch[at] = levelNeed[e]
            }
            var lifted = 0L
            for (k in 1..n) {
                lifted += scratch[k - 1]
                if (lifted > capacity[depth][k]) return Long.MIN_VALUE
            }
            // Each row's concave envelope `W · min(x, t − max(r, −t))` (no subset-sum hull: it must stay concave in the
            // lifts): what the forced lift already gains, then the best of the rest from there.
            intervalCount = 0
            var value = aggregateWeight * maxOf(minOf(level, aggregateTarget), -aggregateTarget)
            for (i in rowElement.indices) {
                val e = rowElement[i]
                val r = current[e].toLong()
                val t = rowTarget[i]
                val room = t - maxOf(r, -t)
                value += rowWeight[i] * maxOf(minOf(r, t), -t)
                if (rowWeight[i] <= 0L || room <= 0L) continue
                value += rowWeight[i] * minOf(levelNeed[e], room)
                addInterval(e, rowWeight[i], levelNeed[e], room)
            }
            return value + maxGain(depth, levelNeed)
        }

        private fun zeroWeightMetUpper(depth: Int): Int {
            if (precision) return 0
            var met = 0
            for (i in rowElement.indices) {
                if (rowWeight[i] == 0L && rowTarget[i] > 0L && current[rowElement[i]] + capacity[depth][1] >= rowTarget[i]) met++
            }
            if (hasAggregate && aggregateWeight == 0L && aggregateTarget > 0L && minLevelUpper(depth) >= aggregateTarget) met++
            return met
        }

        /**
         * An upper bound on the secondary of the completions whose primary equals the best one's — the only ones the
         * secondary can rank. When that primary is the all-met one, such a completion lifts every element to the targets of
         * its weighed rows first ([liftToTargets]); the bound then starts from the lifted values with that mass already
         * taken, which is what keeps the "every target met, now spread the surplus" plateau small.
         */
        private fun secondaryUpper(depth: Int): Long {
            intervalCount = 0
            // The secondary only ranks the all-met plateau; below it every tie reads 0.
            if (bestP != allMetPrimary) return 0L
            if (!liftToTargets(depth)) return Long.MIN_VALUE // the targets are out of reach: no tie possible
            val values = lifted
            val start = need
            return when (mode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT -> {
                    var upper = 0L
                    for (i in rowElement.indices) {
                        val r = values[rowElement[i]].toLong()
                        val t = rowTarget[i].coerceAtLeast(0L)
                        upper += rowWeight[i] * maxOf(minOf(r - t, t), 0L)
                        // clamp(r + x − t, 0, t) − clamp(r − t, 0, t) ≤ min(x, 2t − max(r, t)).
                        addInterval(rowElement[i], rowWeight[i], 0L, 2L * t - maxOf(r, t))
                    }
                    upper += maxGain(depth, start)
                    if (hasAggregate) {
                        val t = aggregateTarget.coerceAtLeast(0L)
                        upper += aggregateWeight * maxOf(minOf(minLevelUpper(depth) - t, t), 0L)
                    }
                    roundDownToStep(upper)
                }

                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> {
                    // Linear: 4·S ≤ 4·Σ rows + Yu (+ 3 while Yu, the aggregate's uncapped sum, could end negative).
                    var scaled = 0L
                    var aggregateSum = 0L
                    for (i in rowElement.indices) {
                        scaled += 4L * rowWeight[i] * values[rowElement[i]]
                        addInterval(rowElement[i], 4L * rowWeight[i], 0L, Long.MAX_VALUE)
                    }
                    if (hasAggregate) {
                        for (e in 0 until n) {
                            aggregateSum += aggregateWeight * values[e]
                            addInterval(e, aggregateWeight, 0L, Long.MAX_VALUE)
                        }
                    }
                    val slack = if (!hasAggregate || (depth >= negativeCount && aggregateSum >= 0L)) 0L else 3L
                    Math.floorDiv(roundDownToStep(scaled + aggregateSum + maxGain(depth, start)) + slack, 4L)
                }

                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> 0L
            }
        }

        // [zeroTargetNeedsFit]'s output: the mass each row-of-target-0 element still needs to get back to 0 (rounded up to a sum
        // the remaining rolls can form), 0 elsewhere.
        private val zeroNeed = LongArray(n)

        /** The forced lift of element [e] at this node: its [zeroNeed] when keeping the rows of target 0, else nothing. */
        private fun liftOf(e: Int): Long = if (keepZeroTargets) zeroNeed[e] else 0L

        /** [addRoomPiece], or a plain interval above an element's forced lift (see [primaryUpper]). */
        private fun addGainPiece(
            depth: Int,
            e: Int,
            slope: Long,
            room: Long,
        ) {
            if (liftOf(e) > 0L) addInterval(e, slope, 0L, room) else addRoomPiece(depth, e, slope, room)
        }

        /**
         * Fills [zeroNeed] for this node; false when the remaining rolls cannot lift every row-of-target-0 element back to 0
         * together: some element's deficit is no sum they can form, or some k of them need more than `capacity[depth][k]`.
         * (Negative rolls still to come only make it harder: the check stays a necessary condition.)
         */
        private fun zeroTargetNeedsFit(depth: Int): Boolean {
            zeroNeed.fill(0L)
            val sums = subsetSums[depth]
            for (e in zeroTargetElements) {
                val deficit = -current[e].toLong()
                if (deficit <= 0L) continue
                if (deficit > Int.MAX_VALUE - 1L) return false
                val reach = sums.nextSetBit(deficit.toInt())
                if (reach < 0) return false
                zeroNeed[e] = reach.toLong()
            }
            return needsFitCapacities(depth, zeroNeed)
        }

        /** Whether the k largest of [needs] fit `capacity[depth][k]` for every k. */
        private fun needsFitCapacities(
            depth: Int,
            needs: LongArray,
        ): Boolean {
            for (e in 0 until n) {
                var at = e
                while (at > 0 && scratch[at - 1] < needs[e]) {
                    scratch[at] = scratch[at - 1]
                    at--
                }
                scratch[at] = needs[e]
            }
            var largest = 0L
            for (k in 1..n) {
                largest += scratch[k - 1]
                if (largest > capacity[depth][k]) return false
            }
            return true
        }

        // [liftToTargets]'s output: the mass each element still needs to meet every weighed row on it, and the values after.
        private val need = LongArray(n)
        private val lifted = IntArray(n)

        /**
         * Fills [need] / [lifted] for a completion meeting every row the primary weighs (the all-met primary). False when the
         * remaining rolls cannot deliver those needs together (some k elements need more than `capacity[depth][k]`).
         */
        private fun liftToTargets(depth: Int): Boolean {
            need.fill(0L)
            for (i in rowElement.indices) {
                if (rowWeight[i] == 0L) continue
                val e = rowElement[i]
                need[e] = maxOf(need[e], rowTarget[i] - current[e])
            }
            if (hasAggregate && aggregateWeight != 0L) {
                for (e in 0 until n) need[e] = maxOf(need[e], aggregateTarget - current[e])
            }
            // Keeping the rows of target 0 at 0 or more: those elements must also get back to 0.
            if (keepZeroTargets) for (e in zeroTargetElements) need[e] = maxOf(need[e], -current[e].toLong())
            // What an element receives is a subset sum of the remaining positive rolls (negative ones only lower it): meeting
            // a need takes at least the smallest such sum that reaches it.
            val sums = subsetSums[depth]
            for (e in 0 until n) {
                if (need[e] <= 0L) continue
                if (need[e] > Int.MAX_VALUE - 1L) return false
                val reach = sums.nextSetBit(need[e].toInt())
                if (reach < 0) return false
                need[e] = reach.toLong()
            }
            // The k largest needs together must fit the k-set capacity.
            if (!needsFitCapacities(depth, need)) return false
            for (e in 0 until n) lifted[e] = (current[e] + need[e]).toInt()
            return true
        }

        /**
         * Element [e]'s gain `slope · min(x, room)` from a further mass `x` — which is a subset sum of the remaining positive
         * rolls. Below the largest such sum α ≤ room the gain is exactly `slope · x`; filling the room needs the smallest
         * sum σ ≥ room, so the gain's concave envelope rises only `slope · (room − α)` over `[α, σ]` (slope rounded up).
         */
        private fun addRoomPiece(
            depth: Int,
            e: Int,
            slope: Long,
            room: Long,
        ) {
            if (slope <= 0L || room <= 0L) return
            val sums = subsetSums[depth]
            val probe = room.coerceAtMost(Int.MAX_VALUE.toLong() - 1L).toInt()
            val below = sums.previousSetBit(probe).toLong()
            val above = sums.nextSetBit(probe).toLong()
            when {
                above == room -> addInterval(e, slope, 0L, room)
                above < 0L -> addInterval(e, slope, 0L, below)
                else -> {
                    addInterval(e, slope, 0L, below)
                    addInterval(e, ceilDiv(slope * (room - below), above - below), below, above)
                }
            }
        }

        private fun addInterval(
            e: Int,
            slope: Long,
            from: Long,
            to: Long,
        ) {
            if (slope <= 0L || to <= from) return
            intervalElement[intervalCount] = e
            intervalSlope[intervalCount] = slope
            intervalFrom[intervalCount] = from
            intervalTo[intervalCount] = to
            intervalCount++
        }

        /**
         * The maximum of `Σ_e g_e(x_e)` — `g_e` the sum of element e's intervals, a concave function — over the masses `x` the
         * remaining positive rolls could place: `x ≥ 0` and, for every k, any k elements together ≤ `capacity[depth][k]`.
         * Separable concave over a polymatroid, so taking the steepest segment first, each as far as the constraints allow,
         * is optimal — and a sound upper bound on every real placement. [start]: mass already committed per element (counted
         * against the capacities, not gained) — the needs of a lift to the targets.
         */
        private fun maxGain(
            depth: Int,
            start: LongArray? = null,
        ): Long {
            if (intervalCount == 0 || capacity[depth][n] <= 0L) return 0L
            var segments = 0
            for (e in 0 until n) {
                // This element's breakpoints, sorted and distinct.
                var points = 0
                for (iv in 0 until intervalCount) {
                    if (intervalElement[iv] != e) continue
                    points = insertDistinct(points, intervalFrom[iv])
                    points = insertDistinct(points, intervalTo[iv])
                }
                for (k in 0 until points - 1) {
                    val from = breakpoints[k]
                    val to = breakpoints[k + 1]
                    var slope = 0L
                    for (iv in 0 until intervalCount) {
                        if (intervalElement[iv] == e && intervalFrom[iv] <= from && intervalTo[iv] >= to) slope += intervalSlope[iv]
                    }
                    if (slope <= 0L) continue
                    // Insertion by slope, descending (an element's own slopes never increase along x: concave).
                    var at = segments
                    while (at > 0 && segmentSlope[at - 1] < slope) {
                        segmentSlope[at] = segmentSlope[at - 1]
                        segmentLength[at] = segmentLength[at - 1]
                        segmentElement[at] = segmentElement[at - 1]
                        at--
                    }
                    segmentSlope[at] = slope
                    segmentLength[at] = to - from
                    segmentElement[at] = e
                    segments++
                }
            }
            if (start == null) taken.fill(0L) else start.copyInto(taken)
            var gain = 0L
            for (s in 0 until segments) {
                val e = segmentElement[s]
                val take = minOf(segmentLength[s], feasibleIncrease(depth, e))
                if (take <= 0L) continue
                gain += segmentSlope[s] * take
                taken[e] += take
            }
            return gain
        }

        private fun insertDistinct(
            count: Int,
            value: Long,
        ): Int {
            var at = count
            while (at > 0 && breakpoints[at - 1] > value) at--
            if (at > 0 && breakpoints[at - 1] == value) return count
            for (k in count downTo at + 1) breakpoints[k] = breakpoints[k - 1]
            breakpoints[at] = value
            return count + 1
        }

        /** How much more element [e] can take under every k-set capacity: the tightest set of size k is e and the k − 1 fullest others. */
        private fun feasibleIncrease(
            depth: Int,
            e: Int,
        ): Long {
            var others = 0
            for (o in 0 until n) {
                if (o == e) continue
                var at = others
                while (at > 0 && scratch[at - 1] < taken[o]) {
                    scratch[at] = scratch[at - 1]
                    at--
                }
                scratch[at] = taken[o]
                others++
            }
            val cap = capacity[depth]
            var slack = Long.MAX_VALUE
            var fullest = 0L
            for (k in 1..n) {
                slack = minOf(slack, cap[k] - taken[e] - fullest)
                if (k - 1 < others) fullest += scratch[k - 1]
            }
            return slack
        }

        /**
         * An upper bound on the final minimum over the elements: every k lowest elements together can still receive at most
         * `capacity[depth][k]`, so the minimum cannot rise past the water-fill level that mass reaches over them.
         */
        private fun minLevelUpper(depth: Int): Long {
            for (e in 0 until n) {
                var at = e
                val v = current[e].toLong()
                while (at > 0 && scratch[at - 1] > v) {
                    scratch[at] = scratch[at - 1]
                    at--
                }
                scratch[at] = v
            }
            val cap = capacity[depth]
            var bound = Long.MAX_VALUE
            for (k in 1..n) {
                var level = scratch[0]
                var left = cap[k]
                var filled = 1
                while (true) {
                    val next = if (filled < k) scratch[filled] else Long.MAX_VALUE
                    if (next == Long.MAX_VALUE || (next - level) * filled > left) {
                        level += left / filled
                        break
                    }
                    left -= (next - level) * filled
                    level = next
                    filled++
                }
                bound = minOf(bound, level)
            }
            return bound
        }
    }

    private fun ceilDiv(
        a: Long,
        b: Long,
    ): Long = -Math.floorDiv(-a, b)

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * How many search nodes one placement may visit. The problem is NP-hard (two elements and one-element rolls already
         * make it a partition problem), so a fixed, deterministic budget bounds the scorers' worst case (0.1–0.7 µs a node).
         * Real builds stay below it: over thousands of random real builds (levels 110–245) under every request shape the GUI
         * can send, the largest searches took 893 k nodes (~0.6 s: max-damage with "all resistances" beside per-element
         * resistance rows) and 593 k (65 ms: most-masteries' four resistance rows with the Kel'Dwa ring's −250 on one random
         * element), most shapes a few thousand at most. Only synthetic shapes with the aggregate row beside per-element rows in
         * most-masteries / max-damage (twelve to sixteen rolls of 10–800 on 1–3 elements) reach it: 52 of 40 000 in a sweep,
         * where the placement kept — the best found, complete — was within 0.05 % of the optimum's primary (≤ 450 solver units).
         * That is the one place the model can claim more than the scorer reads, a warning being logged when it happens: such a
         * cut placement can even read a row as missed (one point short) that the full search — and the solver's hard leg — meets.
         * So "the solver says the targets are met ⇔ the scorer's placement meets them" holds within this budget; no tight probe
         * on real items (12 000 of them, targets set to an achievable placement) came near it.
         */
        internal const val NODE_BUDGET = 2_000_000L

        // How many placements ran out of [NODE_BUDGET] in this JVM (rate-limits the warning).
        private val BUDGET_HITS = AtomicLong()

        // The last placements of this JVM (least recently used out first), see [search]. Shared by every thread: guarded by itself.
        private const val PLACEMENT_MEMO_SIZE = 512
        private val PLACEMENTS =
            object : LinkedHashMap<PlacementKey, Placement?>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlacementKey, Placement?>): Boolean = size > PLACEMENT_MEMO_SIZE
            }

        // Beyond this many visited states the memo stops growing (the search stays exact, only deduplicates less).
        private const val MEMO_LIMIT = 1_000_000

        private fun gcd(
            a: Long,
            b: Long,
        ): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val r = x % y
                x = y
                y = r
            }
            return x
        }

        // The most children a node has: C(4, 2) subsets of the (at most) four elements.
        private const val MAX_CHILDREN = 6

        /** The objective of [family]'s per-element rows in [targetStats] under [mode], or null when they are not read jointly. */
        internal fun of(
            targetStats: TargetStats,
            family: ElementFamily,
            mode: ScoreComputationMode,
        ): ElementRowObjective? {
            if (!targetStats.readsJointPerElementRows(family, mode)) return null
            val wanted = family.wanted(targetStats).keys
            val elements = family.elements.filter { it in wanted }
            // In element order (the request is a hash set): the same rows always give the same objective, and the same memo key.
            val rows =
                targetStats
                    .filter { it.characteristic in elements }
                    .sortedWith(compareBy({ elements.indexOf(it.characteristic) }, { it.target }, { it.userDefinedWeight }))
            val aggregate = targetStats.firstOrNull { it.characteristic == family.aggregate }
            return ElementRowObjective(
                mode = mode,
                elements = elements,
                rowElement = IntArray(rows.size) { elements.indexOf(rows[it].characteristic) },
                rowTarget = LongArray(rows.size) { rows[it].target.toLong() },
                rowWeight = LongArray(rows.size) { targetStats.fixedPointWeight(rows[it]) },
                hasAggregate = aggregate != null,
                aggregateTarget = aggregate?.target?.toLong() ?: 0L,
                aggregateWeight = aggregate?.let { targetStats.fixedPointWeight(it) } ?: 0L
            )
        }

        /** All k-element index subsets of [0, n), in lexicographic order. */
        private fun indexCombinations(
            n: Int,
            k: Int,
        ): List<IntArray> {
            val result = mutableListOf<IntArray>()
            val combo = IntArray(k)

            fun build(
                start: Int,
                depth: Int,
            ) {
                if (depth == k) {
                    result.add(combo.copyOf())
                    return
                }
                for (i in start..n - k + depth) {
                    combo[depth] = i
                    build(i + 1, depth + 1)
                }
            }
            if (k in 0..n) build(0, 0)
            return result
        }
    }
}
