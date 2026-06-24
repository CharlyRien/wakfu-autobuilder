package me.chosante.ui.spells

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Headless widget tests for the "Class spells" tab: renders the real [ClassSpellsPanel] composable (the
 * same code the result-region tab shows) and asserts each class damage spell surfaces a hit number — the
 * interactive path the PNG screenshot smoke-test can't exercise.
 */
@OptIn(ExperimentalTestApi::class)
class ClassSpellsPanelUiTest {
    @Test
    fun `shows a per-spell expected hit for the discovered build`() =
        runComposeUiTest {
            val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
            val build = BuildCombination(equipments = emptyList(), characterSkills = character.characterSkills)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(
                        ui =
                            UiState(
                                clazz = CharacterClass.CRA,
                                level = 110,
                                build = build,
                                achieved = mapOf(Characteristic.CRITICAL_HIT to 20)
                            )
                    )
                }
            }
            onNodeWithText("Class spells").assertExists()
            // Every damage-spell card carries the "expected hit" label, so at least one must render.
            assertThat(onAllNodesWithText("expected hit").fetchSemanticsNodes()).isNotEmpty()
            // The "& passives" tab promises passives: the class's passive pool renders too.
            onNodeWithText("Passives").assertExists()
        }

    @Test
    fun `falls back to base hits when no build is loaded yet`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(ui = UiState(clazz = CharacterClass.CRA, level = 110))
                }
            }
            assertThat(onAllNodesWithText("base hit").fetchSemanticsNodes()).isNotEmpty()
        }
}
