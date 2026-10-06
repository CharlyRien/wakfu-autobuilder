package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.state.Modal
import me.chosante.ui.state.PickerMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ItemPickerUiTest {
    // Fixed names sort identically in both languages, with more rows than either old cap.
    private val catalog =
        (1..150).map { id ->
            Equipment(
                equipmentId = id,
                guiId = 0,
                level = 100,
                name = I18nText("Objet %03d".format(id), "Item %03d".format(id), "", ""),
                rarity = Rarity.COMMON,
                itemType = if (id % 2 == 0) ItemType.BOOTS else ItemType.RING,
                characteristics = emptyMap()
            )
        }

    private fun ComposeUiTest.picker(
        lang: Lang,
        mode: PickerMode,
        selectedNames: Set<String> = emptySet(),
        onPick: (Equipment) -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(LocalLang provides lang) {
                ModalHost(
                    modal = Modal.ItemPicker(mode),
                    excludedCharacteristics = emptySet(),
                    equipmentCatalog = catalog,
                    forcedItemNames = if (mode == PickerMode.Forced) selectedNames else emptySet(),
                    excludedItemNames = if (mode == PickerMode.Excluded) selectedNames else emptySet(),
                    level = 110,
                    onSelectStat = {},
                    onPickItem = onPick,
                    onDismiss = {}
                )
            }
        }
    }

    @Test
    fun `real catalog conditions show in the forced and excluded picker in both languages`() {
        val items = WakfuBestBuildFinderAlgorithm.equipments
        for (lang in Lang.entries) {
            for (mode in listOf(PickerMode.Forced, PickerMode.Excluded)) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) {
                            ModalHost(
                                modal = Modal.ItemPicker(mode),
                                excludedCharacteristics = emptySet(),
                                equipmentCatalog = items,
                                level = 245,
                                onSelectStat = {},
                                onPickItem = {},
                                onDismiss = {}
                            )
                        }
                    }
                    onNode(hasSetTextAction()).performTextInput("Brakmar Sword")
                    onNodeWithText(if (lang == Lang.EN) "Needs Brakmar Ring" else "Nécessite Anneau de Brâkmar").assertExists()
                    onNode(hasSetTextAction()).performTextReplacement("Hairpin")
                    onAllNodesWithText(if (lang == Lang.EN) "Range ≤ 3 (not checked by the search yet)" else "Portée ≤ 3 (pas encore vérifié par la recherche)")[0].assertExists()
                }
            }
        }
    }

    @Test
    fun `the count is localized and the last item is reachable without typing in both flows`() {
        for (lang in Lang.entries) {
            for (mode in listOf(PickerMode.Forced, PickerMode.Excluded)) {
                runComposeUiTest {
                    var picked: Equipment? = null
                    picker(lang, mode, onPick = { picked = it })
                    onNodeWithText(Tr.PICKER_MATCH_COUNT.value(lang).format(150)).assertExists()
                    val last = if (lang == Lang.FR) catalog.last().name.fr else catalog.last().name.en
                    // Lazy rows do not compose the entire catalog on opening.
                    onNodeWithText(last).assertDoesNotExist()
                    onNode(hasScrollToNodeAction()).performScrollToIndex(149)
                    onNodeWithText(last).assertExists().performClick()
                    runOnIdle { assertThat(picked).isEqualTo(catalog.last()) }
                }
            }
        }
    }

    @Test
    fun `slot chips combine with bilingual name search and all slots restores the full list`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                picker(lang, PickerMode.Forced)
                onNodeWithText(ItemType.BOOTS.label(lang)).performScrollTo().performClick()
                onNodeWithText(Tr.PICKER_MATCH_COUNT.value(lang).format(75)).assertExists()
                val search = onNode(hasSetTextAction())
                search.performTextInput("  oBjEt 001  ")
                onNodeWithText(Tr.PICKER_MATCH_COUNT.value(lang).format(0)).assertExists()
                onNodeWithText(Tr.NO_MATCHING_ITEM.value(lang)).assertExists()
                search.performTextReplacement("  iTeM 150  ")
                onNodeWithText(Tr.PICKER_MATCH_COUNT.value(lang).format(1)).assertExists()
                onAllNodesWithText(if (lang == Lang.FR) "Objet 150" else "Item 150")[0].assertExists()
                search.performTextReplacement("")
                onNodeWithText(Tr.ALL_SLOTS.value(lang)).performScrollTo().performClick()
                onNodeWithText(Tr.PICKER_MATCH_COUNT.value(lang).format(150)).assertExists()
            }
        }
    }

    @Test
    fun `a text search lists matches beyond the old hundred and twenty cap and skips selected items`() =
        runComposeUiTest {
            picker(Lang.EN, PickerMode.Excluded, selectedNames = setOf(catalog.first().name.fr))
            onNode(hasSetTextAction()).performTextInput("Item")
            onNodeWithText(Tr.PICKER_MATCH_COUNT.value(Lang.EN).format(149)).assertExists()
            onNodeWithText("Item 001").assertDoesNotExist()
            onNode(hasScrollToNodeAction()).performScrollToIndex(148)
            onNodeWithText("Item 150").assertExists()
        }
}
