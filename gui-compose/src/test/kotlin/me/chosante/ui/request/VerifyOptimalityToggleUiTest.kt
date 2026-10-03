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
 * The "Check optimality after the search" switch in the Search Mode card, next to the search duration: offered where a
 * post-search check exists (most masteries, max damage), absent in precision mode (nothing to switch), and a click reports
 * the flipped value.
 */
@OptIn(ExperimentalTestApi::class)
class VerifyOptimalityToggleUiTest {
    private fun androidx.compose.ui.test.ComposeUiTest.card(
        mode: ScoreComputationMode,
        verify: Boolean,
        lang: Lang = Lang.EN,
        onChange: (Boolean) -> Unit = {},
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
                onVerifyOptimalityChange = onChange
            )
        }
    }

    @Test
    fun `most masteries and max damage offer the switch and a click reports the flipped value`() {
        // One composition per mode: a Compose UI test sets its content once.
        for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
            runComposeUiTest {
                val changes = mutableListOf<Boolean>()
                card(mode, verify = true) { changes += it }
                onNodeWithText("Check optimality after the search").assertExists()
                onNodeWithTag(VERIFY_OPTIMALITY_TOGGLE_TAG).performClick()
                assertThat(changes).describedAs("$mode: ON then a click reports OFF").containsExactly(false)
            }
        }
    }

    @Test
    fun `an OFF switch reports ON when clicked`() =
        runComposeUiTest {
            val changes = mutableListOf<Boolean>()
            card(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, verify = false) { changes += it }
            onNodeWithTag(VERIFY_OPTIMALITY_TOGGLE_TAG).performClick()
            assertThat(changes).containsExactly(true)
        }

    @Test
    fun `precision mode has no post-search check, so the switch is not offered`() =
        runComposeUiTest {
            card(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, verify = true)
            onNodeWithText("Check optimality after the search").assertDoesNotExist()
            onNodeWithTag(VERIFY_OPTIMALITY_TOGGLE_TAG).assertDoesNotExist()
        }

    @Test
    fun `the switch is labelled in French`() =
        runComposeUiTest {
            card(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, verify = true, lang = Lang.FR)
            onNodeWithText("Vérifier l'optimalité après la recherche").assertExists()
        }
}
