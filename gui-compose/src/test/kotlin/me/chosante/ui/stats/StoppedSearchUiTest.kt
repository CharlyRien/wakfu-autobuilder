package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import org.junit.jupiter.api.Test

/**
 * What the stats column shows for a search the user stopped ([UiState.searchStopped]): the best-so-far build is a normal,
 * fully usable result — Save / Zenith / Export enabled — that honestly reads "not proven" and says it was stopped, instead of
 * blaming the time budget or claiming a proof.
 */
@OptIn(ExperimentalTestApi::class)
class StoppedSearchUiTest {
    private fun stoppedUi(lang: Lang = Lang.EN) =
        UiState(
            lang = lang,
            phase = Phase.Done,
            build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)),
            searchStopped = true
        )

    @Test
    fun `a stopped search reads as best found and not proven, with its own hint`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = stoppedUi())
                }
            }
            onNodeWithText(Tr.BEST_FOUND.en).assertExists()
            onNodeWithText(Tr.SEARCH_STOPPED_HINT.en).assertExists()
            onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertDoesNotExist()
            // The time budget did not run out — the user stopped it.
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
        }

    @Test
    fun `the stopped hint speaks French`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.FR) {
                    MatchHero(ui = stoppedUi(Lang.FR))
                }
            }
            onNodeWithText(Tr.BEST_FOUND.fr).assertExists()
            onNodeWithText("Recherche arrêtée avant la fin — relance-la pour laisser le solveur terminer.").assertExists()
        }

    @Test
    fun `a search that ended by itself keeps the generic hint`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    MatchHero(ui = stoppedUi().copy(searchStopped = false))
                }
            }
            onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertExists()
            onNodeWithText(Tr.SEARCH_STOPPED_HINT.en).assertDoesNotExist()
        }

    @Test
    fun `a stopped build keeps Save, Zenith, copy link and Export enabled`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    StatsPanel(
                        ui = stoppedUi(),
                        onOpenZenith = {},
                        onCopyZenith = {},
                        onSaveBuild = {},
                        onExport = {},
                        onViewAsDamage = {}
                    )
                }
            }
            onNodeWithText(Tr.SAVE_BUILD.en).assertIsEnabled()
            onNodeWithText(Tr.OPEN_IN_ZENITH.en).assertIsEnabled()
            onNodeWithText(Tr.COPY_BUILD_LINK.en).assertIsEnabled()
            onNodeWithText(Tr.EXPORT_BUILD.en).assertIsEnabled()
        }

    @Test
    fun `the same build left in the idle phase would have its actions disabled`() =
        runComposeUiTest {
            // The state a stop used to strand the build in — this is what makes the enabled assertions above meaningful.
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    StatsPanel(
                        ui = stoppedUi().copy(phase = Phase.Idle),
                        onOpenZenith = {},
                        onCopyZenith = {},
                        onSaveBuild = {},
                        onExport = {},
                        onViewAsDamage = {}
                    )
                }
            }
            onNodeWithText(Tr.SAVE_BUILD.en).assertIsNotEnabled()
            onNodeWithText(Tr.OPEN_IN_ZENITH.en).assertIsNotEnabled()
        }
}
