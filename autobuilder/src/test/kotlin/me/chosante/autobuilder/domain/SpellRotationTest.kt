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
    fun `a per-spell cap yields the exact capped optimum, not a greedy spam`() {
        // A: 2 AP / 100, B: 3 AP / 160. Budget 6. Uncapped optimum spams B (B+B = 320).
        val scored =
            listOf(
                ScoredSpell(spell(1, 2), apCost = 2, expectedDamagePerCast = 100.0),
                ScoredSpell(spell(2, 3), apCost = 3, expectedDamagePerCast = 160.0)
            )
        assertThat(SpellRotationOptimizer.bestRotation(scored, apBudget = 6).totalExpectedDamage).isEqualTo(320.0)
        // Cap 1 each: B can't be spammed; the true optimum is A+B = 5 AP / 260 (a greedy single-path cap
        // could miss this — the bounded knapsack must find it exactly).
        val capped = SpellRotationOptimizer.bestRotation(scored, apBudget = 6, maxCastsPerSpell = 1)
        assertThat(capped.totalExpectedDamage).isEqualTo(260.0)
        assertThat(capped.casts.map { it.count }).allSatisfy({ assertThat(it).isEqualTo(1) })
    }

    @Test
    fun `the scenario breakdown applies the positional multiplier per orientation`() {
        // A fire build with NO rear/berserk mastery: the only thing that changes between positions is the
        // ×multiplier (face 1.0 / side 1.10 / back 1.25), which the rotation now applies. So side = 1.10×face
        // and back = 1.25×face — proving the displayed damage finally includes the orientation factor.
        val character = Character(clazz = CharacterClass.CRA, level = 200, minLevel = 1)
        val fireItem =
            me.chosante.common.Equipment(
                equipmentId = 1,
                guiId = 1,
                level = 200,
                name = I18nText("Amu", "Amu", "Amu", "Amu"),
                rarity = me.chosante.common.Rarity.COMMON,
                itemType = me.chosante.common.ItemType.AMULET,
                characteristics = mapOf(me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE to 500)
            )
        val build = BuildCombination(equipments = listOf(fireItem), characterSkills = CharacterSkills(200))
        val scenario = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE)

        val breakdown = SpellRotationOptimizer.scenarioBreakdown(build, character, CharacterClass.CRA, scenario, includeBerserk = false)
        val face = breakdown.single { it.orientation == Orientation.FACE && !it.berserk }.totalExpectedDamage
        val side = breakdown.single { it.orientation == Orientation.SIDE && !it.berserk }.totalExpectedDamage
        val back = breakdown.single { it.orientation == Orientation.BACK && !it.berserk }.totalExpectedDamage

        assertThat(breakdown).hasSize(3)
        assertThat(face).isGreaterThan(0.0)
        assertThat(side / face).isCloseTo(
            1.10,
            org.assertj.core.api.Assertions
                .within(1e-6)
        )
        assertThat(back / face).isCloseTo(
            1.25,
            org.assertj.core.api.Assertions
                .within(1e-6)
        )
    }

    @Test
    fun `the scenario breakdown adds a back+berserk row only when asked`() {
        val character = Character(clazz = CharacterClass.CRA, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200))
        val scenario = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE)

        assertThat(SpellRotationOptimizer.scenarioBreakdown(build, character, CharacterClass.CRA, scenario, includeBerserk = false)).hasSize(3)
        val withBerserk = SpellRotationOptimizer.scenarioBreakdown(build, character, CharacterClass.CRA, scenario, includeBerserk = true)
        assertThat(withBerserk).hasSize(4)
        assertThat(withBerserk.last().berserk).isTrue()
        assertThat(withBerserk.last().orientation).isEqualTo(Orientation.BACK)
    }

    @Test
    fun `the scenario breakdown always includes the configured off-grid combo`() {
        // A configured side+berserk scenario is not one of the base FACE/SIDE/BACK(+BACK-berserk) rows, yet the
        // breakdown must still contain it so the result view can highlight the searched scenario.
        val character = Character(clazz = CharacterClass.CRA, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200))
        val scenario =
            DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE, orientation = Orientation.SIDE, berserk = true)

        val breakdown = SpellRotationOptimizer.scenarioBreakdown(build, character, CharacterClass.CRA, scenario, includeBerserk = true)
        assertThat(breakdown).anySatisfy({ assertThat(it.orientation == Orientation.SIDE && it.berserk).isTrue() })
    }

    @Test
    fun `the scenario breakdown reuses a supplied total for the configured combo`() {
        // When the caller already computed the headline rotation, the configured combo's row must reuse that
        // exact total instead of recomputing it.
        val character = Character(clazz = CharacterClass.CRA, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200))
        val scenario = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE, orientation = Orientation.BACK)
        val sentinel = 123_456.0

        val breakdown =
            SpellRotationOptimizer.scenarioBreakdown(build, character, CharacterClass.CRA, scenario, includeBerserk = false, configuredRotationTotal = sentinel)
        assertThat(breakdown.single { it.orientation == Orientation.BACK && !it.berserk }.totalExpectedDamage).isEqualTo(sentinel)
        // The other (non-configured) rows are computed normally, so they are NOT the sentinel.
        assertThat(breakdown.single { it.orientation == Orientation.FACE }.totalExpectedDamage).isNotEqualTo(sentinel)
    }

    @Test
    fun `sequencing ignores a resistance debuff whose enemy target is unconfirmed`() {
        // IOP Focus carries −50 flat resistance but its enemy target is unconfirmed (missingFields contains
        // "resistanceTarget?"), so it must NOT lower the boss's resistance in the valuation (never invent).
        val character = Character(clazz = CharacterClass.IOP, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200))
        val highRes = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE, targetResistancePercent = 60)
        val seq = SpellRotationOptimizer.bestSequencedRotation(build, character, CharacterClass.IOP, highRes, apBudget = 12)
        assertThat(seq.debuffCasts).describedAs("Focus is target-unconfirmed ⇒ never used as an enemy debuff").isEmpty()
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
    fun `the debuff-aware valuation flips the AP choice an objective-only solve would make`() {
        // The breakpoint the external loop exists for. Two builds against a 55%-res boss:
        //   A = 13 AP, mastery 600        (an odd AP that fits "1-AP debuff + an even damage rotation")
        //   C = 12 AP, mastery 660        (the 13th AP's slot spent on +60 mastery instead)
        // A damage-ONLY objective prefers C (more mastery, more per-hit). But once Sram's Assassination
        // debuff is sequenced, A is better — its extra AP lets the debuff fit without dropping a cast.
        val character = Character(clazz = CharacterClass.SRAM, level = 200, minLevel = 1)
        val scenario = DamageScenario(element = me.chosante.autobuilder.domain.SpellElement.FIRE, targetResistancePercent = 55)

        fun amulet(
            ap: Int,
            mastery: Int,
        ) = me.chosante.common.Equipment(
            1,
            1,
            1,
            I18nText("a", "a", "a", "a"),
            me.chosante.common.Rarity.COMMON,
            me.chosante.common.ItemType.AMULET,
            mapOf(me.chosante.common.Characteristic.ACTION_POINT to ap, me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE to mastery)
        )
        // base AP is 6, so +7 → 13 AP and +6 → 12 AP.
        val a = BuildCombination(listOf(amulet(7, 600)), CharacterSkills(200))
        val c = BuildCombination(listOf(amulet(6, 660)), CharacterSkills(200))

        fun damageOnly(b: BuildCombination) = SpellRotationOptimizer.bestAcrossElements(b, character, CharacterClass.SRAM, scenario).totalExpectedDamage

        fun debuffAware(b: BuildCombination) = SpellRotationOptimizer.bestSequencedRotation(b, character, CharacterClass.SRAM, scenario).totalExpectedDamage

        assertThat(damageOnly(c)).describedAs("objective-only prefers the 12-AP, higher-mastery build").isGreaterThan(damageOnly(a))
        assertThat(debuffAware(a)).describedAs("debuff-aware prefers the 13-AP build — the breakpoint").isGreaterThan(debuffAware(c))
    }

    private fun damageSpell(
        ap: Int,
        base: Int,
        maxCastPerTurn: Int? = null,
        maxCastPerTarget: Int? = null,
        cooldown: Int? = null,
        id: Int = 1,
    ) = Spell(
        id = id,
        clazz = CharacterClass.CRA,
        name = I18nText("s", "s", "s", "s"),
        element = SpellElement.FIRE,
        apCost = ap,
        baseDamage = base,
        maxCastPerTurn = maxCastPerTurn,
        maxCastPerTarget = maxCastPerTarget,
        cooldown = cooldown
    )

    @Test
    fun `maxCastsThisTurn folds cooldown, per-turn and per-target to the tightest single-target cap`() {
        // cooldown wins outright, regardless of the looser per-turn / per-target values.
        assertThat(damageSpell(ap = 1, base = 1, maxCastPerTurn = 4, maxCastPerTarget = 3, cooldown = 2).maxCastsThisTurn).isEqualTo(1)
        // per-target tighter than per-turn (the Sablier shape).
        assertThat(damageSpell(ap = 1, base = 1, maxCastPerTurn = 4, maxCastPerTarget = 1).maxCastsThisTurn).isEqualTo(1)
        // per-turn tighter than per-target.
        assertThat(damageSpell(ap = 1, base = 1, maxCastPerTurn = 1, maxCastPerTarget = 3).maxCastsThisTurn).isEqualTo(1)
        // both `0`/null ("no limit" on either axis) ⇒ unbounded.
        assertThat(damageSpell(ap = 1, base = 1, maxCastPerTurn = 0, maxCastPerTarget = 0).maxCastsThisTurn).isNull()
        // only a per-target limit set ⇒ that value (the axis the old code ignored entirely).
        assertThat(damageSpell(ap = 1, base = 1, maxCastPerTurn = 0, maxCastPerTarget = 2).maxCastsThisTurn).isEqualTo(2)
    }

    @Test
    fun `the single-target rotation caps a spell at its per-target limit, not its looser per-turn total`() {
        // Sablier-shaped: 4 casts per turn TOTAL but only 1 on a single target. Against a lone boss the
        // model must cap it at 1 — the binding limit is per-target, not the looser per-turn total. (Before
        // the per-target fix this returned 3× — bounded only by AP — and over-counted the damage.)
        val sablier = damageSpell(ap = 3, base = 100, maxCastPerTurn = 4, maxCastPerTarget = 1)

        val table = SpellRotationOptimizer.baseThroughputTable(listOf(sablier), maxAp = 9, casterLevel = 200)
        assertThat(table[9]).describedAs("9 AP would fit 3 casts, but per-target caps it at 1").isEqualTo(100L)

        val rotation =
            SpellRotationOptimizer.bestRotation(
                listOf(ScoredSpell(sablier, apCost = 3, expectedDamagePerCast = 100.0)),
                apBudget = 9
            )
        assertThat(rotation.casts.single().count).describedAs("1 legal cast against a lone boss").isEqualTo(1)
        assertThat(rotation.apUsed).describedAs("only 1 cast fits the per-target cap ⇒ 6 AP idle").isEqualTo(3)
        assertThat(rotation.totalExpectedDamage).isEqualTo(100.0)
    }

    @Test
    fun `baseThroughputTable caps a spell at its per-turn limit instead of spamming it`() {
        // 2 AP / 100 base, castable at most twice per turn. With 10 AP the unbounded table would stack
        // 5 casts (500); the cap pins it at 2 casts (200), then the extra AP sits idle.
        val table = SpellRotationOptimizer.baseThroughputTable(listOf(damageSpell(ap = 2, base = 100, maxCastPerTurn = 2)), maxAp = 10, casterLevel = 200)
        assertThat(table[10]).isEqualTo(200L)
        assertThat(table[4]).describedAs("2 casts already fit in 4 AP").isEqualTo(200L)
        assertThat(table[2]).describedAs("1 cast in 2 AP").isEqualTo(100L)
    }

    @Test
    fun `baseThroughputTable caps a cooldown spell at a single cast per turn`() {
        // cooldown > 0 ⇒ at most one cast this turn, even though maxCastPerTurn = 0 ("unlimited").
        val table = SpellRotationOptimizer.baseThroughputTable(listOf(damageSpell(ap = 3, base = 100, maxCastPerTurn = 0, cooldown = 2)), maxAp = 12, casterLevel = 200)
        assertThat(table[12]).isEqualTo(100L)
    }

    @Test
    fun `baseThroughputTable leaves an uncapped spell unbounded`() {
        // maxCastPerTurn = 0 / cooldown absent ⇒ no per-turn limit: the budget fills with casts.
        val table = SpellRotationOptimizer.baseThroughputTable(listOf(damageSpell(ap = 2, base = 100, maxCastPerTurn = 0)), maxAp = 10, casterLevel = 200)
        assertThat(table[10]).describedAs("5 casts, no per-turn limit").isEqualTo(500L)
    }

    @Test
    fun `baseThroughputTable bounds each spell by its own cap when several compete`() {
        // A: 2 AP / 100 base, cap 1; B: 3 AP / 120 base, cap 2. Unbounded at 12 AP would spam A (×6 = 600);
        // capped, the best is A×1 + B×2 = 340 (8 AP, rest idle) — each spell honoured its OWN per-turn cap,
        // not a shared/uniform one. This is the multi-spell aggregation the fused CP-SAT objective relies on.
        val table =
            SpellRotationOptimizer.baseThroughputTable(
                listOf(
                    damageSpell(id = 1, ap = 2, base = 100, maxCastPerTurn = 1),
                    damageSpell(id = 2, ap = 3, base = 120, maxCastPerTurn = 2)
                ),
                maxAp = 12,
                casterLevel = 200
            )
        assertThat(table[12]).describedAs("A×1 + B×2, both caps binding").isEqualTo(340L)
        assertThat(table[6]).describedAs("B×2").isEqualTo(240L)
        assertThat(table[5]).describedAs("one of each").isEqualTo(220L)
    }

    @Test
    fun `bestRotation honours distinct per-spell caps across several spells`() {
        // Same shape via the display path: A capped at 1, B at 2 ⇒ A×1 + B×2 = 340 (8 AP); neither spell
        // spammed past its own cap, and the caps don't bleed across spells (per-spell, not uniform).
        val a = ScoredSpell(damageSpell(id = 1, ap = 2, base = 1, maxCastPerTurn = 1), apCost = 2, expectedDamagePerCast = 100.0)
        val b = ScoredSpell(damageSpell(id = 2, ap = 3, base = 1, maxCastPerTurn = 2), apCost = 3, expectedDamagePerCast = 120.0)
        val rotation = SpellRotationOptimizer.bestRotation(listOf(a, b), apBudget = 12)
        assertThat(rotation.totalExpectedDamage).isEqualTo(340.0)
        assertThat(rotation.apUsed).isEqualTo(8)
        assertThat(rotation.casts.single { it.spell.id == 1 }.count).describedAs("A capped at 1").isEqualTo(1)
        assertThat(rotation.casts.single { it.spell.id == 2 }.count).describedAs("B capped at 2").isEqualTo(2)
    }

    @Test
    fun `bestRotation caps a spell by its own per-turn limit without a uniform override`() {
        // The display path must honour the spell's joined cast limit on its own (no maxCastsPerSpell):
        // castable twice/turn, 2 AP, budget 10 ⇒ 2 casts (200), not the unbounded 5 (500).
        val scored = listOf(ScoredSpell(damageSpell(ap = 2, base = 1, maxCastPerTurn = 2), apCost = 2, expectedDamagePerCast = 100.0))
        val rotation = SpellRotationOptimizer.bestRotation(scored, apBudget = 10)
        assertThat(rotation.casts.single().count).isEqualTo(2)
        assertThat(rotation.apUsed).isEqualTo(4)
        assertThat(rotation.totalExpectedDamage).isEqualTo(200.0)
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
