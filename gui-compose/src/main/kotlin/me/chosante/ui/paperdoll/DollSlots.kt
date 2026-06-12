package me.chosante.ui.paperdoll

import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.ui.i18n.Tr

// The 14 paperdoll slots and the (tricky) item → slot mapping. Shared by the paperdoll panel and
// the library's build-card mini-grid so the slot layout is derived in exactly one place. The
// ring1/ring2 split and the two-handed-weapon-fills-both-weapon-tiles rule live here and nowhere
// else — see DollSlotsTest for the pinned contract.

internal data class DollSlot(
    val id: String,
    val labelKey: Tr,
    val glyph: String,
)

internal val leftSlots =
    listOf(
        DollSlot("helmet", Tr.SLOT_HELMET, "⛨"),
        DollSlot("amulet", Tr.SLOT_AMULET, "◌"),
        DollSlot("epaul", Tr.SLOT_EPAULETTES, "▱"),
        DollSlot("chest", Tr.SLOT_BREASTPLATE, "▢"),
        DollSlot("cape", Tr.SLOT_CAPE, "⊳")
    )

internal val rightSlots =
    listOf(
        DollSlot("emblem", Tr.SLOT_EMBLEM, "✦"),
        DollSlot("belt", Tr.SLOT_BELT, "═"),
        DollSlot("ring1", Tr.SLOT_RING_I, "◯"),
        DollSlot("ring2", Tr.SLOT_RING_II, "◯"),
        DollSlot("boots", Tr.SLOT_BOOTS, "⊓")
    )

internal val bottomSlots =
    listOf(
        DollSlot("weapon", Tr.SLOT_WEAPON, "⚔"),
        DollSlot("weapon2", Tr.SLOT_SECOND_WEAPON, "⛉"),
        DollSlot("pet", Tr.SLOT_PET, "❀"),
        DollSlot("mount", Tr.SLOT_MOUNT, "≋")
    )

internal fun slotAssignments(equipments: List<Equipment>): Map<String, Equipment> {
    val rings = equipments.filter { it.itemType == ItemType.RING }
    val twoHanded = equipments.firstOrNull { it.itemType == ItemType.TWO_HANDED_WEAPONS }
    val oneHanded = equipments.firstOrNull { it.itemType == ItemType.ONE_HANDED_WEAPONS }
    val offHand = equipments.firstOrNull { it.itemType == ItemType.OFF_HAND_WEAPONS }
    return buildMap {
        putFirst("helmet", equipments, ItemType.HELMET)
        putFirst("amulet", equipments, ItemType.AMULET)
        putFirst("epaul", equipments, ItemType.SHOULDER_PADS)
        putFirst("chest", equipments, ItemType.CHEST_PLATE)
        putFirst("cape", equipments, ItemType.CAPE)
        putFirst("emblem", equipments, ItemType.EMBLEM)
        putFirst("belt", equipments, ItemType.BELT)
        rings.getOrNull(0)?.let { put("ring1", it) }
        rings.getOrNull(1)?.let { put("ring2", it) }
        putFirst("boots", equipments, ItemType.BOOTS)
        (twoHanded ?: oneHanded)?.let { put("weapon", it) }
        (if (twoHanded != null) twoHanded else offHand)?.let { put("weapon2", it) }
        putFirst("pet", equipments, ItemType.PETS)
        putFirst("mount", equipments, ItemType.MOUNTS)
    }
}

internal fun MutableMap<String, Equipment>.putFirst(
    key: String,
    equipments: List<Equipment>,
    type: ItemType,
) {
    equipments.firstOrNull { it.itemType == type }?.let { put(key, it) }
}
