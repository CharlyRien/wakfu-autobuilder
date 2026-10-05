package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.ProofState
import me.chosante.ui.state.UiState
import org.junit.jupiter.api.Test

/**
 * What the stats headline says under a result that has no optimality badge. A request on several elements is searched on a
 * heuristic selection of the items, so no search of it is ever proven: its result explains that ([UiState.prefilteredRequest])
 * instead of the "raise the search duration" hint, which is right only for a result that merely ran out of time.
 */
@OptIn(ExperimentalTestApi::class)
class NoProofExplanationUiTest {
    private fun uiWith(
        lang: Lang = Lang.EN,
        prefiltered: Boolean = true,
        mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        update: UiState.() -> UiState = { this },
    ) = UiState(
        lang = lang,
        mode = mode,
        phase = Phase.Done,
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
        prefilteredRequest = prefiltered
    ).update()

    private fun hero(
        ui: UiState,
        check: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLang provides ui.lang) {
                MatchHero(ui = ui)
            }
        }
        check()
    }

    @Test
    fun `a request on several elements says why it has no badge, not that the time ran out`() =
        hero(uiWith()) {
            onNodeWithText("No optimality proof for this request").assertExists()
            onNodeWithText(
                "Your request targets several elements. To keep the search fast, the engine only compares a selection of the strongest items, " +
                    "not the whole catalog. The build found is very likely the best, but it can't be proven, even with more time. " +
                    "Single-element requests are not affected."
            ).assertExists()
            // The headline still says the optimum is not proven; the duration hint — false advice here — is gone.
            onNodeWithText(Tr.BEST_FOUND.en).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
            onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertDoesNotExist()
        }

    @Test
    fun `the explanation speaks French, in the words the player approved`() =
        hero(uiWith(lang = Lang.FR)) {
            onNodeWithText("Pas de preuve d'optimalité pour cette requête").assertExists()
            onNodeWithText(
                "Ta requête vise plusieurs éléments. Pour que la recherche reste rapide, le moteur ne compare qu'une sélection des meilleurs " +
                    "objets, pas tout le catalogue. Le build trouvé est très probablement le meilleur, mais on ne peut pas le prouver, même " +
                    "avec plus de temps. Les requêtes sur un seul élément ne sont pas concernées."
            ).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.fr).assertDoesNotExist()
        }

    @Test
    fun `a time-limited result of a single-element request keeps the duration hint`() =
        hero(uiWith(prefiltered = false)) {
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertExists()
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
            onNodeWithText(Tr.NO_PROOF_TITLE.en).assertDoesNotExist()
        }

    @Test
    fun `every mode explains a request on several elements`() {
        for (mode in ScoreComputationMode.entries) {
            hero(uiWith(mode = mode)) {
                onNodeWithTag(NO_PROOF_TAG).assertExists()
                onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `the explanation yields to a badge and to a stopped search, and wins over the other unproven hints`() {
        // A proven result (the engine never proves such a request, but the badge is the stronger statement if one ever lands).
        hero(uiWith { copy(optimal = true) }) {
            onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertExists()
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
        hero(uiWith { copy(proofState = ProofState.ProvenWithin(0.05)) }) {
            onNodeWithText("Best found · at most 5.0% below the optimum").assertExists()
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
        // The user stopped the search: its build is a best-so-far, not that search's result — the stopped hint stays.
        hero(uiWith { copy(searchStopped = true) }) {
            onNodeWithText(Tr.SEARCH_STOPPED_HINT.en).assertExists()
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
        // "Unavailable (forced runes/sublimations)" and "structural" are true of this request too, but the missing proof is not
        // theirs: removing the forced items or the debuff phase would not give this request a proof.
        hero(uiWith { copy(proofState = ProofState.Unavailable, forcedSublimations = listOf("Carnage III")) }) {
            onNodeWithTag(NO_PROOF_TAG).assertExists()
            onNodeWithText(Tr.PROOF_UNAVAILABLE_FORCED.en).assertDoesNotExist()
        }
        hero(uiWith(mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) { copy(maxDamageStructural = true) }) {
            onNodeWithTag(NO_PROOF_TAG).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_STRUCTURAL_HINT.en).assertDoesNotExist()
        }
    }

    @Test
    fun `nothing is explained while the search runs or before there is a build`() {
        hero(uiWith { copy(phase = Phase.Searching) }) {
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
        hero(uiWith { copy(build = null) }) {
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun `the other no-badge messages of a single-element request are unchanged`() {
        // Forced runes / sublimations: the certificate is unavailable for them, and the panel says so.
        hero(uiWith(prefiltered = false) { copy(proofState = ProofState.Unavailable, forcedSublimations = listOf("Carnage III")) }) {
            onNodeWithText(Tr.PROOF_UNAVAILABLE_FORCED.en).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
        }
        // Resistance-debuff sequencing (Sram / Sadida): structurally heuristic, more time won't move it.
        hero(uiWith(prefiltered = false, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) { copy(maxDamageStructural = true) }) {
            onNodeWithText(Tr.NOT_OPTIMAL_STRUCTURAL_HINT.en).assertExists()
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
        }
        // A stopped search.
        hero(uiWith(prefiltered = false) { copy(searchStopped = true) }) {
            onNodeWithText(Tr.SEARCH_STOPPED_HINT.en).assertExists()
        }
        // The certificate found no proof for a max-damage request whose build is only the quick first estimate (the search
        // ended before CP-SAT produced one): not "unsupported" — a longer search is exactly what would change it.
        hero(uiWith(prefiltered = false, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) { copy(proofState = ProofState.Unavailable) }) {
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertExists()
            onNodeWithText(Tr.PROOF_UNAVAILABLE_FORCED.en).assertDoesNotExist()
            onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        }
    }
}
