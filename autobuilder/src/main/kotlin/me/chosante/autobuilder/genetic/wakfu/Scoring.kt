package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import kotlin.math.min
import kotlin.math.roundToInt

fun calculateSuccessPercentage(
    targetStats: TargetStats,
    buildCombination: BuildCombination,
    characterBaseCharacteristics: Map<Characteristic, Int>
): Double {

    val actualCharacteristicsValues = computeCharacteristicsValues(
        buildCombination,
        characterBaseCharacteristics,
        targetStats.masteryElementsWanted,
        targetStats.resistanceElementsWanted,
    )

    val totalActualScore = targetStats.sumOf { targetStat ->
        val weight = targetStats.weight(targetStat)
        val characteristicExpectedScore = targetStats.expectedScoreByCharacteristic.getValue(targetStat)
        val actualScore = when (targetStat.characteristic) {
            Characteristic.MASTERY_ELEMENTARY -> {
                val masteryElementary = actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY] ?: 0
                val fireValue = actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_FIRE] ?: 0
                val waterValue = actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_WATER] ?: 0
                val earthValue = actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_EARTH] ?: 0
                val windValue = actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_WIND] ?: 0
                val fireScore = (fireValue + masteryElementary) * weight
                val waterScore = (waterValue + masteryElementary) * weight
                val earthScore = (earthValue + masteryElementary) * weight
                val windScore = (windValue + masteryElementary) * weight
                (min(fireScore , characteristicExpectedScore)
                        + min(waterScore, characteristicExpectedScore)
                        + min(earthScore, characteristicExpectedScore)
                        + min(windScore, characteristicExpectedScore)) / 4
            }

            Characteristic.RESISTANCE_ELEMENTARY -> {
                val resistanceElementary = actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY] ?: 0
                val fireValue = actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_FIRE] ?: 0
                val waterValue = actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_WATER] ?: 0
                val earthValue = actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_EARTH] ?: 0
                val windValue = actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_WIND] ?: 0
                val fireScore = (fireValue + resistanceElementary) * weight
                val waterScore = (waterValue + resistanceElementary) * weight
                val earthScore = (earthValue + resistanceElementary) * weight
                val windScore = (windValue + resistanceElementary) * weight
                (min(fireScore , characteristicExpectedScore)
                        + min(waterScore, characteristicExpectedScore)
                        + min(earthScore, characteristicExpectedScore)
                        + min(windScore, characteristicExpectedScore)) / 4
            }

            else -> (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
        }

        min(actualScore, characteristicExpectedScore)
    }

    return (totalActualScore / targetStats.totalExpectedScore) * 100
}

