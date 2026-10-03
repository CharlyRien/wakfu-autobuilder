package me.chosante.ui.request

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.Characteristic
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.state.UiState
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The request panel's copy fits at every width it can be resized to (260–440 dp), in both languages. It used to be cut:
 * the mode subtitles ("minimum constraints,"), "Précisi/on" broken mid-word at 260 dp in French, and stat names read
 * "Cri…" / "He…" because the 77 dp priority bar left them ~30 dp. "Fits" is checked on the real text layout: nothing is
 * ellipsized, and no line break falls inside a word.
 */
@OptIn(ExperimentalTestApi::class)
class RequestPanelLayoutUiTest {
    // 260–440 is the range the panel can be resized to; 291/292 and 311/312 straddle the two layout switches (mode selector at
    // 220 dp of card width = 292 dp of panel, target rows at 240 dp = 312).
    private val panelWidths = listOf(260, 270, 280, 291, 292, 300, 311, 312, 320, 360, 440)

    private fun ComposeUiTest.panel(
        ui: UiState,
        panelWidth: Int,
        onModeChange: (ScoreComputationMode) -> Unit = {},
        onTargetWeightChange: (String, Int) -> Unit = { _, _ -> },
        onRemoveTarget: (String) -> Unit = {},
    ) = setContent {
        CompositionLocalProvider(LocalLang provides ui.lang) {
            Box(Modifier.width(panelWidth.dp)) {
                RequestPanel(
                    ui = ui,
                    onModeChange = onModeChange,
                    onScenarioChange = {},
                    onTargetValueChange = { _, _ -> },
                    onTargetWeightChange = onTargetWeightChange,
                    onRemoveTarget = onRemoveTarget,
                    onAddTarget = {},
                    onToggleMastery = {},
                    onToggleRarity = {},
                    onDurationChange = {},
                    onStopAtMatchChange = {},
                    onAddForcedItem = {},
                    onRemoveForcedItem = {},
                    onAddExcludedItem = {},
                    onRemoveExcludedItem = {}
                )
            }
        }
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val read = node.config[SemanticsActions.GetTextLayoutResult].action
        check(read != null && read(results) && results.isNotEmpty()) { "not a text node" }
        return results.first()
    }

    /** Every [texts] entry is on screen, none of its occurrences is ellipsized, and no line of it breaks inside a word. */
    private fun ComposeUiTest.assertReadable(
        texts: Collection<String>,
        context: String,
    ) {
        for (text in texts) {
            val nodes = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes()
            assertThat(nodes).describedAs("$context: '$text' is on screen").isNotEmpty()
            for (node in nodes) {
                val layout = layoutOf(node)
                // Cut off = lines dropped by maxLines, a line ended with an ellipsis, or a line wider than the room it had.
                // (TextLayoutResult.hasVisualOverflow is not used: it reads true for short texts with room to spare.)
                assertThat(layout.multiParagraph.didExceedMaxLines).describedAs("$context: '$text' loses lines").isFalse()
                val room = layout.layoutInput.constraints.maxWidth
                for (line in 0 until layout.lineCount) {
                    assertThat(layout.isLineEllipsized(line)).describedAs("$context: '$text' ends in an ellipsis (line ${line + 1})").isFalse()
                    assertThat(layout.getLineRight(line) - layout.getLineLeft(line))
                        .describedAs("$context: line ${line + 1} of '$text' is wider than its ${room}px of room")
                        .isLessThanOrEqualTo(room + 0.5f)
                }
                val shown = layout.layoutInput.text.text
                for (line in 0 until layout.lineCount - 1) {
                    val end = layout.getLineEnd(line)
                    assertThat(shown[end - 1].isWhitespace() || shown[end].isWhitespace())
                        .describedAs("$context: '$text' breaks inside a word at line ${line + 1} (…${shown.substring(0, end)}|${shown.substring(end)})")
                        .isTrue()
                }
            }
        }
    }

