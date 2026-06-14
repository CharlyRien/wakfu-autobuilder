package me.chosante.autobuilder.domain

import me.chosante.common.Characteristic

/** Spell element of the attack being optimized; maps to the matching elemental mastery. */
enum class SpellElement(
    val masteryCharacteristic: Characteristic,
) {
    FIRE(Characteristic.MASTERY_ELEMENTARY_FIRE),
    WATER(Characteristic.MASTERY_ELEMENTARY_WATER),
    EARTH(Characteristic.MASTERY_ELEMENTARY_EARTH),
    AIR(Characteristic.MASTERY_ELEMENTARY_WIND),
}

/** Distance band of the attack; selects the applicable secondary mastery. */
enum class RangeBand(
    val masteryCharacteristic: Characteristic,
) {
    MELEE(Characteristic.MASTERY_MELEE),
    DISTANCE(Characteristic.MASTERY_DISTANCE),
}

/**
 * Attack orientation: the positional damage multiplier, and whether rear ("back") mastery applies.
 * In Wakfu rear mastery only applies to a back hit; a side hit gets the 1.10 positional multiplier
 * but no rear mastery.
 */
enum class Orientation(
    val multiplierPercent: Int,
    val grantsRearMastery: Boolean,
) {
    FACE(100, false),
    SIDE(110, false),
    BACK(125, true),
}

/**
 * The fixed attack scenario the max-damage mode optimizes for. Wakfu's damage formula has many terms;
 * for a fixed scenario most are build-independent constants — only `ΣMastery`, `% Damage Inflicted`
 * and the crit rate move with the build, so those drive the optimization. The remaining fields
 * ([orientation] multiplier, [targetResistancePercent], [baseDamage]) only scale every build equally,
 * so they affect the *displayed* expected-damage number but not which build is optimal.
 *
 * See docs/ENCHANTMENTS_PLAN.md §8 for the full formula. Note: single-target / area mastery is not
 * modeled — this game-data version exposes no quantifiable source for it (the actionId-400 effect is a
 * valueless marker and WakForge does not model those runes either).
 */
data class DamageScenario(
    val element: SpellElement = SpellElement.FIRE,
    val rangeBand: RangeBand = RangeBand.DISTANCE,
    val orientation: Orientation = Orientation.BACK,
    // Caster is at/below 50% HP (berserk mastery applies).
    val berserk: Boolean = false,
    // The attack is a heal (healing mastery applies).
    val healing: Boolean = false,
    // Upper bound on the usable critical-hit rate (%), e.g. lower it to model a non-crit-focused fight.
    val critCapPercent: Int = 100,
    // Effective elemental resistance of the target (%), already reduced and capped at 90 — a constant
    // (1 − res/100) factor on the displayed damage; does not change which build is optimal.
    val targetResistancePercent: Int = 0,
    // Spell base hit, used only to scale the displayed expected-damage number into a realistic range.
    val baseDamage: Int = 100,
    /**
     * Boss-aware mode: the target's per-element resistance %. When set, the spell-aware max-damage
     * solver optimizes over **all four elements** (each with its own resistance), so it picks the best
     * *playable* element given both the boss's weakness and the class's actual spell kit — instead of
     * the single fixed [element]. Null ⇒ optimize only [element] (with [targetResistancePercent]).
     */
    val elementResistances: Map<SpellElement, Int>? = null,
) {
    /**
     * The (element, resistance%) pairs the max-damage objective/scorer optimize over: every element in
     * [elementResistances] when boss-aware, else just the single [element] with [targetResistancePercent].
     */
    fun candidateElements(): List<Pair<SpellElement, Int>> =
        elementResistances
            ?.let { res -> SpellElement.entries.map { it to (res[it] ?: 0) } }
            ?: listOf(element to targetResistancePercent)

    companion object {
        const val MAX_RESISTANCE_PERCENT = 90
    }
}
