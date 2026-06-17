package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class TagInputUiTest {
    @Test
    fun `typing filters known tags and clicking a suggestion assigns it`() =
        runComposeUiTest {
            val selected = mutableStateListOf<String>()
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    TagInput(
                        selected = selected.toList(),
                        known = listOf("PvP", "Solo", "Speedrun"),
                        onAdd = { tag -> if (selected.none { it.equals(tag, ignoreCase = true) }) selected.add(tag) },
                        onRemove = { tag -> selected.removeAll { it.equals(tag, ignoreCase = true) } }
                    )
                }
            }

            // Typing filters the known tags: "pv" surfaces "PvP" but not "Solo".
            onNode(hasSetTextAction()).performTextInput("pv")
            onNodeWithText("PvP").assertExists()
            onNodeWithText("Solo").assertDoesNotExist()

            // Clicking the suggestion assigns the tag.
            onNodeWithText("PvP").performClick()
            assertThat(selected).containsExactly("PvP")
        }

    @Test
    fun `a brand-new name can be created from the input`() =
        runComposeUiTest {
            val selected = mutableStateListOf<String>()
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    TagInput(
                        selected = selected.toList(),
                        known = listOf("PvP"),
                        onAdd = { tag -> if (selected.none { it.equals(tag, ignoreCase = true) }) selected.add(tag) },
                        onRemove = { tag -> selected.removeAll { it.equals(tag, ignoreCase = true) } }
                    )
                }
            }

            onNode(hasSetTextAction()).performTextInput("Healer")
            // No known tag matches → the create row is offered, then assigns the new tag.
            onNodeWithText("Create \"Healer\"").performClick()
            assertThat(selected).containsExactly("Healer")
        }

    @Test
    fun `removing a chip unassigns the tag`() =
        runComposeUiTest {
            val selected = mutableStateListOf("PvP", "Solo")
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    TagInput(
                        selected = selected.toList(),
                        known = listOf("PvP", "Solo"),
                        onAdd = { tag -> if (selected.none { it.equals(tag, ignoreCase = true) }) selected.add(tag) },
                        onRemove = { tag -> selected.removeAll { it.equals(tag, ignoreCase = true) } }
                    )
                }
            }

            // Each chip renders its label + a ✕; clicking the ✕ next to "Solo" removes it. (The two
            // chips share the ✕ glyph, so target by index: chips are laid out in selection order.)
            onAllNodesWithText("✕").get(1).performClick()
            assertThat(selected).containsExactly("PvP")
        }
}
