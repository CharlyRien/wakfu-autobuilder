package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.Spell
import me.chosante.common.SpellDamage

/**
 * Bridges a discovered build to the reusable [SpellDamage] calculator: resolves the build's actual
 * characteristic totals (equipment + skills + runes, via [computeCharacteristicsValues]) and computes
 * the **expected in-game damage of a spell for that build**.
 *
 * This is what the comparison view needs to show "spell X hits for N on build A vs M on build B"
 * between same-class builds. It is a thin, OR-Tools-free wrapper — the damage maths and the formula
 * live in `common-lib` ([SpellDamage]); here we only feed it the right resolved stats.
 */
object BuildSpellDamage {
    /**
     * Expected damage of [spell] for [build] played by [character], or `null` if the spell has no
     * readable base hit ([Spell.hasDamage] is false). See [SpellDamage.expectedDamage] for the
     * scenario parameters ([rangeBand], rear/berserk masteries, target resistance, crit cap).
     */
    fun expectedDamage(
        spell: Spell,
        build: BuildCombination,
        character: Character,
        rangeBand: SpellDamage.RangeBand? = null,
        rearMastery: Boolean = false,
        berserkMastery: Boolean = false,
        targetResistancePercent: Int = 0,
        critCapPercent: Int = 100,
    ): SpellDamage.Result? {
        val element = spell.element ?: return null
        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = character.baseCharacteristicValues,
                // Fold generic elemental mastery into the spell's element, exactly like the scorers.
                masteryElementsWanted = mapOf(element.masteryCharacteristic to 1),
                resistanceElementsWanted = emptyMap()
            )
                // The generic "+all elements" mastery is now folded into the element key above, so drop
                // the standalone entry — otherwise SpellDamage (which also adds it) would count it twice.
                .toMutableMap()
                .apply { remove(me.chosante.common.Characteristic.MASTERY_ELEMENTARY) }
        return SpellDamage.expectedDamage(
            spell = spell,
            stats = stats,
            characterLevel = character.level,
            rangeBand = rangeBand,
            rearMastery = rearMastery,
            berserkMastery = berserkMastery,
            targetResistancePercent = targetResistancePercent,
            critCapPercent = critCapPercent
        )
    }
}
