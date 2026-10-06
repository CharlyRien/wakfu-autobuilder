package me.chosante.ui.stats

import androidx.compose.runtime.CompositionLocalProvider
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

@OptIn(ExperimentalTestApi::class)
class MasterySummaryUiTest {
    @Test
    fun `max damage only shows the requested mastery metric and header number when a mastery was requested`() {
        for (lang in Lang.entries) {
            for (requested in listOf(false, true)) {
                runComposeUiTest {
                    val ui =
                        UiState(
                            lang = lang,
                            mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                            phase = Phase.Done,
                            build = BuildCombination(emptyList(), CharacterSkills(110)),
                            targets = listOf(statDefFor(if (requested) Characteristic.MASTERY_DISTANCE else Characteristic.ACTION_POINT)!!.toRow("0"))
                        )
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) { MasterySummary(ui) }
                    }
                    onNodeWithText(Tr.MASTERY_SUMMARY.value(lang)).assertExists()
                    assertThat(onAllNodesWithText(Tr.BUILD_MASTERY.value(lang)).fetchSemanticsNodes()).hasSize(if (requested) 1 else 0)
                    assertThat(onAllNodesWithText(Tr.BUILD_MASTERY_HINT.value(lang)).fetchSemanticsNodes()).hasSize(if (requested) 1 else 0)
                    assertThat(onAllNodesWithText("0").fetchSemanticsNodes()).hasSize(if (requested) 3 else 0)
                }
            }
        }
    }
}
