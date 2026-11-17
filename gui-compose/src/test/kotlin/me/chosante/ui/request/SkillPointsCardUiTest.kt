package me.chosante.ui.request

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.ImposedSkillPoints
import me.chosante.ui.stats.SkillTree
import me.chosante.ui.theme.WColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The request panel's skill-points card (issue #284): folding branches, the −/＋ editor, the reset link, and the skill tree's
 * "min N" marks. Set `WAKFU_SKILL_POINTS_SCREENSHOTS=<folder>` to also write the rendered card and tree as PNGs (off-screen).
 */
@OptIn(ExperimentalTestApi::class)
class SkillPointsCardUiTest {
    private val level = 230

    @Test
    fun `branches fold, the steppers edit the points within the limits and reset clears them`() =
        runSkikoComposeUiTest(size = Size(420f, 1400f)) {
            var imposed by mutableStateOf(ImposedSkillPoints().withLine(SkillLine.AGILITY_DODGE, 56, level))
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    Column(modifier = Modifier.width(340.dp).background(WColor.bg).padding(8.dp)) {
                        SkillPointsCard(
                            imposed = imposed,
                            level = level,
                            onChange = { line, value -> imposed = imposed.withLine(line, value, level) },
                            onReset = { imposed = imposed.copy(points = emptyMap()) }
                        )
                    }
                }
            }
            val agilityPool = CharacterSkills(level).agility.maxPointsToAssign
            // The branch holding imposed points starts open; its header reads imposed / pool.
            onNodeWithTag(skillPointsLineTag(SkillLine.AGILITY_DODGE)).assertIsDisplayed()
            onNodeWithText("56/$agilityPool").assertIsDisplayed()

            // ＋ on dodge reaches the pool, then the other agility lines can take nothing more.
            onNode(hasTextExactly("＋") and hasAnyAncestor(hasTestTag(skillPointsLineTag(SkillLine.AGILITY_DODGE)))).performClick()
            assertEquals(agilityPool, imposed[SkillLine.AGILITY_DODGE])
            onNode(hasTextExactly("＋") and hasAnyAncestor(hasTestTag(skillPointsLineTag(SkillLine.AGILITY_LOCK)))).performClick()
            assertEquals(0, imposed[SkillLine.AGILITY_LOCK])

            // A closed branch opens on click and its lines take points.
            onNodeWithTag(skillPointsBranchTag(SkillBranch.LUCK)).performClick()
            onNode(hasTextExactly("＋") and hasAnyAncestor(hasTestTag(skillPointsLineTag(SkillLine.LUCK_BLOCK)))).performClick()
            assertEquals(1, imposed[SkillLine.LUCK_BLOCK])
            onNode(hasTextExactly("−") and hasAnyAncestor(hasTestTag(skillPointsLineTag(SkillLine.LUCK_BLOCK)))).performClick()
            assertEquals(0, imposed[SkillLine.LUCK_BLOCK])

            onNodeWithText(Tr.SKILL_POINTS_RESET.value(Lang.EN)).performClick()
            assertEquals(true, imposed.isEmpty)
        }

    @Test
    fun `the card and the skill tree render with imposed points`() {
        for (lang in listOf(Lang.FR, Lang.EN)) {
            runSkikoComposeUiTest(size = Size(760f, 1500f)) {
                val imposed =
                    ImposedSkillPoints()
                        .withLine(SkillLine.AGILITY_DODGE, 57, level)
                        .withLine(SkillLine.LUCK_CRITICAL_HIT, 20, level)
                        .withLine(SkillLine.LUCK_MASTERY_CRITICAL, 37, level)
                        .withLine(SkillLine.MAJOR_ACTION_POINT, 1, level)
                val skills =
                    CharacterSkills(level).apply {
                        agility.dodge.setPointAssigned(57)
                        luck.criticalHit.setPointAssigned(20)
                        luck.masteryCritical.setPointAssigned(37)
                        major.actionPoint.setPointAssigned(1)
                        major.movementPointAndMasteryElementary.setPointAssigned(1)
                    }
                setContent {
                    CompositionLocalProvider(LocalLang provides lang) {
                        Row(
                            modifier = Modifier.background(WColor.bg).padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.width(330.dp)) {
                                SkillPointsCard(imposed = imposed, level = level, onChange = { _, _ -> }, onReset = {})
                            }
                            Column(modifier = Modifier.width(370.dp)) {
                                SkillTree(skills = skills, showLeftoverWarning = false, imposed = imposed.forEngine())
                            }
                        }
                    }
                }
                // The tree marks the four imposed lines.
                assertEquals(4, onAllNodesWithText(Tr.SKILL_POINT_IMPOSED_MIN.value(lang).substringBefore('%'), substring = true).fetchSemanticsNodes().size)
                System.getenv("WAKFU_SKILL_POINTS_SCREENSHOTS")?.let { folder ->
                    val output = File(folder).apply { mkdirs() }
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(output, "skill-points-${lang.name.lowercase()}.png"))
                }
            }
        }
    }
}
