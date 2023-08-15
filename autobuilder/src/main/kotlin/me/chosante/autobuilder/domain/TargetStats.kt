package me.chosante.autobuilder.domain

import me.chosante.common.Characteristic
import java.math.RoundingMode

class TargetStats(targetStats: List<TargetStat>) :
    HashSet<TargetStat>(targetStats) {
    private val targetStatToWeight = targetStats.associateWeights(100)
    val totalExpectedScore = sumOf { it.target * targetStatToWeight.getValue(it) * it.userDefinedWeight }
    val expectedScoreByCharacteristic: Map<TargetStat, Double> =
        associateWith { it.target * targetStatToWeight.getValue(it) * it.userDefinedWeight }

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

//    fun removeCharacteristicValues(characteristicValues: Map<Characteristic, Int>): TargetStats {
//        val characteristicsToRemove = characteristicValues.keys.filterNot { it == Characteristic.HP }
//        return TargetStats(this.mapNotNull {
//            if (it.characteristic in characteristicsToRemove) {
//                val newTarget = it.target - characteristicValues.getValue(it.characteristic).coerceAtLeast(0)
//                if (newTarget == 0) {
//                    return@mapNotNull null
//                }
//                TargetStat(
//                    characteristic = it.characteristic,
//                    target = newTarget,
//                    userDefinedWeight = it.userDefinedWeight
//                )
//            } else {
//                it
//            }
//        })
//    }
}

fun List<TargetStat>.associateWeights(desiredValue: Int): Map<TargetStat, Double> {
    return associateWith {
        (desiredValue.toBigDecimal().setScale(2, RoundingMode.HALF_UP) / it.target.toBigDecimal()
            .setScale(2, RoundingMode.HALF_UP)).toDouble() * it.userDefinedWeight
    }
}