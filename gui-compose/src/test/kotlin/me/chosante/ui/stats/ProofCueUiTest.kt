package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.ProofPhase
import me.chosante.ui.state.ProofProgress
import me.chosante.ui.state.ProofState
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The background optimality check's cue in the stats headline: both of its states — the "Verifying optimality…" line and
 * the "Refining the proof in the background…" line under a "proven within X %" badge — carry an info tooltip and a "Stop"
 * link, and the link reports the click. The cue holds an infinite spinner and a ticking elapsed-time effect, so the test clock
 * is driven by hand (`autoAdvance = false`) instead of waiting for an idle that never comes.
 */
@OptIn(ExperimentalTestApi::class)
class ProofCueUiTest {
    private fun uiWith(proof: ProofState) =
        UiState(
            mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            phase = Phase.Done,
            build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
            proofState = proof
        )

    private fun verifying() = ProofState.Proving(ProofProgress(phase = ProofPhase.CERTIFYING, startedAtMs = System.currentTimeMillis()))

    @Test
    fun `the Verifying line explains itself and its Stop link reports the click`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var stops = 0
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(verifying()), onStopProof = { stops++ })
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Verifying optimality…", substring = true).assertExists()
            onNodeWithText("i").assertExists() // the info tooltip's affordance
            onNodeWithText("Stop").assertExists()

            onNodeWithTag(PROOF_STOP_TAG).performClick()
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `the refining line under a proven-within badge has the same tooltip and Stop link`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            var stops = 0
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(ProofState.ProvenWithin(0.02, refining = true)), onStopProof = { stops++ })
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Proven within 2.0% of optimal").assertExists()
            onNodeWithText("Refining the proof in the background…").assertExists()
            onNodeWithText("i").assertExists()

            onNodeWithTag(PROOF_STOP_TAG).performClick()
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `hovering the info icon reveals the explanation, on both cue states`() {
        // One composition per state: a Compose UI test sets its content once.
        for (proof in listOf(verifying(), ProofState.ProvenWithin(0.02, refining = true))) {
            runComposeUiTest {
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalLang provides Lang.EN) {
                        MatchHero(ui = uiWith(proof))
                    }
                }
                mainClock.advanceTimeByFrame()
                onNodeWithText(Tr.PROOF_INFO.en).assertDoesNotExist()

                onNodeWithText("i").performMouseInput { moveTo(center) }
                repeat(40) { mainClock.advanceTimeByFrame() } // past the tooltip's reveal delay
                onNodeWithText(Tr.PROOF_INFO.en).assertExists()
            }
        }
    }

    @Test
    fun `the cue speaks French`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.FR) {
                    MatchHero(ui = uiWith(verifying()))
                }
            }
            mainClock.advanceTimeByFrame()
            onNodeWithText("Vérification de l'optimalité…", substring = true).assertExists()
            onNodeWithText("Arrêter").assertExists()
        }

    @Test
    fun `without a running check there is no cue and no Stop link`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val states =
                listOf(
                    ProofState.Idle,
                    ProofState.ProvenOptimal,
                    ProofState.ProvenWithin(0.02, refining = false),
                    ProofState.Unavailable
                )
            var current by mutableStateOf<ProofState>(states.first())
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = uiWith(current))
                }
            }
            for (state in states) {
                current = state
                mainClock.advanceTimeByFrame()
                onNodeWithTag(PROOF_STOP_TAG).assertDoesNotExist()
                onNodeWithText("i").assertDoesNotExist()
            }
        }

    @Test
    fun `the elapsed time never breaks across lines`() {
        assertThat(formatElapsed(45)).isEqualTo("45\u00A0s")
        assertThat(formatElapsed(130)).isEqualTo("2\u00A0min\u00A010\u00A0s")
        assertThat(formatElapsed(60)).isEqualTo("1\u00A0min\u00A00\u00A0s")
    }

    /**
     * The tooltip copy (EN + FR): plain words — no engine jargon — saying what the check is, that it costs processor time, that
     * the badge / build may change, that it can be stopped keeping the current build and badge, and where to switch it off.
     */
    @Test
    fun `the tooltip copy is plain, complete and points at the real switch`() {
        for ((lang, text) in listOf(Lang.EN to Tr.PROOF_INFO.en, Lang.FR to Tr.PROOF_INFO.fr)) {
            val lower = text.lowercase()
            for (jargon in listOf("certificate", "certificat", "cp-sat", "carrier", "porteur", "solver", "solveur")) {
                assertThat(lower).describedAs("$lang copy must not use the engine word '$jargon'").doesNotContain(jargon)
            }
            assertThat(text).describedAs("$lang copy names the switch exactly as its label reads").contains(Tr.VERIFY_OPTIMALITY.value(lang))
        }
        // The five promises, per language.
        assertThat(Tr.PROOF_INFO.en)
            .containsIgnoringCase("best possible")
            .containsIgnoringCase("optional")
            .containsIgnoringCase("processor")
            .containsIgnoringCase("badge")
            .containsIgnoringCase("Max Damage")
            .containsIgnoringCase("stop it at any time")
            .containsIgnoringCase("current build and badge")
        assertThat(Tr.PROOF_INFO.fr)
            .containsIgnoringCase("meilleur possible")
            .containsIgnoringCase("facultative")
            .containsIgnoringCase("processeur")
            .containsIgnoringCase("badge")
            .containsIgnoringCase("Dégâts max")
            .containsIgnoringCase("l'arrêter à tout moment")
            .containsIgnoringCase("build et ton badge actuels")
    }
}
