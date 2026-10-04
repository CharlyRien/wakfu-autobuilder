package me.chosante.common

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Equipment.percentOfLevel] / [Equipment.atLevel]: an item's "X% of the level as <stat>" line (the Dofus Pourpre's 100% of
 * the level as Elemental Mastery) is level-independent data, resolved into [Equipment.characteristics] for the wearer.
 */
class EquipmentPercentOfLevelTest {
    private val pourpre =
        Equipment(
            equipmentId = 33395,
            guiId = 53133395,
            level = 170,
            name = I18nText(fr = "Dofus Pourpre", en = "Crimson Dofus", es = "Dofus Púrpura", pt = "Dofus Púrpura"),
            rarity = Rarity.RELIC,
            itemType = ItemType.EMBLEM,
            characteristics = mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3),
            percentOfLevel = mapOf(Characteristic.MASTERY_ELEMENTARY to 100)
        )

    @Test
    fun `the Dofus Pourpre gives Elemental Mastery equal to the wearer's level`() {
        for (level in listOf(170, 200, 245)) {
            val worn = pourpre.atLevel(level)
            assertEquals(
                mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3, Characteristic.MASTERY_ELEMENTARY to level),
                worn.characteristics,
                "level $level"
            )
            assertTrue(worn.percentOfLevel.isEmpty(), "a resolved item carries no unresolved line")
            // Only the stats change: the identity, the item level and the slot are the catalog item's.
            assertEquals(pourpre.copy(characteristics = worn.characteristics, percentOfLevel = emptyMap()), worn)
        }
    }

    @Test
    fun `a resolved item is final, so it is never counted twice`() {
        val worn = pourpre.atLevel(245)
        assertSame(worn, worn.atLevel(245))
        assertSame(worn, worn.atLevel(200), "re-resolving at another level must not rescale or add the line again")
    }

    @Test
    fun `an item without a level-scaled line is returned as is`() {
        val plain = pourpre.copy(percentOfLevel = emptyMap())
        assertSame(plain, plain.atLevel(245))
    }

    @Test
    fun `a level-scaled line adds to the item's own line of that stat and rounds down`() {
        val item =
            pourpre.copy(
                characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY to 10),
                percentOfLevel = mapOf(Characteristic.MASTERY_ELEMENTARY to 75, Characteristic.LOCK to 50)
            )
        // 75% of 245 = 183.75 and 50% of 245 = 122.5: floored like the sublimations' percent-of-level effects.
        assertEquals(mapOf(Characteristic.MASTERY_ELEMENTARY to 10 + 183, Characteristic.LOCK to 122), item.atLevel(245).characteristics)
        assertEquals(183, SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, 75).magnitudeAtLevel(245))
    }

    @Test
    fun `the line is serialized only when present and older data still decodes`() {
        val json = Json // the equipments extractor's codec: defaults (an empty map) are not written
        val plainJson = json.encodeToString(Equipment.serializer(), pourpre.copy(percentOfLevel = emptyMap()))
        assertFalse("percentOfLevel" in plainJson, plainJson)
        val pourpreJson = json.encodeToString(Equipment.serializer(), pourpre)
        assertTrue("\"percentOfLevel\":{\"MASTERY_ELEMENTARY\":100}" in pourpreJson, pourpreJson)
        assertEquals(pourpre, json.decodeFromString(Equipment.serializer(), pourpreJson))
        // A resource or saved build written before the field existed: no level-scaled line.
        assertEquals(pourpre.copy(percentOfLevel = emptyMap()), json.decodeFromString(Equipment.serializer(), plainJson))
    }
}
