package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.ENGINE_RESULTS_VERSION
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
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
 * A loaded or saved build wearing an item the game would show inactive — a stat gate it breaks, saved before the search
 * enforced them — says so: spelled out in the stats column, an "Inactive item" pill on its My Builds card (the lines on hover).
 */
@OptIn(ExperimentalTestApi::class)
class StatGateWarningUiTest {
    private val catalog = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }

    // Cartes And (+1 range, active only at range ≤ 3) and three other +1 range items: 4 range out of combat.
    private val breaking: List<Equipment> = listOf(27378, 21916, 21924, 23983).map(catalog::getValue)
    private val legal: List<Equipment> = breaking.dropLast(1)

    private val warningEn = "Art'And Cards would be inactive in game: Range ≤ 3, this build has 4"
    private val warningFr = "Cartes And serait inactif en jeu : Portée ≤ 3, ce build a 4"

    private fun ComposeUiTest.statsColumn(
        lang: Lang,
        items: List<Equipment>,
    ) = setContent {
        CompositionLocalProvider(LocalLang provides lang) {
            StatsPanel(
                ui =
                    UiState(
                        lang = lang,
                        phase = Phase.Done,
                        clazz = CharacterClass.CRA,
                        build = BuildCombination(equipments = items, characterSkills = CharacterSkills(200))
                    ),
                onOpenZenith = {},
                onCopyZenith = {},
                onSaveBuild = {},
                onExport = {},
                onViewAsDamage = {}
            )
        }
    }

    @Test
    fun `a loaded build that breaks a stat gate names the item, the gate and the build's value, in both languages`() {
        runComposeUiTest {
            statsColumn(Lang.EN, breaking)
            onNodeWithText(warningEn).assertExists()
            onNodeWithText(Tr.STAT_GATE_CUE_HINT.en).assertExists()
        }
        runComposeUiTest {
            statsColumn(Lang.FR, breaking)
            onNodeWithText(warningFr).assertExists()
        }
    }

    @Test
    fun `a build that keeps every gate shows no warning`() =
        runComposeUiTest {
            statsColumn(Lang.EN, legal)
            assertThat(onAllNodesWithText(Tr.STAT_GATE_CUE_HINT.en).fetchSemanticsNodes()).isEmpty()
        }

    private fun entry(
        id: String,
        items: List<Equipment>,
    ) = HistoryEntry(
        id = id,
        name = "Saved $id",
        createdAt = 1_000L,
        dataVersion = WakfuBestBuildFinderAlgorithm.dataVersion,
        engineResultsVersion = ENGINE_RESULTS_VERSION,
        request =
            RequestSnapshot(
                clazz = "CRA",
                level = 200,
                minLevel = 0,
                mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                maxRarity = Rarity.EPIC,
                duration = "20",
                stopAtMatch = false,
                targets = listOf(TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1")),
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        // A save carries no item condition (Equipment.equipCriterion is never saved): the card reads the catalog's.
        result = ResultSnapshot(equipments = items.map { it.copy(equipCriterion = null) }, skills = emptyMap(), achieved = emptyMap(), match = 1_000.0, optimal = false)
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

    @Test
    fun `the My Builds card of a save that breaks a gate carries the Inactive item pill, the lines on hover`() {
        runComposeUiTest {
            library(Lang.EN, entry("bad", breaking), entry("good", legal))
            assertThat(onAllNodesWithText(Tr.STAT_GATE_BADGE.en).fetchSemanticsNodes()).describedAs("one pill, for the one breaking save").hasSize(1)
            onNodeWithContentDescription(warningEn).assertExists()
        }
        runComposeUiTest {
            library(Lang.FR, entry("bad", breaking))
            onNodeWithText(Tr.STAT_GATE_BADGE.fr).assertExists()
            onNodeWithContentDescription(warningFr).assertExists()
        }
    }
}
