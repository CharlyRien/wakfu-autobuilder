package me.chosante.autobuilder.genetic

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.CharacterClass
import me.chosante.autobuilder.domain.skills.CharacterSkills
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.autobuilder.genetic.wakfu.getAssignedValues
import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.CONTROL
import me.chosante.common.Characteristic.CRITICAL_HIT
import me.chosante.common.Characteristic.DODGE
import me.chosante.common.Characteristic.HP
import me.chosante.common.Characteristic.INITIATIVE
import me.chosante.common.Characteristic.LOCK
import me.chosante.common.Characteristic.MASTERY_BACK
import me.chosante.common.Characteristic.MASTERY_DISTANCE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import me.chosante.common.Characteristic.MASTERY_HEALING
import me.chosante.common.Characteristic.MASTERY_SINGLE_TARGET
import me.chosante.common.Characteristic.MASTERY_ZONE
import me.chosante.common.Characteristic.MOVEMENT_POINT
import me.chosante.common.Characteristic.RANGE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WATER
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WIND
import me.chosante.common.Characteristic.WAKFU_POINT
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScoringTest {
    @Test
    fun `given equipments and character skills the resulted score must be the one expected`() {
        val level = 110
        val characterSkills = CharacterSkills(level).apply {
            intelligence.hpPercentage.setPointAssigned(28)
            strength.masteryDistance.setPointAssigned(19)
            strength.hp.setPointAssigned(8)
            luck.criticalHit.setPointAssigned(20)
            major.actionPoint.setPointAssigned(1)
        }
        val character = Character(clazz = CharacterClass.CRA, level = level, characterSkills)
        val equipments = listOf(
            equipment(
                characteristics = mapOf(
                    RANGE to 1,
                    HP to 90,
                    LOCK to -40,
                    DODGE to 15,
                    MASTERY_DISTANCE to 50,
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 49,
                    CRITICAL_HIT to 3,
                    MASTERY_DISTANCE to 45,
                )
            ),
            equipment(
                characteristics = mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 117,
                    MASTERY_DISTANCE to 20,
                    MASTERY_ELEMENTARY to 10,
                )
            ),
            equipment(
                characteristics = mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 96,
                    MASTERY_DISTANCE to 25,
                )
            ),
            equipment(
                characteristics = mapOf(
                    HP to 57,
                    DODGE to 5,
                    MASTERY_DISTANCE to 22,
                )
            ),
            equipment(
                characteristics = mapOf(
                    RANGE to 1,
                    HP to 65,
                    DODGE to 15,
                    MASTERY_DISTANCE to 35,
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 115,
                    MASTERY_SINGLE_TARGET to 30,
                    MASTERY_DISTANCE to 30,
                    CRITICAL_HIT to 3
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 118,
                    DODGE to 20,
                    MASTERY_ZONE to 25,
                    MASTERY_DISTANCE to 25,
                    CRITICAL_HIT to 7
                )
            ), equipment(
                characteristics = mapOf(
                    HP to 92,
                    MASTERY_DISTANCE to 40,
                    CRITICAL_HIT to 3
                )
            ), equipment(
                characteristics = mapOf(
                    MASTERY_DISTANCE to 40,
                )
            ), equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    MASTERY_DISTANCE to 70,
                    HP to 131,
                    CRITICAL_HIT to 4,
                )
            ), equipment(
                characteristics = mapOf(
                    MASTERY_DISTANCE to 20,
                    MASTERY_SINGLE_TARGET to 20,
                    MASTERY_BACK to -20,
                    HP to 98,
                    CRITICAL_HIT to 2,
                )
            ), equipment(
                characteristics = mapOf(
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
            characterBaseCharacteristics = character.baseCharacteristicValues,
            masteryElementsWanted = mapOf(),
            resistanceElementsWanted = mapOf()
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
    fun `double pano should be taken into account`() {
        val level = 35
        val characterSkills = CharacterSkills(level).apply {
            intelligence.resistance.setPointAssigned(9)
            strength.masteryDistance.setPointAssigned(9)
            luck.masteryHealing.setPointAssigned(8)
            major.actionPoint.setPointAssigned(1)
        }
        val character = Character(clazz = CharacterClass.ENIRIPSA, level = level, characterSkills)
        val equipments = listOf(
            equipment(
                name = "Casque Bouftou Impérial",
                level = 17,
                equipmentSetId = 1,
                characteristics = mapOf(
                    INITIATIVE to 6,
                    HP to 20,
                    MASTERY_ELEMENTARY to 15,
                    RESISTANCE_ELEMENTARY to 5,
                )
            ),
            equipment(
                name = "Amulette du Piou Royal",
                level = 32,
                equipmentSetId = 2,
                characteristics = mapOf(
                    DODGE to 10,
                    HP to 30,
                    CRITICAL_HIT to 4,
                    MASTERY_HEALING to 20,
                )
            ),
            equipment(
                name = "Plastron Bouftou Impérial",
                level = 20,
                equipmentSetId = 1,
                characteristics = mapOf(
                    CONTROL to 1,
                    HP to 20,
                    RESISTANCE_ELEMENTARY to 5,
                    MASTERY_ELEMENTARY to 15,
                )
            ),
            equipment(
                name = "Anneau Bouftou Impérial",
                level = 17,
                equipmentSetId = 1,
                characteristics = mapOf(
                    HP to 12,
                    MASTERY_ELEMENTARY to 8,
                )
            ),
            equipment(
                name = "Anneau de Satisfaction",
                level = 20,
                equipmentSetId = 0,
                characteristics = mapOf(
                    HP to -50,
                    ACTION_POINT to 1,
                    MASTERY_ELEMENTARY to 10,
                )
            ),
            equipment(
                name = "Pantoufle Piou Royal",
                level = 30,
                equipmentSetId = 2,
                characteristics = mapOf(
                    MASTERY_HEALING to 15,
                    HP to 22,
                    DODGE to 10,
                    RESISTANCE_ELEMENTARY to 10,
                )
            ),
            equipment(
                name = "Kapioutte Royale",
                level = 30,
                equipmentSetId = 2,
                characteristics = mapOf(
                    CRITICAL_HIT to 4,
                    HP to 24,
                    MASTERY_HEALING to 15,
                    RESISTANCE_ELEMENTARY to 10,
                )
            ),
            equipment(
                name = "Epaulettes Bouftou Impérial",
                level = 19,
                equipmentSetId = 1,
                characteristics = mapOf(
                    HP to 15,
                    DODGE to 7,
                    MASTERY_ELEMENTARY to 20,
                    RESISTANCE_ELEMENTARY to 5,
                )
            ), equipment(
                name = "Cordon du Piou Royal",
                level = 32,
                equipmentSetId = 2,
                characteristics = mapOf(
                    HP to 28,
                    DODGE to 10,
                    MASTERY_HEALING to 25,
                    CRITICAL_HIT to 3,
                    RESISTANCE_ELEMENTARY to 10
                )
            ), equipment(
                name = "Bouftou de guerre de distance",
                level = 50,
                characteristics = mapOf(
                    MASTERY_DISTANCE to 40,
                )
            ), equipment(
                name = "Lame en table",
                level = 18,
                characteristics = mapOf(
                    INITIATIVE to 5,
                    MASTERY_ELEMENTARY to 15,
                    HP to 10,
                )
            ), equipment(
                name = "Baguette Rangleuse",
                level = 15,
                characteristics = mapOf(
                    MASTERY_HEALING to 25,
                    HP to 15,
                    RESISTANCE_ELEMENTARY to 5,
                    DODGE to 5,
                )
            ), equipment(
                name = "Médaille du mérite",
                level = 10,
                characteristics = mapOf(
                    RESISTANCE_ELEMENTARY to 5,
                )
            ), equipment(
                name = "Chacha des Glaces",
                level = 50,
                characteristics = mapOf(
                    HP to 60,
                    MASTERY_HEALING to 100
                )
            )
        )
        val actualCharacteristics = computeCharacteristicsValues(
            buildCombination = BuildCombination(
                characterSkills = character.characterSkills,
                equipments = equipments
            ),
            characterBaseCharacteristics = character.baseCharacteristicValues,
            masteryElementsWanted = mapOf(
                MASTERY_ELEMENTARY_FIRE to 80,
                MASTERY_ELEMENTARY_WIND to 80,
                MASTERY_ELEMENTARY_WATER to 80,
                MASTERY_ELEMENTARY_EARTH to 80
            ),
            resistanceElementsWanted = mapOf(
                RESISTANCE_ELEMENTARY_EARTH to 140,
                RESISTANCE_ELEMENTARY_FIRE to 140,
                RESISTANCE_ELEMENTARY_WIND to 140,
                RESISTANCE_ELEMENTARY_WATER to 140
            ),
        ).filterNot { it.value == 0 }

        val expectedCharacteristics = mapOf(
            ACTION_POINT to 10,
            MOVEMENT_POINT to 3,
            HP to 606,
            CRITICAL_HIT to 14,
            MASTERY_DISTANCE to 112,
            MASTERY_HEALING to 248,
            DODGE to 42,
            CONTROL to 2,
            INITIATIVE to 11,
            WAKFU_POINT to 6,
            MASTERY_ELEMENTARY to 83,
            RESISTANCE_ELEMENTARY to 145,
        )

        assertEquals(expectedCharacteristics, actualCharacteristics)
    }

    @Test
    fun `test assign values`() {
        val oneRandomElement = listOf(1, 2, 3)
        val twoRandomElement = listOf(4, 5, 6)
        val threeRandomElement = listOf(7, 8, 9)
        val wantedCharacteristics =
            mapOf(
                MASTERY_ELEMENTARY_FIRE to 70,
                MASTERY_ELEMENTARY_WATER to 50,
                MASTERY_ELEMENTARY_WIND to 40
            )
        val expected = mapOf(
            MASTERY_ELEMENTARY_FIRE to 45,
            MASTERY_ELEMENTARY_WATER to 39,
            MASTERY_ELEMENTARY_WIND to 24,
        )
        val result = getAssignedValues(
            oneRandomElement = oneRandomElement,
            twoRandomElement = twoRandomElement,
            threeRandomElement = threeRandomElement,
            characteristicToValueCurrent = mapOf(),
            characteristicToValueWanted = wantedCharacteristics
        )
        assertEquals(expected, result)
    }

    private fun equipment(
        name: String = "name",
        equipmentSetId: Int = 0,
        level: Int = 100,
        characteristics: Map<Characteristic, Int>
    ) =
        Equipment(
            equipmentId = 0,
            equipmentSetId = equipmentSetId,
            level = level,
            name = name,
            rarity = Rarity.RARE,
            itemType = ItemType.HELMET, characteristics = characteristics
        )
}