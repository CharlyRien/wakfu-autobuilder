package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the "secondary masteries ≤ 0" leak: the re-scorer used to sum only MELEE + DISTANCE, so a
 * rear/crit-stacking damage build spuriously satisfied the condition and pocketed Neutrality / Ambition for
 * free. The condition must be evaluated against the SUM of all six secondary masteries
 * ([SECONDARY_MASTERY_CHARACTERISTICS]).
 */
class SublimationConditionTest {
    private fun neutralityLike() =
        Sublimation(
            stateId = 6931,
            name = I18nText("Neutralité I", "Neutrality I", "Neutralidad I", "Neutralidade I"),
            rarity = SublimationRarity.NORMAL,
            maxLevel = 4,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
            effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 24))
        )

    @Test
    fun `secondary-mastery condition is violated by rear mastery alone (the reported CRA leak)`() {
        // The real CRA build had MASTERY_BACK=407 and MASTERY_CRITICAL=203 — neither is melee/distance,
        // so the old melee+distance-only sum saw 0 and wrongly applied the bonus.
        val preSub = mapOf(Characteristic.MASTERY_BACK to 407, Characteristic.MASTERY_CRITICAL to 203)

        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(neutralityLike()),
                preSub = preSub,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions).doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
    }

    @Test
    fun `each secondary mastery on its own breaks the condition`() {
        SECONDARY_MASTERY_CHARACTERISTICS.forEach { mastery ->
            val contributions =
                sublimationFixedContributions(
                    sublimations = listOf(neutralityLike()),
                    preSub = mapOf(mastery to 50),
                    mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    scenario = null,
                    level = 110
                )
            assertThat(contributions)
                .withFailMessage("sub should NOT apply with %s=50 (secondary mastery > 0)", mastery)
                .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
        }
    }

    @Test
    fun `condition holds only when every secondary mastery is at most zero`() {
        // No secondary masteries (a pure-elemental build) — the niche case the sub is actually meant for.
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(neutralityLike()),
                preSub = mapOf(Characteristic.MASTERY_ELEMENTARY to 300),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions[Characteristic.DAMAGE_INFLICTED]).isEqualTo(24)
    }
}
