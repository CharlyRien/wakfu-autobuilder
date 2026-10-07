package me.chosante.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ItemConditionLinesUiTest {
    @Test
    fun `compact rows keep a single condition line and a count while hover reveals the full list`() {
        val lines = listOf(ItemConditionLine("Needs a ring"), ItemConditionLine("Militia rank ≥ 2 (assumed met)", true), ItemConditionLine("Cra only"))
        for (lang in Lang.entries) {
            runComposeUiTest {
                mainClock.autoAdvance = false
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        Box(modifier = Modifier.width(240.dp)) {
                            ItemConditionsHover(lines) { ItemConditionLines(lines, compact = true) }
                        }
                    }
                }
                mainClock.advanceTimeByFrame()
                onNodeWithText("Needs a ring").assertExists()
                onNodeWithText(if (lang == Lang.EN) "+2 more" else "+2 autres").assertExists()
                onNodeWithText(lines[1].text).assertDoesNotExist()
                onNodeWithText(lines[2].text).assertDoesNotExist()
                onNodeWithText("Needs a ring").performMouseInput { moveTo(center) }
                repeat(40) { mainClock.advanceTimeByFrame() }
                onNodeWithText(lines[1].text).assertExists()
                onNodeWithText(lines[2].text).assertExists()
            }
        }
    }
}
