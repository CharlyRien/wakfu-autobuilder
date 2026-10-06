package me.chosante.ui.i18n

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.classDisplayName
import me.chosante.ui.history.toHistoryEntry
import me.chosante.ui.state.UiState
import me.chosante.ui.state.libraryLabel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GuiLabelsTest {
    @Test
    fun `spell costs, debuffs and levels use the official abbreviations in both languages`() {
        assertThat(Tr.STAT_AP_AMOUNT.en.format(4)).isEqualTo("4 AP")
        assertThat(Tr.STAT_AP_AMOUNT.fr.format(4)).isEqualTo("4 PA")
        assertThat(Tr.STAT_WP_AMOUNT.en.format(1)).isEqualTo("1 WP")
        assertThat(Tr.STAT_WP_AMOUNT.fr.format(1)).isEqualTo("1 PW")
        assertThat(Tr.SPELL_DEBUFF_CAST.en.format("Debuff", 2, 50)).isEqualTo("↳ Debuff (2 AP, −50 res)")
        assertThat(Tr.SPELL_DEBUFF_CAST.fr.format("Malus", 2, 50)).isEqualTo("↳ Malus (2 PA, −50 rés.)")
        assertThat(Tr.SPELL_DEBUFF_RESISTANCE.en.format(60)).isEqualTo("→ 60% res after debuffs")
        assertThat(Tr.SPELL_DEBUFF_RESISTANCE.fr.format(60)).isEqualTo("→ 60 % rés. après les malus")
        assertThat(Tr.SPELL_ROTATION_TOTAL.en.format("1,234", 10, 12)).isEqualTo("1,234  (10/12 AP)")
        assertThat(Tr.SPELL_ROTATION_TOTAL.fr.format("1,234", 10, 12)).isEqualTo("1,234  (10/12 PA)")
        for (key in listOf(Tr.LEVEL_SHORT, Tr.LEVEL_PREFIX_SHORT, Tr.BOSS_LEVEL_SHORT)) {
            assertThat(key.en).isEqualTo("Lv")
            assertThat(key.fr).isEqualTo("Niv.")
        }
        assertThat(Characteristic.MOVEMENT_POINT.label(Lang.FR)).isEqualTo("PM")
        assertThat(Characteristic.BLOCK_PERCENTAGE.label(Lang.EN)).isEqualTo("Block %")
        assertThat(Characteristic.BLOCK_PERCENTAGE.label(Lang.FR)).isEqualTo("Parade %")
        assertThat(SublimationRarity.entries.map { it.label(Lang.EN) }).containsExactly("Epic", "Relic", "Normal")
        assertThat(SublimationRarity.entries.map { it.label(Lang.FR) }).containsExactly("Épique", "Relique", "Normale")
    }

    @Test
    fun `saved builds and library filters use the shared class names in the selected language`() {
        for ((clazz, en, fr) in listOf(
            Triple(CharacterClass.SACRIEUR, "Sacrier", "Sacrieur"),
            Triple(CharacterClass.ROUBLARD, "Rogue", "Roublard"),
            Triple(CharacterClass.ZOBAL, "Masqueraider", "Zobal"),
            Triple(CharacterClass.STEAMER, "Foggernaut", "Steamer")
        )) {
            val entry =
                UiState(clazz = clazz, build = BuildCombination(emptyList(), CharacterSkills(110)))
                    .toHistoryEntry("test", "Daily", null, 0, "test")!!
            assertThat(entry.classDisplayName(Lang.EN)).isEqualTo(en)
            assertThat(entry.classDisplayName(Lang.FR)).isEqualTo(fr)
            assertThat(clazz.libraryLabel(Lang.EN)).isEqualTo(en)
            assertThat(clazz.libraryLabel(Lang.FR)).isEqualTo(fr)
        }
    }
}
