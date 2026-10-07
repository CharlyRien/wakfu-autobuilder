package me.chosante.ui.i18n

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.chosante.ui.shell.TopBar
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class LanguageTopBarUiTest {
    @Test
    fun `language toggle and search remain readable on wrapped and single row bars`() {
        for (lang in Lang.entries) {
            for (width in listOf(1200, 1600)) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) {
                            Box(Modifier.width(width.dp)) {
                                TopBar(
                                    ui = UiState(lang = lang),
                                    onSearch = {},
                                    onCancel = {},
                                    onClassChange = {},
                                    onLevelChange = {},
                                    onMinLevelChange = {},
                                    onLangChange = {},
                                    onNavigate = {},
                                    onNewBuild = {},
                                    onDetachActiveBuild = {}
                                )
                            }
                        }
                    }
                    for (text in Lang.entries.map { it.label } + Tr.SEARCH.value(lang)) {
                        val node = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
                        val layout = layouts.single()
                        assertThat(layout.multiParagraph.didExceedMaxLines).describedAs("$lang $width $text").isFalse()
                        for (line in 0 until layout.lineCount) {
                            assertThat(layout.isLineEllipsized(line)).isFalse()
                            assertThat(layout.getLineRight(line) - layout.getLineLeft(line)).isLessThanOrEqualTo(
                                layout.layoutInput.constraints.maxWidth
                                    .toFloat() + 1f
                            )
                        }
                        assertThat(node.boundsInRoot.width).isPositive()
                    }
                }
            }
        }
    }
}
