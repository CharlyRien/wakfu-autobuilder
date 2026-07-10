package me.chosante.ui.components

import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The sublimation effect text is re-synthesized from structured data with localized [Characteristic]
 * labels (the baked rawText is English-only). These lock the localization and the English fallback.
 */
class SublimationTextTest {
    private val conditionalSub =
        Sublimation(
            stateId = 1,
            name = I18nText("Sub", "Sub", "Sub", "Sub"),
            rarity = SublimationRarity.EPIC,
            kind = SublimationKind.STATIC_CONDITIONAL,
            condition = SublimationCondition(SublimationConditionType.AP_AT_MOST, value = 10),
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 15)),
            rawText = "If AP ≤ 10 | +15% Damage Inflicted"
        )

    @Test
    fun `synthesizes a localized condition and percent effect`() {
        val fr = sublimationEffectText(conditionalSub, Lang.FR)
        val en = sublimationEffectText(conditionalSub, Lang.EN)
        assertThat(fr).contains("Si PA ≤ 10").contains("+15%")
        assertThat(en).contains("If AP ≤ 10").contains("+15%")
        // The stat label and the condition are both translated, so the two languages differ.
        assertThat(fr).isNotEqualTo(en)
    }

    @Test
    fun `falls back to the english rawText when there are no structured effects`() {
        val combatSub =
            conditionalSub.copy(
                kind = SublimationKind.COMBAT_CONDITIONAL,
                condition = null,
                effects = emptyList(),
                rawText = "Some in-combat effect"
            )
        assertThat(sublimationEffectText(combatSub, Lang.FR)).isEqualTo("Some in-combat effect")
    }
}
