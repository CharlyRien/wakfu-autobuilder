package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Install-gated end-to-end check of the item-criteria pipeline (bytecode layout → Item table prefix decode → typing),
 * skipped in CI (no game install). It runs the same guards as the extraction (every record's id equals its index id,
 * every kind is a kind-enum constant) and checks that the regeneration reproduces the committed
 * `item-criteria.json` exactly — the CI-runnable locks on that file live in the autobuilder
 * (`EmbeddedItemCriteriaDataTest`).
 */
class ItemCriteriaDecodeTest {
    private val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")

    @Test
    fun `the Item table layout is found structurally and its EQUIP criteria reproduce the committed file`() {
        assumeTrue(File(install, "lib/wakfu-client.jar").isFile, "no local Wakfu install")
        val layout = ItemCriteria.layout(install)
        assertEquals(35, layout.tableId)
        assertEquals(setOf("USE", "USE_IN_FIGHT", "EQUIP", "DROP", "EXCHANGE", "CRAFT", "PICK_UP", "DELETE"), layout.kinds)
        assertEquals(11, layout.breedIds["SACRIER"])
        // The prefix: i32 id, i16 itemSetId, i32 gfxId, i32 femaleGfxId, i32, i16 level, String[] criteria.
        assertTrue(
            layout.prefixes.any { p ->
                p.map { it.type } ==
                    listOf(FieldType.I32, FieldType.I16, FieldType.I32, FieldType.I32, FieldType.I32, FieldType.I16, FieldType.Vec(FieldType.Str))
            },
            "unexpected Item record prefix(es): ${layout.prefixes.map { p -> p.map { it.type } }}"
        )

        val expressions = ItemCriteria.decodeEquipExpressions(install, layout)
        assertEquals("HasEquipmentId(26578)", expressions[26497])

        val resources = File(findRepositoryRoot(), "autobuilder/src/main/resources")
        val built = ItemCriteria.build(expressions, ItemCriteria.poolIds(File(resources, "equipments.json")), layout.breedIds)
        val committed =
            Json.decodeFromString(ListSerializer(ItemEquipCriterion.serializer()), File(resources, "item-criteria.json").readText())
        assertEquals(committed, built)
    }
}
