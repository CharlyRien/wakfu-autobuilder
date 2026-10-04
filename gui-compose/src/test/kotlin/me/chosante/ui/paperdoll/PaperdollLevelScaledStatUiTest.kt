package me.chosante.ui.paperdoll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Equipment
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import org.junit.jupiter.api.Test

/**
 * The paperdoll's item card (its hover tooltip) lists the Dofus Pourpre's "100% of the level as Elemental Mastery" as the
 * value it has at the build's level, like any other stat.
 */
@OptIn(ExperimentalTestApi::class)
class PaperdollLevelScaledStatUiTest {
    /** The catalog item as the embedded data stores it: the mastery is a level-independent line, not yet a stat. */
    private val catalogPourpre: Equipment by lazy { WakfuBestBuildFinderAlgorithm.equipments.single { it.equipmentId == 33395 } }

    private fun ComposeUiTest.hoverTheEmblemOf(
        item: Equipment,
        level: Int,
    ) {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLang provides Lang.EN) {
                Box(modifier = Modifier.size(1100.dp, 820.dp)) {
                    PaperdollPanel(
                        ui = UiState(phase = Phase.Done, level = level, build = BuildCombination(listOf(item), CharacterSkills(level))),
                        onForceItem = {},
                        onExcludeItem = {}
                    )
                }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithText("Crimson Dofus").performMouseInput { moveTo(center) }
        repeat(40) { mainClock.advanceTimeByFrame() } // past the card's reveal delay
    }

    @Test
    fun `a searched build's card shows the Pourpre's Elemental Mastery at the build's level`() =
        runComposeUiTest {
            // What a level-245 search hands the GUI: the pool's copy, resolved at the character's level.
            hoverTheEmblemOf(catalogPourpre.atLevel(245), level = 245)
            onNodeWithText("+245").assertExists()
            onNodeWithText("Elemental Mastery").assertExists()
            onNodeWithText("+1").assertExists() // its AP
            onNodeWithText("+3").assertExists() // its critical hit
        }

    @Test
    fun `an item that was not resolved yet is shown at the character's level`() =
        runComposeUiTest {
            hoverTheEmblemOf(catalogPourpre, level = 200)
            onNodeWithText("+200").assertExists()
            onNodeWithText("Elemental Mastery").assertExists()
        }
}
