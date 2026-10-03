package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.history.BossSnapshot
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
        mode: String = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
        match: Double = 100.0,
        boss: BossSnapshot? = null,
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
                    mode = mode,
                    maxRarity = Rarity.EPIC,
                    duration = "20",
                    stopAtMatch = false,
                    targets = emptyList(),
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scenario = DamageScenarioSnapshot(rangeBand = rangeBand),
                    boss = boss
                ),
            result =
                ResultSnapshot(
                    equipments = emptyList(),
                    skills = emptyMap(),
                    achieved = achieved,
                    match = match,
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

    private val boss =
        Monster(
            id = 4242,
            name = I18nText("Magik Riktus Dominant", "Dominant Magik Riktus", "Magik Riktus Dominante", "Magik Riktus Dominante"),
            level = 105,
            hp = 12_345,
            fireResistance = 10,
            waterResistance = 20,
            earthResistance = 30,
            airResistance = 40
        )

    @Test
    fun `a max-damage column shows its expected damage and boss, with its own table row instead of a zero mastery score`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        ui =
                            UiState(
                                savedBuilds =
                                    listOf(
                                        entry("m", "Masteries", "CRA", achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1_210)),
                                        entry(
                                            "d",
                                            "Boss damage",
                                            "CRA",
                                            mode = "FIND_BUILD_WITH_MAX_DAMAGE",
                                            match = 10_528.8028,
                                            boss = BossSnapshot(boss)
                                        )
                                    ),
                                compareSlots = listOf("m", "d")
                            ),
                        onPick = { _, _ -> },
                        onClear = { },
                        onAdd = { },
                        onBack = { }
                    )
                }
            }
            // The column header: the damage and the boss, no "10528% Match".
            onNodeWithText("10,528 Expected damage · Optimal proven").assertExists()
            onNodeWithText("vs Dominant Magik Riktus").assertExists()
            assertThat(onAllNodesWithText("Match", substring = true).fetchSemanticsNodes()).isEmpty()
            // The table: the mastery row stays for the mastery build, the damage row is new; each has a dash under the other kind.
            onNodeWithText("Mastery score (engine)").assertExists()
            onNodeWithText("Expected damage (engine)").assertExists()
            assertThat(onAllNodesWithText("—").fetchSemanticsNodes()).hasSizeGreaterThanOrEqualTo(2)
            // The mode shows in each column's meta line.
            assertThat(onAllNodesWithText("Max Damage", substring = true).fetchSemanticsNodes()).isNotEmpty()
            assertThat(onAllNodesWithText("Most Masteries", substring = true).fetchSemanticsNodes()).isNotEmpty()
        }

    @Test
    fun `only damage builds compared means no mastery row`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    CompareScreen(
                        ui =
                            UiState(
                                savedBuilds =
                                    listOf(
                                        entry("a", "Damage A", "CRA", mode = "FIND_BUILD_WITH_MAX_DAMAGE", match = 9_000.0),
                                        entry("b", "Damage B", "CRA", mode = "FIND_BUILD_WITH_MAX_DAMAGE", match = 12_000.0)
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
            onNodeWithText("Expected damage (engine)").assertExists()
            onNodeWithText("Mastery score (engine)").assertDoesNotExist()
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
