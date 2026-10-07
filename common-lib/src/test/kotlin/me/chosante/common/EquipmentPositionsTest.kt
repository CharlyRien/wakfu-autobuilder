package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EquipmentPositionsTest {
    @Test
    fun `official positions preserve all fourteen historical rune slot domains`() {
        val expected =
            mapOf(
                ItemType.HELMET to listOf(0),
                ItemType.CHEST_PLATE to listOf(5),
                ItemType.SHOULDER_PADS to listOf(3),
                ItemType.BOOTS to listOf(12),
                ItemType.AMULET to listOf(4),
                ItemType.CAPE to listOf(13),
                ItemType.BELT to listOf(10),
                ItemType.ONE_HANDED_WEAPONS to listOf(15),
                ItemType.TWO_HANDED_WEAPONS to listOf(15),
                ItemType.RING to listOf(7, 8),
                ItemType.OFF_HAND_WEAPONS to emptyList(),
                ItemType.EMBLEM to emptyList(),
                ItemType.PETS to emptyList(),
                ItemType.MOUNTS to emptyList()
            )
        assertEquals(ItemType.entries.toSet(), expected.keys)
        for ((item, ids) in expected) assertEquals(ids, RuneType.slotRawIds(item), item.name)
        // Their actual occupied positions exist, but these slots have no rune placement.
        assertEquals(listOf(16), EquipmentPositions.embedded.rawIds(ItemType.OFF_HAND_WEAPONS))
        assertEquals(listOf(17), EquipmentPositions.embedded.rawIds(ItemType.EMBLEM))
        assertEquals(listOf(22), EquipmentPositions.embedded.rawIds(ItemType.PETS))
        assertEquals(listOf(24), EquipmentPositions.embedded.rawIds(ItemType.MOUNTS))
    }
}
