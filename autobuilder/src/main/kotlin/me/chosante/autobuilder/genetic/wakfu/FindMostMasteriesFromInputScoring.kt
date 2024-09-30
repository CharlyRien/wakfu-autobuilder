package me.chosante.autobuilder.genetic.wakfu

import java.math.BigDecimal
import java.math.RoundingMode
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic

object FindMostMasteriesFromInputScoring {
    private val characteristicsToCheckBeforeCheckingMasteries =
        listOf(
            Characteristic.ACTION_POINT,
            Characteristic.CONTROL,
            Characteristic.MOVEMENT_POINT,
            Characteristic.RANGE,
            Characteristic.WAKFU_POINT,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP,
            Characteristic.LOCK,
            Characteristic.DODGE,
            Characteristic.BLOCK_PERCENTAGE,
            Characteristic.GIVEN_ARMOR_PERCENTAGE,
            Characteristic.RECEIVED_ARMOR_PERCENTAGE,
            Characteristic.INITIATIVE,
            Characteristic.RESISTANCE_BACK,
            Characteristic.RESISTANCE_CRITICAL
        )

    private val masteryCharacteristicsWithoutElementaries =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    private val elementaryMasteries =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        )

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

        val totalActualScore =
            targetStats
                .sumOf { targetStat ->
                    val weight = targetStats.weight(targetStat)
                    val actualScore =
                        if (targetStat.characteristic in characteristicsToCheckBeforeCheckingMasteries) {
                            (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
                        } else {
                            0.0
                        }
                    actualScore.coerceAtMost(targetStats.expectedScoreByCharacteristic[targetStat] ?: 0.0)
                }.toBigDecimal()
                .setScale(4, RoundingMode.FLOOR)

        val totalExpectedScore =
            targetStats
                .filter { it.characteristic in characteristicsToCheckBeforeCheckingMasteries }
                .sumOf { it.target * targetStats.weight(it) }
                .toBigDecimal()
                .setScale(4, RoundingMode.FLOOR)

        val successPercentageOnAskedCharacteristic =
            ((totalActualScore.coerceAtLeast(1.0.toBigDecimal()) / totalExpectedScore.coerceAtLeast(1.0.toBigDecimal())) * 100.0.toBigDecimal()).coerceAtMost(100.0.toBigDecimal())
        // we calculate a penalty factor to penalize the score if the stats asked are too low compared to the stats we have
        val penaltyFactor = (100.0.toBigDecimal().setScale(4) / successPercentageOnAskedCharacteristic.coerceAtLeast(1.0.toBigDecimal())).pow(6)

        val masteriesStatsWithoutElementary = targetStats.filter { it.characteristic in masteryCharacteristicsWithoutElementaries }
        val sumOfMasteriesWithoutElementary =
            masteriesStatsWithoutElementary.sumOf {
                actualCharacteristicsValues[it.characteristic] ?: 0
            }

        val removeNegativeMasteries =
            actualCharacteristicsValues
                .filterKeys {
                    it in listOf(Characteristic.MASTERY_BACK, Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_BERSERK) && it !in targetStats.map { it.characteristic }
                }.filterValues { it < 0 }
                .values
                .sum()

        val lowestWantedElementaryMasteryValue =
            if (targetStats.any { it.characteristic == Characteristic.MASTERY_ELEMENTARY }) {
                elementaryMasteries.minOfOrNull { actualCharacteristicsValues[it] ?: 0 } ?: 0
            } else {
                targetStats
                    .filter { it.characteristic in elementaryMasteries }
                    .minOfOrNull { actualCharacteristicsValues[it.characteristic] ?: 0 } ?: 0
            }

        // Combine sums and adjust for negative mastery penalties
        val finalMasteryScore = sumOfMasteriesWithoutElementary + lowestWantedElementaryMasteryValue + removeNegativeMasteries

        return (finalMasteryScore.toBigDecimal() / penaltyFactor)
    }
}