    private fun modeTitles(lang: Lang) = listOf(Tr.MODE_MASTERIES, Tr.MODE_PRECISION, Tr.MODE_MAX_DAMAGE).map { it.value(lang) }

    private fun rowLabels(ui: UiState) =
        ui.targets
            .filterNot { ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT && it.characteristic.isMaximizableMastery() }
            .map { it.characteristic.label(ui.lang) }

    @Test
    fun `the mode titles, their description and the stat names fit at every width, in both languages and both target modes`() {
        for (lang in Lang.entries) {
            for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)) {
                for (width in panelWidths) {
                    runComposeUiTest {
                        val ui = UiState(lang = lang, mode = mode)
                        panel(ui, width)
                        val context = "$lang / ${mode.marketingName} / ${width}dp"

                        assertReadable(modeTitles(lang), context)
                        assertReadable(listOf(Tr.KIND_EXACT.value(lang)), context)
                        assertReadable(rowLabels(ui), context)
                    }
                }
            }
        }
    }

    @Test
    fun `long stat names wrap onto two lines instead of being cut`() {
        // The longest names a target row can carry ("Résistance Élémentaire" is 152 dp wide, "Maîtrise Élémentaire" nearly as much).
        val longNames =
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY,
                Characteristic.RESISTANCE_CRITICAL,
                Characteristic.RESISTANCE_BACK,
                Characteristic.MASTERY_ELEMENTARY,
                Characteristic.PROSPECTION
            )
        for (lang in Lang.entries) {
            for (width in panelWidths) {
                runComposeUiTest {
                    val rows = longNames.mapNotNull { statDefFor(it)?.toRow("0") }
                    assertThat(rows).hasSameSizeAs(longNames)
                    val ui = UiState(lang = lang, mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, targets = rows)
                    panel(ui, width)

                    assertReadable(rows.map { it.characteristic.label(lang) }, "$lang / ${width}dp")
                }
            }
        }
    }

    @Test
    fun `the selector shows the selected mode's description under the options, capitalized`() {
        runComposeUiTest {
            panel(UiState(lang = Lang.EN), 320)
            onNodeWithText("Minimum constraints, max masteries").assertExists()
        }
        runComposeUiTest {
            panel(UiState(lang = Lang.FR, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE), 320)
            onNodeWithText("Maximise les dégâts attendus").assertExists()
        }
    }

    @Test
    fun `every mode can be picked, side by side and stacked`() {
        for (width in listOf(260, 440)) {
            runComposeUiTest {
                val picked = mutableListOf<ScoreComputationMode>()
                panel(UiState(lang = Lang.EN), width, onModeChange = { picked += it })

                onNodeWithText(Tr.MODE_PRECISION.en).performClick()
                onNodeWithText(Tr.MODE_MAX_DAMAGE.en).performClick()
                onNodeWithText(Tr.MODE_MASTERIES.en).performClick()

                assertThat(picked)
                    .describedAs("${width}dp")
                    .containsExactly(
                        ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                        ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                    )
            }
        }
    }

    @Test
    fun `a target row's priority bar and remove button work in both of its layouts`() {
        for (width in listOf(260, 440)) {
            runComposeUiTest {
                val weights = mutableListOf<Pair<String, Int>>()
                val removed = mutableListOf<String>()
                panel(UiState(lang = Lang.EN), width, onTargetWeightChange = { id, weight -> weights += id to weight }, onRemoveTarget = { removed += it })

                // The panel scrolls and the first target row sits far below the mode and rarity cards: bring each control into view.
                onNodeWithTag(priorityMeterTestTag(Characteristic.ACTION_POINT.name)).performScrollTo().performTouchInput { click(Offset(right - 1f, centerY)) }
                onAllNodesWithText("×")[0].performScrollTo().performClick()

                assertThat(weights).describedAs("${width}dp").containsExactly(Characteristic.ACTION_POINT.name to 5)
                assertThat(removed).describedAs("${width}dp").containsExactly(Characteristic.ACTION_POINT.name)
            }
        }
    }
}
