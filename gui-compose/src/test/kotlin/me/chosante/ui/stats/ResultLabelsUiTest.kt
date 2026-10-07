package me.chosante.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.SpellCast
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Passive
import me.chosante.common.Spell
import me.chosante.common.SpellElement
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.components.localized
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ResultLabelsUiTest {
    @Test
    fun `the rotation and passive result display localized costs resistance and characteristic names`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                val name = I18nText(en = "Debuff", fr = "Malus", es = "Malus ES", pt = "Malus PT")
                val spell = Spell(1, CharacterClass.CRA, name, element = SpellElement.FIRE, apCost = 2, targetResistanceReductionFlat = 50)
                val cast = SpellCast(spell, 1, 2, 1234.0)
                val passive = Passive(1, clazz = "CRA", name = name, flatBuildStats = mapOf("BLOCK_PERCENTAGE" to 10.0))
                val ui =
                    me.chosante.ui.state.UiState(
                        lang = lang,
                        build = BuildCombination(emptyList(), CharacterSkills(110), passives = listOf(passive)),
                        spellRotation = SpellRotation(SpellElement.FIRE, 12, 10, listOf(cast), 1234.0, listOf(cast), 60)
                    )
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        Column {
                            SpellRotationCard(ui)
                            PassivesResult(ui)
                        }
                    }
                }
                onNodeWithText(Tr.SPELL_DEBUFF_CAST.value(lang).format(name.localized(lang), 2, 50)).assertExists()
                onNodeWithText(Tr.SPELL_DEBUFF_RESISTANCE.value(lang).format(60)).assertExists()
                onNodeWithText(Tr.SPELL_ROTATION_TOTAL.value(lang).format("1,234", 10, 12)).assertExists()
                onNodeWithText("+10 ${Characteristic.BLOCK_PERCENTAGE.label(lang)}").assertExists()
                assertThat(onAllNodesWithText("BLOCK_PERCENTAGE", substring = true).fetchSemanticsNodes()).isEmpty()
            }
        }
    }
}
