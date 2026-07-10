package me.chosante.ui.paperdoll

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.ui.i18n.Tr

// The 14 paperdoll slots and the (tricky) item → slot mapping. Shared by the paperdoll panel and
// the library's build-card mini-grid so the slot layout is derived in exactly one place. The
// ring1/ring2 split and the two-handed-weapon-fills-both-weapon-tiles rule live here and nowhere
// else — see DollSlotsTest for the pinned contract.

internal data class DollSlot(
    val id: String,
    val labelKey: Tr,
    val glyph: String,
    /**
     * Ankama `ItemType.id` whose slot-silhouette icon (`assets/itemTypes/<id>.png`, extracted from the
     * client's gui.jar `miscellaneous/itemTypes`) is shown on the empty card — replacing the [glyph],
     * which now only serves as the fallback when the asset is missing. The two weapon cards both accept a
     * range of weapon types: the main card uses the one-handed glyph (518) as a generic weapon, the second
     * the off-hand glyph (112).
     */
    val itemTypeId: Int,
)

internal val leftSlots =
    listOf(
        DollSlot("helmet", Tr.SLOT_HELMET, "⛨", ItemType.HELMET.id),
        DollSlot("amulet", Tr.SLOT_AMULET, "◌", ItemType.AMULET.id),
        DollSlot("epaul", Tr.SLOT_EPAULETTES, "▱", ItemType.SHOULDER_PADS.id),
        DollSlot("chest", Tr.SLOT_BREASTPLATE, "▢", ItemType.CHEST_PLATE.id),
        DollSlot("cape", Tr.SLOT_CAPE, "⊳", ItemType.CAPE.id)
    )

internal val rightSlots =
    listOf(
        DollSlot("emblem", Tr.SLOT_EMBLEM, "✦", ItemType.EMBLEM.id),
        DollSlot("belt", Tr.SLOT_BELT, "═", ItemType.BELT.id),
        DollSlot("ring1", Tr.SLOT_RING_I, "◯", ItemType.RING.id),
        DollSlot("ring2", Tr.SLOT_RING_II, "◯", ItemType.RING.id),
        DollSlot("boots", Tr.SLOT_BOOTS, "⊓", ItemType.BOOTS.id)
    )

internal val bottomSlots =
    listOf(
        DollSlot("weapon", Tr.SLOT_WEAPON, "⚔", ItemType.ONE_HANDED_WEAPONS.id),
        DollSlot("weapon2", Tr.SLOT_SECOND_WEAPON, "⛉", ItemType.OFF_HAND_WEAPONS.id),
        DollSlot("pet", Tr.SLOT_PET, "❀", ItemType.PETS.id),
        DollSlot("mount", Tr.SLOT_MOUNT, "≋", ItemType.MOUNTS.id)
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

/**
 * Why an EMPTY paperdoll slot is empty — the "explain the solver's choices" hints. An empty slot in a
 * discovered build reads as a bug ("the second weapon is not displayed"), when it is usually a deliberate,
 * score-driven decision:
 *  - [EmptySlotHint.SubRequiresEmpty]: a chosen sublimation's `NO_OFFHAND_OR_TWO_HANDED` condition (Light
 *    Weapons Expert…) FORBIDS a shield/dagger/two-handed weapon — the empty off-hand is load-bearing.
 *    Factual whenever the sub is in the build, so it is always shown.
 *  - [EmptySlotHint.NoUsefulItem]: the engine maximizes ONLY the requested stats, so when no item in a
 *    slot can improve them (e.g. every mount carries only elemental mastery when none was requested) the
 *    optimum leaves the slot empty — AGENTS.md §4 "why the solver can leave slots empty". Only meaningful
 *    once the search is DONE (mid-search an empty slot may just be "not chosen yet"); the caller gates it.
 */
internal sealed class EmptySlotHint {
    data class SubRequiresEmpty(
        val sub: Sublimation,
    ) : EmptySlotHint()

    data object NoUsefulItem : EmptySlotHint()
}

/** Per-slot-id hint for every EMPTY slot of a discovered build (empty map when there is no build yet). */
internal fun emptySlotHints(
    assignments: Map<String, Equipment>,
    build: BuildCombination?,
): Map<String, EmptySlotHint> {
    if (build == null) return emptyMap()
    val noOffhandSub =
        build.sublimations.values
            .flatten()
            .firstOrNull { it.condition?.type == SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED }
    return buildMap {
        for (slot in leftSlots + rightSlots + bottomSlots) {
            if (assignments.containsKey(slot.id)) continue
            if (slot.id == "weapon2" && noOffhandSub != null) {
                put(slot.id, EmptySlotHint.SubRequiresEmpty(noOffhandSub))
            } else {
                put(slot.id, EmptySlotHint.NoUsefulItem)
            }
        }
    }
}
