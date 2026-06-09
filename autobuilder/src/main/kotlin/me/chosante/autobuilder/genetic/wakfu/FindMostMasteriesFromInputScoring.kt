package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.math.BigDecimal
import java.math.RoundingMode

object FindMostMasteriesFromInputScoring {
    private val masteryCharacteristicsWithoutElementaries =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
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
                        if (targetStat.characteristic.isRequiredMostMasteriesTarget()) {
                            (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
                        } else {
                            0.0
                        }
                    actualScore.coerceAtMost(targetStats.expectedScoreByCharacteristic[targetStat] ?: 0.0)
                }.toBigDecimal()
                .setScale(4, RoundingMode.FLOOR)

        val totalExpectedScore =
            targetStats
                .filter { it.characteristic.isRequiredMostMasteriesTarget() }
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

        val targetCharacteristics = targetStats.map { it.characteristic }
        val removeNegativeMasteries =
            actualCharacteristicsValues
                .filterKeys { characteristic ->
                    characteristic in
                        listOf(
                            Characteristic.MASTERY_BACK,
                            Characteristic.MASTERY_CRITICAL,
                            Characteristic.MASTERY_BERSERK
                        ) &&
                        characteristic !in targetCharacteristics
                }.filterValues { it < 0 }
                .values
                .sum()

        // Specific elements win over a co-requested "all elements" (MASTERY_ELEMENTARY): minimise
        // over the elements the user actually asked for, so adding "all elements" no longer drags the
        // objective onto off-elements they never wanted. Mirrors the OR-Tools objective via the shared
        // TargetStats.masteryElementsToMinimize so both engines optimise the same value.
        val lowestWantedElementaryMasteryValue =
            targetStats.masteryElementsToMinimize
                .minOfOrNull { actualCharacteristicsValues[it] ?: 0 } ?: 0

        // Combine sums and adjust for negative mastery penalties
        val finalMasteryScore = sumOfMasteriesWithoutElementary + lowestWantedElementaryMasteryValue + removeNegativeMasteries

        return (finalMasteryScore.toBigDecimal() / penaltyFactor)
    }
}
