package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import kotlin.math.min
import kotlin.math.roundToInt

fun calculateSuccessPercentage(
    targetStats: TargetStats,
    buildCombination: BuildCombination,
): Double {

    val actualCharacteristicsValues = computeCharacteristicsValues(
        buildCombination,
        targetStats.masteryElementsWanted,
        targetStats.resistanceElementsWanted,
        targetStats.masteryElementaryWanted,
        targetStats.resistanceElementaryWanted
    )

    val totalActualScore = targetStats.sumOf { targetStat ->
        val weight = targetStats.weight(targetStat)
        val characteristicExpectedScore = targetStats.expectedScoreByCharacteristic.getValue(targetStat)
        val actualScore = when (targetStat.characteristic) {
            Characteristic.MASTERY_ELEMENTARY -> {
                val fire = (actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_FIRE] ?: 0) * weight
                val water = (actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_WATER] ?: 0) * weight
                val earth = (actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_EARTH] ?: 0) * weight
                val wind = (actualCharacteristicsValues[Characteristic.MASTERY_ELEMENTARY_WIND] ?: 0) * weight
                (min(fire, characteristicExpectedScore)
                        + min(water, characteristicExpectedScore)
                        + min(earth, characteristicExpectedScore)
                        + min(wind, characteristicExpectedScore)) / 4
            }
            Characteristic.RESISTANCE_ELEMENTARY -> {
                val fire = (actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_FIRE] ?: 0) * weight
                val water = (actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_WATER] ?: 0) * weight
                val earth = (actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_EARTH] ?: 0) * weight
                val wind = (actualCharacteristicsValues[Characteristic.RESISTANCE_ELEMENTARY_WIND] ?: 0) * weight
                (min(fire, characteristicExpectedScore)
                        + min(water, characteristicExpectedScore)
                        + min(earth, characteristicExpectedScore)
                        + min(wind, characteristicExpectedScore)) / 4
            }
            else -> {
                (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
            }
        }

        min(actualScore, characteristicExpectedScore)
    }

    return (totalActualScore / targetStats.totalExpectedScore) * 100
}

fun computeCharacteristicsValues(
    buildCombination: BuildCombination,
    masteryElementsWanted: Map<Characteristic, Int>,
    resistanceElementsWanted: Map<Characteristic, Int>,
    masteryElementaryWanted: Int?,
    resistanceElementaryWanted: Int?
): Map<Characteristic, Int> {
    val eachCharacteristicValueLineByEquipment = buildCombination.equipments.flatMap { it.characteristics.entries }
        .groupBy({ it.key }, { it.value })
        .toMutableMap()
        .let { characteristicValues ->
            val masteryElementaryValue = characteristicValues[Characteristic.MASTERY_ELEMENTARY]?.sum() ?: 0
            val resistanceElementaryValue = characteristicValues[Characteristic.RESISTANCE_ELEMENTARY]?.sum() ?: 0
            if (masteryElementsWanted.isNotEmpty()) {
                getMasteryElementsWithRandomElementsAssigned(characteristicValues, masteryElementsWanted)
                    .forEach { (key, value) ->
                        characteristicValues[key] = listOf(value + masteryElementaryValue)
                    }
            }
            if (resistanceElementsWanted.isNotEmpty()) {
                getResistanceElementsWithRandomElementsAssigned(characteristicValues, resistanceElementsWanted)
                    .forEach { (key, value) ->
                        characteristicValues[key] = listOf(value + resistanceElementaryValue)
                    }
            }

            masteryElementaryWanted?.let { masteryElementaryTarget ->
                getMasteryElementsWithRandomElementsAssigned(
                    characteristicValues = characteristicValues,
                    elementsWanted = mapOf(
                        Characteristic.MASTERY_ELEMENTARY_WIND to masteryElementaryTarget,
                        Characteristic.MASTERY_ELEMENTARY_EARTH to masteryElementaryTarget,
                        Characteristic.MASTERY_ELEMENTARY_WATER to masteryElementaryTarget,
                        Characteristic.MASTERY_ELEMENTARY_FIRE to masteryElementaryTarget
                    )
                ).forEach {
                    characteristicValues[it.key] = listOf(it.value + masteryElementaryValue)
                }
            }

            resistanceElementaryWanted?.let { resistanceElementaryTarget ->
                getResistanceElementsWithRandomElementsAssigned(
                    characteristicValues = characteristicValues,
                    resistanceElementsWanted = mapOf(
                        Characteristic.RESISTANCE_ELEMENTARY_WIND to resistanceElementaryTarget,
                        Characteristic.RESISTANCE_ELEMENTARY_EARTH to resistanceElementaryTarget,
                        Characteristic.RESISTANCE_ELEMENTARY_WATER to resistanceElementaryTarget,
                        Characteristic.RESISTANCE_ELEMENTARY_FIRE to resistanceElementaryTarget
                    )
                ).forEach {
                    characteristicValues[it.key] = listOf(it.value + resistanceElementaryValue)
                }
            }

            characteristicValues
        }
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
        mapOf(Characteristic.ACTION_POINT to numberOfSetWith4ElementsOrMore)
    )

    val actualCharacteristics = sumOfCharacteristicFixedValues.mapValues { (key, value) ->
        characteristicGivenBySkillsPercentValues[key]?.let { percent ->
            (value + value * (percent.toDouble() / 100)).roundToInt()
        } ?: return@mapValues value
    }

    return actualCharacteristics
}

private fun getMasteryElementsWithRandomElementsAssigned(
    characteristicValues: Map<Characteristic, List<Int>>,
    elementsWanted: Map<Characteristic, Int>
): Map<Characteristic, Int> {
    val oneRandomMastery = characteristicValues[Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()
    val twoRandomMasteryValue = characteristicValues[Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()
    val threeRandomMastery = characteristicValues[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()

    val masteryToAssign = assignValues(
        oneRandomMastery,
        twoRandomMasteryValue,
        threeRandomMastery,
        elementsWanted
    )

    masteryToAssign.mapValues { (characteristic, value) ->
        value + (characteristicValues[characteristic]?.sum() ?: 0)
    }

    return masteryToAssign
}

private fun getResistanceElementsWithRandomElementsAssigned(
    characteristicValues: Map<Characteristic, List<Int>>,
    resistanceElementsWanted: Map<Characteristic, Int>
): Map<Characteristic, Int> {
    val oneRandomResistance = characteristicValues[Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()
    val twoRandomResistance = characteristicValues[Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()
    val threeRandomResistance =
        characteristicValues[Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()

    val resistanceToAssign = assignValues(
        oneRandomResistance,
        twoRandomResistance,
        threeRandomResistance,
        resistanceElementsWanted
    )

    resistanceToAssign.mapValues { (characteristic, value) ->
        value + (characteristicValues[characteristic]?.sum() ?: 0)
    }

    return resistanceToAssign
}

fun mergeAndSumCharacteristicValues(
    vararg characteristicValuesMaps: Map<Characteristic, Int>
): Map<Characteristic, Int> = characteristicValuesMaps.flatMap { it.entries }
    .groupingBy { it.key }
    .fold(0) { accumulator, element -> accumulator + element.value }


fun assignValues(
    oneRandomElement: List<Int>,
    twoRandomElement: List<Int>,
    threeRandomElement: List<Int>,
    characteristicToValueWanted: Map<Characteristic, Int>
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = 0
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
