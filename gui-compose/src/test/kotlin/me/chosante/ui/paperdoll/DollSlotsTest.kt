package me.chosante.ui.paperdoll

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
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

    // ---- emptySlotHints ("explain the solver's choices") -----------------------------------------

    @Test
    fun `no build means no hints`() {
        assertThat(emptySlotHints(emptyMap(), null)).isEmpty()
    }

    @Test
    fun `an empty off-hand names the no-off-hand sublimation that requires it`() {
        val sword = item(4, ItemType.ONE_HANDED_WEAPONS, "Épée")
        val lwe = noOffhandSub("Expert des armes légères II")
        val build =
            BuildCombination(
                equipments = listOf(sword),
                characterSkills = CharacterSkills(110),
                sublimations = mapOf(sword to listOf(lwe))
            )
        val hints = emptySlotHints(slotAssignments(build.equipments), build)

        assertThat(hints["weapon2"]).isEqualTo(EmptySlotHint.SubRequiresEmpty(lwe))
        // Other empty slots get the generic explanation, not the sub one.
        assertThat(hints["mount"]).isEqualTo(EmptySlotHint.NoUsefulItem)
    }

    @Test
    fun `without the sub an empty off-hand gets the generic explanation`() {
        val sword = item(4, ItemType.ONE_HANDED_WEAPONS, "Épée")
        val build = BuildCombination(equipments = listOf(sword), characterSkills = CharacterSkills(110))

        val hints = emptySlotHints(slotAssignments(build.equipments), build)

        assertThat(hints["weapon2"]).isEqualTo(EmptySlotHint.NoUsefulItem)
    }

    @Test
    fun `a two-handed weapon fills both tiles so no weapon hint appears`() {
        val staff = item(3, ItemType.TWO_HANDED_WEAPONS, "Bâton")
        val build = BuildCombination(equipments = listOf(staff), characterSkills = CharacterSkills(110))

        val hints = emptySlotHints(slotAssignments(build.equipments), build)

        assertThat(hints).doesNotContainKeys("weapon", "weapon2")
    }

    @Test
    fun `filled slots get no hint`() {
        val boots = item(6, ItemType.BOOTS, "Bottes")
        val build = BuildCombination(equipments = listOf(boots), characterSkills = CharacterSkills(110))

        val hints = emptySlotHints(slotAssignments(build.equipments), build)

        assertThat(hints).doesNotContainKey("boots")
        assertThat(hints["helmet"]).isEqualTo(EmptySlotHint.NoUsefulItem)
    }

    private fun noOffhandSub(name: String): Sublimation =
        Sublimation(
            stateId = 6821,
            name = I18nText(fr = name, en = name, es = name, pt = name),
            rarity = SublimationRarity.NORMAL,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(type = SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
        )

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
