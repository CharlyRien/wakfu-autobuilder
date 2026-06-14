package me.chosante.autobuilder.domain

import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.I18nText
import me.chosante.common.Spell
import me.chosante.common.SpellElement
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpellRotationTest {
    private fun spell(
        id: Int,
        ap: Int,
    ) = Spell(id = id, clazz = CharacterClass.CRA, name = I18nText("s$id", "s$id", "s$id", "s$id"), element = SpellElement.FIRE, apCost = ap, baseDamage = 1)

    @Test
    fun `fills the AP budget with the single most efficient spell when one dominates`() {
        val scored = listOf(ScoredSpell(spell(1, 3), apCost = 3, expectedDamagePerCast = 100.0))
        val rotation = SpellRotationOptimizer.bestRotation(scored, apBudget = 12)
        assertThat(rotation.apUsed).isEqualTo(12)
        assertThat(rotation.casts).singleElement().satisfies({ assertThat(it.count).isEqualTo(4) })
        assertThat(rotation.totalExpectedDamage).isEqualTo(400.0)
    }

    @Test
    fun `chooses the higher-total combination over the higher per-AP-efficiency one`() {
        // A: 5 AP / 100 (20.0 per AP) ; B: 6 AP / 130 (~21.7 per AP). Budget 12.
        // B+B = 12 AP, 260 beats A+A = 10 AP, 200 — the DP must find it, not greedily take per-AP.
        val scored =
            listOf(
                ScoredSpell(spell(1, 5), apCost = 5, expectedDamagePerCast = 100.0),
                ScoredSpell(spell(2, 6), apCost = 6, expectedDamagePerCast = 130.0)
            )
        val rotation = SpellRotationOptimizer.bestRotation(scored, apBudget = 12)
        assertThat(rotation.totalExpectedDamage).isEqualTo(260.0)
        assertThat(rotation.apUsed).isEqualTo(12)
    }

    @Test
    fun `mixes spells when that beats repeating one and leaves AP idle when nothing fits`() {
        val scored =
            listOf(
                ScoredSpell(spell(1, 5), apCost = 5, expectedDamagePerCast = 100.0),
                ScoredSpell(spell(2, 6), apCost = 6, expectedDamagePerCast = 130.0)
            )
        // Budget 11: best is A(5)+B(6) = 230, using all 11 AP.
        val rotation = SpellRotationOptimizer.bestRotation(scored, apBudget = 11)
        assertThat(rotation.totalExpectedDamage).isEqualTo(230.0)
        assertThat(rotation.casts).hasSize(2)
    }

    @Test
    fun `respects an explicit per-spell cast cap`() {
        val scored = listOf(ScoredSpell(spell(1, 3), apCost = 3, expectedDamagePerCast = 100.0))
        val rotation = SpellRotationOptimizer.bestRotation(scored, apBudget = 12, maxCastsPerSpell = 2)
        assertThat(rotation.casts.single().count).isEqualTo(2)
        assertThat(rotation.apUsed).isEqualTo(6)
    }

    @Test
    fun `sequencing opens with the resistance debuff when there's AP room to profit, and skips it when there isn't`() {
        // Sram's Assassination (-100 flat resistance, 1 AP, pure debuff) should be cast FIRST against a
        // resistant boss — it raises every following hit — when there's AP left to benefit.
        val character = Character(clazz = CharacterClass.SRAM, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200))
        val base = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE)

        val highRes = base.copy(targetResistancePercent = 60)
        val seqHigh = SpellRotationOptimizer.bestSequencedRotation(build, character, CharacterClass.SRAM, highRes, apBudget = 12)
        val noDebuffHigh = SpellRotationOptimizer.bestAcrossElements(build, character, CharacterClass.SRAM, highRes, apBudget = 12)
        assertThat(seqHigh.debuffCasts).describedAs("should open with Assassination vs a resistant boss").isNotEmpty
        assertThat(
            seqHigh.debuffCasts
                .single()
                .spell.name.en
        ).isEqualTo("Assassination")
        assertThat(seqHigh.totalExpectedDamage)
            .describedAs("debuff-sequenced damage beats the no-debuff rotation")
            .isGreaterThan(noDebuffHigh.totalExpectedDamage)
        assertThat(seqHigh.effectiveResistancePercent!!).isLessThan(60)

        // With only 1 AP there's no room left after the 1-AP debuff for any 2+-AP damage spell to profit,
        // so the pure debuff (0 own damage) isn't worth it — spend the AP elsewhere.
        val seqTight = SpellRotationOptimizer.bestSequencedRotation(build, character, CharacterClass.SRAM, highRes, apBudget = 1)
        assertThat(seqTight.debuffCasts).describedAs("no AP room to profit ⇒ no debuff").isEmpty()
    }

    @Test
    fun `forBuild gates the rotation to the class's playable element — Cra has no Water spells`() {
        val character = Character(clazz = CharacterClass.CRA, level = 230, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(230))

        val fire = SpellRotationOptimizer.forBuild(build, character, CharacterClass.CRA, DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE), apBudget = 12)
        assertThat(fire.isEmpty).isFalse()
        assertThat(fire.element).isEqualTo(SpellElement.FIRE)
        assertThat(fire.apUsed).isLessThanOrEqualTo(12)
        assertThat(fire.casts).allSatisfy({ assertThat(it.spell.element).isEqualTo(SpellElement.FIRE) })

        val water =
            SpellRotationOptimizer.forBuild(
                build,
                character,
                CharacterClass.CRA,
                DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.WATER),
                apBudget = 12
            )
        assertThat(water.isEmpty).describedAs("Cra cannot play Water — rotation must be empty").isTrue()
    }
}
