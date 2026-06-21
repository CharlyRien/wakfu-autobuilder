package me.chosante.ui.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.common.Rarity
import me.chosante.ui.components.RarityIcon
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.paperdoll.PaperdollPanel
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the real Compose components off-screen to PNGs so the paperdoll item-type icons and the
 * rarity gems can be eyeballed without a live window / search. **Opt-in only**: it is skipped unless
 * `WAKFU_RENDER_SHOTS` is set (so `./gradlew test` / CI never writes files), mirroring the
 * `WAKFU_COMPOSE_SCREENSHOT` smoke-test convention. Output dir: `WAKFU_RENDER_DIR` or `~/Downloads`.
 */
@OptIn(ExperimentalTestApi::class)
class PaperdollRenderTest {
    private val outDir: File get() = File(System.getenv("WAKFU_RENDER_DIR") ?: "${System.getProperty("user.home")}/Downloads")

    @Test
    fun `render empty paperdoll with item-type slot icons`() {
        assumeTrue(System.getenv("WAKFU_RENDER_SHOTS") != null, "set WAKFU_RENDER_SHOTS to render")
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    Box(modifier = Modifier.size(920.dp, 780.dp).background(WColor.bg)) {
                        // phase = Done so the empty cards render at full opacity (Idle dims them to 0.48).
                        PaperdollPanel(
                            ui = UiState(phase = Phase.Done),
                            onForceItem = {},
                            onExcludeItem = {},
                            onEditRunes = {}
                        )
                    }
                }
            }
            writePng("paperdoll_itemtype_icons.png")
        }
    }

    @Test
    fun `render rarity gems showcase`() {
        assumeTrue(System.getenv("WAKFU_RENDER_SHOTS") != null, "set WAKFU_RENDER_SHOTS to render")
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    RarityGemsShowcase()
                }
            }
            writePng("rarity_gems_showcase.png")
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.writePng(name: String) {
        val image = onRoot().captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, name)
        ImageIO.write(image, "png", file)
        println("RENDERED $name -> ${file.absolutePath} (${image.width}x${image.height})")
    }
}

@Composable
private fun RarityGemsShowcase() {
    Column(
        modifier = Modifier.background(WColor.bg).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Rarity gems (official-grounded)", style = WTypography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Rarity.entries.forEach { rarity ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RarityIcon(rarity = rarity, size = 52.dp)
                    Text(
                        text = rarity.name,
                        style = WTypography.labelSmall.copy(color = WColor.muted),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
