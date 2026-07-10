package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.firesInMostMasteries
import me.chosante.common.Characteristic
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.ScenarioGate
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType

// SublimationSemantics — the SINGLE SOURCE OF TRUTH for the sublimation DECISION logic shared by the CP-SAT
// objective (StatBuilder / SublimationTerms / SublimationModelBuilder / MaxDamageCertifier) and the scalar
// re-scorers (FindClosestBuildFromInputScoring & friends). B3 of docs/code-review-followups.md.
//
// The two engines are two BY NECESSITY — the objective emits reified `IntVar` constraints while the scorers do
// scalar int math — so only the *decision* logic can be shared here, not the arithmetic. These are the pure,
// value-free predicates both engines were transcribing independently (the "mirror the solver" comments and the
// A1 drift fault line): which static conditions are modelable, and whether a scenario gate fires for a request.
// Anything that computes a magnitude (a condition's reified/scalar evaluation, a per-element strongest test)
// stays in its own engine.

/**
 * The static-conditional sublimation [SublimationConditionType]s the solver can reify against build stats
 * (research §4a) — and therefore exactly the set the scorers must treat as conditionally applied. A single set
 * so the objective's `reifyCondition` and the scorer's `subConditionHolds` can never disagree on which subs are
 * gated (an unsupported condition ⇒ the sub applies unconditionally in both engines).
 */
internal val SUPPORTED_SUB_CONDITIONS =
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

/**
 * Whether a scenario-gated sublimation effect can fire for a request, from unpacked request facts — the shared
 * decision behind the solver's [WakfuBuildSolver.scenarioGateMatches] (which calls this with `params` unpacked)
 * and the scorers' gate check. Gates are damage-scenario specific, so a gated effect only counts in max-damage
 * mode when the configured [scenario] matches — except a pure element gate in a mono-element most-masteries
 * request, where the build is single-element so a "+% <element> damage" sub boosts all of its damage (see
 * [firesInMostMasteries]). Area is not modeled by [DamageScenario]; it is treated as satisfiable
 * (best-achievable). Ungated effects always count; outside max-damage / most-masteries no gate fires.
 *
 * The solver always supplies a non-null [scenario] (`params.damageScenario`), so its `scenario == null` branch is
 * dead there; the scorers may pass null (defensively) and get `false`, which matches the solver's outcome for
 * every input it produces.
 */
internal fun scenarioGateMatchesCore(
    gate: ScenarioGate?,
    mode: ScoreComputationMode?,
    scenario: DamageScenario?,
    level: Int,
    wantedElements: Set<Characteristic>,
): Boolean {
    if (gate == null) return true
    // Mono-element most-masteries: a pure element gate fires (the build is single-element).
    if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT && gate.firesInMostMasteries(wantedElements)) {
        return true
    }
    if (mode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE || scenario == null) return false
    gate.rangeBand?.let { if (scenario.rangeBand.name != it) return false }
    gate.orientation?.let { if (scenario.orientation.name != it) return false }
    gate.element?.let { if (scenario.element.name != it) return false }
    if (gate.berserk == true && !scenario.berserk) return false
    if (gate.ranged == true && scenario.rangeBand.name != "DISTANCE") return false
    gate.minCharacterLevel?.let { if (level < it) return false }
    return true
}

/**
 * The four per-element elemental-mastery characteristics (FIRE→…_FIRE, AIR→…_WIND), **derived** from
 * [SpellElement] so the list can never drift from the element enum. The single set both engines compare against
 * when testing which element is a build's strongest — the scalar `scenarioElementIsStrongest` and the solver's
 * `scenarioElementStrongestVar` / best-element gating — replacing a hand-written `listOf(...)` in the scorer and
 * three inline `SpellElement.entries.map { … }` copies in the solver.
 */
internal val ELEMENT_MASTERY_CHARACTERISTICS: List<Characteristic> =
    SpellElement.entries.map { it.masteryCharacteristic }

/** The comparison a build-static sublimation condition applies to a (summed) pre-combat stat value. */
internal enum class ConditionComparison {
    AT_MOST,
    AT_LEAST,
    EXACT,
    ;

    /** Whether [value] satisfies this comparison against [threshold]. */
    fun holds(
        value: Int,
        threshold: Int,
    ): Boolean =
        when (this) {
            AT_MOST -> value <= threshold
            AT_LEAST -> value >= threshold
            EXACT -> value == threshold
        }
}

