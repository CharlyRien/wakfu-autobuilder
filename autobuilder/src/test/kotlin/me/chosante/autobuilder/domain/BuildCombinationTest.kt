package me.chosante.autobuilder.domain

import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BuildCombinationTest {
    @Test
    fun `a build with a single ring is valid`() {
        val build =
            BuildCombination(
                equipments = listOf(equipment("Gelano")),
                characterSkills = CharacterSkills(level = 110)
            )

        assertThat(build.isValid()).isTrue()
    }

    @Test
    fun `a build with the same ring twice is invalid`() {
        val build =
            BuildCombination(
                equipments = listOf(equipment("Gelano"), equipment("Gelano")),
                characterSkills = CharacterSkills(level = 110)
            )

        assertThat(build.isValid()).isFalse()
    }

    private fun equipment(name: String): Equipment =
        Equipment(
            equipmentId = 1,
            level = 65,
            name = I18nText(fr = name, en = name, es = "", pt = ""),
            rarity = Rarity.RELIC,
            itemType = ItemType.RING,
            characteristics = emptyMap(),
            guiId = 1
        )
}
