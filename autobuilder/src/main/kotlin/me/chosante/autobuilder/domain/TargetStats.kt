package me.chosante.autobuilder.domain

import java.math.RoundingMode
import me.chosante.common.Characteristic

class TargetStats(targetStats: List<TargetStat>) :
    HashSet<TargetStat>(targetStats) {
    private val targetStatToWeight = targetStats.associateWeights(100)
    val totalExpectedScore = sumOf { it.target * targetStatToWeight.getValue(it) }
    val expectedScoreByCharacteristic: Map<TargetStat, Double> =
        associateWith { it.target * targetStatToWeight.getValue(it) }

    fun weight(targetStat: TargetStat): Double =
        targetStatToWeight.getValue(targetStat)

    val masteryElementsWanted = targetStats.firstOrNull { it.characteristic == Characteristic.MASTERY_ELEMENTARY }
        ?.let {
            mapOf(
                Characteristic.MASTERY_ELEMENTARY_EARTH to it.target,
                Characteristic.MASTERY_ELEMENTARY_WIND to it.target,
                Characteristic.MASTERY_ELEMENTARY_WATER to it.target,
                Characteristic.MASTERY_ELEMENTARY_FIRE to it.target
            )
        } ?: filter {
        it.characteristic in listOf(
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND,
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE
        )
    }.associate { it.characteristic to it.target }

    val resistanceElementsWanted = targetStats
        .firstOrNull { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }
        ?.let {
            mapOf(
                Characteristic.RESISTANCE_ELEMENTARY_EARTH to it.target,
                Characteristic.RESISTANCE_ELEMENTARY_WIND to it.target,
                Characteristic.RESISTANCE_ELEMENTARY_WATER to it.target,
                Characteristic.RESISTANCE_ELEMENTARY_FIRE to it.target
            )
        } ?: filter {
        it.characteristic in listOf(
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND,
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE
        )
    }.associate { it.characteristic to it.target }
}

fun List<TargetStat>.associateWeights(normalizeValue: Int): Map<TargetStat, Double> {
    return associateWith {
        if (it.target == 0) {
            return@associateWith 0.0
        }

        val normalizedWeight = normalizeValue.toBigDecimal().setScale(2, RoundingMode.HALF_UP) / it.target.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
        normalizedWeight.toDouble() * it.userDefinedWeight
    }
}
