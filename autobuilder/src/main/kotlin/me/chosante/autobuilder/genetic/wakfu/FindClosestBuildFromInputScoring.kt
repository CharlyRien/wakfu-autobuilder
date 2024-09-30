package me.chosante.autobuilder.genetic.wakfu

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.min
import kotlin.math.roundToInt
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic

object FindClosestBuildFromInputScoring {
    fun computeScore(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
    ): BigDecimal {
        val actualCharacteristicsValues =
            computeCharacteristicsValues(
                buildCombination,
                characterBaseCharacteristics,
                targetStats.masteryElementsWanted,
                targetStats.resistanceElementsWanted
            )

        var totalActualScore = calculateTotalActualScore(targetStats, actualCharacteristicsValues, targetStats.expectedScoreByCharacteristic, canExceedPerfectScore = false)

        // Apply penalty on asked characteristics with target 0 and negative value on the current build
        targetStats
            .filter { it.target == 0 }
            .forEach { targetStat ->
                actualCharacteristicsValues[targetStat.characteristic]?.let {
                    if (it < 0) {
                        totalActualScore /= 2
                    }
                }
            }

        val successPercentage = (totalActualScore / targetStats.totalExpectedScore) * 100.0

        // try to find better build when we have found build maximizing every characteristic asked
        if (successPercentage.toBigDecimal() == BigDecimal("100.0")) {
            val calculateTotalActualScoreExceedingPerfectScore =
                calculateTotalActualScore(targetStats, actualCharacteristicsValues, targetStats.expectedScoreByCharacteristic, canExceedPerfectScore = true)
            return ((calculateTotalActualScoreExceedingPerfectScore / targetStats.totalExpectedScore) * 100).toBigDecimal(MathContext(4, RoundingMode.FLOOR))
        }
        return successPercentage.toBigDecimal(MathContext(4, RoundingMode.FLOOR))
    }

    private fun calculateTotalActualScore(
        targetStats: TargetStats,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val totalActualScore =
            targetStats.sumOf { targetStat ->
                val weight = targetStats.weight(targetStat)
                val actualScore =
                    when (targetStat.characteristic) {
                        Characteristic.MASTERY_ELEMENTARY ->
                            calculateElementaryMasteryScore(
                                targetStat,
                                actualCharacteristicsValues,
                                weight,
                                expectedScoreByCharacteristic,
                                canExceedPerfectScore
                            )

                        Characteristic.RESISTANCE_ELEMENTARY ->
                            calculateElementaryResistanceScore(
                                targetStat,
                                actualCharacteristicsValues,
                                weight,
                                expectedScoreByCharacteristic,
                                canExceedPerfectScore
                            )

                        else -> (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
                    }
                if (canExceedPerfectScore) actualScore else min(actualScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalActualScore
    }

    private fun calculateElementaryMasteryScore(
        targetStat: TargetStat,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        weight: Double,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val elements =
            listOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_ELEMENTARY_WATER,
                Characteristic.MASTERY_ELEMENTARY_EARTH,
                Characteristic.MASTERY_ELEMENTARY_WIND
            )
        val totalScore =
            elements.sumOf { element ->
                val elementValue = actualCharacteristicsValues[element] ?: 0
                val actualElementScore = elementValue * weight
                if (canExceedPerfectScore) actualElementScore else min(actualElementScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalScore / 4
    }

    private fun calculateElementaryResistanceScore(
        targetStat: TargetStat,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        weight: Double,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val elements =
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                Characteristic.RESISTANCE_ELEMENTARY_WATER,
                Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                Characteristic.RESISTANCE_ELEMENTARY_WIND
            )
        val totalScore =
            elements.sumOf { element ->
                val elementValue = actualCharacteristicsValues[element] ?: 0
                val actualElementScore = elementValue * weight
                if (canExceedPerfectScore) actualElementScore else min(actualElementScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalScore / 4
    }
}

fun computeCharacteristicsValues(
    buildCombination: BuildCombination,
    characterBaseCharacteristics: Map<Characteristic, Int>,
    masteryElementsWanted: Map<Characteristic, Int>,
    resistanceElementsWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> {
    val eachCharacteristicValueLineByEquipment =
        buildCombination.equipments
            .flatMap { it.characteristics.entries }
            .groupBy({ it.key }, { it.value })

    val characteristicsGivenByEquipmentCombination: Map<Characteristic, Int> =
        eachCharacteristicValueLineByEquipment
            .mapValues { (_, values) -> values.sum() }

    val edgeCasesCharacteristicsGivenByEquipment =
        mapOf(
            Characteristic.ACTION_POINT to (
                characteristicsGivenByEquipmentCombination[Characteristic.MAX_ACTION_POINT]
                    ?: 0
                ),
            Characteristic.MOVEMENT_POINT to (
                characteristicsGivenByEquipmentCombination[Characteristic.MAX_MOVEMENT_POINT]
                    ?: 0
                ),
            Characteristic.WAKFU_POINT to (characteristicsGivenByEquipmentCombination[Characteristic.MAX_WAKFU_POINTS] ?: 0)
        )

    val (characteristicGivenBySkillsFixedValues, characteristicGivenBySkillsPercentValues) =
        buildCombination
            .characterSkills
            .allCharacteristicValues

    val sumOfCharacteristicFixedValues =
        mergeAndSumCharacteristicValues(
            characteristicsGivenByEquipmentCombination,
            characteristicGivenBySkillsFixedValues,
            edgeCasesCharacteristicsGivenByEquipment,
            characterBaseCharacteristics
        )

    val mutableActualCharacteristics = sumOfCharacteristicFixedValues.toMutableMap()
    if (masteryElementsWanted.isNotEmpty()) {
        val currentSpecificMasteryElements = currentStatSpecificElements(masteryElementsWanted, sumOfCharacteristicFixedValues, Characteristic.MASTERY_ELEMENTARY)
        val specificMasteryElementsWithRandomValuesAssigned =
            assignUniformlyMasteryRandomValues(
                getMasteryRandoms(eachCharacteristicValueLineByEquipment),
                currentSpecificMasteryElements,
                masteryElementsWanted
            )
        specificMasteryElementsWithRandomValuesAssigned.forEach {
            mutableActualCharacteristics[it.key] = it.value
        }
        mutableActualCharacteristics[Characteristic.MASTERY_ELEMENTARY] = specificMasteryElementsWithRandomValuesAssigned.minOfOrNull { it.value } ?: 0
    }

    val resistanceElementsCurrent = currentStatSpecificElements(resistanceElementsWanted, sumOfCharacteristicFixedValues, Characteristic.RESISTANCE_ELEMENTARY)
    if (resistanceElementsWanted.isNotEmpty()) {
        val specificResistanceElementsWithRandomValuesAssigned =
            assignUniformlyResistanceRandomValues(
                getResistanceRandoms(eachCharacteristicValueLineByEquipment),
                resistanceElementsCurrent,
                resistanceElementsWanted
            )
        specificResistanceElementsWithRandomValuesAssigned.forEach {
            mutableActualCharacteristics[it.key] = it.value
        }

        specificResistanceElementsWithRandomValuesAssigned.minOfOrNull { it.value }?.let {
            mutableActualCharacteristics[Characteristic.RESISTANCE_ELEMENTARY] = it
        }
    }

    val actualCharacteristics =
        mutableActualCharacteristics.mapValues { (key, value) ->
            characteristicGivenBySkillsPercentValues[key]?.let { percent ->
                (value + value * (percent.toDouble() / 100)).roundToInt()
            } ?: return@mapValues value
        }

    return actualCharacteristics
}

fun mergeAndSumCharacteristicValues(vararg characteristicValuesMaps: Map<Characteristic, Int>): Map<Characteristic, Int> =
    characteristicValuesMaps
        .flatMap { it.entries }
        .groupingBy { it.key }
        .fold(0) { accumulator, element -> accumulator + element.value }

fun assignUniformlyMasteryRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = characteristicToValueCurrent[characteristic] ?: 0
    }

    val valueToNumberOfCharacteristicAssignable = mutableListOf<Pair<Int, Int>>()
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 1)
    }
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 2)
    }
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 3)
    }

    return result.assignValues(valueToNumberOfCharacteristicAssignable, characteristicToValueWanted)
}

