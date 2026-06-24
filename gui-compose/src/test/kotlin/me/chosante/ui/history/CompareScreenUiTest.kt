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
import me.chosante.common.history.DamageScenarioSnapshot
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
        achieved: Map<Characteristic, Int> = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500),
        rangeBand: String = "DISTANCE",
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
                    excludedItems = emptyList(),
                    scenario = DamageScenarioSnapshot(rangeBand = rangeBand)
                ),
            result =
                ResultSnapshot(
                    equipments = emptyList(),
                    skills = emptyMap(),
                    achieved = achieved,
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
    fun `damage group surfaces the damage-inflicted difference between builds`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        // One build carries a −20% Damage Inflicted (the most-masteries bait); the other 0. The
                        // grouped Damage section must surface the row so the softer-hitting build is explainable.
                        ui =
                            UiState(
                                savedBuilds =
                                    listOf(
                                        entry("a", "Soft", "CRA", achieved = mapOf(Characteristic.DAMAGE_INFLICTED to -20, Characteristic.MASTERY_DISTANCE to 300)),
                                        entry("b", "Hard", "CRA", achieved = mapOf(Characteristic.MASTERY_DISTANCE to 300))
                                    ),
                                compareSlots = listOf("a", "b")
                            ),
                        onPick = { _, _ -> },
                        onClear = { },
                        onAdd = { },
                        onBack = { }
                    )
                }
            }
            onNodeWithText("Damage").assertExists()
            onNodeWithText("Damage Inflicted").assertExists()
            onNodeWithText("Distance Mastery").assertExists()
        }

    @Test
    fun `each column shows its own range band in the spell-damage header`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        ui =
                            UiState(
                                savedBuilds =
                                    listOf(
                                        entry("a", "Ranged", "CRA", rangeBand = "DISTANCE"),
                                        entry("b", "Melee", "CRA", rangeBand = "MELEE")
                                    ),
                                compareSlots = listOf("a", "b")
                            ),
                        onPick = { _, _ -> },
                        onClear = { },
                        onAdd = { },
                        onBack = { }
                    )
                }
            }
            // Each column credits its own band, so BOTH labels must render (column A = Distance, B = Melee).
            // The builds carry no distance/melee MASTERY (achieved is fire only), so these strings can only
            // come from the per-column band labels — proving the bands are per-column, not a single default.
            assertThat(onAllNodesWithText("Distance", substring = true).fetchSemanticsNodes()).isNotEmpty()
            assertThat(onAllNodesWithText("Melee", substring = true).fetchSemanticsNodes()).isNotEmpty()
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
