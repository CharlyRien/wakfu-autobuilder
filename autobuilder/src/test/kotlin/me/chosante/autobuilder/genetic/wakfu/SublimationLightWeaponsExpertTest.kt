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
 * Covers the two pieces that make "Light Weapons Expert" solver-modelable (it was forced-only + mis-decoded
 * as phantom Lock): the [SublimationEffect.PercentOfLevel] magnitude (`floor(percent · level / 100)`) and the
 * [SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED] "no shield/dagger/2H" gate. Scorer-level so it is
 * deterministic and mirrors the CP-SAT model exactly (`preCombatStat` / `reifyCondition`).
 */
class SublimationLightWeaponsExpertTest {
    /** Light Weapons Expert II: "if no off-hand/2H, +75% of level as elemental mastery" (25%·maxTier 3). */
    private fun expert() =
        Sublimation(
            stateId = 6821,
            name = I18nText("Expert des armes légères II", "Light Weapons Expert II", "Experto II", "Especialista II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 6,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED),
            effects = listOf(SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, 75))
        )

    @Test
    fun `magnitudeAtLevel floors percent of level, and is level-independent for a flat effect`() {
        assertThat(SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, 25).magnitudeAtLevel(200))
            .describedAs("25%% of level 200")
            .isEqualTo(50)
        assertThat(SublimationEffect.PercentOfLevel(Characteristic.LOCK, 50).magnitudeAtLevel(245))
            .describedAs("50%% of level 245, floored")
            .isEqualTo(122)
        assertThat(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 15).magnitudeAtLevel(200)).isEqualTo(15)
    }

    @Test
    fun `a percent-of-level mastery is credited scaled to the character level when the gate holds`() {
        // No off-hand / 2H equipped (usesOffhandOrTwoHanded = false): the condition holds, so 75% of level 200
        // = 150 elemental mastery is credited.
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(expert()),
                preSub = emptyMap(),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                scenario = null,
                level = 200,
                usesOffhandOrTwoHanded = false
            )
        assertThat(contributions).containsEntry(Characteristic.MASTERY_ELEMENTARY, 150)
    }

    @Test
    fun `the light-weapons gate blocks the mastery when an off-hand or two-handed weapon is equipped`() {
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(expert()),
                preSub = emptyMap(),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                scenario = null,
                level = 200,
                usesOffhandOrTwoHanded = true
            )
        assertThat(contributions)
            .describedAs("a shield/dagger/2H build does not satisfy NO_OFFHAND_OR_TWO_HANDED, so no mastery is credited")
            .doesNotContainKey(Characteristic.MASTERY_ELEMENTARY)
    }
}
