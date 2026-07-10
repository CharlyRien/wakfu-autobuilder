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
internal class SublimationModel(
    val subVars: Map<Sublimation, IntVar>,
    val forced: Set<Sublimation>,
    val characterLevel: Int,
    /**
     * Extra copy booleans for cumulable normal subs ([Sublimation.maxCopies] > 1): `copyVars[sub] = [b1..b_{k-1}]`,
     * the socketed copies BEYOND the base [subVars] boolean, ordered `b_i ≤ b_{i-1}`. The build hosts
     * `subVars[sub] + Σ copyVars[sub]` copies of `sub` (each on its own carrier), and every copy adds one more
     * single-copy value to the objective. Empty for single-copy subs (the common non-stacking case).
     */
    val copyVars: Map<Sublimation, List<IntVar>> = emptyMap(),
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
