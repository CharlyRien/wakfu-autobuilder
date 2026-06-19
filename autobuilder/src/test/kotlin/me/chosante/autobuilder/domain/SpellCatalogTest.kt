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
    fun `joins the baked per-spell cast limits onto the catalog`() {
        // Anchor on oracle-verified spells (docs/SPELL_CAST_LIMITS_EXTRACTION.md §8). The ids are pinned to
        // the current Wakfu data version; firstOrNull + isNotNull turns a future data bump that removes one
        // into a clear "update the anchor" message instead of a raw NoSuchElementException.
        // Cra "Flèche ardente" (Blazing Arrow, 4769) reads "3 uses per turn" in-game, but only 2 on the same
        // target — so against a lone boss the single-target cap is the tighter per-target value, 2.
        val blazingArrow = SpellCatalog.forClass(CharacterClass.CRA).firstOrNull { it.id == 4769 }
        assertThat(blazingArrow).describedAs("oracle anchor 'Flèche ardente' (4769) — update if the data version changed").isNotNull
        assertThat(blazingArrow!!.maxCastPerTurn).isEqualTo(3)
        assertThat(blazingArrow.maxCastPerTarget).isEqualTo(2)
        assertThat(blazingArrow.maxCastsThisTurn).describedAs("min(3 per turn, 2 per target) = 2 vs a lone boss").isEqualTo(2)
        assertThat(blazingArrow.wpCost).describedAs("a normal AP arrow costs no WP").isEqualTo(0)

        // WP cost is joined too (for display + future modelling): Cra "Flèche destructrice" (4814) is a
        // WP-gated arrow ("Points Wakfu" / pw_base = 1). It is NOT folded into the per-turn cap.
        val destructiveArrow = SpellCatalog.forClass(CharacterClass.CRA).firstOrNull { it.id == 4814 }
        assertThat(destructiveArrow).describedAs("oracle anchor 'Flèche destructrice' (4814) — update if the data version changed").isNotNull
        assertThat(destructiveArrow!!.wpCost).isEqualTo(1)

        // Guard against a silent join failure: if the cast-limit resource didn't deserialize, the join
        // would leave every spell unbounded. A real join caps a substantial share of the kit.
        assertThat(SpellCatalog.spells.count { it.maxCastsThisTurn != null })
            .describedAs("spells carrying a real per-turn cast limit")
            .isGreaterThan(50)
        assertThat(SpellCatalog.spells.count { (it.wpCost ?: 0) > 0 })
            .describedAs("spells carrying a WP cost")
            .isGreaterThan(50)
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
