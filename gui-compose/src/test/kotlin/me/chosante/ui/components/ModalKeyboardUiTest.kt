package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Modal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The keyboard in the modals: the cursor starts in the field the player means to type in (it used to land on the LAST text
 * field composed — the Save dialog's note, the Edit dialog's tags), Esc closes any modal, and the form dialogs submit with
 * Enter (name field) or Ctrl/Cmd+Enter (anywhere, so also from the note).
 */
@OptIn(ExperimentalTestApi::class)
class ModalKeyboardUiTest {
    private class Spy {
        val saves = mutableListOf<Triple<String, String?, Boolean>>()
        val edits = mutableListOf<List<Any?>>()
        val renames = mutableListOf<String>()
        var dismissals = 0
    }

    private fun entry(
        id: String = "saved-1",
        name: String = "Cra 110 · Distance",
        note: String? = "daily build",
    ) = HistoryEntry(
        id = id,
        name = name,
        createdAt = 1_000L,
        note = note,
        dataVersion = "test",
        request =
            RequestSnapshot(
                clazz = "CRA",
                level = 110,
                minLevel = 0,
                mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                maxRarity = Rarity.EPIC,
                duration = "20",
                stopAtMatch = false,
                targets = emptyList(),
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        result = ResultSnapshot(equipments = emptyList(), skills = emptyMap(), achieved = emptyMap(), match = 0.0, optimal = false)
    )

    private fun ComposeUiTest.open(
        modal: Modal,
        spy: Spy,
        suggestedSaveName: String = "Cra 110 · Distance",
        isEditingExisting: Boolean = false,
        takenNames: Set<String> = emptySet(),
        editingEntry: HistoryEntry? = null,
        lang: Lang = Lang.EN,
    ) {
        setContent {
            CompositionLocalProvider(LocalLang provides lang) {
                ModalHost(
                    modal = modal,
                    excludedCharacteristics = emptySet<Characteristic>(),
                    equipmentCatalog = null,
                    onSelectStat = {},
                    onPickItem = {},
                    onDismiss = { spy.dismissals++ },
                    suggestedSaveName = suggestedSaveName,
                    isEditingExisting = isEditingExisting,
                    takenNames = takenNames,
                    editingEntry = editingEntry,
                    onSaveBuild = { name, note, asNew -> spy.saves += Triple(name, note, asNew) },
                    onEditBuild = { id, name, note, tags, folder -> spy.edits += listOf(id, name, note, tags, folder) },
                    onRenameFolder = { _, newName -> spy.renames += newName }
                )
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.field(index: Int): SemanticsNodeInteraction = onAllNodes(hasSetTextAction())[index]

    private fun SemanticsNodeInteraction.press(key: Key) = performKeyInput { pressKey(key) }

    private fun SemanticsNodeInteraction.pressWith(
        modifier: Key,
        key: Key,
    ) = performKeyInput { withKeyDown(modifier) { pressKey(key) } }

    // -- the cursor starts where the player types --------------------------------------------------------------------------

    @Test
    fun `the Save dialog opens with the cursor in the name field, not the note`() =
        runComposeUiTest {
            open(Modal.SaveBuild, Spy())

            field(0).assertIsFocused()
            field(1).assertIsNotFocused()
        }

    @Test
    fun `the Edit dialog opens on the name, not on its last field (the tags)`() =
        runComposeUiTest {
            open(Modal.EditBuild("saved-1"), Spy(), editingEntry = entry())

            // name, note, tag input (three fields, in that order)
            assertThat(onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(3)
            field(0).assertIsFocused()
            field(1).assertIsNotFocused()
            field(2).assertIsNotFocused()
        }

    @Test
    fun `every picker still opens with its search field focused`() {
        val pickers =
            listOf(
                Modal.AddStat,
                Modal.BossPicker,
                Modal.SublimationPicker(),
                Modal.PassivePicker,
                Modal.RenameFolder("Raids"),
                Modal.CreateTag
            )
        pickers.forEach { modal ->
            runComposeUiTest {
                open(modal, Spy())
                field(0).assertIsFocused()
            }
        }
    }

    // -- Esc ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `Esc closes the Save dialog`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)

            field(0).press(Key.Escape)

            assertThat(spy.dismissals).isEqualTo(1)
            assertThat(spy.saves).isEmpty()
        }

    @Test
    fun `Esc closes the Save dialog from the note field too`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)

            field(1).requestFocus()
            field(1).press(Key.Escape)

            assertThat(spy.dismissals).isEqualTo(1)
        }

    @Test
    fun `Esc closes a dialog that has no text field`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.ConfirmReSearch, spy)
            assertThat(onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()

            onRoot().performKeyInput { pressKey(Key.Escape) }

            assertThat(spy.dismissals).isEqualTo(1)
        }

    @Test
    fun `Esc closes a picker`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.BossPicker, spy)

            field(0).press(Key.Escape)

