package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.IntVar
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.RuneType
import me.chosante.common.Sublimation

// Leaf CP-SAT model types (Term/RuneModel/SublimationModel/... + the Term range helpers), de-nested from
// the WakfuBuildSolver object so both it and StatBuilder.kt reference them by bare name (B1 of
// docs/code-review-followups.md).

internal data class Term(
    val variable: IntVar,
    val coefficient: Long,
)

/**
 * The reachable contribution range of `coefficient · variable` when the variable ranges over [domain] —
 * SIGN-AWARE (a negative coefficient flips the endpoints). These feed CP-SAT big-M / reachable-domain
 * bounds, so a hand-transposed `first`/`last` is an unsound domain and a wrong "proven optimum"; this is
 * the single definition every site now shares (C1 of docs/code-review-followups.md).
 */
internal fun Term.scaledRange(domain: LongRange): LongRange =
    if (coefficient >= 0) {
        domain.first * coefficient..domain.last * coefficient
    } else {
        domain.last * coefficient..domain.first * coefficient
    }

/** The MAX reachable contribution of `coefficient · variable` over [domain] — `scaledRange(domain).last`, allocation-free. */
internal fun Term.maxContribution(domain: LongRange): Long = if (coefficient >= 0) domain.last * coefficient else domain.first * coefficient

