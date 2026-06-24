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
        // Aggregate RESISTANCE_ELEMENTARY makes the resistance score a min over the four elements (water-fill
        // optimally); specific per-element resistance targets stay capped/greedy.
        val resistanceElementsToMinimize =
            if (targetStats.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }) {
                targetStats.resistanceElementsWanted.keys.toList()
            } else {
                null
            }
        val actualCharacteristicsValues =
            computeCharacteristicsValues(
                buildCombination,
                characterBaseCharacteristics,
                targetStats.masteryElementsWanted,
                targetStats.resistanceElementsWanted,
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                masteryElementsToMinimize = targetStats.masteryElementsToMinimize,
                resistanceElementsToMinimize = resistanceElementsToMinimize
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

        // Fold the global % Damage Inflicted multiplier in exactly as the CP-SAT objective does
        // (StatBuilder.diAdjustedMasteryScore): maximize mastery × (1 + DI/100) so the proxy is damage-faithful
        // and a −DI choice only pays off when its mastery gain outweighs it. The mastery is clamped to ≥ 0
        // BEFORE the multiply (mirroring the objective, which clamps via clampVar) so a negative-mastery build
        // can never invert the DI incentive; DI is clamped to [−FLOOR, MAX] and the product is floored back onto
        // the objective's domain. Integer truncation mirrors CP-SAT's addDivisionEquality so the two never drift.
        val damageInflicted = (actualCharacteristicsValues[Characteristic.DAMAGE_INFLICTED] ?: 0).coerceIn(-DAMAGE_DI_FLOOR.toInt(), DAMAGE_DI_MAX.toInt())
        val diAdjustedScore =
            (maxOf(finalMasteryScore.toLong(), 0L) * (100L + damageInflicted) / 100L)
                .coerceIn(-MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX)

        return (diAdjustedScore.toBigDecimal() / penaltyFactor)
    }
}
