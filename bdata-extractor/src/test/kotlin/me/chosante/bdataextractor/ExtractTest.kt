package me.chosante.bdataextractor

import kotlinx.serialization.json.JsonPrimitive
import me.chosante.common.Characteristic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the install-independent helpers (the full decode is verified at extraction time by
 * the per-record size guard + the semantic diff against the committed artifacts — neither can run in CI,
 * which has no game install).
 */
class ExtractTest {
    @Test
    fun `num emits integers for integral values and decimals otherwise`() {
        assertEquals(JsonPrimitive(50L), num(50.0))
        assertEquals(JsonPrimitive(-1L), num(-1.0))
        assertEquals(JsonPrimitive(0L), num(0.0))
        assertEquals(JsonPrimitive(0.2), num(0.2))
        assertEquals(JsonPrimitive(2.1), num(2.1))
    }

    @Test
    fun `unescapeHtml resolves the named entities present in the catalogue`() {
        assertEquals("Immunité", unescapeHtml("Immunit&eacute;"))
        assertEquals("Éclair", unescapeHtml("&Eacute;clair"))
        assertEquals("Présage", unescapeHtml("Pr&eacute;sage"))
        assertEquals("Flèche ardente", unescapeHtml("Flèche ardente")) // already-unescaped passes through
    }

    @Test
    fun `ActionCatalog parses charac tokens, sign, damage and random-element actions`() {
        val json =
            """
            [
              {"definition":{"id":80,"effect":"Gain : Résistance Élémentaire"},"description":{"fr":"[#charac RES_IN_PERCENT] [#1] Résistance Élémentaire"}},
              {"definition":{"id":90,"effect":"Perte : Résistance Élémentaire"},"description":{"fr":"[#charac RES_IN_PERCENT] -[#1] Résistance élémentaire"}},
              {"definition":{"id":41,"effect":"Boost : PM"},"description":{"fr":"[#charac MP] [#1] PM"}},
              {"definition":{"id":1,"effect":"Dommage : Neutre"},"description":{"fr":"Dommage [el0] : [#1]"}},
              {"definition":{"id":24,"effect":"Soin : Neutre (Vol de vie)"},"description":{"fr":"Soin [el0] : [#1]"}},
              {"definition":{"id":56,"effect":"Deboost : PA"},"description":{"fr":"[#charac AP] -[#1] PA max"}},
              {"definition":{"id":39,"effect":"Gain : charac passée en paramètre"},"description":{"fr":"[#1] [#3]"}},
              {"definition":{"id":40,"effect":"Perte : charac passée en paramètre"},"description":{"fr":"-[#1] [#3]"}},
              {"definition":{"id":1068,"effect":"Gain : Maîtrise Élémentaire dans un élément"},"description":{"fr":"[#charac DMG_IN_PERCENT] ..."}},
              {"definition":{"id":1069,"effect":"Gain : Résistance Élémentaire dans un élément"},"description":{"fr":"[#charac RES_IN_PERCENT] ..."}},
              {"definition":{"id":330,"effect":"REG : execute le groupe d'effets"},"description":{"fr":""}}
            ]
            """.trimIndent()
        val catalog = ActionCatalog.parse(json)

        assertEquals(ActionKind.Stat(Characteristic.RESISTANCE_ELEMENTARY, 1), catalog.kind(80))
        assertEquals(ActionKind.Stat(Characteristic.RESISTANCE_ELEMENTARY, -1), catalog.kind(90))
        assertEquals(ActionKind.Stat(Characteristic.MOVEMENT_POINT, 1), catalog.kind(41))
        assertEquals(ActionKind.Stat(Characteristic.ACTION_POINT, -1), catalog.kind(56)) // Deboost -> sign -1
        assertEquals(ActionKind.Damage, catalog.kind(1))
        assertEquals(ActionKind.Heal, catalog.kind(24)) // Soin -> Heal
        assertEquals(ActionKind.DynamicStat(1), catalog.kind(39)) // Gain : param-selected charac
        assertEquals(ActionKind.DynamicStat(-1), catalog.kind(40)) // Perte : param-selected charac
        assertEquals(ActionKind.RandomElement(mastery = true), catalog.kind(1068))
        assertEquals(ActionKind.RandomElement(mastery = false), catalog.kind(1069))
        // 330 is a structural REG action with no [#charac] token -> not a stat/damage kind
        assertEquals(null, catalog.kind(330))
        assertEquals(setOf(80, 90, 41, 56, 1, 24, 39, 40, 1068, 1069, 330), catalog.allActionIds)
    }

    @Test
    fun `unescapeHtml decodes numeric entities too`() {
        assertEquals("é", unescapeHtml("&#233;"))
        assertEquals("ê", unescapeHtml("&#xEA;"))
        assertEquals("Œuf", unescapeHtml("&OElig;uf")) // named entity still works alongside numeric
    }
}
