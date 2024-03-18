package me.chosante.autobuilder.genetic

import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.assignUniformlyMasteryRandomValues
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.BLOCK_PERCENTAGE
import me.chosante.common.Characteristic.CONTROL
import me.chosante.common.Characteristic.CRITICAL_HIT
import me.chosante.common.Characteristic.DODGE
import me.chosante.common.Characteristic.HP
import me.chosante.common.Characteristic.INITIATIVE
import me.chosante.common.Characteristic.LOCK
import me.chosante.common.Characteristic.MASTERY_BACK
import me.chosante.common.Characteristic.MASTERY_CRITICAL
import me.chosante.common.Characteristic.MASTERY_DISTANCE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import me.chosante.common.Characteristic.MASTERY_HEALING
import me.chosante.common.Characteristic.MOVEMENT_POINT
import me.chosante.common.Characteristic.RANGE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WATER
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WIND
import me.chosante.common.Characteristic.WAKFU_POINT
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FindClosestBuildFromInputScoringTest {

    private val equipments = this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!.let {
        Json.decodeFromString<List<Equipment>>(String(it))
    }

    @Test
    fun `given equipments with random elements and resistance and character skills the resulted score must be the one expected`() {
        val level = 110
        val characterSkills = CharacterSkills(level).apply {
            strength.masteryDistance.setPointAssigned(27)
            major.actionPoint.setPointAssigned(1)
            major.rangeAndMasteryElementary.setPointAssigned(1)
        }

        val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
        val equipmentNames = listOf(
            "casque hazieff",
            "amulette du corbeau blanc",
            "combinaison lardante",
            "halo de magmog",
            "gelano",
            "bottes massetard",
            "coulée de magmog",
            "ottopaulettes",
            "ceinture du corbeau blanc",
            "monture godron",
            "la seconde jumelle d'azael",
            "frogmourne",
            "emblème du pouvoir",
            "peroucan"
        )
        val filteredEquipments = equipments.filter {
            it.name.fr.lowercase() in equipmentNames
        }.groupBy { equipment -> equipment.name.fr }
            .mapValues { it.value.sortedByDescending(Equipment::rarity).take(1) }
            .values.flatten()
        val actualCharacteristics = computeCharacteristicsValues(
            buildCombination = BuildCombination(
                characterSkills = character.characterSkills,
                equipments = filteredEquipments
            ),
            characterBaseCharacteristics = character.baseCharacteristicValues,
            masteryElementsWanted = mapOf(MASTERY_ELEMENTARY_WATER to 530, MASTERY_ELEMENTARY_FIRE to 530),
            resistanceElementsWanted = mapOf()
        ).filterNot { it.value == 0 }

        val expectedCharacteristics = mapOf(
            ACTION_POINT to 13,
            MOVEMENT_POINT to 4,
            WAKFU_POINT to 6,
            RANGE to 3,
            HP to 2418,
            CRITICAL_HIT to 27,
            MASTERY_DISTANCE to 373,
            DODGE to 124,
            LOCK to 55,
            CONTROL to 2,
            MASTERY_BACK to 18,
            MASTERY_CRITICAL to 45,
            BLOCK_PERCENTAGE to -7,
            MASTERY_ELEMENTARY to 530,
            MASTERY_ELEMENTARY_WATER to 530,
            MASTERY_ELEMENTARY_FIRE to 530,
            MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 123,
            MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 170,
            RESISTANCE_ELEMENTARY to 37,
            RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to 63,
            RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 39,
            RESISTANCE_ELEMENTARY_EARTH to 74,
            RESISTANCE_ELEMENTARY_FIRE to 94,
            RESISTANCE_ELEMENTARY_WATER to 151,
            RESISTANCE_ELEMENTARY_WIND to 57
        )
        assertThat(actualCharacteristics).isEqualTo(expectedCharacteristics)
    }

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
        val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
        val equipments = listOf(
            equipment(
                characteristics = mapOf(
                    RANGE to 1,
                    HP to 90,
                    LOCK to -40,
                    DODGE to 15,
                    MASTERY_DISTANCE to 50
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 49,
                    CRITICAL_HIT to 3,
                    MASTERY_DISTANCE to 45
                )
            ),
            equipment(
                characteristics = mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 117,
                    MASTERY_DISTANCE to 20,
                    MASTERY_ELEMENTARY to 10
                )
            ),
            equipment(
                characteristics = mapOf(
                    MOVEMENT_POINT to 1,
                    HP to 96,
                    MASTERY_DISTANCE to 25
                )
            ),
            equipment(
                characteristics = mapOf(
                    HP to 57,
                    DODGE to 5,
                    MASTERY_DISTANCE to 22
                )
            ),
            equipment(
                characteristics = mapOf(
                    RANGE to 1,
                    HP to 65,
                    DODGE to 15,
                    MASTERY_DISTANCE to 35
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 115,
                    MASTERY_DISTANCE to 30,
                    CRITICAL_HIT to 3
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    HP to 118,
                    DODGE to 20,
                    MASTERY_DISTANCE to 25,
                    CRITICAL_HIT to 7
                )
            ),
            equipment(
                characteristics = mapOf(
                    HP to 92,
                    MASTERY_DISTANCE to 40,
                    CRITICAL_HIT to 3
                )
            ),
            equipment(
                characteristics = mapOf(
                    MASTERY_DISTANCE to 40
                )
            ),
            equipment(
                characteristics = mapOf(
                    ACTION_POINT to 1,
                    MASTERY_DISTANCE to 70,
                    HP to 131,
                    CRITICAL_HIT to 4
                )
            ),
            equipment(
                characteristics = mapOf(
                    MASTERY_DISTANCE to 20,
                    MASTERY_BACK to -20,
                    HP to 98,
                    CRITICAL_HIT to 2
                )
            ),
            equipment(
                characteristics = mapOf(
                    MASTERY_DISTANCE to 60,
                    HP to 30,
                    CONTROL to 1
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
            DODGE to 55,
            LOCK to -40,
            CONTROL to 2,
            MASTERY_ELEMENTARY to 10,
            MASTERY_BACK to -20
        )
        assertThat(actualCharacteristics).isEqualTo(expectedCharacteristics)
    }

    @Test
    fun `double pano should not be taken into account`() {
        val level = 35
        val characterSkills = CharacterSkills(level).apply {
            intelligence.resistance.setPointAssigned(9)
            strength.masteryDistance.setPointAssigned(9)
            luck.masteryHealing.setPointAssigned(8)
            major.actionPoint.setPointAssigned(1)
        }
        val character = Character(clazz = CharacterClass.ENIRIPSA, level = level, minLevel = level, characterSkills)
        val equipments = listOf(
            equipment(
                name = "Casque Bouftou Impérial",
                level = 17,
                characteristics = mapOf(
                    INITIATIVE to 6,
                    HP to 20,
                    MASTERY_ELEMENTARY to 15,
                    RESISTANCE_ELEMENTARY to 5
                )
            ),
            equipment(
                name = "Amulette du Piou Royal",
                level = 32,
                characteristics = mapOf(
                    DODGE to 10,
                    HP to 30,
                    CRITICAL_HIT to 4,
                    MASTERY_HEALING to 20
                )
            ),
            equipment(
                name = "Plastron Bouftou Impérial",
                level = 20,
                characteristics = mapOf(
                    CONTROL to 1,
                    HP to 20,
                    RESISTANCE_ELEMENTARY to 5,
                    MASTERY_ELEMENTARY to 15
                )
            ),
            equipment(
                name = "Anneau Bouftou Impérial",
                level = 17,
                characteristics = mapOf(
                    HP to 12,
                    MASTERY_ELEMENTARY to 8
                )
            ),
            equipment(
                name = "Anneau de Satisfaction",
                level = 20,
                characteristics = mapOf(
                    HP to -50,
                    ACTION_POINT to 1,
                    MASTERY_ELEMENTARY to 10
                )
            ),
            equipment(
                name = "Pantoufle Piou Royal",
                level = 30,
                characteristics = mapOf(
                    MASTERY_HEALING to 15,
                    HP to 22,
                    DODGE to 10,
                    RESISTANCE_ELEMENTARY to 10
                )
            ),
            equipment(
                name = "Kapioutte Royale",
                level = 30,
                characteristics = mapOf(
                    CRITICAL_HIT to 4,
                    HP to 24,
                    MASTERY_HEALING to 15,
                    RESISTANCE_ELEMENTARY to 10
                )
            ),
            equipment(
                name = "Epaulettes Bouftou Impérial",
                level = 19,
                characteristics = mapOf(
                    HP to 15,
                    DODGE to 7,
                    MASTERY_ELEMENTARY to 20,
                    RESISTANCE_ELEMENTARY to 5
                )
            ),
            equipment(
                name = "Cordon du Piou Royal",
                level = 32,
                characteristics = mapOf(
                    HP to 28,
                    DODGE to 10,
                    MASTERY_HEALING to 25,
                    CRITICAL_HIT to 3,
                    RESISTANCE_ELEMENTARY to 10
                )
            ),
            equipment(
                name = "Bouftou de guerre de distance",
                level = 50,
                characteristics = mapOf(
                    MASTERY_DISTANCE to 40
                )
            ),
            equipment(
                name = "Lame en table",
                level = 18,
                characteristics = mapOf(
                    INITIATIVE to 5,
                    MASTERY_ELEMENTARY to 15,
                    HP to 10
                )
            ),
            equipment(
                name = "Baguette Rangleuse",
                level = 15,
                characteristics = mapOf(
                    MASTERY_HEALING to 25,
                    HP to 15,
                    RESISTANCE_ELEMENTARY to 5,
                    DODGE to 5
                )
            ),
            equipment(
                name = "Médaille du mérite",
                level = 10,
                characteristics = mapOf(
                    RESISTANCE_ELEMENTARY to 5
                )
            ),
            equipment(
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
            )
        ).filterNot { it.value == 0 }

        val expectedCharacteristics = mapOf(
            ACTION_POINT to 8,
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
            MASTERY_ELEMENTARY_FIRE to 83,
            MASTERY_ELEMENTARY_WIND to 83,
            MASTERY_ELEMENTARY_WATER to 83,
            MASTERY_ELEMENTARY_EARTH to 83,
            RESISTANCE_ELEMENTARY_EARTH to 145,
            RESISTANCE_ELEMENTARY_FIRE to 145,
            RESISTANCE_ELEMENTARY_WIND to 145,
            RESISTANCE_ELEMENTARY_WATER to 145
        )

        assertThat(actualCharacteristics).isEqualTo(expectedCharacteristics)
    }

    @Test
    fun `test assign values`() {
        val randomElements = mapOf(
            MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to listOf(1, 2, 3),
            MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to listOf(4, 5, 6),
            MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to listOf(7, 8, 9)
        )
        val wantedCharacteristics =
            mapOf(
                MASTERY_ELEMENTARY_FIRE to 70,
                MASTERY_ELEMENTARY_WATER to 50,
                MASTERY_ELEMENTARY_WIND to 40
            )
        val expected = mapOf(
            MASTERY_ELEMENTARY_FIRE to 45,
            MASTERY_ELEMENTARY_WATER to 39,
            MASTERY_ELEMENTARY_WIND to 24
        )
        val result = assignUniformlyMasteryRandomValues(
            randomElements = randomElements,
            characteristicToValueCurrent = mapOf(),
            characteristicToValueWanted = wantedCharacteristics
        )
        assertThat(result).isEqualTo(expected)
    }

    private fun equipment(
        name: String = "name",
        level: Int = 100,
        characteristics: Map<Characteristic, Int>,
    ) =
        Equipment(
            equipmentId = 0,
            level = level,
            name = I18nText(fr = name, en = "", es = "", pt = ""),
            rarity = Rarity.RARE,
            itemType = ItemType.HELMET,
            characteristics = characteristics,
            guiId = 0
        )
}
