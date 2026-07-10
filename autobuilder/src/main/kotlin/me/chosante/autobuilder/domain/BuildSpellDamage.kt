package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.Characteristic
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
        orientationMultiplierPercent: Int = 100,
        targetResistancePercent: Int = 0,
        critCapPercent: Int = 100,
        damageScenario: DamageScenario? = null,
    ): SpellDamage.Result? {
        val stats = resolveStats(spell, build, character, damageScenario) ?: return null
        return SpellDamage.expectedDamage(
            spell = spell,
            stats = stats,
            characterLevel = character.level,
            rangeBand = rangeBand,
            rearMastery = rearMastery,
            berserkMastery = berserkMastery,
            orientationMultiplierPercent = orientationMultiplierPercent,
            targetResistancePercent = targetResistancePercent,
            critCapPercent = critCapPercent
        )
    }

    /**
     * The build's resolved characteristic totals as [SpellDamage] reads them for [spell]'s element — the
     * generic "+all elements" mastery folded into the element key, then the standalone entry dropped so
     * [SpellDamage.expectedDamage] (which re-adds it) doesn't double-count. `null` when the spell has no element.
     *
     * Resolving a build's stats is the expensive part; expose it so a caller showing several scenarios of the
     * SAME spell (e.g. face / back / berserk) can resolve **once** and call [SpellDamage.expectedDamage] per
     * scenario, instead of paying a full re-resolution for each via [expectedDamage].
     */
    fun resolveStats(
        spell: Spell,
        build: BuildCombination,
        character: Character,
        // The attack scenario, or null for a scenario-agnostic view (e.g. the generic compare grid). When a
        // max-damage scenario is supplied, the stats are resolved in max-damage mode WITH the scenario, so the
        // scenario-gated / best-element-concentration / per-element sublimation Damage-Inflicted the CP-SAT
        // objective credits is credited here too — otherwise the shown damage (and the sequencedScore the
        // external loop ranks builds by) silently drops it, re-opening the objective↔display mismatch.
        damageScenario: DamageScenario? = null,
    ): Map<Characteristic, Int>? {
        val element = spell.element ?: return null
        return computeCharacteristicsValues(
            buildCombination = build,
            characterBaseCharacteristics = character.baseCharacteristicValues,
            // Fold generic elemental mastery into the spell's element, exactly like the scorers.
            masteryElementsWanted = mapOf(element.masteryCharacteristic to 1),
            resistanceElementsWanted = emptyMap(),
            scoreComputationMode = damageScenario?.let { ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE },
            damageScenario = damageScenario
        ).toMutableMap()
            .apply { remove(Characteristic.MASTERY_ELEMENTARY) }
    }
}
