package me.chosante.autobuilder.genetic

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.autobuilder.domain.skills.CharacterSkills
import me.chosante.autobuilder.genetic.wakfu.assignValues
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Characteristic.*
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScoringTest {
    @Test
    fun `given equipments and character skills the resulted score must be the one expected`() {
        val characterSkills = CharacterSkills(110).apply {
            intelligence.hpPercentage.setPointAssigned(28)
            strength.masteryDistance.setPointAssigned(19)
            strength.hp.setPointAssigned(8)
            luck.criticalHit.setPointAssigned(20)
            major.actionPoint.setPointAssigned(1)
        }
        val character = Character(clazz = CharacterClass.CRA, level = 110, characterSkills)
        val equipments = listOf(
            equipment(
                mapOf(
                    RANGE to 1,
                    HP to 90,
                    LOCK to -40,
                    DODGE to 15,
                    MASTERY_DISTANCE to 50,
                )
            ),
            equipment(
                mapOf(
                    ACTION_POINT to 1,
                    HP to 49,
                    CRITICAL_HIT to 3,
                    MASTERY_DISTANCE to 45,
                )
            ),
            equipment(
                mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 117,
                    MASTERY_DISTANCE to 20,
                    MASTERY_ELEMENTARY to 10,
                )
            ),
            equipment(
                mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 96,
                    MASTERY_DISTANCE to 25,
                )
            ),
            equipment(
                mapOf(
                    HP to 57,
                    DODGE to 5,
                    MASTERY_DISTANCE to 22,
                )
            ),
            equipment(
                mapOf(
                    RANGE to 1,
                    HP to 65,
                    DODGE to 15,
                    MASTERY_DISTANCE to 35,
                )
            ),
            equipment(
                mapOf(
                    ACTION_POINT to 1,
                    HP to 115,
                    MASTERY_SINGLE_TARGET to 30,
                    MASTERY_DISTANCE to 30,
                    CRITICAL_HIT to 3
                )
            ),
            equipment(
                mapOf(
                    ACTION_POINT to 1,
                    HP to 118,
                    DODGE to 20,
                    MASTERY_ZONE to 25,
                    MASTERY_DISTANCE to 25,
                    CRITICAL_HIT to 7
                )
            ), equipment(
                mapOf(
                    HP to 92,
                    MASTERY_DISTANCE to 40,
                    CRITICAL_HIT to 3
                )
            ), equipment(
                mapOf(
                    MASTERY_DISTANCE to 40,
                )
            ), equipment(
                mapOf(
                    ACTION_POINT to 1,
                    MASTERY_DISTANCE to 70,
                    HP to 131,
                    CRITICAL_HIT to 4,
                )
            ), equipment(
                mapOf(
                    MASTERY_DISTANCE to 20,
                    MASTERY_SINGLE_TARGET to 20,
                    MASTERY_BACK to -20,
                    HP to 98,
                    CRITICAL_HIT to 2,
                )
            ), equipment(
                mapOf(
                    MASTERY_DISTANCE to 60,
                    HP to 30,
                    CONTROL to 1,
                )
            )
        )
        val actualCharacteristics = computeCharacteristicsValues(
            buildCombination = BuildCombination(
                characterSkills = character.characterSkills,
                equipments = equipments
            ),
            masteryElementsWanted = mapOf(),
            resistanceElementsWanted = mapOf(),
            masteryElementaryWanted = null,
            resistanceElementaryWanted = null
        ).filterNot { it.value == 0 }

        val expectedCharacteristics = mapOf(
            ACTION_POINT to 11,
            MOVEMENT_POINT to 5,
            WAKFU_POINT to 6,
            RANGE to 2,
            HP to 5020,
            CRITICAL_HIT to 45,
            MASTERY_DISTANCE to 634,
            MASTERY_ZONE to 25,
            DODGE to 55,
            LOCK to -40,
            CONTROL to 2,
            MASTERY_SINGLE_TARGET to 50,
            MASTERY_ELEMENTARY to 10,
            MASTERY_BACK to -20
        )

        assertEquals(expectedCharacteristics, actualCharacteristics)
    }

    @Test
    fun `test assign values`() {
        val list1 = listOf(1, 2, 3)
        val list2 = listOf(4, 5, 6)
        val list3 = listOf(7, 8, 9)
        val characteristics =
            mapOf(MASTERY_ELEMENTARY_WATER to 50,
                MASTERY_ELEMENTARY_FIRE to 70,
                MASTERY_ELEMENTARY_WIND to 40)
        val expected = mapOf(
            MASTERY_ELEMENTARY_FIRE to 45,
            MASTERY_ELEMENTARY_WATER to 39,
            MASTERY_ELEMENTARY_WIND to 24,
        )
        val result = assignValues(list1, list2, list3, characteristics)
        assertEquals(expected, result)
    }

    private fun equipment(characteristics: Map<Characteristic, Int>) =
        Equipment(0, 0, 100, "name", Rarity.RARE, ItemType.HELMET, characteristics = characteristics)
}