package me.chosante.ui.request

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import me.chosante.ui.stats.SKILL_IMPOSE_ALL_TAG
import me.chosante.ui.stats.SkillTree
import me.chosante.ui.stats.skillLockTag
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The imposed skill points in the GUI (issue #284): the request card's chips and picker, the result tree's padlocks. Set
 * `WAKFU_SKILL_POINTS_SCREENSHOTS=<folder>` to also write the French renders (off-screen) of the wired components.
 */
@OptIn(ExperimentalTestApi::class)
class SkillPointsCardUiTest {
    private val level = 230
    private val agilityPool = CharacterSkills(level).agility.maxPointsToAssign

    private val issue =
        ImposedSkillPoints()
            .withLine(SkillLine.AGILITY_DODGE, 57, level)
            .withLine(SkillLine.LUCK_CRITICAL_HIT, 20, level)
            .withLine(SkillLine.LUCK_MASTERY_CRITICAL, 37, level)
            .withLine(SkillLine.MAJOR_ACTION_POINT, 1, level)

    private val result =
        CharacterSkills(level).apply {
            intelligence.hpPercentage.setPointAssigned(58)
            strength.masteryDistance.setPointAssigned(40)
            strength.masteryElementary.setPointAssigned(17)
            agility.dodge.setPointAssigned(57)
            luck.criticalHit.setPointAssigned(20)
            luck.masteryCritical.setPointAssigned(30)
            major.actionPoint.setPointAssigned(1)
            major.movementPointAndMasteryElementary.setPointAssigned(1)
            major.rangeAndMasteryElementary.setPointAssigned(1)
        }

    private fun shot(name: String) = System.getenv("WAKFU_SKILL_POINTS_SCREENSHOTS")?.let { File(File(it).apply { mkdirs() }, "$name.png") }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        WTheme { CompositionLocalProvider(LocalLang provides Lang.FR) { content() } }
    }

    @Test
    fun `the card shows only the imposed lines as chips, edits them and adds through the picker`() =
        runSkikoComposeUiTest(size = Size(900f, 1400f)) {
            var imposed by mutableStateOf(ImposedSkillPoints())
            var pickerOpen by mutableStateOf(false)
            setContent {
                Themed {
                    Row(modifier = Modifier.background(WColor.bg).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.width(320.dp)) {
                            SkillPointsCard(
                                imposed = imposed,
                                level = level,
                                onChange = { line, value -> imposed = imposed.withLine(line, value, level) },
                                onAdd = { pickerOpen = true },
                                onReset = { imposed = ImposedSkillPoints() }
                            )
                        }
                        if (pickerOpen) {
                            SkillPickerContent(
                                imposed = imposed,
                                level = level,
                                onChange = { line, value -> imposed = imposed.withLine(line, value, level) },
                                onDone = { pickerOpen = false }
                            )
                        }
                    }
                }
            }
            onNodeWithText(Tr.SKILL_POINTS_EMPTY.value(Lang.FR)).assertIsDisplayed()

            // The picker: Agility tab, "All here" on dodge takes the whole pool; the tab then has nothing left.
            onNodeWithTag(SKILL_POINTS_ADD_TAG).performClick()
            onNodeWithTag(skillPickerTabTag(SkillBranch.AGILITY)).performClick()
            onNode(hasText(Tr.SKILL_PICKER_ALL_HERE.value(Lang.FR).format(agilityPool)) and hasAnyAncestor(hasTestTag(skillPickerLineTag(SkillLine.AGILITY_DODGE)))).performClick()
            assertEquals(agilityPool, imposed[SkillLine.AGILITY_DODGE])
            onNodeWithText(Tr.SKILL_PICKER_FULL.value(Lang.FR).format("Agilité", agilityPool, agilityPool)).assertIsDisplayed()
            // Luck: +1 on critical hit, then the chip's editor sets it to its cap with "Max".
            onNodeWithTag(skillPickerTabTag(SkillBranch.LUCK)).performClick()
            onNode(hasText("+1") and hasAnyAncestor(hasTestTag(skillPickerLineTag(SkillLine.LUCK_CRITICAL_HIT)))).performClick()
            assertEquals(1, imposed[SkillLine.LUCK_CRITICAL_HIT])
            onNodeWithText(Tr.SKILL_PICKER_DONE.value(Lang.FR)).performClick()

            onNodeWithTag(skillChipTag(SkillLine.LUCK_CRITICAL_HIT)).performClick()
            onNodeWithText(Tr.SKILL_EDITOR_MAX.value(Lang.FR)).performClick()
            assertEquals(20, imposed[SkillLine.LUCK_CRITICAL_HIT])
            onNodeWithText(Tr.SKILL_POINTS_LEFT.value(Lang.FR).format("Agilité 0 · Chance ${CharacterSkills(level).luck.maxPointsToAssign - 20}")).assertIsDisplayed()

            // × on a chip frees the line; reset frees everything.
            onNode(hasText("×") and hasAnyAncestor(hasTestTag(skillChipTag(SkillLine.LUCK_CRITICAL_HIT)))).performClick()
            assertEquals(0, imposed[SkillLine.LUCK_CRITICAL_HIT])
            onNodeWithText(Tr.SKILL_POINTS_RESET.value(Lang.FR)).performClick()
            assertTrue(imposed.isEmpty)
        }

    @Test
    fun `a padlock imposes the line at its value and the link imposes or frees the allocation`() =
        runSkikoComposeUiTest(size = Size(500f, 1500f)) {
            var imposed by mutableStateOf(ImposedSkillPoints())
            setContent {
                Themed {
                    Box(modifier = Modifier.width(360.dp)) {
                        SkillTree(
                            skills = result,
                            showLeftoverWarning = true,
                            imposed = imposed,
                            onToggleLock = { line ->
                                imposed = if (line in imposed) imposed.withLine(line, 0, level) else imposed.withLine(line, line.of(result).pointsAssigned, level)
                            },
                            onToggleImposeAll = {
                                val shown = ImposedSkillPoints.of(result)
                                imposed = if (imposed == shown) ImposedSkillPoints() else shown
                            }
                        )
                    }
                }
            }
            onNodeWithTag(skillLockTag(SkillLine.AGILITY_DODGE)).performClick()
            assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57), imposed.points)
            onNodeWithText(Tr.SKILL_LOCK_COUNT.value(Lang.FR).format(1)).assertIsDisplayed()
            onNodeWithTag(skillLockTag(SkillLine.AGILITY_DODGE)).performClick()
            assertTrue(imposed.isEmpty)
            // No padlock where nothing is spent (block).
            assertEquals(0, onAllNodes(hasTestTag(skillLockTag(SkillLine.LUCK_BLOCK))).fetchSemanticsNodes().size)
            // The unplaced points are counted (Luck spends 50 of its pool).
            val left = SkillBranch.entries.sumOf { it.of(result).maxPointsToAssign - it.of(result).pointsAssigned() }
            onNodeWithText(Tr.SKILL_LEFTOVER_WARNING.value(Lang.FR).format(left)).assertIsDisplayed()

            onNodeWithTag(SKILL_IMPOSE_ALL_TAG).performClick()
            assertEquals(ImposedSkillPoints.of(result), imposed)
            onNodeWithText(Tr.SKILL_LOCK_RELEASE_ALL.value(Lang.FR)).performClick()
            assertTrue(imposed.isEmpty)
        }

    @Test
    fun `French renders of the wired card, picker and tree`() {
        val card = shot("final-carte-4-lignes") ?: return
        runSkikoComposeUiTest(size = Size(1400f, 1700f)) {
            setContent {
                Themed {
                    Row(
                        modifier = Modifier.testTag("shot").background(WColor.bg).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.width(320.dp).testTag("card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SkillPointsCard(imposed = ImposedSkillPoints(), level = level, onChange = { _, _ -> }, onAdd = {}, onReset = {})
                            SkillPointsCard(imposed = issue, level = level, onChange = { _, _ -> }, onAdd = {}, onReset = {})
                        }
                        Box(modifier = Modifier.testTag("picker")) {
                            SkillPickerContent(imposed = issue, level = level, onChange = { _, _ -> }, onDone = {})
                        }
                        Box(modifier = Modifier.width(360.dp).testTag("tree")) {
                            SkillTree(
                                skills = result,
                                showLeftoverWarning = true,
                                imposed = ImposedSkillPoints().withLine(SkillLine.AGILITY_DODGE, 57, level).withLine(SkillLine.LUCK_CRITICAL_HIT, 20, level),
                                onToggleLock = {},
                                onToggleImposeAll = {}
                            )
                        }
                    }
                }
            }
            ImageIO.write(onNodeWithTag("card").captureToImage().toAwtImage(), "png", card)
            ImageIO.write(onNodeWithTag("picker").captureToImage().toAwtImage(), "png", shot("final-selecteur")!!)
            ImageIO.write(onNodeWithTag("tree").captureToImage().toAwtImage(), "png", shot("final-arbre-cadenas")!!)
            ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", shot("final-contexte")!!)
        }
    }
}
