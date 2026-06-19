package me.chosante.autobuilder

import me.chosante.common.Equipment
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
}
