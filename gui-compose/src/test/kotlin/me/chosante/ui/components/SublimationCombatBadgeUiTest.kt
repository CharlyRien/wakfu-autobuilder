package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.SublimationKind
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.localized
import me.chosante.ui.request.SublimationsRunesCard
import me.chosante.ui.state.Modal
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class SublimationCombatBadgeUiTest {
    private val combat = WakfuBestBuildFinderAlgorithm.sublimations.first { it.name.fr == "Puissance brute III" }
    private val flat = WakfuBestBuildFinderAlgorithm.sublimations.first { it.kind == SublimationKind.FLAT }

    @Test
    fun `picker shows the combat note and explanation in every language but not on a flat sub`() {
        for (lang in Lang.entries) {
            for (sub in listOf(combat, flat)) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) {
                            ModalHost(
                                modal = Modal.SublimationPicker(),
                                excludedCharacteristics = emptySet(),
                                equipmentCatalog = emptyList(),
                                onSelectStat = {},
                                onPickItem = {},
                                onPickSublimation = {},
                                onDismiss = {}
                            )
                        }
                    }
                    onNode(hasSetTextAction()).performTextInput(sub.name.localized(lang))
                    onNodeWithTag("picker-choice-${sub.stateId}").assertExists()
                    if (sub == combat) {
                        onNodeWithText(Tr.SUBLIMATION_COMBAT_NOT_COUNTED.value(lang)).assertExists()
                        onNodeWithContentDescription(Tr.SUBLIMATION_COMBAT_NOT_COUNTED_INFO.value(lang)).assertExists()
                    } else {
                        onNodeWithText(Tr.SUBLIMATION_COMBAT_NOT_COUNTED.value(lang)).assertDoesNotExist()
                    }
                }
            }
        }
    }

    @Test
    fun `forced chip shows the combat note even with automatic selection off and flat chip has none`() {
        for (lang in Lang.entries) {
            for (sub in listOf(combat, flat)) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) {
                            SublimationsRunesCard(
                                useSublimations = false,
                                maxSublimationTier = null,
                                forcedSublimations = listOf(sub.name.fr),
                                excludedSublimations = emptyList(),
                                onToggleSublimations = {},
                                onMaxSublimationTierChange = {},
                                onOpenSublimationPicker = {},
                                onRemoveForcedSublimation = {},
                                onOpenExcludedSublimationPicker = {},
                                onRemoveExcludedSublimation = {},
                                onToggleExcludeAllSublimationsOfRarity = {}
                            )
                        }
                    }
                    onNodeWithText(sub.name.fr).assertExists()
                    if (sub == combat) {
                        onNodeWithText(Tr.SUBLIMATION_COMBAT_NOT_COUNTED.value(lang)).assertExists()
                        onNodeWithContentDescription(Tr.SUBLIMATION_COMBAT_NOT_COUNTED_INFO.value(lang)).assertExists()
                    } else {
                        onNodeWithText(Tr.SUBLIMATION_COMBAT_NOT_COUNTED.value(lang)).assertDoesNotExist()
                    }
                }
            }
        }
    }
}
