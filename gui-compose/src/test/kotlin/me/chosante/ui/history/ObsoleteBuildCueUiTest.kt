package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.ENGINE_RESULTS_VERSION
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.StaleEngine
import me.chosante.ui.state.UiState
import me.chosante.ui.stats.StatsPanel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The "Obsolete" badge of a saved build a new search may improve — (A) game data updated, (B) engine improved since it was
 * saved. It appears on the My Builds card of such a build (its reasons on hover, exposed as the badge's description) and,
 * spelled out with a "Re-run the search" link, in the stats column once it is loaded. It blocks nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ObsoleteBuildCueUiTest {
    private val current = WakfuBestBuildFinderAlgorithm.dataVersion
    private val older = "1.92.1.58"

    private fun dataReason(lang: Lang) = Tr.OBSOLETE_DATA_REASON.value(lang).format(versionChange(older, current))

    private fun entry(
        id: String,
        name: String,
        dataVersion: String,
        engineVersion: Int? = ENGINE_RESULTS_VERSION,
        optimal: Boolean = false,
    ) = HistoryEntry(
        id = id,
        name = name,
        createdAt = 1_000L,
        dataVersion = dataVersion,
        engineResultsVersion = engineVersion,
        request =
            RequestSnapshot(
                clazz = "CRA",
                level = 110,
                minLevel = 0,
                mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                maxRarity = Rarity.EPIC,
                duration = "20",
                stopAtMatch = false,
                targets = listOf(TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1")),
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        result =
            ResultSnapshot(
                equipments = emptyList(),
                skills = emptyMap(),
                achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1_210),
                match = 1_210.0,
                optimal = optimal
            )
    )

    private fun ComposeUiTest.library(
        lang: Lang,
        vararg entries: HistoryEntry,
        onRerun: (String) -> Unit = {},
    ) = setContent {
        CompositionLocalProvider(LocalLang provides lang) {
            LibraryScreen(
                ui = UiState(lang = lang, savedBuilds = entries.toList()),
                onImport = {},
                onLoad = {},
                onCompare = {},
                onDuplicate = {},
                onEdit = {},
                onDelete = { _, _ -> },
                onToggleTag = {},
                onCreateTag = {},
                onRenameTag = {},
                onDeleteTag = {},
                onFolderFilterChange = {},
                onRenameFolder = {},
                onDeleteFolder = {},
                onGoBuilder = {},
                onSearchChange = {},
                onSortChange = {},
                onClassFilterChange = {},
                onToggleGroup = {},
                onClearFilters = {},
                onRerun = onRerun
            )
        }
    }

    // -- the My Builds card --------------------------------------------------------------------------------------------------

    @Test
    fun `a card saved with other game data is badged with reason A, and a current card is not badged`() =
        runComposeUiTest {
            library(Lang.EN, entry("old", "Saved before the update", older), entry("new", "Saved today", current))

            onNodeWithContentDescription(dataReason(Lang.EN)).assertExists()
            assertThat(onAllNodesWithText(Tr.OBSOLETE_BADGE.en).fetchSemanticsNodes())
                .describedAs("one badge, for the one obsolete card")
                .hasSize(1)
        }

    @Test
    fun `a card saved by an older engine, or before the engine was recorded, is badged with reason B`() =
        runComposeUiTest {
            library(Lang.EN, entry("a", "Older engine", current, engineVersion = ENGINE_RESULTS_VERSION - 1), entry("b", "Unrecorded", current, engineVersion = null))

            onAllNodesWithText(Tr.OBSOLETE_BADGE.en).assertCountEquals(2)
            onAllNodesWithContentDescription(Tr.OBSOLETE_ENGINE_REASON.en).assertCountEquals(2)
        }

    @Test
    fun `when both reasons apply the badge gives both`() =
        runComposeUiTest {
            library(Lang.EN, entry("old", "Both", older, engineVersion = null))

            onNodeWithContentDescription(dataReason(Lang.EN) + "\n\n" + Tr.OBSOLETE_ENGINE_REASON.en).assertExists()
        }

    @Test
    fun `the card badge speaks French in the French app`() =
        runComposeUiTest {
            library(Lang.FR, entry("old", "Enregistré avant la mise à jour", older))

            onNodeWithText("Obsolète").assertExists()
            onNodeWithContentDescription(dataReason(Lang.FR)).assertExists()
            onNodeWithText("Relancer la recherche").assertExists()
        }

    @Test
    fun `the card's re-run link re-runs that build's search`() =
        runComposeUiTest {
            val rerun = mutableListOf<String>()
            library(Lang.EN, entry("old", "Saved before the update", older), onRerun = { rerun += it })

            onNodeWithText(Tr.ACTION_RERUN_SEARCH.en).performClick()

            assertThat(rerun).containsExactly("old")
        }

    @Test
    fun `a card shows the numbers of the current rules once re-scored, and the stored ones in the badge`() =
        runComposeUiTest {
            val stored = entry("old", "Saved before the update", older)
            val rescored = stored.result.copy(achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1_500), match = 1_500.0)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    LibraryScreen(
                        ui =
                            UiState(
                                savedBuilds = listOf(stored),
                                libraryRescores =
                                    mapOf(
                                        "old" to
                                            me.chosante.ui.state
                                                .RescoredResult(stored = stored.result, current = rescored)
                                    )
                            ),
                        onImport = {},
                        onLoad = {},
                        onCompare = {},
                        onDuplicate = {},
                        onEdit = {},
                        onDelete = { _, _ -> },
                        onToggleTag = {},
                        onCreateTag = {},
                        onRenameTag = {},
                        onDeleteTag = {},
                        onFolderFilterChange = {},
                        onRenameFolder = {},
                        onDeleteFolder = {},
                        onGoBuilder = {},
                        onSearchChange = {},
                        onSortChange = {},
                        onClassFilterChange = {},
                        onToggleGroup = {},
                        onClearFilters = {}
                    )
                }
            }

            onNodeWithText("1,500").assertExists()
            assertThat(onAllNodesWithText("1,210").fetchSemanticsNodes()).describedAs("the stored score is no longer the headline").isEmpty()
            onNodeWithContentDescription(Tr.OBSOLETE_STORED_SCORE.en.format("1,210 ${Tr.MASTERY_SHORT.en}"), substring = true).assertExists()
        }

    @Test
    fun `a proof made by an older engine is dimmed with a tooltip saying so, a current proof is not`() =
        runComposeUiTest {
            library(
                Lang.EN,
                entry("old", "Proven long ago", current, engineVersion = null, optimal = true),
                entry("new", "Proven today", current, optimal = true)
            )

            onAllNodesWithText(Tr.OPTIMAL_PROVEN.en).assertCountEquals(2)
            onAllNodesWithContentDescription(Tr.PROVEN_BY_OLDER_ENGINE.en).assertCountEquals(1)
        }

    // -- the stats column of a loaded build --------------------------------------------------------------------------------

    private fun doneUi(
        lang: Lang,
        stale: String?,
        staleEngine: StaleEngine? = null,
    ) = UiState(
        lang = lang,
        phase = Phase.Done,
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
        staleDataVersion = stale,
        staleEngine = staleEngine
    )

    private fun ComposeUiTest.statsColumn(
        ui: UiState,
        onRerun: () -> Unit = {},
    ) = setContent {
        CompositionLocalProvider(LocalLang provides ui.lang) {
            StatsPanel(ui = ui, onOpenZenith = {}, onCopyZenith = {}, onSaveBuild = {}, onExport = {}, onViewAsDamage = {}, onRerunSearch = onRerun)
        }
    }

    @Test
    fun `a loaded obsolete build spells its reasons out in the stats column, in the language of the app`() {
        runComposeUiTest {
            statsColumn(doneUi(Lang.EN, stale = older, staleEngine = StaleEngine(null)))

            onNodeWithText(dataReason(Lang.EN)).assertExists()
            onNodeWithText(Tr.OBSOLETE_ENGINE_REASON.en).assertExists()
        }
        runComposeUiTest {
            statsColumn(doneUi(Lang.FR, stale = older))

            onNodeWithText(dataReason(Lang.FR)).assertExists()
            assertThat(onAllNodesWithText(Tr.OBSOLETE_ENGINE_REASON.fr).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun `the stats column's re-run link starts the search`() =
        runComposeUiTest {
            var reruns = 0
            statsColumn(doneUi(Lang.EN, stale = null, staleEngine = StaleEngine(0)), onRerun = { reruns++ })

            onNodeWithText(Tr.ACTION_RERUN_SEARCH.en).performClick()

            assertThat(reruns).isEqualTo(1)
        }

    @Test
    fun `a build found by this app shows no note`() =
        runComposeUiTest {
            statsColumn(doneUi(Lang.EN, stale = null))

            assertThat(onAllNodesWithText(Tr.ACTION_RERUN_SEARCH.en).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `the note blocks nothing, the build stays fully usable`() =
        runComposeUiTest {
            statsColumn(doneUi(Lang.EN, stale = older))

            onNodeWithText(Tr.SAVE_BUILD.en).assertIsEnabled()
            onNodeWithText(Tr.OPEN_IN_ZENITH.en).assertIsEnabled()
            onNodeWithText(Tr.COPY_BUILD_LINK.en).assertIsEnabled()
            onNodeWithText(Tr.EXPORT_BUILD.en).assertIsEnabled()
        }
}
