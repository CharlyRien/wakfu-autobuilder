package me.chosante.ui.request

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The optimality settings reminder in the Search Mode card, next to the search duration: offered where a
 * post-search check exists (most masteries, max damage), absent in precision mode (nothing to switch), and a click opens
 * Settings. The moved switch itself is covered by SettingsScreenUiTest.
 */
@OptIn(ExperimentalTestApi::class)
class VerifyOptimalityToggleUiTest {
    private fun androidx.compose.ui.test.ComposeUiTest.card(
        mode: ScoreComputationMode,
        verify: Boolean,
        lang: Lang = Lang.EN,
        onOpenSettings: () -> Unit = {},
    ) = setContent {
        CompositionLocalProvider(LocalLang provides lang) {
            SearchModeCard(
                selected = mode,
                duration = "120",
                stopAtMatch = false,
                verifyOptimality = verify,
                onSelect = {},
                onDurationChange = {},
                onStopAtMatchChange = {},
                onOpenSettings = onOpenSettings
            )
        }
    }

    @Test
    fun `most masteries and max damage offer the reminder and a click opens Settings`() {
        // One composition per mode: a Compose UI test sets its content once.
        for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
            runComposeUiTest {
                var opened = 0
                card(mode, verify = true) { opened++ }
                onNodeWithText("Optimality proof: on · Settings").assertExists()
                onNodeWithTag(OPTIMALITY_SETTINGS_TAG).performClick()
                assertThat(opened).describedAs("$mode: clicking the ON reminder opens Settings").isEqualTo(1)
            }
        }
    }

    @Test
    fun `an OFF reminder opens Settings when clicked`() =
        runComposeUiTest {
            var opened = 0
            card(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, verify = false) { opened++ }
            onNodeWithTag(OPTIMALITY_SETTINGS_TAG).performClick()
            assertThat(opened).isEqualTo(1)
        }

    @Test
    fun `precision mode has no post-search check, so the switch is not offered`() =
        runComposeUiTest {
            card(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, verify = true)
            onNodeWithText("Check optimality after the search").assertDoesNotExist()
            onNodeWithTag(OPTIMALITY_SETTINGS_TAG).assertDoesNotExist()
        }

    @Test
    fun `the reminder is labelled in French`() =
        runComposeUiTest {
            card(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, verify = true, lang = Lang.FR)
            onNodeWithText("Preuve d’optimalité : activée · Réglages").assertExists()
        }
}
