package me.chosante.ui.spells

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.BuildSpellDamage
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SpellDamage
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Headless widget tests for the "Class spells" tab: renders the real [ClassSpellsPanel] composable (the
 * same code the result-region tab shows) and asserts each class damage spell surfaces a hit number — the
 * interactive path the PNG screenshot smoke-test can't exercise.
 */
@OptIn(ExperimentalTestApi::class)
class ClassSpellsPanelUiTest {
    @Test
    fun `shows a per-spell expected hit for the discovered build`() =
        runComposeUiTest {
            val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
            val build = BuildCombination(equipments = emptyList(), characterSkills = character.characterSkills)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(
                        ui =
                            UiState(
                                clazz = CharacterClass.CRA,
                                level = 110,
                                build = build,
                                achieved = mapOf(Characteristic.CRITICAL_HIT to 20)
                            )
                    )
                }
            }
            onNodeWithText("Class spells").assertExists()
            // Every damage-spell card carries the "expected hit" label, so at least one must render.
            assertThat(onAllNodesWithText("expected hit").fetchSemanticsNodes()).isNotEmpty()
            // The "& passives" tab promises passives: the class's passive pool renders too.
            onNodeWithText("Passives").assertExists()
        }

    @Test
    fun `falls back to base hits when no build is loaded yet`() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(ui = UiState(clazz = CharacterClass.CRA, level = 110))
                }
            }
            assertThat(onAllNodesWithText("base hit").fetchSemanticsNodes()).isNotEmpty()
        }

    @Test
    fun `surfaces the range band the damage credits`() =
        runComposeUiTest {
            val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
            val build = BuildCombination(equipments = emptyList(), characterSkills = character.characterSkills)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(
                        ui =
                            UiState(
                                clazz = CharacterClass.CRA,
                                level = 110,
                                build = build,
                                scenario = DamageScenario(rangeBand = RangeBand.MELEE)
                            )
                    )
                }
            }
            // The panel must say which secondary mastery it folds in, so the numbers are transparent.
            assertThat(onAllNodesWithText("melee mastery", substring = true).fetchSemanticsNodes()).isNotEmpty()
        }

    @Test
    fun `shows back-hit scenario variants under each spell`() =
        runComposeUiTest {
            val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
            val build = BuildCombination(equipments = emptyList(), characterSkills = character.characterSkills)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(ui = UiState(clazz = CharacterClass.CRA, level = 110, build = build))
                }
            }
            // Every damage-spell card adds a "back" variant line (×1.25 + rear), so many nodes carry "back" —
            // far more than the lone CRA spell whose description happens to mention it.
            assertThat(onAllNodesWithText("back", substring = true).fetchSemanticsNodes().size).isGreaterThanOrEqualTo(5)
        }

    @Test
    fun `warns when the build carries both distance and melee mastery`() =
        runComposeUiTest {
            val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
            val build = BuildCombination(equipments = emptyList(), characterSkills = character.characterSkills)
            setContent {
                CompositionLocalProvider(LocalLang provides Lang.EN) {
                    ClassSpellsPanel(
                        ui =
                            UiState(
                                clazz = CharacterClass.CRA,
                                level = 110,
                                build = build,
                                achieved = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.MASTERY_MELEE to 300)
                            )
                    )
                }
            }
            assertThat(onAllNodesWithText("a hit uses only one", substring = true).fetchSemanticsNodes()).isNotEmpty()
        }

    @Test
    fun `crediting the range band raises a distance build's spell damage`() {
        // Pure-function guard backing the UI fix: passing the build's range band must add its distance mastery,
        // so a distance build's expected hit rises versus the old rangeBand=null call that silently dropped it.
        val character = Character(CharacterClass.CRA, level = 110, minLevel = 0)
        val distanceItem =
            Equipment(
                equipmentId = 1,
                guiId = 1,
                level = 1,
                name = I18nText("Dist", "Dist", "Dist", "Dist"),
                rarity = Rarity.COMMON,
                itemType = ItemType.AMULET,
                characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 400)
            )
        val build = BuildCombination(equipments = listOf(distanceItem), characterSkills = character.characterSkills)
        val spell = SpellCatalog.damageSpells(CharacterClass.CRA).first { it.hasDamage }

        val withBand = BuildSpellDamage.expectedDamage(spell, build, character, rangeBand = SpellDamage.RangeBand.DISTANCE)
        val withoutBand = BuildSpellDamage.expectedDamage(spell, build, character, rangeBand = null)

        assertThat(withBand).isNotNull
        assertThat(withoutBand).isNotNull
        assertThat(withBand!!.expected).isGreaterThan(withoutBand!!.expected)
    }
}
