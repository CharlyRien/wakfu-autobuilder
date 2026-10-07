package me.chosante.ui.settings

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.chosante.autobuilder.genetic.wakfu.ComputeBudget
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.shell.AppShell
import me.chosante.ui.state.BuildSearchModel
import me.chosante.ui.state.ComputeSettings
import me.chosante.ui.state.LibraryPreferences
import me.chosante.ui.state.Modal
import me.chosante.ui.state.ProcessorUse
import me.chosante.ui.state.Screen
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class SettingsScreenUiTest {
    @TempDir lateinit var dir: Path

    private fun model(scope: CoroutineScope) =
        BuildSearchModel(
            scope = scope,
            libraryPreferences = LibraryPreferences(null),
            historyRepository = HistoryRepository(dir, Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            backgroundProofCanceller = {}
        )

    @Test
    fun `gear opens every section in all languages and Back restores the previous screen`() {
        for (lang in Lang.entries) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val model = model(scope)
                model.setLang(lang)
                model.goToScreen(Screen.Library)
                runSkikoComposeUiTest(size = Size(1200f, 1200f)) {
                    setContent { AppShell(model) }
                    onNodeWithTag("settings-gear").performClick()
                    assertThat(model.ui.screen).isEqualTo(Screen.Settings)
                    onNodeWithTag("processor-MAXIMUM").assertIsSelected()
                    onNodeWithTag("processor-CUSTOM").performClick()
                    for (key in listOf(Tr.SETTINGS_COMPUTING, Tr.SETTINGS_INTERFACE, Tr.SETTINGS_ABOUT, Tr.VERIFY_OPTIMALITY, Tr.SETTINGS_HIDE_CHOSEN)) {
                        onNodeWithText(key.value(lang)).assertIsDisplayed()
                    }
                    for (key in listOf(Tr.SETTINGS_CPU_TRADEOFF, Tr.SETTINGS_CPU_EFFECT, Tr.SETTINGS_HIDE_CHOSEN)) {
                        val node = onNodeWithText(key.value(lang), useUnmergedTree = true).fetchSemanticsNode()
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
                        val layout = layouts.single()
                        assertThat(layout.multiParagraph.didExceedMaxLines).describedAs("$lang $key").isFalse()
                        for (line in 0 until layout.lineCount) {
                            assertThat(layout.isLineEllipsized(line)).isFalse()
                            assertThat(layout.getLineRight(line) - layout.getLineLeft(line))
                                .isLessThanOrEqualTo(
                                    layout.layoutInput.constraints.maxWidth
                                        .toFloat() + 1f
                                )
                        }
                    }
                    // Optional visual QA of the actual shell in all four languages.
                    System.getenv("WAKFU_SETTINGS_SCREENSHOTS")?.let { folder ->
                        val output = File(folder).apply { mkdirs() }
                        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "settings-${lang.name.lowercase()}.png"))
                    }
                    onNodeWithTag("settings-back").performClick()
                    assertThat(model.ui.screen).isEqualTo(Screen.Library)
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `preset controls and custom slider update the actual model`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = model(scope)
            model.openSettings()
            runSkikoComposeUiTest(size = Size(1000f, 1000f)) {
                setContent { AppShell(model) }
                for (preset in ProcessorUse.entries) {
                    onNodeWithTag("processor-${preset.name}").performClick()
                    assertThat(model.ui.computeSettings.preset).isEqualTo(preset)
                    onNodeWithTag("processor-${preset.name}").assertIsSelected()
                }
                if (ComputeBudget.availableCores > 1) {
                    onNodeWithTag("settings-core-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
                    assertThat(model.ui.computeSettings.cores()).isEqualTo(1)
                    onNodeWithTag("settings-core-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(ComputeBudget.availableCores.toFloat()) }
                    assertThat(model.ui.computeSettings.cores()).isEqualTo(ComputeBudget.availableCores)
                } else {
                    onNodeWithTag("settings-core-slider").assertIsNotEnabled()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `proof language and picker preferences stay in sync with the shell and pickers`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = model(scope)
            model.openSettings()
            runSkikoComposeUiTest(size = Size(1200f, 1100f)) {
                setContent { AppShell(model) }
                onNodeWithTag(VERIFY_OPTIMALITY_TOGGLE_TAG).performClick()
                assertThat(model.ui.verifyOptimality).isFalse()
                onNodeWithTag("settings-language-FR").performClick()
                assertThat(model.ui.lang).isEqualTo(Lang.FR)
                onNodeWithTag("topbar-language-PT").performClick()
                assertThat(model.ui.lang).isEqualTo(Lang.PT)
                onNodeWithText(Tr.SETTINGS_LANGUAGE.value(Lang.PT)).assertExists()
                onNodeWithTag("settings-hide-chosen").performClick()
                assertThat(model.ui.pickerHideChosen).isTrue()
                onNodeWithTag("settings-back").performClick()
                onNodeWithText(Tr.SETTINGS_PROOF_OFF.value(Lang.PT)).performClick()
                assertThat(model.ui.screen).isEqualTo(Screen.Settings)
                onNodeWithTag(VERIFY_OPTIMALITY_TOGGLE_TAG).performClick()
                assertThat(model.ui.verifyOptimality).isTrue()
                onNodeWithTag("settings-back").performClick()
                runOnIdle { model.openModal(Modal.AddStat) }
                onNodeWithTag("picker-choice-ACTION_POINT").assertDoesNotExist()
                onNodeWithText(Tr.PICKER_HIDE_CHOSEN.value(Lang.PT)).performClick()
                assertThat(model.ui.pickerHideChosen).isFalse()
                onNodeWithTag("picker-choice-ACTION_POINT").assertExists()
                runOnIdle {
                    model.closeModal()
                    model.openSettings()
                }
                onNodeWithTag("settings-hide-chosen").performClick()
                assertThat(model.ui.pickerHideChosen).isTrue()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reset asks for confirmation and keeps the language`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = model(scope)
            model.setLang(Lang.FR)
            model.setProcessorUse(ProcessorUse.LOW)
            model.setVerifyOptimality(false)
            model.setPickerHideChosen(true)
            model.openSettings()
            runSkikoComposeUiTest(size = Size(1200f, 1100f)) {
                setContent { AppShell(model) }
                onNodeWithTag("settings-reset").performScrollTo().performClick()
                onNodeWithText(Tr.SETTINGS_RESET_HINT.value(Lang.FR)).assertIsDisplayed()
                assertThat(model.ui.computeSettings.preset).isEqualTo(ProcessorUse.LOW)
                onNodeWithText(Tr.CANCEL.value(Lang.FR)).performClick()
                assertThat(model.ui.computeSettings.preset).isEqualTo(ProcessorUse.LOW)
                onNodeWithTag("settings-reset").performClick()
                onNodeWithTag("settings-reset-confirm").performClick()
                assertThat(model.ui.computeSettings.preset).isEqualTo(ProcessorUse.MAXIMUM)
                assertThat(model.ui.verifyOptimality).isTrue()
                assertThat(model.ui.pickerHideChosen).isFalse()
                assertThat(model.ui.lang).isEqualTo(Lang.FR)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `custom control is safe and disabled on a single core host`() {
        runSkikoComposeUiTest(size = Size(840f, 1000f)) {
            setContent {
                SettingsScreen(
                    ui = UiState(computeSettings = ComputeSettings(ProcessorUse.CUSTOM, 8)),
                    onBack = {},
                    onProcessorUse = {},
                    onCustomCores = {},
                    onVerifyOptimality = {},
                    onLang = {},
                    onHideChosen = {},
                    onReportBug = {},
                    onReset = {},
                    availableCores = 1
                )
            }
            onNodeWithTag("settings-core-slider").assertIsNotEnabled()
            onNodeWithText("Cores used: 1").assertIsDisplayed()
        }
    }
}
