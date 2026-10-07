package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class SpellNamesReproductionTest {
    @Test
    fun `client names preserve every cast limit and passive effect`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "contents/bdata/${Tables.SPELL}.jar").isFile, "no local Wakfu install")
        val i18n = I18nBundle.load(install, setOf(3, 4))
        val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val casts = buildCastLimits(spells, i18n)
        val passives = buildPassives(spells, effects, ActionCatalog.fetch(WakfuData.VERSION), i18n)
        val resources = File(findRepositoryRoot(), "autobuilder/src/main/resources")

        fun withoutText(entry: JsonObject) = JsonObject(entry.filterKeys { it != "name" && it != "description" })
        for ((file, fresh) in listOf(
            "spell-cast-limits.json" to Json.encodeToJsonElement(casts).jsonArray,
            "spell-passives.json" to Json.encodeToJsonElement(passives).jsonArray
        )) {
            val old = Json.parseToJsonElement(File(resources, file).readText()).jsonArray
            assertEquals(old.map { withoutText(it.jsonObject) }, fresh.map { withoutText(it.jsonObject) }, file)
        }
        for (cast in casts) assertEquals(i18n.text(3, cast.spellId), cast.name)
        for (passive in passives) {
            assertEquals(i18n.text(3, passive.spellId), passive.name)
            assertEquals(i18n.text(4, passive.spellId), passive.description)
        }
        assertEquals("Météorite", casts.single { it.spellId == 6968 }.name?.fr)
        val glyph = passives.single { it.spellId == 6988 }
        assertEquals(i18n.requireText(3, 6988), glyph.name)
        assertEquals(i18n.requireText(4, 6988), glyph.description)
    }
}
