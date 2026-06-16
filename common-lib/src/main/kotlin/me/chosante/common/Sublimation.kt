package me.chosante.common

import kotlinx.serialization.Serializable

/** Where a sublimation goes: an epic/relic dedicated character slot, or a normal 3-colour socket set. */
@Serializable
enum class SublimationRarity { EPIC, RELIC, NORMAL }

/**
 * How a sublimation's effect can be modeled by the solver (see docs/SUBLIMATIONS_LOT3_RESEARCH.md §4b):
 * - [FLAT]: unconditional stat contributions.
 * - [STATIC_CONDITIONAL]: applies only when a build-static start-of-combat [condition] holds (CP-SAT modelable).
 * - [CONVERSION]: moves a percentage of one stat into another (optionally under a static condition).
 * - [COMBAT_CONDITIONAL]: depends on in-combat events; **not** solver-modelable — forced-input only.
 */
@Serializable
enum class SublimationKind { FLAT, STATIC_CONDITIONAL, CONVERSION, COMBAT_CONDITIONAL }

/** The finite vocabulary of build-static start-of-combat conditions (see research §4a). */
@Serializable
enum class SublimationConditionType {
    AP_AT_MOST,
    AP_AT_LEAST,
    AP_EXACT,
    AP_ODD,
    CRIT_AT_MOST,
    CRIT_AT_LEAST,
    BLOCK_AT_LEAST,
    RANGE_AT_MOST,
    RANGE_AT_LEAST,
    RANGE_EXACT,
    DODGE_LT_PCT_OF_LEVEL,
    SECONDARY_MASTERIES_AT_MOST,
    WEAPON_TYPE_EQUIPPED,
    HIGHEST_ELEM_MASTERY_GT_REAR,
    HIGHEST_ELEM_MASTERY_GT_HEALING,
    OTHER,
}

@Serializable
data class SublimationCondition(
    val type: SublimationConditionType,
    /** Numeric threshold for the comparison conditions (e.g. 10 for AP_AT_MOST 10). */
    val value: Int? = null,
    /** Free text payload for non-numeric conditions (e.g. "dagger"/"shield" for WEAPON_TYPE_EQUIPPED). */
    val text: String? = null,
)

/**
 * Restricts an effect to a specific attack [me.chosante.autobuilder.domain.DamageScenario]. All fields
 * are compile-time constants for a given scenario, so a non-matching gated effect is simply dropped by
 * the solver (no variable needed). Strings mirror the scenario enums (`MELEE`/`DISTANCE`, `BACK`/`FRONT`/`SIDE`).
 */
@Serializable
data class ScenarioGate(
    val rangeBand: String? = null,
    val orientation: String? = null,
    val berserk: Boolean? = null,
    val ranged: Boolean? = null,
    val area: Boolean? = null,
    val minCharacterLevel: Int? = null,
)

/** One stat contribution of a sublimation, at its max level (best-achievable model). */
@Serializable
data class SublimationEffect(
    val characteristic: Characteristic,
    val value: Int,
    val scenarioGate: ScenarioGate? = null,
)

/** A [SublimationKind.CONVERSION] payload: move [percent]% of [from] into [to] at start of combat. */
@Serializable
data class SublimationConversion(
    val from: Characteristic,
    val to: Characteristic,
    val percent: Int,
)

/**
 * A Wakfu sublimation, keyed by its in-game `stateId` (the stable join key — 467 item rows collapse to
 * 232 distinct effects). Effect data is curated from the community WakForge dump (Ankama's `states.json`
 * carries no effect text); see docs/SUBLIMATIONS_LOT3_RESEARCH.md.
 *
 * Best-achievable model: a chosen sublimation contributes its **max-level** [effects].
 */
@Serializable
data class Sublimation(
    val stateId: Int,
    /** The sublimation's in-game item id — used as Zenith's `/shard/add` `id_shard` (a sub socketes like a rune). */
    val zenithId: Int = 0,
    val name: I18nText,
    val rarity: SublimationRarity,
    /** Normal subs: 3 socket colours (`1`=red, `2`=green, `3`=blue, matching [RuneColor]). Empty for epic/relic. */
    val slotColorPattern: List<Int> = emptyList(),
    val maxLevel: Int = 1,
    val kind: SublimationKind,
    /** True only when the solver can correctly model every effect of this sub (FLAT/STATIC/CONVERSION). */
    val solverChoosable: Boolean = false,
    val condition: SublimationCondition? = null,
    val effects: List<SublimationEffect> = emptyList(),
    val conversion: SublimationConversion? = null,
    /** Human-readable effect text (English), for tooltips and CLI/UI display. */
    val rawText: String? = null,
) {
    /** The required socket colours for a normal sublimation (empty for epic/relic). */
    val colors: List<RuneColor>
        get() = slotColorPattern.mapNotNull { code -> runCatching { RuneColor.fromCode(code) }.getOrNull() }
}
