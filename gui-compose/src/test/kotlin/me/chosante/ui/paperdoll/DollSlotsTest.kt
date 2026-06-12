package me.chosante.ui.paperdoll

import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// slotAssignments is now shared by the paperdoll panel and the library build-card mini-grid, so its
// trickier rules (ring1/ring2 split, two-handed weapon fills both weapon tiles) are pinned here.
class DollSlotsTest {
    @Test
    fun `two rings land in ring1 and ring2 in list order`() {
        val ringA = item(1, ItemType.RING, "Anneau A")
        val ringB = item(2, ItemType.RING, "Anneau B")

        val assignments = slotAssignments(listOf(ringA, ringB))

        assertThat(assignments["ring1"]).isEqualTo(ringA)
        assertThat(assignments["ring2"]).isEqualTo(ringB)
    }

    @Test
    fun `a two-handed weapon fills both weapon tiles`() {
        val staff = item(3, ItemType.TWO_HANDED_WEAPONS, "Bâton")

        val assignments = slotAssignments(listOf(staff))

        assertThat(assignments["weapon"]).isEqualTo(staff)
        assertThat(assignments["weapon2"]).isEqualTo(staff)
    }

    @Test
    fun `one-handed goes to weapon and off-hand to weapon2`() {
        val sword = item(4, ItemType.ONE_HANDED_WEAPONS, "Épée")
        val shield = item(5, ItemType.OFF_HAND_WEAPONS, "Bouclier")

        val assignments = slotAssignments(listOf(sword, shield))

        assertThat(assignments["weapon"]).isEqualTo(sword)
        assertThat(assignments["weapon2"]).isEqualTo(shield)
    }

    @Test
    fun `an empty equipment list yields an empty map`() {
        assertThat(slotAssignments(emptyList())).isEmpty()
    }

    private fun item(
        id: Int,
        type: ItemType,
        name: String,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = 100,
            name = I18nText(fr = name, en = name, es = "", pt = ""),
            rarity = Rarity.RARE,
            itemType = type,
            characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 10)
        )
}
