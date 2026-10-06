package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.common.Characteristic
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.statCatalog
import me.chosante.ui.theme.WColor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class StatGlyphLanguageTest {
    @Test
    fun `aggregate resistance is the only unmapped catalog icon and every mapped PNG exists`() {
        assertThat(statCatalog.filter { it.characteristic.iconResourcePath() == null }.map { it.characteristic })
            .containsExactly(Characteristic.RESISTANCE_ELEMENTARY)
        statCatalog.forEach { def ->
            def.characteristic.iconResourcePath()?.let { path ->
                assertThat(javaClass.classLoader.getResource(path)).describedAs(path).isNotNull()
            }
        }
    }

    @Test
    fun `abbreviations localize even for normally mapped icons while symbols stay unchanged`() {
        for ((stat, en, fr) in listOf(
            Triple(Characteristic.ACTION_POINT, "AP", "PA"),
            Triple(Characteristic.MOVEMENT_POINT, "MP", "PM"),
            Triple(Characteristic.WAKFU_POINT, "WP", "PW"),
            Triple(Characteristic.MASTERY_HEALING, "He", "So"),
            Triple(Characteristic.RESISTANCE_ELEMENTARY, "rE", "rÉ")
        )) {
            assertThat(stat.localizedStatGlyph("fallback", Lang.EN)).isEqualTo(en)
            assertThat(stat.localizedStatGlyph("fallback", Lang.FR)).isEqualTo(fr)
        }
        assertThat(Characteristic.RANGE.localizedStatGlyph("◎", Lang.FR)).isEqualTo("◎")
    }

    @Test
    fun `the actual fallback updates when the language switches`() =
        runComposeUiTest {
            val lang = mutableStateOf(Lang.EN)
            setContent {
                CompositionLocalProvider(LocalLang provides lang.value) {
                    StatGlyphIcon(Characteristic.RESISTANCE_ELEMENTARY, "rE", WColor.accent)
                }
            }
            onNodeWithText("rE").assertExists()
            runOnIdle { lang.value = Lang.FR }
            onNodeWithText("rÉ").assertExists()
            onNodeWithText("rE").assertDoesNotExist()
        }
}
