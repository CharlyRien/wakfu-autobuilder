package me.chosante.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.ErrorRetry
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiError
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The results panel's error banner: a plain-language message and, when repeating the failed action can help, a "Retry" link
 * that reports its click. Zenith / search failures used to print the raw exception text ("api.zenithwakfu.com").
 */
@OptIn(ExperimentalTestApi::class)
class ErrorBannerUiTest {
    private val withBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private fun panel(
        ui: UiState,
        lang: Lang = Lang.EN,
        onRetry: () -> Unit = {},
    ): @Composable () -> Unit =
        {
            CompositionLocalProvider(LocalLang provides lang) {
                StatsPanel(
                    ui = ui,
                    onOpenZenith = {},
                    onCopyZenith = {},
                    onSaveBuild = {},
                    onExport = {},
                    onViewAsDamage = {},
                    onRetryError = onRetry
                )
            }
        }

    @Test
    fun `a Zenith failure shows its plain sentence and a Retry that reports the click`() =
        runComposeUiTest {
            var retries = 0
            val ui = UiState(phase = Phase.Done, build = withBuild, error = UiError(Tr.ZENITH_UNREACHABLE.en, ErrorRetry.COPY_ZENITH))
            setContent(panel(ui, onRetry = { retries++ }))

            onNodeWithText(Tr.ZENITH_UNREACHABLE.en).assertExists()
            onNodeWithText(Tr.RETRY.en).assertExists()
            onNodeWithTag(ERROR_RETRY_TAG).performClick()
            assertThat(retries).isEqualTo(1)
        }

    @Test
    fun `an error with nothing to repeat has no Retry`() =
        runComposeUiTest {
            val ui = UiState(phase = Phase.Done, build = withBuild, error = UiError(Tr.ZENITH_BROWSER_FAILED.en.format(Tr.COPY_BUILD_LINK.en)))
            setContent(panel(ui))

            onNodeWithText(Tr.ZENITH_BROWSER_FAILED.en.format(Tr.COPY_BUILD_LINK.en)).assertExists()
            onNodeWithTag(ERROR_RETRY_TAG).assertDoesNotExist()
        }

    @Test
    fun `before any build a failed search shows its banner with a Retry too`() =
        runComposeUiTest {
            var retries = 0
            val ui = UiState(phase = Phase.Idle, build = null, error = UiError(Tr.SEARCH_FAILED.en, ErrorRetry.SEARCH))
            setContent(panel(ui, onRetry = { retries++ }))

            onNodeWithText(Tr.SEARCH_FAILED.en).assertExists()
            onNodeWithTag(ERROR_RETRY_TAG).performClick()
            assertThat(retries).isEqualTo(1)
        }

    @Test
    fun `the Retry link and the sentences speak French`() =
        runComposeUiTest {
            val ui = UiState(lang = Lang.FR, phase = Phase.Done, build = withBuild, error = UiError(Tr.ZENITH_UNREACHABLE.fr, ErrorRetry.OPEN_ZENITH))
            setContent(panel(ui, lang = Lang.FR))

            onNodeWithText("Zenith n'a pas répondu — vérifie ta connexion et réessaie.").assertExists()
            onNodeWithText("Réessayer").assertExists()
        }
}
