package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Headless widget tests for the multi-build compare screen: renders the real [CompareScreen] with stub
 * saved builds and drives real clicks (Add, ✕) — verifying the per-spell damage table, the same-class
 * guard, and the N-column add/remove wiring that the PNG screenshot smoke-test can't reach.
 *
 * The stub builds carry no equipment, so both columns compute the same base-only spell damage; the numeric
 * difference between real builds is covered elsewhere. These tests assert the table renders and the
 * controls fire.
 */
@OptIn(ExperimentalTestApi::class)
class CompareScreenUiTest {
    private fun entry(
        id: String,
        name: String,
        clazz: String,
    ): HistoryEntry =
        HistoryEntry(
            id = id,
            name = name,
            createdAt = 0L,
            dataVersion = "test",
            request =
                RequestSnapshot(
                    clazz = clazz,
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
            result =
                ResultSnapshot(
                    equipments = emptyList(),
                    skills = emptyMap(),
                    achieved = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500),
                    match = 100.0,
                    optimal = true
                )
        )

    @Test
    fun `same-class builds render the spell-damage table and Add fires`() =
        runComposeUiTest {
            var added = false
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        ui =
                            UiState(
                                savedBuilds = listOf(entry("a", "Build A", "CRA"), entry("b", "Build B", "CRA")),
                                compareSlots = listOf("a", "b")
                            ),
                        onPick = { _, _ -> },
                        onClear = { },
                        onAdd = { added = true },
                        onBack = { }
                    )
                }
            }
            onNodeWithText("Spell damage").assertExists()
            onNodeWithText("Mastery score (engine)").assertExists()
            onNodeWithText("Add build").performClick()
            assertThat(added).isTrue()
        }

    @Test
    fun `mixed classes replace the per-spell rows with a note`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        ui =
                            UiState(
                                savedBuilds = listOf(entry("a", "Cra", "CRA"), entry("b", "Eni", "ENUTROF")),
                                compareSlots = listOf("a", "b")
                            ),
                        onPick = { _, _ -> },
                        onClear = { },
                        onAdd = { },
                        onBack = { }
                    )
                }
            }
            assertThat(onAllNodesWithText("different classes", substring = true).fetchSemanticsNodes()).isNotEmpty()
        }

    @Test
    fun `clicking a column clear fires onClear with that column index`() =
        runComposeUiTest {
            var cleared = -1
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        // Three columns → the ✕ shows on each; clicking the first reports index 0.
                        ui =
                            UiState(
                                savedBuilds = listOf(entry("a", "Build A", "CRA"), entry("b", "Build B", "CRA")),
                                compareSlots = listOf("a", "b", "a")
                            ),
                        onPick = { _, _ -> },
                        onClear = { cleared = it },
                        onAdd = { },
                        onBack = { }
                    )
                }
            }
            onAllNodesWithText("✕").onFirst().performClick()
            assertThat(cleared).isEqualTo(0)
        }
}
