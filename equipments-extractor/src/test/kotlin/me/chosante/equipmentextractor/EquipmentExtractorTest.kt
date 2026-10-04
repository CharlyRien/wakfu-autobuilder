package me.chosante.equipmentextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.equipmentextractor.dataretriever.WakfuData
import me.chosante.equipmentextractor.dataretriever.dtos.Effect
import me.chosante.equipmentextractor.dataretriever.dtos.ItemSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import me.chosante.equipmentextractor.dataretriever.dtos.ItemType as CdnItemType

/**
 * The equip-effect decoding of [extractData] on the Dofus Pourpre's CDN record (1.93.1.62 `items.json`, descriptions
 * trimmed): its action 999 ("X% of the level as <stat>", no description in actions.json) used to be skipped silently, so
 * the item lost its Elemental Mastery equal to the wearer's level.
 */
class EquipmentExtractorTest {
    private val cdnJson = Json { ignoreUnknownKeys = true }

    /** An `actions.json` entry (the extractor reads the French description; null = none, like 400 and 999 on the CDN). */
    private fun action(
        id: Int,
        effect: String,
        frenchDescription: String?,
    ): String {
        val description = frenchDescription?.let { """, "description": {"fr": "$it", "en": "$it", "es": "$it", "pt": "$it"}""" } ?: ""
        return """{"definition": {"id": $id, "effect": "$effect"}$description}"""
    }

    /** The CDN's `actions.json` entries the record uses. */
    private val actions =
        listOf(
            action(31, "Boost : PA", "[#charac AP] [#1] PA"),
            action(120, "Gain : Maîtrise Élémentaire", "[#charac DMG_IN_PERCENT] [#1] Maîtrise Élémentaire"),
            action(150, "Gain : Coup Critique (%)", "[#charac FEROCITY] [#1] % Coup critique"),
            action(304, "State : Applique un état", "[#1]"),
            action(400, "NullEffect : Effet Vide", null),
            action(999, "REG : niveau des enfants fonction du niveau du caster/cible de cet effet", null)
        ).joinToString(",", prefix = "[", postfix = "]")

    private val emblemType =
        """
        [{"definition": {"id": 646, "equipmentPositions": ["ACCESSORY"], "equipmentDisabledPositions": [], "isRecyclable": false,
          "isVisibleInAnimation": false}, "title": {"fr": "Emblème{[~1]?s:}", "en": "Emblem{[~1]?s:}", "es": "Emblema{[~1]?s:}", "pt": "Emblema{[~1]?s:}"}}]
        """.trimIndent()

    private fun effect(
        id: Int,
        actionId: Int,
        params: String,
        subEffects: String? = null,
    ) = """{"effect": {"definition": {"id": $id, "actionId": $actionId, "areaShape": 32767, "areaSize": [], "params": $params}}""" +
        (subEffects?.let { """, "subEffects": $it""" } ?: "") + "}"

    private val stateEffect = effect(417985, 304, "[9364.0, 0.0, 1.0, 0.0, -1.0, 0.0]")
    private val apEffect = effect(417986, 31, "[1.0, 0.0, 1.0, 0.0, 0.0, 0.0]")
    private val critEffect = effect(417991, 150, "[3.0, 0.0]")
    private val masteryChild = effect(417993, 120, "[0.0, 1.0]")
    private val levelPercentParams = "[0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 100.0, 0.0]"
    private val levelPercentEffect = effect(417992, 999, levelPercentParams, subEffects = "[$masteryChild]")

    private fun pourpre(vararg equipEffects: String): String =
        """
        {"definition": {
           "item": {"id": 33395, "level": 170,
             "baseParameters": {"itemTypeId": 646, "itemSetId": 0, "rarity": 5, "bindType": 0, "minimumShardSlotNumber": 0, "maximumShardSlotNumber": 0},
             "useParameters": {"useCostAp": 0, "useCostMp": 0, "useCostWp": 0, "useRangeMin": 0, "useRangeMax": 0, "useTestFreeCell": false,
               "useTestLos": false, "useTestOnlyLine": false, "useTestNoBorderCell": false, "useWorldTarget": 0},
             "graphicParameters": {"gfxId": 53133395, "femaleGfxId": 53133395},
             "properties": [8]},
           "useEffects": [], "useCriticalEffects": [],
           "equipEffects": [${equipEffects.joinToString(",")}]},
         "title": {"fr": "Dofus Pourpre", "en": "Crimson Dofus", "es": "Dofus Púrpura", "pt": "Dofus Púrpura"}}
        """.trimIndent()

    private fun extract(item: String): Equipment =
        extractData(
            WakfuData(
                items = cdnJson.decodeFromString(ListSerializer(ItemSerializer), "[$item]"),
                jobs = emptyList(),
                effects = cdnJson.decodeFromString(ListSerializer(Effect.serializer()), actions),
                itemTypes = cdnJson.decodeFromString(ListSerializer(CdnItemType.serializer()), emblemType)
            )
        ).single()

    @Test
    fun `the Dofus Pourpre's action 999 becomes a percent-of-level Elemental Mastery line`() {
        val pourpre = extract(pourpre(stateEffect, apEffect, critEffect, levelPercentEffect))

        assertEquals(33395, pourpre.equipmentId)
        assertEquals(170, pourpre.level)
        assertEquals(Rarity.RELIC, pourpre.rarity)
        assertEquals(ItemType.EMBLEM, pourpre.itemType)
        // The flat lines; the reactive state (304 → 9364) is deliberately not modeled.
        assertEquals(mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3), pourpre.characteristics)
        // "100 % du niveau en Maîtrise Élémentaire": the child names the stat, the wrapper's lone param > 1 is the percent.
        assertEquals(mapOf(Characteristic.MASTERY_ELEMENTARY to 100), pourpre.percentOfLevel)
        assertEquals(245, pourpre.atLevel(245).characteristics[Characteristic.MASTERY_ELEMENTARY])
    }

    @Test
    fun `an item without a level-scaled line keeps an empty one`() {
        assertTrue(extract(pourpre(apEffect, critEffect)).percentOfLevel.isEmpty())
    }

    @Test
    fun `an action 999 the decoder does not understand fails the extraction instead of vanishing`() {
        val noChild = effect(417992, 999, levelPercentParams)
        val scaledChild = effect(417992, 999, levelPercentParams, subEffects = "[${effect(417993, 120, "[5.0, 2.0]")}]")
        val twoPercents = effect(417992, 999, "[50.0, 0.0, 100.0, 0.0]", subEffects = "[$masteryChild]")
        val notAStat = effect(417992, 999, levelPercentParams, subEffects = "[${effect(417993, 400, "[0.0, 1.0]")}]")
        for (bad in listOf(noChild, scaledChild, twoPercents, notAStat)) {
            assertThrows<IllegalStateException>(bad) { extract(pourpre(apEffect, bad)) }
        }
    }

    @Test
    fun `an undescribed equip action fails the extraction unless it is known to grant no stat`() {
        // 400 ("NullEffect : Effet Vide") is a known stat-free placeholder: skipped.
        assertEquals(mapOf(Characteristic.ACTION_POINT to 1), extract(pourpre(apEffect, effect(1, 400, "[]"))).characteristics)
        // An action absent from actions.json: its effect would be dropped, so the extraction stops and names it.
        val error = assertThrows<IllegalStateException> { extract(pourpre(apEffect, effect(2, 4242, "[1.0, 0.0]"))) }
        assertTrue("4242" in error.message.orEmpty(), error.message)
    }
}
