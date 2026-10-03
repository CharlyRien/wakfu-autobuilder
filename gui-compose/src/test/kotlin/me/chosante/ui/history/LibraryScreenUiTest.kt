package me.chosante.ui.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.history.BossSnapshot
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The library cards label a build by what its mode maximized: a max-damage build used to read "10528% Match" under a
 * "Precision" pill, because only the mastery mode had its own branch and everything else was treated as a precision build.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryScreenUiTest {
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

    private fun entry(
        id: String,
        name: String,
        mode: String,
        match: Double,
        optimal: Boolean = false,
        bossSnapshot: BossSnapshot? = null,
    ) = HistoryEntry(
        id = id,
        name = name,
        createdAt = 1_000L,
        dataVersion = "test",
        request =
            RequestSnapshot(
                clazz = "CRA",
                level = 110,
                minLevel = 0,
                mode = mode,
                maxRarity = Rarity.EPIC,
                duration = "20",
                stopAtMatch = false,
                targets = listOf(TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1")),
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                boss = bossSnapshot
            ),
        result =
            ResultSnapshot(
                equipments = emptyList(),
                skills = emptyMap(),
                achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1_210),
                match = match,
                optimal = optimal
            )
    )

    private fun androidx.compose.ui.test.ComposeUiTest.library(
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

    private val damageEntry =
        entry("dmg", "Cra 110 · Boss damage", "FIND_BUILD_WITH_MAX_DAMAGE", match = 10_528.8028, bossSnapshot = BossSnapshot(boss, element = "FIRE"))

    @Test
    fun `a max-damage card shows its damage and a Max Damage pill, not a percentage under Precision`() =
        runComposeUiTest {
            library(Lang.EN, damageEntry)

            onNodeWithText("10,528").assertExists()
            onNodeWithText("Expected damage").assertExists()
            onNodeWithText("Max Damage").assertExists()
            assertThat(onAllNodesWithText("Precision").fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("10528%").fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("%", substring = true).fetchSemanticsNodes()).describedAs("no percentage anywhere on the card").isEmpty()
        }

    @Test
    fun `the card names the boss, in the language of the app`() {
        runComposeUiTest {
            library(Lang.EN, damageEntry)
            onNodeWithText("vs Dominant Magik Riktus").assertExists()
        }
        runComposeUiTest {
            library(Lang.FR, damageEntry)
            onNodeWithText("contre Magik Riktus Dominant").assertExists()
            onNodeWithText("Dégâts attendus").assertExists()
            onNodeWithText("Dégâts max").assertExists()
        }
    }

    @Test
    fun `a damage build without a recorded boss shows no boss line`() =
        runComposeUiTest {
            library(Lang.EN, damageEntry.copy(request = damageEntry.request.copy(boss = null)))

            onNodeWithText("Max Damage").assertExists()
            assertThat(onAllNodesWithText("vs ", substring = true).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `a precision card that overshoots its targets reads 100 percent and targets met`() {
        runComposeUiTest {
            library(Lang.EN, entry("pr", "Precision build", "FIND_CLOSEST_BUILD_FROM_INPUT", match = 20_330.0))

            onNodeWithText("100%").assertExists()
            onNodeWithText("Targets met").assertExists()
            assertThat(onAllNodesWithText("20330", substring = true).fetchSemanticsNodes()).isEmpty()
        }
        runComposeUiTest {
            library(Lang.FR, entry("pr", "Precision build", "FIND_CLOSEST_BUILD_FROM_INPUT", match = 248.5))

            onNodeWithText("100%").assertExists()
            onNodeWithText("Cibles atteintes").assertExists()
        }
        // Targets met AND proven optimal: both facts show, one under the other.
        runComposeUiTest {
            library(Lang.EN, entry("pr", "Precision build", "FIND_CLOSEST_BUILD_FROM_INPUT", match = 248.5, optimal = true))

            onNodeWithText("100%").assertExists()
            onNodeWithText("Targets met").assertExists()
            onNodeWithText("Optimal proven").assertExists()
        }
    }

    @Test
    fun `mastery and precision cards keep their own headline and pill`() =
        runComposeUiTest {
            library(
                Lang.EN,
                entry("mm", "Masteries build", "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT", match = 1_210.0),
                entry("pr", "Precision build", "FIND_CLOSEST_BUILD_FROM_INPUT", match = 87.0)
            )

            onNodeWithText("1,210").assertExists()
            onNodeWithText("Most Masteries").assertExists()
            onNodeWithText("87%").assertExists()
            onNodeWithText("Precision").assertExists()
            assertThat(onAllNodesWithText("Max Damage").fetchSemanticsNodes()).isEmpty()
        }
}
