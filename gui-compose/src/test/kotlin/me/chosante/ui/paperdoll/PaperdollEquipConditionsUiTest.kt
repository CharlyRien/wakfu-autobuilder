package me.chosante.ui.paperdoll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.components.localized
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class PaperdollEquipConditionsUiTest {
    @Test
    fun `a saved item's tooltip restores its transient criterion in both languages`() {
        val sword = WakfuBestBuildFinderAlgorithm.equipments.single { it.equipmentId == 26497 }.copy(equipCriterion = null)
        for (lang in Lang.entries) {
            runComposeUiTest {
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        Box(modifier = Modifier.size(1100.dp, 820.dp)) {
                            PaperdollPanel(
                                ui = UiState(phase = Phase.Done, level = 245, build = BuildCombination(listOf(sword), CharacterSkills(245))),
                                onForceItem = {},
                                onExcludeItem = {}
                            )
                        }
                    }
                }
                mainClock.advanceTimeByFrame()
                onNodeWithText(sword.name.localized(lang)).performMouseInput { moveTo(center) }
                repeat(40) { mainClock.advanceTimeByFrame() }
                onNodeWithText(
                    Tr.EQUIP_NEEDS.value(lang).format(
                        WakfuBestBuildFinderAlgorithm.equipments
                            .single {
                                it.equipmentId in
                                    WakfuBestBuildFinderAlgorithm.equipments
                                        .single { it.equipmentId == sword.equipmentId }
                                        .equipCriterion!!
                                        .requiresItems
                            }.name
                            .localized(lang)
                    )
                ).assertExists()
            }
        }
    }
}
