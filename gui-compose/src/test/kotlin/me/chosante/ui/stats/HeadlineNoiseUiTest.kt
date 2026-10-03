package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The headline of the stats column before there is a result. It used to print its number anyway — "0 Requested mastery",
 * "0 Expected damage", "0 %" — as if the player had asked for nothing and got nothing; it now shows a dash until a build exists.
 * And Max Damage, which starts without target rows, no longer shows an empty "Desired vs Achieved" card.
 */
@OptIn(ExperimentalTestApi::class)
class HeadlineNoiseUiTest {
    private val dash = "—"
    private val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private fun ComposeUiTest.hero(ui: UiState) =
        setContent {
            CompositionLocalProvider(LocalLang provides ui.lang) {
                MatchHero(ui = ui)
            }
        }

    private fun ComposeUiTest.column(ui: UiState) =
        setContent {
            CompositionLocalProvider(LocalLang provides ui.lang) {
                StatsPanel(ui = ui, onOpenZenith = {}, onCopyZenith = {}, onSaveBuild = {}, onExport = {}, onViewAsDamage = {})
            }
        }

    // -- before any result ---------------------------------------------------------------------------------------------

    @Test
    fun `before a search, Most Masteries shows a dash and its label, not a zero`() =
        runComposeUiTest {
            hero(UiState())

            onNodeWithText(dash).assertExists()
            onNodeWithText(Tr.BUILD_MASTERY.en).assertExists()
            assertThat(onAllNodesWithText("0").fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `before a search, Max Damage shows a dash and its label, not a zero`() =
        runComposeUiTest {
            hero(UiState(mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE))

            onNodeWithText(dash).assertExists()
            onNodeWithText(Tr.EXPECTED_DAMAGE.en).assertExists()
            assertThat(onAllNodesWithText("0").fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `before a search, Precision shows a dash, no percent sign and no empty meter`() =
        runComposeUiTest {
            hero(UiState(mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT))

            onNodeWithText(dash).assertExists()
            onNodeWithText(Tr.BUILD_MATCH.en).assertExists()
            assertThat(onAllNodesWithText("0").fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("%").fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `the dash speaks every language the same, with the label in French`() =
        runComposeUiTest {
            hero(UiState(lang = Lang.FR, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE))

            onNodeWithText(dash).assertExists()
            onNodeWithText(Tr.EXPECTED_DAMAGE.fr).assertExists()
        }

    @Test
    fun `while the first build is still being searched the headline is a dash too`() =
        runComposeUiTest {
            hero(UiState(phase = Phase.Searching, progress = 12))

            onNodeWithText(dash).assertExists()
            assertThat(onAllNodesWithText("0").fetchSemanticsNodes()).isEmpty()
        }

    // -- once there is a build, the numbers are back (a real zero included) ----------------------------------------------

    @Test
    fun `with a build the headline is its number, in every mode`() {
        runComposeUiTest {
            val achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1_210)
            hero(UiState(phase = Phase.Done, build = build, achieved = achieved))

            onNodeWithText("1,210").assertExists()
            assertThat(onAllNodesWithText(dash).fetchSemanticsNodes()).isEmpty()
        }
        runComposeUiTest {
            hero(UiState(mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, phase = Phase.Done, build = build, match = BigDecimal("10528.8")))

            onNodeWithText("10,528").assertExists()
            assertThat(onAllNodesWithText(dash).fetchSemanticsNodes()).isEmpty()
        }
        runComposeUiTest {
            hero(UiState(mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, phase = Phase.Done, build = build, match = BigDecimal("87")))

            onNodeWithText("87").assertExists()
            onNodeWithText("%").assertExists()
            assertThat(onAllNodesWithText(dash).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun `a build that really scores zero still reads zero`() =
        runComposeUiTest {
            hero(UiState(mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, phase = Phase.Done, build = build, match = BigDecimal.ZERO))

            onNodeWithText("0").assertExists()
            assertThat(onAllNodesWithText(dash).fetchSemanticsNodes()).describedAs("a result, not a missing one").isEmpty()
        }

    // -- Desired vs Achieved ------------------------------------------------------------------------------------------------

    private fun maxDamageResult(targets: List<Characteristic>) =
        UiState(
            mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            phase = Phase.Done,
            build = build,
            targets = targets.map { statDefFor(it)!!.toRow("1") }
        )

    @Test
    fun `Max Damage without target rows has no empty Desired vs Achieved card`() =
        runComposeUiTest {
            column(maxDamageResult(emptyList()))

            assertThat(onAllNodesWithText(Tr.DESIRED_VS_ACHIEVED.en).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `Max Damage with a target row shows the card with it`() =
        runComposeUiTest {
            column(maxDamageResult(listOf(Characteristic.ACTION_POINT)))

            onNodeWithText(Tr.DESIRED_VS_ACHIEVED.en).assertExists()
        }

    @Test
    fun `the other modes keep the card`() {
        for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)) {
            runComposeUiTest {
                column(
                    UiState(
                        mode = mode,
                        phase = Phase.Done,
                        build = build,
                        targets = listOf(statDefFor(Characteristic.ACTION_POINT)!!.toRow("11"))
                    )
                )

                onNodeWithText(Tr.DESIRED_VS_ACHIEVED.en).assertExists()
            }
        }
    }
}
