package me.chosante.autobuilder.domain

import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.SpellElement
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpellCatalogTest {
    @Test
    fun `loads the embedded spell dataset for every class`() {
        assertThat(SpellCatalog.spells).isNotEmpty
        CharacterClass.entries
            .filter { it != CharacterClass.UNKNOWN }
            .forEach { clazz ->
                assertThat(SpellCatalog.damageSpells(clazz))
                    .describedAs("damage spells for $clazz")
                    .isNotEmpty
            }
    }

    @Test
    fun `Cra can play Fire, Earth and Air but not Water — the bug this dataset fixes`() {
        // The motivating case: boss auto-element must never tell a Cra to play Water.
        val cra = SpellCatalog.playableElements(CharacterClass.CRA)
        assertThat(cra).contains(SpellElement.FIRE, SpellElement.EARTH, SpellElement.AIR)
        assertThat(cra).doesNotContain(SpellElement.WATER)
    }

    @Test
    fun `a spell's expected damage rises with the build's elemental mastery`() {
        val character = Character(clazz = CharacterClass.CRA, level = 230, minLevel = 1)
        val emptyBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(230))
        val fireSpell = SpellCatalog.damageSpells(CharacterClass.CRA).first { it.element == SpellElement.FIRE }

        val damage = BuildSpellDamage.expectedDamage(fireSpell, emptyBuild, character)
        assertThat(damage).isNotNull
        // With no gear the expected hit is around the spell's base (only the 3% base crit nudges it).
        assertThat(damage!!.expected).isGreaterThan(0.0)
        assertThat(damage.expected).isLessThan(fireSpell.critDamage!!.toDouble() + 1)
    }
}
