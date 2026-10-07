package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Monster
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Modal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class BossPickerSelectionUiTest {
    @Test
    fun `current boss at the end of the roster is checked and visible on open in every language`() {
        for (lang in Lang.entries) {
            val roster = bossRoster(WakfuBestBuildFinderAlgorithm.monsters, lang)
            val current = roster.last()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        ModalHost(
                            modal = Modal.BossPicker,
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog = emptyList(),
                            selectedBoss = current,
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = {}
                        )
                    }
                }
                onNode(hasSetTextAction()).assertIsFocused()
                onNodeWithText(Tr.PICKER_CURRENT_BOSS.value(lang).format(current.displayName(lang))).assertIsDisplayed()
                onNodeWithTag("picker-choice-${current.id}").assertIsSelected().assertIsDisplayed()
                capturePickerForReview("boss-${lang.name}")
                onNodeWithTag("picker-choice-${roster.first().id}").assertDoesNotExist()
                // Searching from this scroll position still exposes an earlier matching boss.
                onNode(hasSetTextAction()).performTextInput(roster.first().displayName(lang))
                onNodeWithTag("picker-choice-${roster.first().id}").assertIsNotSelected().assertIsDisplayed()
                onNodeWithText(Tr.PICKER_CURRENT_BOSS.value(lang).format(current.displayName(lang))).assertIsDisplayed()
                onNode(hasSetTextAction()).performTextReplacement("")
                // Initial positioning is consumed once. Clearing a query never jumps back to the current boss.
                onNodeWithTag("picker-choice-${roster.first().id}").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `namesake bosses stay distinct and a pick replaces the current ID and closes`() {
        for (lang in Lang.entries) {
            val roster = bossRoster(WakfuBestBuildFinderAlgorithm.monsters, lang)
            val namesakes = roster.groupBy { it.name.fr }.values.first { it.size == 3 }
            val current = namesakes.last()
            val replacement = namesakes.first()
            runComposeUiTest {
                val selected = mutableStateOf<Monster?>(current)
                val modal = mutableStateOf<Modal?>(Modal.BossPicker)
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        ModalHost(
                            modal = modal.value,
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog = emptyList(),
                            selectedBoss = selected.value,
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = { modal.value = null },
                            onPickBoss = {
                                selected.value = it
                                modal.value = null
                            }
                        )
                    }
                }
                onNode(hasSetTextAction()).performTextInput(current.name.fr)
                onNodeWithTag("picker-choice-${current.id}").assertIsSelected()
                onNodeWithTag("picker-choice-${replacement.id}").assertIsNotSelected().performClick()
                onNode(hasSetTextAction()).assertDoesNotExist()
                runOnIdle {
                    assertThat(selected.value?.id).isEqualTo(replacement.id)
                    modal.value = Modal.BossPicker
                }
                onNodeWithTag("picker-choice-${replacement.id}").assertIsSelected().assertIsDisplayed()
                onNodeWithText(Tr.PICKER_CURRENT_BOSS.value(lang).format(replacement.displayName(lang))).assertIsDisplayed()
            }
        }
    }
}
