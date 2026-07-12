package me.chosante.autobuilder

import me.chosante.common.Equipment
import me.chosante.common.ItemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the embedded `equipments.json` against silently losing its enchantment-socket data.
 *
 * The solver only sockets runes into items whose [Equipment.maxShardSlots] is > 0. When the data pipeline
 * regenerated the resource, the `equipments-extractor` was not carrying that field through from the CDN, so
 * every item came back with 0 sockets and runes silently vanished from every build — no rune icons in the
 * GUI, nothing exported to Zenith. Nothing failed at the time because the field is optional with a 0 default;
 * hence this explicit floor, so any future regeneration that drops the field fails here instead.
 */
class EmbeddedEquipmentsDataTest {
    private val equipments: List<Equipment> =
        EmbeddedResources.decodeList<Equipment>("equipments.json")!!

    @Test
    fun `embedded equipments carry enchantment sockets (maxShardSlots)`() {
        val socketable = equipments.count { it.maxShardSlots > 0 }
        // Most equippable gear has 4 sockets, so a large majority is expected (~90%). Requiring more than
        // half keeps the threshold robust to data churn while still catching an all-zero regeneration.
        assertThat(socketable)
            .`as`("equipments.json must carry maxShardSlots so the solver can socket runes")
            .isGreaterThan(equipments.size / 2)
    }

    /**
     * Lucky Charms (the "Porte-bonheur" PETS items, Ankama item type 849) share the pet slot with familiers but,
     * unlike them, carry a real character-level requirement. They must be flagged [Equipment.levelRestricted] so
     * the searcher level-filters them like ordinary gear; otherwise they inherit the familier level-exemption and
     * become equippable below their level (a level-35 charm on a level-10 build). Conversely, no level-less
     * companion may be flagged, or it would stop appearing at low character levels.
     *
     * (Note the pet-slot Lucky Charms are matched by type AND name: the data also has a level-230 *amulet*,
     * "Porte-bonheur sufokien", which is ordinary level-gated gear and correctly NOT flagged.)
     */
    @Test
    fun `Lucky Charms are flagged level-restricted, level-less companions are not`() {
        val luckyCharms = equipments.filter { it.itemType == ItemType.PETS && it.name.fr.startsWith("Porte-bonheur") }
        assertThat(luckyCharms)
            .`as`("embedded equipments.json must contain the Porte-bonheur (Lucky Charm) pet-slot items")
            .isNotEmpty
        assertThat(luckyCharms).allMatch { it.levelRestricted }

        // Only the pet-slot Lucky Charms are level-restricted; a flagged familier/mount/amulet would be a regression.
        assertThat(equipments.filter { it.levelRestricted })
            .allMatch { it.itemType == ItemType.PETS && it.name.fr.startsWith("Porte-bonheur") }
    }
}
