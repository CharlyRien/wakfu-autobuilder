package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Sublimation
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.request.SublimationsRunesCard
import me.chosante.ui.state.Modal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SublimationLevelSelectorUiTest {
    private val neutrality = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 6931 }

    @Test
    fun `picker defaults to max and passes the chosen level with the family`() =
        runComposeUiTest {
            var picked: Sublimation? = null
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.FR) {
                    ModalHost(
                        modal = Modal.SublimationPicker(),
                        excludedCharacteristics = emptySet(),
                        equipmentCatalog = emptyList(),
                        onSelectStat = {},
                        onPickItem = {},
                        onPickSublimation = { picked = it },
                        onDismiss = {}
                    )
                }
            }
            onNode(hasSetTextAction()).performTextInput(neutrality.name.fr)
            onNodeWithTag("picker-level-6931").assertTextEquals("niveau 4/4 ▾").performClick()
            onNodeWithTag("picker-level-6931-4").assertExists()
            onNodeWithTag("picker-level-6931-2").performClick()
            onNodeWithTag("picker-choice-6931").performClick()
            assertThat(picked?.stackLevel).isEqualTo(2)
        }

    @Test
    fun `forced chip defaults to max and changes the selected level`() =
        runComposeUiTest {
            val levels = mutableStateOf<Map<String, Int>>(emptyMap())
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    SublimationsRunesCard(
                        useSublimations = true,
                        maxSublimationTier = null,
                        forcedSublimations = listOf(neutrality.name.fr),
                        forcedSublimationLevels = levels.value,
                        excludedSublimations = emptyList(),
                        onToggleSublimations = {},
                        onMaxSublimationTierChange = {},
                        onOpenSublimationPicker = {},
                        onRemoveForcedSublimation = {},
                        onOpenExcludedSublimationPicker = {},
                        onRemoveExcludedSublimation = {},
                        onToggleExcludeAllSublimationsOfRarity = {},
                        onForcedSublimationLevelChange = { name, level -> levels.value = levels.value + (name to level) }
                    )
                }
            }
            onNodeWithTag("chip-level-6931").assertTextEquals("level 4/4 ▾").performClick()
            onNodeWithTag("chip-level-6931-4").assertExists()
            onNodeWithTag("chip-level-6931-2").performClick()
            assertThat(levels.value).containsEntry(neutrality.name.fr, 2)
        }

    @Test
    fun `tier II only family offers 2 4 6 and no unreachable levels`() =
        runComposeUiTest {
            val family = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Ravage secondaire II" }
            setContent { SublimationLevelSelector(family, null, null, {}, "test") }
            onNodeWithTag("test-level-${family.stateId}").performClick()
            for (level in listOf(2, 4, 6)) onNodeWithTag("test-level-${family.stateId}-$level").assertExists()
            for (level in listOf(1, 3, 5)) onNodeWithTag("test-level-${family.stateId}-$level").assertDoesNotExist()
        }
}
