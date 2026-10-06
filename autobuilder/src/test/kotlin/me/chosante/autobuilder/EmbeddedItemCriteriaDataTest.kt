package me.chosante.autobuilder

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Equipment
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the committed `item-criteria.json` (the bdata-extractor's decode of the client's Item table, which can't run in
 * CI): the four nation swords need their ring, the mutually exclusive ring triples, the 37 class items and the four
 * never-equippable items — and the engine's catalog carries each criterion on its item.
 */
class EmbeddedItemCriteriaDataTest {
    private val criteria: List<ItemEquipCriterion> = EmbeddedResources.decodeList<ItemEquipCriterion>("item-criteria.json")!!
    private val byId = criteria.associateBy { it.itemId }
    private val equipments: Map<Int, Equipment> = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }

    @Test
    fun `every criterion belongs to a catalog item, sorted by id, and the catalog carries it`() {
        assertThat(criteria.map { it.itemId }).isSorted.doesNotHaveDuplicates()
        assertThat(criteria).allMatch { it.itemId in equipments }
        assertThat(criteria).allMatch { equipments.getValue(it.itemId).equipCriterion == it }
        assertThat(equipments.values.count { it.equipCriterion != null }).isEqualTo(criteria.size)
        // 1.93: 1063 pool items carry an EQUIP criterion — 816 of them only "never two of the same ring".
        assertThat(criteria).hasSize(1063)
        assertThat(criteria.count { it.uniqueEquipped && !it.constrainsBuild && it.statGates.isEmpty() && it.playerState.isEmpty() })
            .isEqualTo(816)
    }

    @Test
    fun `the four nation swords each need their own ring, a zero-stat EPIC ring`() {
        val swordToRing = mapOf(26494 to 26575, 26495 to 26576, 26496 to 26577, 26497 to 26578)
        assertThat(criteria.filter { it.requiresItems.isNotEmpty() }.associate { it.itemId to it.requiresItems })
            .isEqualTo(swordToRing.mapValues { listOf(it.value) })
        for ((sword, ring) in swordToRing) {
            assertThat(equipments.getValue(sword).itemType).isEqualTo(ItemType.ONE_HANDED_WEAPONS)
            assertThat(equipments.getValue(sword).rarity.name).isEqualTo("RELIC")
            val key = equipments.getValue(ring)
            assertThat(key.itemType).isEqualTo(ItemType.RING)
            assertThat(key.rarity.name).isEqualTo("EPIC")
            assertThat(key.characteristics).isEmpty()
            assertThat(key.maxShardSlots).isEqualTo(4)
        }
        assertThat(equipments.getValue(26497).name.fr).isEqualTo("Epée de Brâkmar")
        assertThat(equipments.getValue(26578).name.fr).isEqualTo("Anneau de Brâkmar")
    }

    @Test
    fun `mutually exclusive rings come in triples, read as a symmetric closure`() {
        // Each triple's rings forbid each other; "Le Lieute" (24505) lists its bans only in its CRAFT criterion, so its
        // pairs come from the other two rings' EQUIP criteria — the engine reads either direction.
        val triples =
            listOf(
                setOf(19698, 22226, 22227), // Issé Sceau (one name, also blocked by the same-name rule)
                setOf(24392, 24408, 24426),
                setOf(24440, 24456, 24473),
                setOf(24488, 24505, 24521),
                setOf(24537, 24552, 24567),
                setOf(24585, 24601, 24615)
            )
        val forbidding = criteria.filter { it.forbidsItems.isNotEmpty() }
        assertThat(forbidding).hasSize(17)
        val pairs = forbidding.flatMap { c -> c.forbidsItems.map { setOf(c.itemId, it) } }.toSet()
        val expected = triples.flatMap { t -> t.flatMap { a -> (t - a).map { b -> setOf(a, b) } } }.toSet()
        assertThat(pairs).isEqualTo(expected)
        assertThat(triples.flatten()).allMatch { equipments.getValue(it).itemType == ItemType.RING }
    }

    @Test
    fun `37 class items - an emblem and an amulet per class, and the Xelor belt`() {
        val classItems = criteria.filter { it.classes.isNotEmpty() }
        assertThat(classItems).hasSize(37)
        assertThat(classItems).allMatch { it.classes.size == 1 }
        val classes = CharacterClass.entries - CharacterClass.UNKNOWN
        for (itemType in listOf(ItemType.EMBLEM, ItemType.AMULET)) {
            val perClass = classItems.filter { equipments.getValue(it.itemId).itemType == itemType }.groupBy { it.classes.single() }
            assertThat(perClass.keys).containsExactlyInAnyOrderElementsOf(classes)
            assertThat(perClass.values).allMatch { it.size == 1 }
        }
        // The client says SACRIER where the project says SACRIEUR: mapped by breed id.
        assertThat(byId.getValue(15260).classes).containsExactly(CharacterClass.SACRIEUR)
        assertThat(byId.getValue(26643).classes).containsExactly(CharacterClass.XELOR)
        assertThat(equipments.getValue(26643).itemType).isEqualTo(ItemType.BELT)
    }

    @Test
    fun `four items can never be equipped`() {
        assertThat(criteria.filter { it.never }.map { it.itemId }).containsExactly(32988, 33375, 33376, 33377)
    }

    @Test
    fun `stat gates and player-state conditions are kept for display`() {
        // Ceinture Pimentée needs max AP ≤ 11 (not enforced yet: is the item's own bonus counted?).
        assertThat(
            byId
                .getValue(27303)
                .statGates
                .single()
                .toString()
        ).contains("ACTION_POINT", "max=true", "LE", "11")
        assertThat(criteria.count { it.statGates.isNotEmpty() }).isEqualTo(149)
        assertThat(criteria.count { it.playerState.isNotEmpty() }).isEqualTo(36)
        assertThat(
            byId
                .getValue(19547)
                .playerState
                .single()
                .function
        ).isEqualTo("GetCompanyRank")
    }
}
