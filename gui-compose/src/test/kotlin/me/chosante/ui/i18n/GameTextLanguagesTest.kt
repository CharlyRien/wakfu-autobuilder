package me.chosante.ui.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.ui.components.localized
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameTextLanguagesTest {
    @Test
    fun `passive panel text selects every client language`() {
        val glyph = PassiveCatalog.passives.single { it.spellId == 6988 }
        val expected = listOf("Glyphe augmenté", "Increased Glyph", "Glifo Aumentado", "Glifo Aumentado")
        val languages = listOf(Lang.FR, Lang.EN, Lang.ES, Lang.PT)
        for ((lang, name) in languages.zip(expected)) {
            assertThat(glyph.name?.localized(lang)).isEqualTo(name)
            assertThat(glyph.description?.localized(lang)).isNotBlank()
        }
        assertThat(languages.map { glyph.description?.localized(it) }.distinct()).hasSize(4)
    }

    @Test
    fun `every language selects its own game text and missing text falls back to English`() {
        val name = I18nText(fr = "Nom FR", en = "Name EN", es = "Nombre ES", pt = "Nome PT")
        for (lang in Lang.entries) {
            assertThat(name.localized(lang)).endsWith(lang.label)
        }
        assertThat(name.copy(es = "").localized(Lang.ES)).isEqualTo(name.en)
        assertThat(name.copy(pt = "").localized(Lang.PT)).isEqualTo(name.en)
    }

    @Test
    fun `mastery and lock labels use the official rune names in Spanish and Portuguese`() {
        val text = requireNotNull(javaClass.getResourceAsStream("/runes.json")).bufferedReader().use { it.readText() }
        val runes =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("runes")
                .jsonArray
        for (lang in listOf(Lang.ES, Lang.PT)) {
            for (rune in runes) {
                val stat =
                    Characteristic.valueOf(
                        rune.jsonObject
                            .getValue("characteristic")
                            .jsonPrimitive.content
                    )
                if (stat.name.startsWith("MASTERY_") || stat == Characteristic.LOCK || stat == Characteristic.DODGE) {
                    assertThat(stat.label(lang))
                        .describedAs("$lang $stat")
                        .isEqualTo(
                            rune.jsonObject
                                .getValue("name")
                                .jsonObject
                                .getValue(lang.resourceSuffix)
                                .jsonPrimitive.content
                        )
                }
            }
        }
        assertThat(Characteristic.BLOCK_PERCENTAGE.label(Lang.PT)).isEqualTo("Parada %")
        assertThat(skillLabel("HP", Lang.PT)).isEqualTo("PV")
        assertThat(skillLabel("Dodge and lock", Lang.PT)).isEqualTo("Esquiva e Bloqueio")
    }
}
