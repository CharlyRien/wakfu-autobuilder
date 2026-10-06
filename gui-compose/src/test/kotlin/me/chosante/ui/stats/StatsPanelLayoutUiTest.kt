package me.chosante.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.ScenarioDamage
import me.chosante.autobuilder.domain.SpellCast
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Monster
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.Spell
import me.chosante.common.SpellElement
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.label
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow
import me.chosante.ui.theme.WColor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

/** Rich result fixtures include every stats card, large values, long labels, a rotation, subs and passives. */
@OptIn(ExperimentalTestApi::class)
class StatsPanelLayoutUiTest {
    private fun text(
        en: String,
        fr: String = en,
    ) = I18nText(en = en, fr = fr, es = "", pt = "")

    private fun result(
        lang: Lang,
        mode: ScoreComputationMode,
    ): UiState {
        val gear = Equipment(1, 1, 110, text("Cape"), Rarity.LEGENDARY, ItemType.CAPE, emptyMap())
        val skills = CharacterSkills(110)
        val sub = Sublimation(stateId = 1, name = text("Ambition"), rarity = SublimationRarity.EPIC, kind = SublimationKind.FLAT)
        val passive = Passive(1, name = text("Protection"), clazz = "CRA", flatBuildStats = mapOf("BLOCK_PERCENTAGE" to 10.0, "HP" to 150.0))
        val build = BuildCombination(listOf(gear), skills, sublimations = mapOf(gear to listOf(sub)), passives = listOf(passive))
        val achieved =
            mapOf(
                Characteristic.ACTION_POINT to 12,
                Characteristic.MOVEMENT_POINT to 6,
                Characteristic.WAKFU_POINT to 6,
                Characteristic.HP to 12345,
                Characteristic.RANGE to 3,
                Characteristic.CRITICAL_HIT to 45,
                Characteristic.MASTERY_DISTANCE to 1210,
                Characteristic.MASTERY_ELEMENTARY_FIRE to 1000,
                Characteristic.MASTERY_ELEMENTARY_WATER to 2000,
                Characteristic.MASTERY_ELEMENTARY_WIND to 9999,
                Characteristic.MASTERY_ELEMENTARY_EARTH to 1234,
                Characteristic.RESISTANCE_ELEMENTARY_FIRE to 400,
                Characteristic.RESISTANCE_ELEMENTARY_WIND to 500,
                Characteristic.GIVEN_ARMOR_PERCENTAGE to 30,
                Characteristic.RECEIVED_ARMOR_PERCENTAGE to 20,
                Characteristic.DODGE to 1234
            )
        val targets =
            listOf(Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT, Characteristic.HP, Characteristic.MASTERY_DISTANCE, Characteristic.RESISTANCE_ELEMENTARY_FIRE)
                .map { statDefFor(it)!!.toRow((achieved[it]!! - 1).toString()) }
        val spell = Spell(1, CharacterClass.CRA, text("Explosive Arrow", "Flèche explosive"), element = SpellElement.FIRE, apCost = 4, targetResistanceReductionFlat = 50)
        val cast = SpellCast(spell, 3, 4, 1234.0)
        return UiState(
            lang = lang,
            mode = mode,
            phase = Phase.Done,
            build = build,
            achieved = achieved,
            targets = targets,
            match = 12345.toBigDecimal(),
            spellRotation = SpellRotation(SpellElement.FIRE, 12, 12, listOf(cast), 12345.0, listOf(cast), 60),
            selectedBoss = Monster(1, text("Boss", "Boss"), 110, 100000, rank = 1, fireResistance = 400, waterResistance = 400, earthResistance = 400, airResistance = 400),
            scenarioDamages = listOf(ScenarioDamage(Orientation.FACE, false, 12345.0), ScenarioDamage(Orientation.BACK, false, 15431.0))
        )
    }

