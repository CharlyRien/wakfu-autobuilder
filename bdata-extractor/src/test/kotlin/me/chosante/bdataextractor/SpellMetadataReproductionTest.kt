package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.CharacterClass
import me.chosante.common.I18nText
import me.chosante.common.Spell
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class SpellMetadataReproductionTest {
    @Test
    fun `client metadata reproduces the whole committed catalog without changing encyclopedia fields`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "contents/bdata/66.jar").isFile, "no local Wakfu install")
        val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
        val names = I18nBundle.load(install, setOf(3))
        val resourceNames = CharacIdCatalog.load(install)
        val old = LENIENT_JSON.decodeFromString(ListSerializer(Spell.serializer()), File(findRepositoryRoot(), "autobuilder/src/main/resources/spells.json").readText())
        val fresh = buildSpellMetadata(spells, names, old, resourceNames::scriptNameFor)
        assertEquals(old, fresh, "every committed field, including encyclopedia damage, must reproduce")
        val ids = spells.records.map { it["id"] as Int }.toSet()
        assertEquals(setOf(5150, 5089, 5123), old.filter { it.id !in ids }.map { it.id }.toSet())
        for (spell in fresh.filter { it.id in ids }) {
            assertEquals(names.requireText(3, spell.id).es, spell.name.es)
            assertEquals(names.requireText(3, spell.id).pt, spell.name.pt)
        }
        val arrow = fresh.single { it.id == 5594 }
        assertEquals(6, arrow.apCost)
        assertEquals(2 to 5, arrow.rangeMin to arrow.rangeMax)
        assertEquals(200, arrow.resourceCosts.single().amount)
        assertEquals("HUPPERMAGE_RESOURCE", arrow.resourceCosts.single().scriptName)
        val wall = fresh.single { it.id == 5576 }
        assertEquals(0, wall.apCost)
        assertEquals(1 to 3, wall.rangeMin to wall.rangeMax)
        assertEquals(150, wall.resourceCosts.single().amount)
        assertEquals(2, fresh.single { it.id == 7077 }.mpCost)
        assertEquals(2, fresh.single { it.id == 4774 }.wpCost)
        assertEquals(
            2,
            fresh
                .single { it.id == 6918 }
                .resourceCosts
                .single()
                .amount,
            "positive SP is a cost"
        )
        assertEquals(
            "SP",
            fresh
                .single { it.id == 6918 }
                .resourceCosts
                .single()
                .scriptName
        )
        assertEquals(
            1,
            fresh
                .single { it.id == 6920 }
                .resourceCosts
                .single()
                .amount
        )
        assertEquals(
            3,
            fresh
                .single { it.id == 6922 }
                .resourceCosts
                .single()
                .amount
        )
    }

    @Test
    fun `missing client records preserve unknown values and untouched fields survive a merge`() {
        val name = I18nText("Sort", "Spell", "Spell", "Spell")
        val original = Spell(42, CharacterClass.HUPPERMAGE, name, baseDamage = 100, missingFields = listOf("apCost", "rangeMin", "element(LIGHT)"))
        assertEquals(listOf(original), buildSpellMetadata(Table(emptyList(), emptyList()), I18nBundle(emptyMap()), listOf(original)) { null })
        val record =
            mapOf<String, Any?>(
                "id" to 42,
                "max_level" to 10,
                "pa_base" to 2f,
                "pa_inc" to 0.1f,
                "pm_base" to 0f,
                "pm_inc" to 0f,
                "pw_base" to 1f,
                "pw_inc" to 0f,
                "range_min_base" to 1f,
                "range_min_level_increment" to 0f,
                "range_max_base" to 3f,
                "range_max_inc" to 0f,
                "base_cast_parameters" to mapOf(111 to mapOf("base" to -20, "increment" to -2f), 123 to mapOf("base" to 3, "increment" to 0f))
            )
        val i18n = I18nBundle(listOf("fr", "en", "es", "pt").associateWith { mapOf("content.3.42" to if (it == "en") "Spell" else "Client $it") })
        val result = buildSpellMetadata(Table(emptyList(), listOf(record)), i18n, listOf(original)) { if (it == 111) "HUPPERMAGE_RESOURCE" else "SP" }.single()
        assertEquals(3, result.apCost)
        assertEquals(40, result.resourceCosts.single { it.scriptName == "HUPPERMAGE_RESOURCE" }.amount)
        assertEquals(3, result.resourceCosts.single { it.scriptName == "SP" }.amount)
        assertEquals("Sort", result.name.fr, "unreviewed French name stays on the encyclopedia")
        assertEquals(100, result.baseDamage)
        assertEquals(listOf("element(LIGHT)"), result.missingFields)
    }
}