/**
 * The normalized, engine-agnostic meaning of a sublimation condition — the shared DECISION behind the scalar
 * re-scorer's `subConditionHolds` and the CP-SAT reifier's `reifyCondition`. The two engines differ only in HOW
 * they evaluate a spec (scalar ints vs a reified `IntVar`), never in WHAT a condition means. See [subConditionSpec].
 */
internal sealed interface SubConditionSpec {
    /** `sum(`[stats]`)` [comparison] [threshold], read on the build's PRE-COMBAT (character-sheet) stats. */
    data class StatBound(
        val stats: List<Characteristic>,
        val comparison: ConditionComparison,
        val threshold: Int,
    ) : SubConditionSpec

    /** Holds iff the build equips no off-hand and no two-handed weapon — a slot-occupancy test, not a stat read. */
    object NoOffhandOrTwoHanded : SubConditionSpec

    /** No condition, or one the solver does not model ⇒ the sub applies unconditionally in both engines. */
    object AlwaysApplies : SubConditionSpec
}

/**
 * The single decision for a sublimation [cond] (or its absence) at character [level]: which pre-combat stat(s) it
 * reads, the comparison, and the resolved threshold. Shared so the scalar re-scorer and the CP-SAT reifier can
 * never disagree on what a condition *means* — a new condition type is taught once, here, instead of in two
 * transcribed `when` ladders. The set of types that map to a non-[SubConditionSpec.AlwaysApplies] spec is exactly
 * [SUPPORTED_SUB_CONDITIONS] (locked by a test); any other type is not solver-modeled, so the sub applies
 * unconditionally in both engines. `DODGE_LT_PCT_OF_LEVEL` normalizes its strict `< n·level/100` to the
 * integer-equivalent `AT_MOST n·level/100 − 1` so both engines share one threshold.
 */
internal fun subConditionSpec(
    cond: SublimationCondition?,
    level: Int,
): SubConditionSpec {
    val n = cond?.value ?: 0
    return when (cond?.type) {
        null -> SubConditionSpec.AlwaysApplies
        SublimationConditionType.AP_AT_MOST -> SubConditionSpec.StatBound(listOf(Characteristic.ACTION_POINT), ConditionComparison.AT_MOST, n)
        SublimationConditionType.AP_AT_LEAST -> SubConditionSpec.StatBound(listOf(Characteristic.ACTION_POINT), ConditionComparison.AT_LEAST, n)
        SublimationConditionType.AP_EXACT -> SubConditionSpec.StatBound(listOf(Characteristic.ACTION_POINT), ConditionComparison.EXACT, n)
        SublimationConditionType.CRIT_AT_MOST -> SubConditionSpec.StatBound(listOf(Characteristic.CRITICAL_HIT), ConditionComparison.AT_MOST, n)
        SublimationConditionType.CRIT_AT_LEAST -> SubConditionSpec.StatBound(listOf(Characteristic.CRITICAL_HIT), ConditionComparison.AT_LEAST, n)
        SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> SubConditionSpec.StatBound(listOf(Characteristic.MASTERY_CRITICAL), ConditionComparison.AT_MOST, n)
        SublimationConditionType.BLOCK_AT_LEAST -> SubConditionSpec.StatBound(listOf(Characteristic.BLOCK_PERCENTAGE), ConditionComparison.AT_LEAST, n)
        SublimationConditionType.RANGE_AT_MOST -> SubConditionSpec.StatBound(listOf(Characteristic.RANGE), ConditionComparison.AT_MOST, n)
        SublimationConditionType.RANGE_AT_LEAST -> SubConditionSpec.StatBound(listOf(Characteristic.RANGE), ConditionComparison.AT_LEAST, n)
        SublimationConditionType.RANGE_EXACT -> SubConditionSpec.StatBound(listOf(Characteristic.RANGE), ConditionComparison.EXACT, n)
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL ->
            SubConditionSpec.StatBound(listOf(Characteristic.DODGE), ConditionComparison.AT_MOST, (n * level) / 100 - 1)
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
            SubConditionSpec.StatBound(SECONDARY_MASTERY_CHARACTERISTICS.toList(), ConditionComparison.AT_MOST, n)
        SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED -> SubConditionSpec.NoOffhandOrTwoHanded
        else -> SubConditionSpec.AlwaysApplies // AP_ODD / WEAPON_TYPE_EQUIPPED / HIGHEST_* / OTHER — not solver-modeled
    }
}
