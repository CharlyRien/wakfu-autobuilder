package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the pre-combat crit timing bug (reported 2026-06-27). A build-static start-of-combat
 * condition (Measure III: `CRIT_AT_MOST 50`) is evaluated against the **pre-combat / character-sheet** crit.
 * A PERMANENT sublimation crit (Influence II, +15, `appliesBeforeCombat=true`) IS part of that pre-combat
 * crit, so it can push the build past the threshold; a START-OF-COMBAT / conditional crit (Ambition,
 * Secondary Devastation II — `appliesBeforeCombat=false`) is applied *at* combat start, after the condition
 * is read, so it must NOT count. The scorer ([sublimationFixedContributions]) mirrors the solver's
 * `preCombatStat` exactly, so this is the deterministic test of both.
 */
class SublimationPreCombatConditionTest {
    /** Influence II (6026): permanent +9 crit — shows on the sheet, so it feeds a pre-combat condition. */
    private fun influenceII() =
        Sublimation(
            stateId = 6026,
            name = I18nText("Influence II", "Influence II", "Influencia II", "Influência II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 6,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 9, appliesBeforeCombat = true))
        )

    /** Measure III (8492): "+20% damage if pre-combat crit ≤ 50%". */
    private fun measureIII() =
        Sublimation(
            stateId = 8492,
            name = I18nText("Mesure III", "Measure III", "Medida III", "Medida III"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 3,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, value = 50),
            // appliesBeforeCombat defaults false: a conditional sub's effects apply only at combat start.
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 20))
        )

    /** Ambition I (7115): start-of-combat +25 crit if secondary masteries ≤ 0 (NOT permanent). */
    private fun ambitionI() =
        Sublimation(
            stateId = 7115,
            name = I18nText("Ambition I", "Ambition I", "Ambición I", "Ambição I"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 1,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
            effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 25))
        )

    /** Secondary Devastation II (6013): start-of-combat +7 crit/willpower/block (FLAT but NOT permanent). */
    private fun secondaryDevastationII() =
        Sublimation(
            stateId = 6013,
            name = I18nText("Dévastation secondaire II", "Secondary Devastation II", "Devastación II", "Devastação II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 5,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects =
                listOf(
                    SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 7),
                    SublimationEffect.Flat(Characteristic.WILLPOWER, 7),
                    SublimationEffect.Flat(Characteristic.BLOCK_PERCENTAGE, 7)
                )
        )

    private fun contributions(
        subs: List<Sublimation>,
        baseCrit: Int,
    ) = sublimationFixedContributions(
        sublimations = subs,
        preSub = mapOf(Characteristic.CRITICAL_HIT to baseCrit),
        mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        scenario = null,
        level = 245
    )

    @Test
    fun `Influence II + 47-base crit cannot also carry Measure III (the reported bug)`() {
        // 47 base + Influence's permanent +9 = 56% pre-combat crit > 50, so Measure III must NOT apply.
        val result = contributions(listOf(influenceII(), measureIII()), baseCrit = 47)

        assertThat(result)
            .describedAs("Influence II's permanent +9 crit IS part of the pre-combat crit (47 -> 56)")
            .containsEntry(Characteristic.CRITICAL_HIT, 9)
        assertThat(result)
            .describedAs("56%% pre-combat crit > 50, so Measure III's +20%% damage must not be credited")
            .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
    }

    @Test
    fun `Measure III alone applies at 47-base crit`() {
        // Control: without the permanent crit sub, 47 <= 50 so the condition holds.
        assertThat(contributions(listOf(measureIII()), baseCrit = 47))
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
    }

    @Test
    fun `Influence II does not block Measure III when the base crit is low enough`() {
        // 30 base + 9 = 39 <= 50: the permanent crit is counted but stays within the threshold.
        val result = contributions(listOf(influenceII(), measureIII()), baseCrit = 30)
        assertThat(result).containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result).containsEntry(Characteristic.CRITICAL_HIT, 9)
    }

    @Test
    fun `Ambition's start-of-combat crit does not block Measure III (so it beats Influence here)`() {
        // No secondary masteries, so Ambition applies; its +25 crit is start-of-combat, NOT pre-combat, so
        // the pre-combat crit stays at 47 <= 50 and Measure III still applies. This is why, for a Measure-III
        // build, a start-of-combat crit sub is strictly better than permanent Influence.
        val result = contributions(listOf(ambitionI(), measureIII()), baseCrit = 47)
        assertThat(result)
            .describedAs("Ambition's start-of-combat crit must not feed Measure III's pre-combat condition")
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result)
            .describedAs("Ambition's +25 crit is still credited to the in-combat build")
            .containsEntry(Characteristic.CRITICAL_HIT, 25)
    }

    @Test
    fun `Secondary Devastation II's start-of-combat crit does not block Measure III`() {
        // SecDev II is decoded FLAT but its crit applies at start of combat (appliesBeforeCombat=false), so it
        // must not count toward the pre-combat CRIT_AT_MOST — exactly the "FLAT != permanent" case.
        val result = contributions(listOf(secondaryDevastationII(), measureIII()), baseCrit = 47)
        assertThat(result).containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result).containsEntry(Characteristic.CRITICAL_HIT, 7)
    }

    @Test
    fun `a conditional sub's own crit does not feed its own condition (no circular activation)`() {
        // A CRIT_AT_MOST 50 sub that itself grants +10 (start-of-combat) crit. At 45 base crit it must apply:
        // its own +10 must NOT be added before its condition is read (else 55 > 50 would wrongly block it).
        val selfCrit =
            Sublimation(
                stateId = -1,
                name = I18nText("SelfCrit", "SelfCrit", "SelfCrit", "SelfCrit"),
                rarity = SublimationRarity.NORMAL,
                maxStackLevel = 1,
                kind = SublimationKind.STATIC_CONDITIONAL,
                solverChoosable = true,
                condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, value = 50),
                effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 10))
            )
        assertThat(contributions(listOf(selfCrit), baseCrit = 45))
            .describedAs("the sub's own start-of-combat crit must not feed its own pre-combat condition")
            .containsEntry(Characteristic.CRITICAL_HIT, 10)
    }
}