internal data class RandomEntry(
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
internal class RuneModel(
    val runeByCharacteristic: Map<Characteristic, RuneType>,
    val runeVars: Map<Equipment, Map<Characteristic, IntVar>>,
    /**
     * True ⇒ the max-damage rune FOLD ([createRuneModel]): a carrier's [runeVars] are boolean single-type PICKS (one
     * chosen type fills every socket of the item; a pick contributes `slots·coeff`, leaf domain 0..1) — EXCEPT on the
     * [countCarriers], whose vars are per-type counts (0..slots, `coeff` each) because a threshold read can make a
     * part-filled item optimal. Read a var through [isPickCarrier] / [runeVarHi] / [runeVarMultiplier], never through
     * this flag alone. False ⇒ the per-stat count model on every carrier.
     */
    val singleTypePerItem: Boolean = false,
    private val runeTypeByVar: Map<IntVar, RuneType> = emptyMap(),
    private val coefficientByVar: Map<IntVar, Long> = emptyMap(),
    val extraTerms: Map<Characteristic, List<Term>> = emptyMap(),
    private val suppressedBy: Map<Pair<Equipment, Characteristic>, IntVar> = emptyMap(),
    /**
     * True ⇒ the max-damage per-item CHOICE COLLAPSE (`maxDamageRuneChoiceCollapse` in [createRuneModel]): each
     * carrier offers only the Pareto set of its candidate runes (the scenario's M-feeding masteries + critical
     * mastery, each keyed under its own characteristic) over everything the model reads from them
     * ([MaxDamageRuneReads]). With no secondary cap that is the best M-feeding rune, riding the equip var, and, when
     * larger, the critical-mastery rune as a swap bool. A carrier whose best rune is a secondary mastery read by a cap
     * keeps explicit picks instead (`Σ picks = equipped`): a cheaper elemental / secondary / crit rune can free budget.
     * Such carriers coexist with collapsed defaults. False with [singleTypePerItem] ⇒ the GENERAL single-type fold —
     * one pick bool per modeled rune stat (a target row added a non-damage rune type). The AP-cell certifier mirrors
     * the shapes differently (see `certifyMaxPerHitAtApPass`).
     */
    val maxDamageChoiceCollapse: Boolean = false,
    /**
     * Collapse choices kept only because a CHOOSABLE conditional sub reads them, each with those subs
     * ([MaxDamageRuneReads.choiceGates]): `pick ≤ Σ subVar` is posted once the sub model exists. A pure search cut —
     * every build it removes has a never-gated choice that is at least as good while those subs are untaken — so the
     * certifier, which never reads it, bounds a relaxation of the same optimum.
     */
    val choiceGates: Map<IntVar, Set<Sublimation>> = emptyMap(),
    /**
     * CERTIFIER_VERSION 62: the [singleTypePerItem] carriers whose vars are per-type COUNTS (0..slots, `Σ = slots·selected`)
     * instead of picks — a carrier offering a type a single-type fill cannot represent exactly
     * ([MaxDamageRuneReads.mixedStats]: a budgeted secondary cap, a forced condition, a required row, …). Empty
     * without the fold (every carrier is then a count carrier by [singleTypePerItem] alone).
     */
    val countCarriers: Set<Equipment> = emptySet(),
    // Each [countCarriers] var → its carrier's socket count (a gated count is posted as `count ≤ slots·Σ subVar`).
    private val countVarSlots: Map<IntVar, Long> = emptyMap(),
    /**
     * The mixed-rune hybrid's FOLD stage only (`materializeCountCarriers = false` in [createRuneModel]): the carriers
     * [MaxDamageRuneReads.mixedStats] would have made [countCarriers], left on single-type picks. Non-empty ⇒ this model
     * is NOT exact (a part-filled item can beat its optimum), so its CP-SAT `OPTIMAL` proves nothing about the request —
     * see [WakfuBuildSolver.mixedRuneHybrid]. Empty everywhere else.
     */
    val foldInexactCarriers: Set<Equipment> = emptySet(),
    /**
     * The SEARCH model's aggregated count carriers ([RuneCountClass]): count carriers that share an item type, a socket
     * count and their rune choices with the same per-socket values hold ONE count per type for the whole class instead of
     * one per carrier. Those carriers have no [runeVars] entry. Empty in every model a certificate reads (the AP-cell
     * certifier mirrors per-carrier vars, and bails on a model with classes).
     */
    val countClasses: List<RuneCountClass> = emptyList(),
) {
    /** Each [countClasses] var → its class. */
    val countClassByVar: Map<IntVar, RuneCountClass> =
        buildMap { countClasses.forEach { rc -> rc.vars.values.forEach { put(it, rc) } } }

    /** Whether [equip]'s rune vars are boolean single-type PICKS (else per-type counts 0..slots). */
    fun isPickCarrier(equip: Equipment): Boolean = singleTypePerItem && equip !in countCarriers

    /** The upper bound of [equip]'s rune vars: 1 for a pick, the socket count for a count. */
    fun runeVarHi(equip: Equipment): Long = if (isPickCarrier(equip)) 1L else equip.maxShardSlots.toLong()

    /** Sockets one unit of [equip]'s rune var fills: all of them for a pick, one for a count. */
    fun runeVarMultiplier(equip: Equipment): Long = if (isPickCarrier(equip)) equip.maxShardSlots.toLong() else 1L

    /** The largest value a gated choice var takes (1 for a pick, the carrier's sockets for a count, a class's top). */
    fun gateScale(variable: IntVar): Long = countClassByVar[variable]?.hi ?: countVarSlots[variable] ?: 1L

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
 * A CLASS of interchangeable max-damage count carriers ([RuneModel.countClasses]): every [carriers] item has the same item
 * type, [slots] sockets, and offers the same rune types at the same per-socket value ([coefficients]). The search model
 * gives the class ONE count per type ([vars], `0..`[hi]) with `Σ_t vars[t] = slots · Σ_{c ∈ carriers} x_c`, instead of
 * one count per type on every carrier (`Σ_t n_{c,t} = slots · x_c`).
 *
 * Exact (same optimum, same objective value per build): every reader of a rune reads it through the stat sums of
 * [StatBuilder.baseTermsFor] — `Σ_c Σ_t coeff_t · n_{c,t}` — and a class's carriers share `coeff_t`, so a build's stat
 * totals depend on the per-carrier counts only through their class sums `m_t = Σ_c n_{c,t}`. Per-carrier → class: summing
 * a per-carrier solution gives `Σ_t m_t = slots · Σ_c x_c`, the same totals, and each choice gate `n_{c,t} ≤ slots·Σ subVar`
 * implies `m_t ≤ hi·Σ subVar`. Class → per-carrier: with `N = Σ_c x_c` equipped carriers, any `m ≥ 0` with
 * `Σ_t m_t = slots · N` splits into N fills of exactly [slots] runes ([distribute]: fill the carriers one after the other),
 * unequipped carriers get none, and a type with `m_t > 0` has a taken gate sub, so every per-carrier gate holds. The
 * carriers' own stats, socket counts and sublimation slots are untouched (they ride the equip vars). Nothing else reads a
 * count carrier's rune var: forced runes keep the per-stat count model (no fold, no class), and the certificates read the
 * per-carrier model (no class there: the AP-cell certifier bails on a model with classes).
 *
 * Why: the per-carrier counts make every interchangeable carrier its own copy of the rune decision (2,004 carriers × 7
 * counts on the GUI-default level-110 request), a search space the fold's picks did not have; the class keeps the exact
 * mixed fills at a fraction of the variables (docs/MIXED_RUNE_SECONDARY_BUDGET.md "Count classes").
 */
internal class RuneCountClass(
    val carriers: List<Equipment>,
    val slots: Int,
    val vars: Map<Characteristic, IntVar>,
    val coefficients: Map<Characteristic, Long>,
    val runeTypes: Map<Characteristic, RuneType>,
    // The most carriers of the class one build can wear (two rings, else one), capped by the class size.
    val maxWorn: Int,
) {
    /** The top of every class count: all sockets of every carrier the build can wear. */
    val hi: Long get() = slots.toLong() * maxWorn

    /**
     * The runes of each equipped carrier for the class counts [counts] (`Σ = slots · |equipped|`): the carriers are
     * filled one after the other, each with exactly [slots] runes taken from the types in [vars] order. Deterministic.
     */
    fun distribute(
        equipped: List<Equipment>,
        counts: Map<Characteristic, Long>,
    ): Map<Equipment, List<RuneType>> {
        val left = LinkedHashMap<Characteristic, Long>()
        for (stat in vars.keys) left[stat] = counts[stat] ?: 0L
        val out = LinkedHashMap<Equipment, List<RuneType>>()
        for (equip in equipped) {
            val runes = ArrayList<RuneType>(slots)
            for ((stat, n) in left) {
                if (runes.size >= slots) break
                val take = minOf(n, (slots - runes.size).toLong())
                if (take <= 0L) continue
                repeat(take.toInt()) { runes += runeTypes.getValue(stat) }
                left[stat] = n - take
            }
            if (runes.isNotEmpty()) out[equip] = runes
        }
        return out
    }
}

/**
 * Per-search sublimation modelling: the chosen/forced boolean for each modeled sub, the set of subs
 * the user forced (applied unconditionally), and the character level. [EMPTY] means no sub is modeled.
 */
internal class SublimationModel(
    val subVars: Map<Sublimation, IntVar>,
    val forced: Set<Sublimation>,
    val characterLevel: Int,
    /**
     * Extra copy booleans for cumulable normal subs ([Sublimation.maxCopies] > 1): `copyVars[sub] = [b1..b_{k-1}]`,
     * the socketed copies BEYOND the base [subVars] boolean, ordered `b_i ≤ b_{i-1}`. The build hosts
     * `subVars[sub] + Σ copyVars[sub]` copies of `sub` (each on its own carrier), and each shard adds its
     * family-level marginal value to the objective. Empty for single-copy subs (the common non-stacking case).
     */
    val copyVars: Map<Sublimation, List<IntVar>> = emptyMap(),
    /** Forced numeric families are fixed at this exact level; every needed shard is pinned. */
    val chosenLevels: Map<Sublimation, Int> = emptyMap(),
) {
    companion object {
        val EMPTY = SublimationModel(emptyMap(), emptySet(), 0)
    }
}

internal data class SkillTerms(
    val fixed: Map<Characteristic, List<Term>>,
    val percent: Map<Characteristic, List<Term>>,
)

internal data class PowerTable(
    val values: LongArray,
    val maxValue: Long,
)
