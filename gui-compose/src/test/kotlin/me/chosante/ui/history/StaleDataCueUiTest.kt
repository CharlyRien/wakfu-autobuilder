package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
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
import me.chosante.ui.state.UiState
import me.chosante.ui.stats.StatsPanel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The note on a build computed with other game data: "Saved with game data X — re-run the search to update". It appears on the
 * My Builds card of such a build and in the stats column once it is loaded, in the language of the app, and it blocks nothing.
 */
@OptIn(ExperimentalTestApi::class)
class StaleDataCueUiTest {
    private val current = WakfuBestBuildFinderAlgorithm.dataVersion
    private val older = "1.92.1.58"

    private fun note(
        lang: Lang,
        version: String,
    ) = Tr.SAVED_WITH_OTHER_DATA.value(lang).format(version)

    private fun entry(
        id: String,
        name: String,
        dataVersion: String,
    ) = HistoryEntry(
        id = id,
        name = name,
        createdAt = 1_000L,
        dataVersion = dataVersion,
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
                optimal = false
            )
    )

    private fun ComposeUiTest.library(
        lang: Lang,
        vararg entries: HistoryEntry,
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
                onClearFilters = {}
            )
        }
    }

    // -- the My Builds card --------------------------------------------------------------------------------------------------

    @Test
    fun `a card saved with other game data says so, and a card saved with the current data does not`() =
        runComposeUiTest {
            library(Lang.EN, entry("old", "Saved before the update", older), entry("new", "Saved today", current))

            onNodeWithText(note(Lang.EN, older)).assertExists()
            assertThat(onAllNodesWithText("Saved with game data", substring = true).fetchSemanticsNodes())
                .describedAs("one note, for the one stale card")
                .hasSize(1)
        }

    @Test
    fun `the card note speaks French in the French app`() =
        runComposeUiTest {
            library(Lang.FR, entry("old", "Enregistré avant la mise à jour", older))

            onNodeWithText("Enregistré avec les données de jeu $older — relance la recherche pour mettre à jour").assertExists()
        }

    @Test
    fun `every card of a library saved before the update carries the note, with its own version`() =
        runComposeUiTest {
            library(Lang.EN, entry("a", "First", "1.91.1.54"), entry("b", "Second", older), entry("c", "Third", current))

            onNodeWithText(note(Lang.EN, "1.91.1.54")).assertExists()
            onNodeWithText(note(Lang.EN, older)).assertExists()
            assertThat(onAllNodesWithText("Saved with game data", substring = true).fetchSemanticsNodes()).hasSize(2)
        }

    // -- the stats column of a loaded build --------------------------------------------------------------------------------

    private fun doneUi(
        lang: Lang,
        stale: String?,
    ) = UiState(
        lang = lang,
        phase = Phase.Done,
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
        staleDataVersion = stale
    )

    private fun ComposeUiTest.statsColumn(ui: UiState) =
        setContent {
            CompositionLocalProvider(LocalLang provides ui.lang) {
                StatsPanel(ui = ui, onOpenZenith = {}, onCopyZenith = {}, onSaveBuild = {}, onExport = {}, onViewAsDamage = {})
            }
        }

    @Test
    fun `a loaded stale build shows the note in the stats column, in the language of the app`() {
        runComposeUiTest {
            statsColumn(doneUi(Lang.EN, stale = older))

            onNodeWithText(note(Lang.EN, older)).assertExists()
        }
        runComposeUiTest {
            statsColumn(doneUi(Lang.FR, stale = older))

            onNodeWithText(note(Lang.FR, older)).assertExists()
        }
    }

    @Test
    fun `a build found by this app shows no note`() =
        runComposeUiTest {
            statsColumn(doneUi(Lang.EN, stale = null))

            assertThat(onAllNodesWithText("Saved with game data", substring = true).fetchSemanticsNodes()).isEmpty()
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
