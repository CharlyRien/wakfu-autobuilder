package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.state.Modal
import me.chosante.ui.state.PickerMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class PickerScaffoldUiTest {
    private val carrier =
        Equipment(
            equipmentId = 1,
            guiId = 0,
            level = 100,
            name = I18nText("Bottes test", "Test boots", "Botas de prueba", "Botas de teste"),
            rarity = Rarity.COMMON,
            itemType = ItemType.BOOTS,
            characteristics = emptyMap(),
            maxShardSlots = 4
        )
    private val rune =
        RuneType(
            id = 1,
            name = I18nText("Rune test", "Test rune", "Runa de prueba", "Runa de teste"),
            color = RuneColor.RED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            doubleBonusPosition = RuneType.slotRawIds(ItemType.BOOTS),
            gfxId = 0
        )

    @Test
    fun `all six catalog shells focus search and typing immediately filters to empty`() {
        val modals =
            listOf(
                Modal.AddStat,
                Modal.ItemPicker(PickerMode.Forced),
                Modal.ItemPicker(PickerMode.Excluded),
                Modal.SublimationPicker(),
                Modal.PassivePicker,
                Modal.BossPicker,
                Modal.ItemRunePicker(carrier.name.fr)
            )
        val emptyLabels =
            listOf(
                Tr.NO_MATCHING_STAT,
                Tr.NO_MATCHING_ITEM,
                Tr.NO_MATCHING_ITEM,
                Tr.NO_MATCHING_SUBLIMATION,
                Tr.NO_MATCHING_PASSIVE,
                Tr.NO_MATCHING_BOSS,
                Tr.NO_MATCHING_RUNE
            )
        modals.zip(emptyLabels).forEach { (modal, emptyLabel) ->
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = modal,
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog = listOf(carrier),
                            runePickerCarrier = carrier,
                            runeOptions = listOf(rune),
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = {}
                        )
                    }
                }
                onNode(hasSetTextAction()).assertIsFocused().performTextInput("zzzz-no-catalog-entry")
                onNodeWithText(emptyLabel.value(Lang.EN)).assertExists()
            }
        }
    }

    @Test
    fun `hide chosen hides both item states including every rarity variant`() {
        for (mode in PickerMode.entries) {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = Modal.ItemPicker(mode),
                            excludedCharacteristics = emptySet(),
                            equipmentCatalog =
                                listOf(
                                    carrier,
                                    carrier.copy(equipmentId = 2, rarity = Rarity.RARE),
                                    carrier.copy(equipmentId = 3, name = I18nText("Autre", "Other", "Otro", "Outro"))
                                ),
                            hideChosen = true,
                            forcedItemNames = setOf(carrier.name.fr),
                            excludedItemNames = setOf("Autre"),
                            onSelectStat = {},
                            onPickItem = {},
                            onDismiss = {}
                        )
                    }
                }
                onNodeWithText("Test boots").assertDoesNotExist()
                onNodeWithText("Other").assertDoesNotExist()
                onNodeWithText(Tr.PICKER_MATCH_COUNT.value(Lang.EN).format(0)).assertExists()
            }
        }
    }

    @Test
    fun `hide chosen removes each additive choice from rows and closes only with Done`() {
        val subs =
            WakfuBestBuildFinderAlgorithm.sublimations
                .distinctBy { it.stateId }
                .distinctBy { it.name.fr }
                .take(2)
        val passives = PassiveCatalog.forClass(CharacterClass.CRA).filter { it.name != null }.take(2)
        val stats = listOf(Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT)
        val items = listOf(carrier, carrier.copy(equipmentId = 2, name = I18nText("Autre", "Other", "Otro", "Outro")))
        listOf(
            Modal.AddStat,
            Modal.ItemPicker(PickerMode.Forced),
            Modal.ItemPicker(PickerMode.Excluded),
            Modal.SublimationPicker(),
            Modal.SublimationPicker(exclude = true),
            Modal.PassivePicker
        ).forEach { opening ->
            runComposeUiTest {
                val modal = mutableStateOf<Modal?>(opening)
                val chosenNames = mutableStateOf(emptySet<String>())
                val chosenStats = mutableStateOf(emptySet<Characteristic>())
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        ModalHost(
                            modal = modal.value,
                            excludedCharacteristics = chosenStats.value,
                            equipmentCatalog = items,
                            hideChosen = true,
                            forcedItemNames = chosenNames.value,
                            excludedItemNames = chosenNames.value,
                            forcedSublimations = chosenNames.value.toList(),
                            excludedSublimations = chosenNames.value.toList(),
                            forcedPassives = chosenNames.value.toList(),
                            onSelectStat = { chosenStats.value += it },
                            onPickItem = { chosenNames.value += it.name.fr },
                            onPickSublimation = { chosenNames.value += it.name.fr },
                            onPickPassive = { chosenNames.value += it.name!!.fr },
                            onDismiss = { modal.value = null }
                        )
                    }
                }
                val labels =
                    when (opening) {
                        Modal.AddStat -> stats.map { it.label(Lang.EN) }
                        is Modal.ItemPicker -> items.map { it.name.localized(Lang.EN) }
                        is Modal.SublimationPicker -> subs.map { it.name.localized(Lang.EN) }
                        else -> passives.map { it.name!!.localized(Lang.EN) }
                    }
                labels.forEach { label ->
                    onNode(hasSetTextAction()).performTextReplacement(label)
                    onNode(hasText(label) and !hasSetTextAction()).performClick()
                    onNodeWithText(Tr.DONE.value(Lang.EN)).assertExists()
                }
                runOnIdle {
                    assertThat(modal.value).isEqualTo(opening)
                    assertThat(chosenNames.value.size + chosenStats.value.size).isEqualTo(2)
                }
                onNodeWithText(Tr.DONE.value(Lang.EN)).performClick().assertDoesNotExist()
                runOnIdle { assertThat(modal.value).isNull() }
            }
        }
    }

    @Test
    fun `rune editor keeps selected runes available for repetition and removal until Save`() =
        runComposeUiTest {
            var saved = emptyList<Int>()
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ModalHost(
                        modal = Modal.ItemRunePicker(carrier.name.fr),
                        excludedCharacteristics = emptySet(),
                        equipmentCatalog = emptyList(),
                        runePickerCarrier = carrier,
                        runeOptions = listOf(rune),
                        initialPinnedRunes = listOf(rune.id),
                        onSelectStat = {},
                        onPickItem = {},
                        onDismiss = {},
                        onConfirmItemRunes = { _, ids -> saved = ids }
                    )
                }
            }
            onNodeWithText("Test rune").assertExists()
            onNodeWithText("×2").assertExists()
            onNodeWithText("＋").performClick()
            onNodeWithText("Test rune").assertExists()
            onNodeWithText("−").performClick()
            onNodeWithText(Tr.SAVE.value(Lang.EN)).performClick()
            runOnIdle { assertThat(saved).containsExactly(rune.id) }
        }
}