fun assignUniformlyResistanceRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = characteristicToValueCurrent[characteristic] ?: 0
    }

    val valueToNumberOfCharacteristicAssignable = mutableListOf<Pair<Int, Int>>()
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 1)
    }
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 2)
    }
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 3)
    }

    return result.assignValues(valueToNumberOfCharacteristicAssignable, characteristicToValueWanted)
}

private fun MutableMap<Characteristic, Int>.assignValues(
    valueToNumberOfCharacteristicAssignable: List<Pair<Int, Int>>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): MutableMap<Characteristic, Int> {
    for ((value, count) in valueToNumberOfCharacteristicAssignable) {
        val sortedCategories =
            characteristicToValueWanted.entries.sortedByDescending { entry ->
                val currentValue = this[entry.key]!!
                val target = entry.value
                val difference = target - currentValue
                difference
            }
        var assignedCount = 0
        for (entry in sortedCategories) {
            if (assignedCount >= count) break
            val characteristic = entry.key
            val currentValue = this[characteristic]!!
            this[characteristic] = currentValue + value
            assignedCount++
        }
    }
    return this
}

internal fun currentStatSpecificElements(
    elementsWanted: Map<Characteristic, Int>,
    actualCharacteristics: Map<Characteristic, Int>,
    characteristic: Characteristic,
): Map<Characteristic, Int> =
    elementsWanted.mapValues {
        (actualCharacteristics[it.key] ?: 0) + (actualCharacteristics[characteristic] ?: 0)
    }

internal fun getMasteryRandoms(eachCharacteristicValueLineByEquipment: Map<Characteristic, List<Int>>) =
    eachCharacteristicValueLineByEquipment.filterKeys {
        it in
            listOf(
                Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
                Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
                Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
            )
    }

internal fun getResistanceRandoms(eachCharacteristicValueLineByEquipment: Map<Characteristic, List<Int>>) =
    eachCharacteristicValueLineByEquipment.filterKeys {
        it in
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
                Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
                Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
            )
    }
