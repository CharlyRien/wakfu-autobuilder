package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.shell.TopBar
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Precision mode's match headline never exceeds 100 %: a build that meets every target reads "100 %" with "Targets met",
 * instead of the engine's raw overshoot score (248 for a realistic request, 20 330 with a "Distance mastery 1" row).
 */
@OptIn(ExperimentalTestApi::class)
class PrecisionMatchUiTest {
    private fun precisionUi(
        match: String,
        lang: Lang = Lang.EN,
    ) = UiState(
        lang = lang,
        mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
        phase = Phase.Done,
        match = BigDecimal(match),
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
    )

    @Test
    fun `a build that overshoots its targets reads 100 and targets met, never the raw score`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = precisionUi("248.5"))
                }
            }
            onNodeWithText("100").assertExists()
            onNodeWithText(Tr.TARGETS_MET.en).assertExists()
            assertThat(onAllNodesWithText("248", substring = true).fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText(Tr.BUILD_MATCH.en).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `a huge overshoot is capped the same way`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = precisionUi("20330.5"))
                }
            }
            onNodeWithText("100").assertExists()
            assertThat(onAllNodesWithText("20330", substring = true).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `a build short of its targets keeps its percentage and the match label`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = precisionUi("87.3"))
                }
            }
            onNodeWithText("87").assertExists()
            onNodeWithText(Tr.BUILD_MATCH.en).assertExists()
            assertThat(onAllNodesWithText(Tr.TARGETS_MET.en).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `the targets-met wording speaks French`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.FR) {
                    MatchHero(ui = precisionUi("248.5", Lang.FR))
                }
            }
            onNodeWithText("Cibles atteintes").assertExists()
        }

    @Test
    fun `the top bar meter is capped too`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    TopBar(
                        ui = precisionUi("248.5"),
                        onSearch = {},
                        onCancel = {},
                        onClassChange = {},
                        onLevelChange = {},
                        onMinLevelChange = {},
                        onLangChange = {},
                        onNavigate = {},
                        onNewBuild = {},
                        onDetachActiveBuild = {}
                    )
                }
            }
            onNodeWithText("100%").assertExists()
            assertThat(onAllNodesWithText("248", substring = true).fetchSemanticsNodes()).isEmpty()
        }
}
