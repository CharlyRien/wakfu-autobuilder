package me.chosante.autobuilder.genetic.wakfu

import java.math.BigDecimal
import java.math.RoundingMode
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic

object FindMostMasteriesFromInputScoring {
    fun computeScore(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
    ): BigDecimal {
        val actualCharacteristicsValues = computeCharacteristicsValues(
            buildCombination,
            characterBaseCharacteristics,
            targetStats.masteryElementsWanted,
            targetStats.resistanceElementsWanted
        )

        val totalActualScore = targetStats.sumOf { targetStat ->
            val weight = targetStats.weight(targetStat)
            val actualScore = when (targetStat.characteristic) {
                in listOf(
                    Characteristic.ACTION_POINT,
                    Characteristic.CONTROL,
                    Characteristic.MOVEMENT_POINT,
                    Characteristic.RANGE,
                    Characteristic.WAKFU_POINT,
                    Characteristic.CRITICAL_HIT
                ),
                -> (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight

                else -> 0.0
            }
            actualScore.coerceAtMost(targetStats.expectedScoreByCharacteristic.getValue(targetStat))
        }.toBigDecimal().setScale(4, RoundingMode.FLOOR)

        val totalExpectedScore = targetStats.filter {
            it.characteristic in listOf(
                Characteristic.ACTION_POINT,
                Characteristic.CONTROL,
                Characteristic.MOVEMENT_POINT,
                Characteristic.RANGE,
                Characteristic.WAKFU_POINT,
                Characteristic.CRITICAL_HIT
            )
        }.sumOf {
            it.target * targetStats.weight(it)
        }.toBigDecimal().setScale(4, RoundingMode.FLOOR)

        val successPercentageOnAskedCharacteristic =
            ((totalActualScore.coerceAtLeast(1.0.toBigDecimal()) / totalExpectedScore.coerceAtLeast(1.0.toBigDecimal())) * 100.0.toBigDecimal()).coerceAtMost(100.0.toBigDecimal())
        val penaltyFactor = (100.0.toBigDecimal().setScale(4) / successPercentageOnAskedCharacteristic.coerceAtLeast(1.0.toBigDecimal())).pow(6)
        val targetedStats = targetStats.filter {
            it.characteristic in listOf(
                Characteristic.MASTERY_BACK,
                Characteristic.MASTERY_BERSERK,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.MASTERY_DISTANCE,
                Characteristic.MASTERY_HEALING,
                Characteristic.MASTERY_MELEE
            )
        }
        return targetedStats.sumOf {
            (actualCharacteristicsValues[it.characteristic] ?: 0)
        }.let { sumOfMasteriesWithoutElementary ->
            val removeNegativeMasteries = actualCharacteristicsValues.entries.sumOf {
                if (it.key in listOf(
                        Characteristic.MASTERY_BACK,
                        Characteristic.MASTERY_CRITICAL,
                        Characteristic.MASTERY_BERSERK
                    ) && it.value < 0
                ) {
                    it.value
                } else {
                    0
                }
            }

            val lowestWantedElementaryMasteryValue = targetStats
                .filter {
                    it.characteristic in listOf(
                        Characteristic.MASTERY_ELEMENTARY_WATER,
                        Characteristic.MASTERY_ELEMENTARY_FIRE,
                        Characteristic.MASTERY_ELEMENTARY_EARTH,
                        Characteristic.MASTERY_ELEMENTARY_WIND
                    )
                }.minOfOrNull { (actualCharacteristicsValues[it.characteristic] ?: 0) } ?: 0
            ((sumOfMasteriesWithoutElementary + lowestWantedElementaryMasteryValue + removeNegativeMasteries).toBigDecimal() / penaltyFactor)
        }
    }
}
