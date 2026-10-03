package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.ProofState
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The certificate's "within X %" verdict on a build it could not prove optimal. It used to read "Optimal prouvé à 2.2 % près"
 * (a success) stacked under "Meilleur trouvé · optimum non prouvé" (the opposite). It is now ONE headline worded as a bound —
 * "Best found · at most 2.2% below the optimum" — with the number in the language's own format.
 */
@OptIn(ExperimentalTestApi::class)
class BoundBadgeUiTest {
    private fun uiWith(
        proof: ProofState,
        lang: Lang,
        optimal: Boolean = false,
    ) = UiState(
        lang = lang,
        phase = Phase.Done,
        optimal = optimal,
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
        proofState = proof
    )

    @Test
    fun `English reads as a bound on one headline, with no contradicting line`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(ProofState.ProvenWithin(0.0216), Lang.EN))
                }
            }
            onNodeWithText("Best found · at most 2.2% below the optimum").assertExists()
            assertThat(onAllNodesWithText("not proven", substring = true).fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("Proven", substring = true).fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText(Tr.NOT_OPTIMAL_HINT.en).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `French reads as a bound with a comma decimal, never as an optimal result`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.FR) {
                    MatchHero(ui = uiWith(ProofState.ProvenWithin(0.0216), Lang.FR))
                }
            }
            // A no-break space keeps the number and its % together.
            onNodeWithText("Meilleur trouvé · au plus 2,2 % sous l'optimum").assertExists()
            assertThat(onAllNodesWithText("Optimal prouvé", substring = true).fetchSemanticsNodes()).isEmpty()
            assertThat(onAllNodesWithText("non prouvé", substring = true).fetchSemanticsNodes()).isEmpty()
        }

    @Test
    fun `a proven optimum and a plain best-found keep their own wording`() {
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(ProofState.ProvenOptimal, Lang.EN))
                }
            }
            onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertExists()
            assertThat(onAllNodesWithText("below the optimum", substring = true).fetchSemanticsNodes()).isEmpty()
        }
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(ProofState.Idle, Lang.EN))
                }
            }
            onNodeWithText(Tr.BEST_FOUND.en).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertExists()
        }
    }

    @Test
    fun `the bound is formatted in the language of the app`() {
        assertThat(formatBoundPercent(0.0216, Lang.EN)).isEqualTo("2.2")
        assertThat(formatBoundPercent(0.0216, Lang.FR)).isEqualTo("2,2")
        assertThat(formatBoundPercent(0.02, Lang.FR)).isEqualTo("2,0")
        assertThat(formatBoundPercent(0.0, Lang.EN)).isEqualTo("0.0")
        assertThat(formatBoundPercent(1.5, Lang.FR)).isEqualTo("150,0")
    }
}
