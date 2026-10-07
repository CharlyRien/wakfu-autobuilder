package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.state.LibraryPreferences
import me.chosante.ui.state.Modal
import me.chosante.ui.state.PickerMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class PickerSelectionUiTest {
    private val item = Equipment(1, 0, 100, I18nText("Bottes test", "Test boots", "Botas de prueba", "Botas de teste"), Rarity.COMMON, ItemType.BOOTS, emptyMap())

    @Test
    fun `all additive pickers retain checked choices and row or chip toggles remove them`() {
        val stat = Characteristic.ACTION_POINT
        val sub = WakfuBestBuildFinderAlgorithm.sublimations.first()
        val passive = PassiveCatalog.forClass(CharacterClass.CRA).first { it.name != null }
        for (opening in listOf(
            Modal.AddStat,
            Modal.ItemPicker(PickerMode.Forced),
            Modal.ItemPicker(PickerMode.Excluded),
            Modal.SublimationPicker(),
            Modal.SublimationPicker(true),
            Modal.PassivePicker
        )) {
            runComposeUiTest {
                val chosen = mutableStateOf(false)
                val name =
                    when (opening) {
                        Modal.AddStat -> stat.label(Lang.EN)
                        is Modal.ItemPicker -> item.name.localized(Lang.EN)
                        is Modal.SublimationPicker -> sub.name.localized(Lang.EN)
                        else -> passive.name!!.localized(Lang.EN)
                    }
                val canonical =
                    when (opening) {
                        Modal.AddStat -> stat.toString()
                        is Modal.ItemPicker -> item.name.fr
                        is Modal.SublimationPicker -> sub.name.fr
                        else -> passive.name!!.fr
                    }
                val rowId =
                    when (opening) {
                        Modal.AddStat -> stat.toString()
                        is Modal.ItemPicker -> item.equipmentId.toString()
                        is Modal.SublimationPicker -> sub.stateId.toString()
                        else -> passive.spellId.toString()
                    }
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        val names = if (chosen.value) setOf(canonical) else emptySet()
                        ModalHost(
                            modal = opening,
                            excludedCharacteristics = emptySet(),
                            selectedCharacteristics = if (chosen.value) setOf(stat) else emptySet(),
                            equipmentCatalog = listOf(item),
                            forcedItemNames = if ((opening as? Modal.ItemPicker)?.mode == PickerMode.Forced) names else emptySet(),
                            excludedItemNames = if ((opening as? Modal.ItemPicker)?.mode == PickerMode.Excluded) names else emptySet(),
                            forcedSublimations = names.toList(),
                            excludedSublimations = names.toList(),
                            forcedPassives = names.toList(),
                            onSelectStat = { chosen.value = true },
                            onPickItem = { chosen.value = true },
                            onPickSublimation = { chosen.value = true },
                            onPickPassive = { chosen.value = true },
                            onRemoveStat = { chosen.value = false },
                            onRemoveForcedItem = { chosen.value = false },
                            onRemoveExcludedItem = { chosen.value = false },
                            onRemoveForcedSublimation = { chosen.value = false },
                            onRemoveExcludedSublimation = { chosen.value = false },
                            onRemovePassive = { chosen.value = false },
                            onDismiss = {}
                        )
                    }
                }
                onNode(hasSetTextAction()).performTextInput(name)
                val row = onNodeWithTag("picker-choice-$rowId")
                row.assertIsNotSelected().performClick().assertIsSelected()
                onNodeWithText(Tr.PICKER_CHOSEN_COUNT.value(Lang.EN).format(1)).assertExists()
                row.performClick().assertIsNotSelected()
                onNodeWithText(Tr.PICKER_CHOSEN_COUNT.value(Lang.EN).format(1)).assertDoesNotExist()
                row.performClick()
                onNodeWithTag("picker-remove-$canonical").performClick()
                row.assertIsNotSelected()
                runOnIdle { assertThat(chosen.value).isFalse() }
            }
        }
    }

    @Test
    fun `item variants share badges and switching forced or excluded moves instead of removing`() {
        for (mode in PickerMode.entries) {
            runComposeUiTest {
                val forced = mutableStateOf(mode == PickerMode.Excluded)
                val excluded = mutableStateOf(mode == PickerMode.Forced)
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = Modal.ItemPicker(mode),
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog = listOf(item, item.copy(equipmentId = 2, rarity = Rarity.RARE)),
                            forcedItemNames = if (forced.value) setOf(item.name.fr) else emptySet(),
                            excludedItemNames = if (excluded.value) setOf(item.name.fr) else emptySet(),
                            onSelectStat = {},
                            onPickItem = {
                                forced.value = mode == PickerMode.Forced
                                excluded.value = mode == PickerMode.Excluded
                            },
                            onRemoveForcedItem = { forced.value = false },
                            onRemoveExcludedItem = { excluded.value = false },
                            onDismiss = {}
                        )
                    }
                }
                val first = onNodeWithTag("picker-choice-1")
                val second = onNodeWithTag("picker-choice-2")
                first.assertIsSelected()
                second.assertIsSelected()
                val badge = if (mode == PickerMode.Forced) Tr.PICKER_FORCED else Tr.PICKER_EXCLUDED
                first.performClick()
                capturePickerForReview("items-${mode.name}")
                onNodeWithTag("picker-remove-${item.name.fr}").assertExists()
                runOnIdle {
                    assertThat(forced.value).isEqualTo(mode == PickerMode.Forced)
                    assertThat(excluded.value).isEqualTo(mode == PickerMode.Excluded)
                }
                // Each rarity variant carries the same state badge in its merged row semantics.
                first.assertIsSelected()
                second.assertIsSelected()
                assertThat(first.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text }).contains(badge.value(Lang.EN))
                second.performClick()
                first.assertIsNotSelected()
                second.assertIsNotSelected()
            }
        }
    }

    @Test
    fun `hide chosen stays shared across catalogs and a fresh launch while chips stay removable`() {
        val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/PickerSelectionUiTest")
        try {
            node.clear()
            runComposeUiTest {
                val prefs = LibraryPreferences(node)
                val hidden = mutableStateOf(prefs.loadHideChosen())
                val modal = mutableStateOf<Modal>(Modal.ItemPicker(PickerMode.Forced))
                val chosen = mutableStateOf(true)
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = modal.value,
                            excludedCharacteristics = emptySet(),
                            selectedCharacteristics = setOf(Characteristic.ACTION_POINT),
                            equipmentCatalog = listOf(item),
                            forcedItemNames = if (chosen.value) setOf(item.name.fr) else emptySet(),
                            hideChosen = hidden.value,
                            onHideChosenChange = {
                                hidden.value = it
                                prefs.saveHideChosen(it)
                            },
                            onRemoveForcedItem = { chosen.value = false },
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = {}
                        )
                    }
                }
                onNodeWithTag("picker-choice-1").assertIsSelected()
                onNodeWithText(Tr.PICKER_HIDE_CHOSEN.value(Lang.EN)).performClick()
                onNodeWithTag("picker-choice-1").assertDoesNotExist()
                onNodeWithTag("picker-remove-${item.name.fr}").performClick()
                runOnIdle {
                    assertThat(chosen.value).isFalse()
                    modal.value = Modal.AddStat
                }
                onNodeWithTag("picker-choice-ACTION_POINT").assertDoesNotExist()
                onNodeWithTag("picker-remove-ACTION_POINT").assertExists()
            }
            node.flush()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = Modal.ItemPicker(PickerMode.Forced),
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog = listOf(item),
                            forcedItemNames = setOf(item.name.fr),
                            hideChosen = LibraryPreferences(node).loadHideChosen(),
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = {}
                        )
                    }
                }
                onNodeWithTag("picker-choice-1").assertDoesNotExist()
                onNodeWithTag("picker-remove-${item.name.fr}").assertExists()
            }
        } finally {
            runCatching { node.removeNode() }
        }
    }

    @Test
    fun `full passive loadout disables unchosen rows but keeps selected rows removable`() =
        runComposeUiTest {
            val all = PassiveCatalog.forClass(CharacterClass.CRA).filter { it.name != null }
            val cap = PassiveCatalog.slotsForLevel(245)
            val selected = mutableStateOf(all.take(cap).map { it.name!!.fr })
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ModalHost(
                        modal = Modal.PassivePicker,
                        excludedCharacteristics = emptySet(),
                        equipmentCatalog = emptyList(),
                        level = 245,
                        forcedPassives = selected.value,
                        onRemovePassive = { selected.value -= it },
                        onSelectStat = {},
                        onPickItem = {},
                        onPickPassive = { selected.value += it.name!!.fr },
                        onDismiss = {}
                    )
                }
            }
            onNodeWithText("$cap / $cap").assertExists()
            capturePickerForReview("passives-full")
            onNode(hasSetTextAction()).performTextInput(all[cap].name!!.localized(Lang.EN))
            onNodeWithTag("picker-choice-${all[cap].spellId}").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("picker-remove-${all.first().name!!.fr}").performScrollTo().performClick()
            onNodeWithTag("picker-choice-${all[cap].spellId}").performScrollTo().assertIsEnabled()
            onNodeWithText("${cap - 1} / $cap").assertExists()
        }
}

/** Optional local review captures; normal test runs write no files. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.capturePickerForReview(name: String) {
    val directory = System.getenv("WAKFU_PICKER_CAPTURE_DIR") ?: return
    val target = File(directory, "$name.png")
    target.parentFile.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
}
