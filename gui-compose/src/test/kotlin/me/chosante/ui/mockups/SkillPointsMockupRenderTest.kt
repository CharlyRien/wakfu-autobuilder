package me.chosante.ui.mockups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.request.SearchModeCard
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTheme
import me.chosante.ui.theme.WTypography
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the skill-points design mock-ups ([SkillPointsMockups.kt]) off-screen to PNGs, at the panels' real widths (request
 * 320 dp, stats 360 dp). Opt-in: skipped unless `WAKFU_SKILL_POINTS_MOCKUPS=<folder>` is set, so `./gradlew test` writes nothing.
 * The committed captures live in `docs/design-reference/skill-points/`.
 */
@OptIn(ExperimentalTestApi::class)
class SkillPointsMockupRenderTest {
    private val requestWidth = 320.dp
    private val statsWidth = 360.dp

    private val threeLocked =
        mapOf(
            SkillLine.AGILITY_DODGE to 57,
            SkillLine.LUCK_CRITICAL_HIT to 20,
            SkillLine.MAJOR_ACTION_POINT to 1
        )

    private fun render(
        name: String,
        width: Dp?,
        content: @Composable () -> Unit,
    ) {
        val folder = System.getenv("WAKFU_SKILL_POINTS_MOCKUPS")
        assumeTrue(folder != null, "set WAKFU_SKILL_POINTS_MOCKUPS to render")
        runSkikoComposeUiTest(size = Size(1400f, 1900f)) {
            setContent {
                WTheme {
                    CompositionLocalProvider(LocalLang provides LANG) {
                        Box(
                            modifier =
                                Modifier
                                    .testTag("shot")
                                    .background(WColor.bg)
                                    .padding(12.dp)
                                    .then(if (width != null) Modifier.width(width + 24.dp) else Modifier)
                        ) {
                            content()
                        }
                    }
                }
            }
            val output = File(folder!!).apply { mkdirs() }
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(output, "$name.png"))
        }
    }

    @Test
    fun `A - chips`() {
        render("a1-puces-vide", requestWidth) { ChipsCard(emptyMap()) }
        render("a2-puces-4-lignes", requestWidth) { ChipsCard(ISSUE) }
        render("a2b-puces-edition", requestWidth) { ChipsCard(ISSUE, editing = SkillLine.LUCK_CRITICAL_HIT) }
        render("a3-selecteur-ouvert", null) { SkillPicker(mapOf(SkillLine.LUCK_CRITICAL_HIT to 20), SkillBranch.LUCK) }
        render("a4-selecteur-branche-pleine", null) { SkillPicker(ISSUE, SkillBranch.AGILITY) }
    }

    @Test
    fun `B - pin from the result`() {
        render("b1-resultat-sans-verrou", statsWidth) { LockableSkillTree(RESULT, emptyMap()) }
        render("b2-resultat-3-verrous", statsWidth) { LockableSkillTree(RESULT, threeLocked) }
        render("b3-resultat-tout-verrouille", statsWidth) { LockableSkillTree(RESULT, RESULT) }
    }

    @Test
    fun `C - dedicated window`() {
        render("c1-resume", requestWidth) { SummaryCard(ISSUE) }
        render("c2-fenetre-ouverte", null) { SkillWindow(ISSUE) }
    }

    @Test
    fun `A+B in context`() {
        render("ab-contexte", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.width(requestWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Panneau de requête")
                    SearchModeCard(
                        selected = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        duration = "60",
                        stopAtMatch = false,
                        verifyOptimality = true,
                        onSelect = {},
                        onDurationChange = {},
                        onStopAtMatchChange = {},
                        onOpenSettings = {}
                    )
                    ChipsCard(threeLocked, locked = threeLocked.keys)
                }
                Column(modifier = Modifier.width(statsWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Panneau de stats (résultat)")
                    LockableSkillTree(RESULT, threeLocked)
                }
            }
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(text = text, style = WTypography.labelSmall.copy(color = WColor.muted))
    }
}
