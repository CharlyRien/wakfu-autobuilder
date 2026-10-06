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
import me.chosante.common.I18nText
import me.chosante.common.Passive
import me.chosante.common.Spell
import me.chosante.common.SpellElement
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ResultLabelsUiTest {
    @Test
    fun `the rotation and passive result display localized costs resistance and characteristic names`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                val name = I18nText(en = "Debuff", fr = "Malus", es = "", pt = "")
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
                onNodeWithText(if (lang == Lang.EN) "↳ Debuff (2 AP, −50 res)" else "↳ Malus (2 PA, −50 rés.)").assertExists()
                onNodeWithText(if (lang == Lang.EN) "→ 60% res after debuffs" else "→ 60 % rés. après les malus").assertExists()
                onNodeWithText(if (lang == Lang.EN) "1,234  (10/12 AP)" else "1,234  (10/12 PA)").assertExists()
                onNodeWithText(if (lang == Lang.EN) "+10 Block %" else "+10 Parade %").assertExists()
                assertThat(onAllNodesWithText("BLOCK_PERCENTAGE", substring = true).fetchSemanticsNodes()).isEmpty()
            }
        }
    }
}
