package me.chosante.ui.spells

import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.common.CharacterClass
import me.chosante.common.SpellElement
import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DisplayOnlySpellElementTest {
    @Test
    fun `unsupported presentation stays outside the four-element domain and translates in every locale`() {
        val arrow = SpellCatalog.spells.single { it.id == 5594 }
        assertThat(arrow.displayOnlyElement()).isEqualTo(DisplayOnlySpellElement.LIGHT)
        assertThat(arrow.copy(missingFields = listOf("element(STASIS)")).displayOnlyElement()).isEqualTo(DisplayOnlySpellElement.STASIS)
        assertThat(arrow.copy(element = SpellElement.FIRE).displayOnlyElement()).isNull()
        assertThat(SpellElement.entries).hasSize(4)
        assertThat(SpellCatalog.playableElements(CharacterClass.HUPPERMAGE)).allMatch { it in SpellElement.entries }
        for (element in DisplayOnlySpellElement.entries) {
            assertThat(javaClass.classLoader.getResource(element.iconPath)).isNotNull()
            for (lang in Lang.entries) assertThat(element.label.value(lang)).isNotBlank()
        }
    }
}
