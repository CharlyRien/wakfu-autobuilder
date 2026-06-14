package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * The four Wakfu damage elements, each mapped to the elemental-mastery [Characteristic] that scales a
 * spell of that element. (`AIR` maps to `MASTERY_ELEMENTARY_WIND`, Ankama's internal name for air.)
 *
 * Defined here in `common-lib` so the spell dataset and the damage helper ([SpellDamage]) can reference
 * it without depending on the search engine. The max-damage / boss feature branches carry their own
 * `SpellElement` in `autobuilder/domain`; the two are 1:1 and meant to be reconciled when those
 * branches and this dataset meet — see `docs/SPELLS_AND_COMBO_RESEARCH.md`.
 */
@Serializable
enum class SpellElement(
    val masteryCharacteristic: Characteristic,
) {
    FIRE(Characteristic.MASTERY_ELEMENTARY_FIRE),
    WATER(Characteristic.MASTERY_ELEMENTARY_WATER),
    EARTH(Characteristic.MASTERY_ELEMENTARY_EARTH),
    AIR(Characteristic.MASTERY_ELEMENTARY_WIND),
}

/** Whether a spell is an active (castable) spell or a passive. Damage only ever applies to actives. */
@Serializable
enum class SpellCategory {
    ACTIVE,
    PASSIVE,
}

/** Best-effort target shape of a spell, parsed from its description (mono-target vs an area of effect). */
@Serializable
enum class SpellArea {
    SINGLE_TARGET,
    AREA,
}

/**
 * A Wakfu class spell, extracted from the Ankama encyclopedia by the `spells-extractor` module and
 * baked into the `autobuilder` resources as `spells-v<VERSION>.json` (same pattern as
 * `equipments`/`monsters`/`runes`).
 *
 * **No value is ever invented.** Every numeric field is nullable: when the extractor cannot read a
 * field off the encyclopedia page it stores `null` and records the field name in [missingFields], so
 * coverage is auditable and downstream code can tell "0" from "unknown".
 *
 * [baseDamage] / [critDamage] are the spell's base hit at the encyclopedia's **reference level** (the
 * page's default, i.e. the max character level it scales to) — the build-independent `Base` term of
 * Wakfu's damage formula. They are what [SpellDamage] multiplies by the build's masteries.
 *
 * @property element the spell's primary damage element, or `null` for utility / no-damage / passive
 *   spells (the gate that answers "can this class even play this element").
 * @property source data provenance, for traceability (e.g. `"ankama-encyclopedia"`).
 * @property missingFields names of fields the extractor could not parse for this spell.
 */
@Serializable
data class Spell(
    val id: Int,
    val clazz: CharacterClass,
    val name: I18nText,
    val element: SpellElement? = null,
    val category: SpellCategory = SpellCategory.ACTIVE,
    val apCost: Int? = null,
    val wpCost: Int? = null,
    val rangeMin: Int? = null,
    val rangeMax: Int? = null,
    val baseDamage: Int? = null,
    val critDamage: Int? = null,
    val area: SpellArea? = null,
    val requiresLineOfSight: Boolean? = null,
    val levelRequired: Int? = null,
    val cooldown: Int? = null,
    val iconId: Int? = null,
    val description: I18nText? = null,
    /**
     * Flat **all-element** resistance this spell removes from the target when cast — a vulnerability
     * debuff (e.g. 100 = "-100 Elemental Resistance"), or null if it applies none. Reducing the target's
     * resistance raises every subsequent hit, so a rotation casts this first; the magnitude is FLAT (the
     * unit the boss profile and `flatResistanceToPercent` use). Only enemy-applied reductions on active
     * spells are captured; duration/conditions and element-specific reductions are not modeled (the
     * latter don't occur in this data version) — see docs/SPELL_ROTATION.md.
     */
    val targetResistanceReductionFlat: Int? = null,
    val source: String = "ankama-encyclopedia",
    val missingFields: List<String> = emptyList(),
) {
    /** True when the spell carries a readable base hit, i.e. it can be fed to [SpellDamage]. */
    val hasDamage: Boolean get() = element != null && baseDamage != null

    /** True when this active spell removes resistance from the target (usable as a rotation-opening debuff). */
    val isResistanceDebuff: Boolean get() = (targetResistanceReductionFlat ?: 0) > 0
}