            assertThat(spy.dismissals).isEqualTo(1)
        }

    @Test
    fun `other keys do not close a dialog`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.ConfirmReSearch, spy)

            onRoot().performKeyInput {
                pressKey(Key.Enter)
                pressKey(Key.Spacebar)
                pressKey(Key.Tab)
            }

            assertThat(spy.dismissals).isZero()
        }

    // -- Enter / Ctrl+Enter / Cmd+Enter ------------------------------------------------------------------------------------

    @Test
    fun `Enter in the name field saves the suggested name`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)

            field(0).press(Key.Enter)

            assertThat(spy.saves).containsExactly(Triple("Cra 110 · Distance", null, false))
        }

    @Test
    fun `the suggested name starts selected, so typing replaces it instead of landing in front of it`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)

            val selection = field(0).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            assertThat(selection).isEqualTo(TextRange(0, "Cra 110 · Distance".length))

            field(0).performTextInput("Pvp daily")
            field(0).press(Key.NumPadEnter) // the numpad Enter submits too

            assertThat(spy.saves).containsExactly(Triple("Pvp daily", null, false))
        }

    @Test
    fun `Enter does nothing while Save is disabled (blank name, or a name another build uses)`() {
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy, suggestedSaveName = "")

            field(0).press(Key.Enter)

            assertThat(spy.saves).isEmpty()
            assertThat(spy.dismissals).isZero()
        }
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy, suggestedSaveName = "Cra 110 · Distance", takenNames = setOf("cra 110 · distance"))
            onNodeWithText("Another build already uses this name").assertExists()

            field(0).press(Key.Enter)

            assertThat(spy.saves).isEmpty()
        }
    }

    @Test
    fun `plain Enter in the note field does not save, Ctrl+Enter and Cmd+Enter do, with the note`() {
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)
            field(1).requestFocus()
            field(1).performTextInput("mid-game")

            field(1).press(Key.Enter)
            assertThat(spy.saves).describedAs("a plain Enter in the note is not a submit").isEmpty()

            field(1).pressWith(Key.CtrlLeft, Key.Enter)
            assertThat(spy.saves).containsExactly(Triple("Cra 110 · Distance", "mid-game", false))
        }
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy)
            field(1).requestFocus()
            field(1).performTextInput("mid-game")

            field(1).pressWith(Key.MetaLeft, Key.Enter)

            assertThat(spy.saves).containsExactly(Triple("Cra 110 · Distance", "mid-game", false))
        }
    }

    @Test
    fun `Ctrl+Enter does nothing while Save is disabled`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy, suggestedSaveName = "Cra 110 · Distance", takenNames = setOf("cra 110 · distance"))
            field(1).requestFocus()

            field(1).pressWith(Key.CtrlLeft, Key.Enter)

            assertThat(spy.saves).isEmpty()
        }

    @Test
    fun `Enter on a loaded build updates it instead of saving a copy`() =
        runComposeUiTest {
            val spy = Spy()
            open(Modal.SaveBuild, spy, suggestedSaveName = "Loaded build", isEditingExisting = true)

            field(0).press(Key.Enter)

            assertThat(spy.saves).containsExactly(Triple("Loaded build", null, false))
        }

    @Test
    fun `the Edit dialog saves on Enter in the name and on Ctrl+Enter from the note`() {
        runComposeUiTest {
            val spy = Spy()
            open(Modal.EditBuild("saved-1"), spy, editingEntry = entry())

            field(0).performTextInput("Cra 110 · Pvp")
            field(0).press(Key.Enter)

            assertThat(spy.edits).containsExactly(listOf("saved-1", "Cra 110 · Pvp", "daily build", emptyList<String>(), null))
        }
        runComposeUiTest {
            val spy = Spy()
            open(Modal.EditBuild("saved-1"), spy, editingEntry = entry())
            field(1).requestFocus()

            field(1).press(Key.Enter)
            assertThat(spy.edits).describedAs("a plain Enter in the note is not a submit").isEmpty()
            field(1).pressWith(Key.CtrlLeft, Key.Enter)

            assertThat(spy.edits).hasSize(1)
        }
    }

    @Test
    fun `the Edit dialog does not submit a name another build uses, but its own name is fine`() {
        runComposeUiTest {
            val spy = Spy()
            open(Modal.EditBuild("saved-1"), spy, takenNames = setOf("other build"), editingEntry = entry())

            field(0).performTextInput("Other Build")
            field(0).press(Key.Enter)

            assertThat(spy.edits).isEmpty()
        }
        runComposeUiTest {
            val spy = Spy()
            open(Modal.EditBuild("saved-1"), spy, takenNames = setOf("other build"), editingEntry = entry())

            field(0).press(Key.Enter)

            assertThat(spy.edits).describedAs("the build's own name is not a collision").hasSize(1)
        }
    }

    @Test
    fun `the rename dialog renames on Enter, and not a blank name`() {
        runComposeUiTest {
            val spy = Spy()
            open(Modal.RenameFolder("Raids"), spy)

            field(0).performTextInput("Raids 2")
            field(0).press(Key.Enter)

            assertThat(spy.renames).containsExactly("Raids 2")
        }
        runComposeUiTest {
            val spy = Spy()
            open(Modal.RenameFolder(""), spy)

            field(0).press(Key.Enter)

            assertThat(spy.renames).isEmpty()
        }
    }

    @Test
    fun `Save as new prefills a free name and rejects every existing name in both languages`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                val spy = Spy()
                open(Modal.SaveBuild, spy, suggestedSaveName = "Daily", isEditingExisting = true, takenNames = setOf("daily (2)", "other"), lang = lang)
                onNodeWithText(Tr.SAVE_AS_NEW.value(lang)).performClick()
                onNodeWithText("Daily (3)").assertExists()
                assertThat(spy.saves).isEmpty()
                for (taken in listOf(" DAILY ", "daily (2)", "other")) {
                    field(0).performTextReplacement(taken)
                    onNodeWithText(Tr.SAVE_NAME_TAKEN.value(lang)).assertExists()
                    onNodeWithText(Tr.SAVE.value(lang), useUnmergedTree = true).assertHasNoClickAction()
                    field(0).press(Key.Enter)
                    assertThat(spy.saves).isEmpty()
                }
                field(0).performTextReplacement("Daily (3)")
                field(0).press(Key.Enter)
                assertThat(spy.saves).containsExactly(Triple("Daily (3)", null, true))
            }
        }
    }
}