fun computeCharacteristicsValues(
    buildCombination: BuildCombination,
    characterBaseCharacteristics: Map<Characteristic, Int>,
    masteryElementsWanted: Map<Characteristic, Int>,
    resistanceElementsWanted: Map<Characteristic, Int>
): Map<Characteristic, Int> {
    val eachCharacteristicValueLineByEquipment = buildCombination.equipments.flatMap { it.characteristics.entries }
        .groupBy({ it.key }, { it.value })

    val characteristicsGivenByEquipmentCombination: Map<Characteristic, Int> =
        eachCharacteristicValueLineByEquipment
            .mapValues { (_, values) -> values.sum() }

    val numberOfSetWith4ElementsOrMore = buildCombination.equipments
        .filter { it.level <= 35 && it.equipmentSetId !in listOf(0, 568, 567, 569) }
        .groupingBy { it.equipmentSetId }
        .eachCount()
        .count { it.value >= 4 }

    val edgeCasesCharacteristicsGivenByEquipment = mapOf(
        Characteristic.ACTION_POINT to (characteristicsGivenByEquipmentCombination[Characteristic.MAX_ACTION_POINT]
            ?: 0),
        Characteristic.MOVEMENT_POINT to (characteristicsGivenByEquipmentCombination[Characteristic.MAX_MOVEMENT_POINT]
            ?: 0),
        Characteristic.WAKFU_POINT to (characteristicsGivenByEquipmentCombination[Characteristic.MAX_WAKFU_POINTS] ?: 0)
    )

    val (characteristicGivenBySkillsFixedValues, characteristicGivenBySkillsPercentValues) = buildCombination
        .characterSkills
        .allCharacteristicValues

    val sumOfCharacteristicFixedValues = mergeAndSumCharacteristicValues(
        characteristicsGivenByEquipmentCombination,
        characteristicGivenBySkillsFixedValues,
        edgeCasesCharacteristicsGivenByEquipment,
        characterBaseCharacteristics,
        mapOf(Characteristic.ACTION_POINT to numberOfSetWith4ElementsOrMore)
    )

    val actualCharacteristics = sumOfCharacteristicFixedValues.mapValues { (key, value) ->
        characteristicGivenBySkillsPercentValues[key]?.let { percent ->
            (value + value * (percent.toDouble() / 100)).roundToInt()
        } ?: return@mapValues value
    }

    actualCharacteristics
        .toMutableMap()
        .let { mutableCharacteristicsValue ->
            if (masteryElementsWanted.isNotEmpty()) {
                val masteryElementsCurrent = masteryElementsWanted.mapValues {
                    (actualCharacteristics[it.key] ?: 0) + (actualCharacteristics[Characteristic.MASTERY_ELEMENTARY] ?: 0)
                }
                val masteryOneRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT]
                val masteryTwoRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT]
                val masteryThreeRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT]
                getAssignedValues(
                    masteryOneRandom ?: listOf(),
                    masteryTwoRandom ?: listOf(),
                    masteryThreeRandom ?: listOf(),
                    masteryElementsCurrent,
                    masteryElementsWanted
                ).forEach {
                    mutableCharacteristicsValue[it.key] = it.value
                }
            }

            if (resistanceElementsWanted.isNotEmpty()) {
                val resistanceElementsCurrent = resistanceElementsWanted.mapValues {
                    (actualCharacteristics[it.key] ?: 0) + (actualCharacteristics[Characteristic.RESISTANCE_ELEMENTARY] ?: 0)
                }
                val resistanceOneRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT]
                val resistanceTwoRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT]
                val resistanceThreeRandom =
                    eachCharacteristicValueLineByEquipment[Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT]
                getAssignedValues(
                    resistanceOneRandom ?: listOf(),
                    resistanceTwoRandom ?: listOf(),
                    resistanceThreeRandom ?: listOf(),
                    resistanceElementsCurrent,
                    resistanceElementsWanted
                ).forEach {
                    mutableCharacteristicsValue[it.key] = it.value
                }
            }
        }


    return actualCharacteristics
}

fun mergeAndSumCharacteristicValues(
    vararg characteristicValuesMaps: Map<Characteristic, Int>
): Map<Characteristic, Int> = characteristicValuesMaps.flatMap { it.entries }
    .groupingBy { it.key }
    .fold(0) { accumulator, element -> accumulator + element.value }


fun getAssignedValues(
    oneRandomElement: List<Int>,
    twoRandomElement: List<Int>,
    threeRandomElement: List<Int>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = characteristicToValueCurrent[characteristic] ?: 0
    }

    val valueToNumberOfCharacteristicAssignable = mutableMapOf<Int, Int>()
    for (value in oneRandomElement) {
        valueToNumberOfCharacteristicAssignable[value] = 1
    }
    for (value in twoRandomElement) {
        valueToNumberOfCharacteristicAssignable[value] = 2
    }
    for (value in threeRandomElement) {
        valueToNumberOfCharacteristicAssignable[value] = 3
    }

    for ((value, count) in valueToNumberOfCharacteristicAssignable) {
        val sortedCategories = characteristicToValueWanted.entries.sortedByDescending { entry ->
            val currentValue = result[entry.key]!!
            val target = entry.value
            val difference = target - currentValue
            difference
        }
        var assignedCount = 0
        for (entry in sortedCategories) {
            if (assignedCount >= count) break
            val characteristic = entry.key
            val currentValue = result[characteristic]!!
            val target = entry.value
            if (currentValue < target) {
                result[characteristic] = currentValue + value
                assignedCount++
            }
        }
    }

    return result
}