    @Test
    fun `full results fit the minimum default and maximum widths in both languages and modes`() {
        val problems = mutableListOf<String>()
        for (lang in Lang.entries) {
            for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
                runSkikoComposeUiTest(size = Size(500f, 6500f)) {
                    val width = mutableStateOf(300)
                    setContent {
                        CompositionLocalProvider(LocalLang provides lang) {
                            Box(Modifier.width(width.value.dp).fillMaxSize().background(WColor.bg)) {
                                StatsPanel(result(lang, mode), {}, {}, {}, {}, {})
                            }
                        }
                    }
                    val positionsByWidth = mutableMapOf<Int, List<String>>()
                    for (w in listOf(300, 360, 431, 432, 460, 431, 432, 300, 460)) {
                        runOnIdle { width.value = w }
                        waitForIdle()
                        val context = "$lang / $mode / $w dp"
                        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
                        val ap = onNodeWithText(Characteristic.ACTION_POINT.label(lang)).fetchSemanticsNode().boundsInRoot
                        val mp = onNodeWithText(Characteristic.MOVEMENT_POINT.label(lang)).fetchSemanticsNode().boundsInRoot
                        if (w >= 432) {
                            assertThat(mp.left).describedAs("$context: second stat is in the second column").isGreaterThan(ap.right)
                            assertThat(mp.top).isEqualTo(ap.top)
                        } else {
                            assertThat(mp.left).isEqualTo(ap.left)
                            assertThat(mp.top).isGreaterThan(ap.bottom)
                        }
                        val positions = nodes.map { "${it.config[SemanticsProperties.Text]}:${it.boundsInRoot}" }
                        positionsByWidth[w]?.let { assertThat(positions).describedAs("$context: resizing back has the same layout").isEqualTo(it) }
                        positionsByWidth[w] = positions
                        for (node in nodes) {
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.config
                                .getOrNull(SemanticsActions.GetTextLayoutResult)
                                ?.action
                                ?.invoke(layouts)
                            for (layout in layouts) {
                                val text = layout.layoutInput.text.text
                                val room = layout.layoutInput.constraints.maxWidth
                                if (text.matches(Regex("[+−~0-9,/% .∞]+")) && text.isNotBlank() && layout.lineCount != 1) {
                                    problems += "$context: numeric value '$text' wraps"
                                }
                                for (line in 0 until layout.lineCount - 1) {
                                    val end = layout.getLineEnd(line)
                                    if (end > 0 && end < text.length && text[end - 1].isLetter() && text[end].isLetter()) {
                                        problems += "$context: '$text' breaks inside a word"
                                    }
                                }
                                if (layout.multiParagraph.didExceedMaxLines ||
                                    (0 until layout.lineCount).any { layout.isLineEllipsized(it) || layout.getLineRight(it) - layout.getLineLeft(it) > room + 0.5f }
                                ) {
                                    problems += "$context: '$text' is truncated or overflows"
                                }
                            }
                        }
                        for ((i, first) in nodes.withIndex()) {
                            val firstBox = first.boundsInRoot
                            if (firstBox.isEmpty) continue
                            for (second in nodes.drop(i + 1)) {
                                val overlap = firstBox.intersect(second.boundsInRoot)
                                if (overlap.width > 0.5f && overlap.height > 0.5f) {
                                    problems += "$context: text overlaps: ${first.config[SemanticsProperties.Text]} / ${second.config[SemanticsProperties.Text]}"
                                }
                            }
                        }
                        System.getenv("WAKFU_STATS_RENDER_DIR")?.let { directory ->
                            val file = File(directory, "stats-$lang-${mode.name}-$w.png")
                            file.parentFile.mkdirs()
                            val capture = onRoot().captureToImage().toAwtImage()
                            ImageIO.write(capture, "png", file)
                            ImageIO.write(capture.getSubimage(0, 0, capture.width, 1600), "png", File(directory, "top-${file.name}"))
                        }
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }
}
