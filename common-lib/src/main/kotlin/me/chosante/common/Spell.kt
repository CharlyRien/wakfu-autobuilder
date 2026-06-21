package me.chosante.common

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.min

/**
 * The **per-level damage formula** of a spell, decoded from the local client's bdata (the spell's
 * StaticEffect `[base, inc]` params) and baked into `spell-damage.json` by the `bdata-extractor`. Joined
 * onto [Spell] by [spellId] at catalog load, exactly like the cast limits.
 *
 * Wakfu spell damage scales with the **caster's level**: `hit = floor(base + inc × level)`, capped at
 * [levelCap] (the spell's `max_level`, normally 245). This is the piece the encyclopedia can't provide —
 * it only ever publishes the value at one level — so a level-20 build no longer gets level-245 numbers.
 *
 * The right bdata effect is pinned by **anchoring on the encyclopedia**: the effect whose
 * `floor(base + inc × levelCap)` equals the encyclopedia's displayed value is the spell's base hit. When
 * no effect reproduces it ([matched] = false — DoT/random/special spells), a linear approximation through
 * that known value is used (`base = 0`, `inc = encyclopediaValue / levelCap`), so the value stays exact at
 * `levelCap` and degrades gracefully below it — never worse than the old flat number.
 */
@Serializable
data class SpellDamageScaling(
    val spellId: Int,
    val base: Double,
    val inc: Double,
    val critBase: Double,
    val critInc: Double,
    val levelCap: Int,
    val matched: Boolean,
)

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
 * [baseDamage] / [critDamage] are the spell's base hit at **max level** (the encyclopedia's reference
 * level) — the build-independent `Base` term of Wakfu's damage formula. To get the hit at an arbitrary
 * caster level, use [baseDamageAt] / [critDamageAt], which scale via the bdata [damageScaling] formula
 * (falling back to these flat max-level values when no scaling is available). [SpellDamage] multiplies the
 * level-scaled base by the build's masteries.
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
    /**
     * WP (Wakfu Point) base cost (Ankama's `pw_base`), or null when unknown. `0` = free. Sourced from the
     * baked cast-limit data (`spell-cast-limits-v<VERSION>.json`, joined by [id] in `SpellCatalog`), **not**
     * the encyclopedia. Carried for display and future rotation modelling — WP is a per-fight pool, not a
     * per-turn cap, so it is deliberately **not** folded into [maxCastsThisTurn] yet (see
     * `docs/FULL_DAMAGE_MODE_STATUS.md` "Lot 1").
     */
    val wpCost: Int? = null,
    val rangeMin: Int? = null,
    val rangeMax: Int? = null,
    val baseDamage: Int? = null,
    val critDamage: Int? = null,
    val area: SpellArea? = null,
    val requiresLineOfSight: Boolean? = null,
    val levelRequired: Int? = null,
    /**
     * Minimum number of turns between two casts of this spell (Ankama's `cast_min_interval`), or null
     * when unknown. Sourced from the baked cast-limit data (`spell-cast-limits-v<VERSION>.json`, joined
     * by [id] in `SpellCatalog`), **not** the encyclopedia. `0`/null means "no cooldown"; any value
     * `> 0` means the spell can be cast at most once this turn (see [maxCastsThisTurn]).
     */
    val cooldown: Int? = null,
    /**
     * Maximum number of times this spell may be cast in a single turn (Ankama's `cast_max_per_turn`),
     * or null when unknown. Sourced from the baked cast-limit data (`spell-cast-limits-v<VERSION>.json`,
     * joined by [id] in `SpellCatalog`), **not** the encyclopedia. Per that data's convention `0` means
     * "no per-turn limit" (unlimited), which [maxCastsThisTurn] treats the same as null. See
     * `docs/SPELL_CAST_LIMITS_EXTRACTION.md`.
     */
    val maxCastPerTurn: Int? = null,
    /**
     * Maximum number of times this spell may be cast **on a single target** in one turn (Ankama's
     * `cast_max_per_target`), or null when unknown. Sourced from the baked cast-limit data
     * (`spell-cast-limits-v<VERSION>.json`, joined by [id] in `SpellCatalog`), **not** the encyclopedia.
     * Per that data's convention `0` means "no per-target limit". The rotation is single-target, so for a
     * lone boss this often binds **below** [maxCastPerTurn] (e.g. Sablier: 4 per turn but 1 per target ⇒
     * 1 legal cast) — [maxCastsThisTurn] folds it in.
     */
    val maxCastPerTarget: Int? = null,
    /**
     * Per-level damage formula (bdata), joined by [id] in `SpellCatalog` from `spell-damage.json`, **not**
     * the encyclopedia. Lets [baseDamageAt] scale the hit to the caster's level; null when the spell has no
     * damage or no bdata scaling could be derived (then [baseDamageAt] falls back to the flat [baseDamage]).
     */
    val damageScaling: SpellDamageScaling? = null,
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

    /**
     * The spell's **base (non-crit) hit at the caster's [level]**, level-scaled via [damageScaling]
     * (`floor(base + inc × min(level, levelCap))`). Falls back to the flat encyclopedia [baseDamage]
     * (the max-level value) when no scaling is available — so the result is never inflated above, and at
     * `levelCap` equals, the old behaviour. Null only when the spell has no readable damage at all.
     */
    fun baseDamageAt(level: Int): Int? = damageScaling?.let { floor(it.base + it.inc * min(level, it.levelCap) + DAMAGE_FLOOR_EPSILON).toInt() } ?: baseDamage

    /** The spell's **critical hit at the caster's [level]** (see [baseDamageAt]); falls back to [critDamage]. */
    fun critDamageAt(level: Int): Int? = damageScaling?.let { floor(it.critBase + it.critInc * min(level, it.levelCap) + DAMAGE_FLOOR_EPSILON).toInt() } ?: critDamage

    /**
     * How many times this spell can be cast **at a single target** in one turn, or `null` when it is
     * effectively unbounded (limited only by the AP budget). The rotation models single-target damage
     * (turns-to-kill one boss), so every cast lands on the same target and the binding per-turn cap is the
     * tightest of the baked cast-limit fields:
     *  - [cooldown] `> 0` (a minimum-interval spell) ⇒ at most **one** cast this turn;
     *  - otherwise the **minimum** of [maxCastPerTurn] (total casts this turn) and [maxCastPerTarget]
     *    (casts on one target this turn) — each `0`/null in the data means "no limit" on that axis.
     *
     * Example: a spell with `maxCastPerTurn = 4` but `maxCastPerTarget = 1` can be cast **once** against a
     * lone boss (4 total across targets, but only 1 on the same one).
     *
     * Per-spell **WP** cost ([wpCost]) is deliberately **not** folded in: WP is a per-*fight* pool (not a
     * per-turn allowance), so bounding a single turn by it needs a separate amortized WP-budget model —
     * see `docs/FULL_DAMAGE_MODE_STATUS.md` "Lot 1".
     */
    val maxCastsThisTurn: Int?
        get() {
            if ((cooldown ?: 0) > 0) return 1
            val perTurn = (maxCastPerTurn ?: 0).takeIf { it > 0 }
            val perTarget = (maxCastPerTarget ?: 0).takeIf { it > 0 }
            return listOfNotNull(perTurn, perTarget).minOrNull()
        }

    /** True when this active spell removes resistance from the target (usable as a rotation-opening debuff). */
    val isResistanceDebuff: Boolean get() = (targetResistanceReductionFlat ?: 0) > 0

    /**
     * True when [isResistanceDebuff] **and** the extractor confirmed the reduction targets the enemy (no
     * [RESISTANCE_TARGET_UNCERTAIN_FLAG] in [missingFields]). Only confirmed debuffs may lower a boss's
     * resistance in rotation valuation — an unconfirmed one might be a self/ally buff, so using it would
     * invent an enemy debuff that may not exist.
     */
    val isConfirmedResistanceDebuff: Boolean get() = isResistanceDebuff && RESISTANCE_TARGET_UNCERTAIN_FLAG !in missingFields

    companion object {
        /** [missingFields] marker the extractor adds when a resistance debuff's enemy target is unconfirmed. */
        const val RESISTANCE_TARGET_UNCERTAIN_FLAG = "resistanceTarget?"

        /** Absorbs float error before flooring a level-scaled hit (e.g. `76/245·245 = 75.9999…` → 76). The
         *  same epsilon is used when the formula is derived, so extraction and runtime agree. */
        const val DAMAGE_FLOOR_EPSILON = 1e-6
    }
}
