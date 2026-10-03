package me.chosante.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Monster
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.request.RequestPanel
import me.chosante.ui.state.Modal
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.stats.StatsPanel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Boss names follow the language of the app wherever they are shown — the picker, the boss card of the request panel and the
 * damage card of the stats column. They used to be the French name everywhere, "because the English names are lowercased"
 * (none is), so an English player read French in the picker and found it sorted by an English name they could not see.
 */
@OptIn(ExperimentalTestApi::class)
class BossNamesUiTest {
    /** A real boss whose names and family differ between the two languages, so a mix-up cannot pass unnoticed. */
    private val boss: Monster =
        WakfuBestBuildFinderAlgorithm.monsters.first {
            it.isBoss &&
                it.name.fr != it.name.en &&
                it.displayFamily(Lang.EN) != null &&
                it.displayFamily(Lang.FR) != null &&
                it.family?.fr != it.family?.en
        }

    private fun ComposeUiTest.picker(lang: Lang) {
        setContent {
            CompositionLocalProvider(LocalLang provides lang) {
                ModalHost(
                    modal = Modal.BossPicker,
                    excludedCharacteristics = emptySet(),
                    equipmentCatalog = null,
                    onSelectStat = {},
                    onPickItem = {},
                    onDismiss = {}
                )
            }
        }
        waitForIdle()
    }

    /** The picker rows showing [name] — not the search field, which also holds it once it has been typed. */
    private fun ComposeUiTest.rows(name: String) = onAllNodes(hasText(name) and !hasSetTextAction())

    @Test
    fun `the picker names a boss in English in the English app and in French in the French one`() {
        runComposeUiTest {
            picker(Lang.EN)
            onAllNodes(hasSetTextAction())[0].performTextInput(boss.name.en)

            assertThat(rows(boss.name.en).fetchSemanticsNodes()).describedAs("the English name is shown").hasSize(1)
            assertThat(rows(boss.name.fr).fetchSemanticsNodes()).describedAs("no French name in the English app").isEmpty()
        }
        runComposeUiTest {
            picker(Lang.FR)
            onAllNodes(hasSetTextAction())[0].performTextInput(boss.name.fr)

            assertThat(rows(boss.name.fr).fetchSemanticsNodes()).describedAs("the French name is shown").hasSize(1)
            assertThat(rows(boss.name.en).fetchSemanticsNodes()).describedAs("no English name in the French app").isEmpty()
        }
    }

    @Test
    fun `the search finds a boss by its name in either language`() {
        runComposeUiTest {
            picker(Lang.EN)
            onAllNodes(hasSetTextAction())[0].performTextInput(boss.name.fr) // typed in French, shown in English

            assertThat(rows(boss.name.en).fetchSemanticsNodes()).hasSize(1)
        }
    }

    @Test
    fun `the list opens on the first names of the displayed language, and the garbled entry is gone`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                picker(lang)
                val firstRows = bossRoster(WakfuBestBuildFinderAlgorithm.monsters, lang).take(3).map { it.displayName(lang) }

                val tops = firstRows.map { name -> onAllNodesWithText(name)[0].fetchSemanticsNode().boundsInRoot.top }

                assertThat(tops).describedAs("$lang: the first three rows, top to bottom").isSorted()
                assertThat(onAllNodesWithText("!@#dh`~").fetchSemanticsNodes()).describedAs("the garbled entry is not offered").isEmpty()
            }
        }
    }

    @Test
    fun `the three Cire Momore are listed one after the other, told apart by their level`() =
        runComposeUiTest {
            picker(Lang.EN)
            onAllNodes(hasSetTextAction())[0].performTextInput("Cire Momore")

            assertThat(rows("Cire Momore").fetchSemanticsNodes()).hasSize(3)
            val tops = listOf(58, 73, 233).map { level -> onNodeWithText("Lv $level").fetchSemanticsNode().boundsInRoot.top }
            assertThat(tops).describedAs("lowest level first").isSorted()
        }

    @Test
    fun `the picker lists every boss, not only the first hundred and twenty`() =
        runComposeUiTest {
            picker(Lang.EN)
            val roster = bossRoster(WakfuBestBuildFinderAlgorithm.monsters, Lang.EN)
            assertThat(roster.size).describedAs("the bestiary has more bosses than the old cap").isGreaterThan(120)

            // The alphabetically last boss is reachable by scrolling alone, with nothing typed (the old cap cut the list at 120).
            val last = roster.last().displayName(Lang.EN)
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText(last))

            assertThat(onAllNodesWithText(last).fetchSemanticsNodes()).isNotEmpty()
        }

    // -- the request panel's boss card and the stats column ----------------------------------------------------------------

    private fun ComposeUiTest.requestPanel(ui: UiState) =
        setContent {
            CompositionLocalProvider(LocalLang provides ui.lang) {
                Box(Modifier.width(360.dp)) {
                    RequestPanel(
                        ui = ui,
                        onModeChange = {},
                        onScenarioChange = {},
                        onTargetValueChange = { _, _ -> },
                        onTargetWeightChange = { _, _ -> },
                        onRemoveTarget = {},
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

    @Test
    fun `the boss card names the boss and its family in the language of the app`() {
        for (lang in Lang.entries) {
            runComposeUiTest {
                requestPanel(UiState(lang = lang, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, selectedBoss = boss))
                val other = if (lang == Lang.EN) Lang.FR else Lang.EN

                onNodeWithText(boss.displayName(lang)).assertExists()
                onNodeWithText("${Tr.BOSS_LEVEL_SHORT.value(lang)} ${boss.level}  ·  ${boss.displayFamily(lang)}").assertExists()
                assertThat(onAllNodesWithText(boss.displayName(other)).fetchSemanticsNodes()).describedAs("$lang: no $other name").isEmpty()
                assertThat(onAllNodesWithText(boss.displayFamily(other)!!, substring = true).fetchSemanticsNodes()).describedAs("$lang: no $other family").isEmpty()
            }
        }
    }

    @Test
    fun `a boss whose family is its own name shows its level alone under the name`() =
        runComposeUiTest {
            val self = WakfuBestBuildFinderAlgorithm.monsters.first { it.isBoss && it.family != null && it.displayFamily(Lang.EN) == null }
            requestPanel(UiState(lang = Lang.EN, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, selectedBoss = self))

            onNodeWithText("Lv ${self.level}").assertExists()
        }

    @Test
    fun `the damage card says which boss it counts the turns against, in the language of the app`() {
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
        val character = Character(CharacterClass.CRA, 110, 0, build.characterSkills)
        val rotation = SpellRotationOptimizer.bestSequencedRotation(build, character, character.clazz, DamageScenario())
        assertThat(rotation.totalExpectedDamage).describedAs("the fixture rotation does damage").isGreaterThan(0.0)

        for (lang in Lang.entries) {
            runComposeUiTest {
                val ui =
                    UiState(
                        lang = lang,
                        mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        phase = Phase.Done,
                        build = build,
                        spellRotation = rotation,
                        selectedBoss = boss
                    )
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        StatsPanel(ui = ui, onOpenZenith = {}, onCopyZenith = {}, onSaveBuild = {}, onExport = {}, onViewAsDamage = {})
                    }
                }

                onNodeWithText("${Tr.TURNS_TO_KILL.value(lang)} · ${boss.displayName(lang)}").assertExists()
            }
        }
    }
}
