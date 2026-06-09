package me.chosante.autobuilder.domain

import me.chosante.common.Characteristic
import java.math.RoundingMode

class TargetStats(
    targetStats: List<TargetStat>,
) : HashSet<TargetStat>(targetStats) {
    private val targetStatToWeight = targetStats.associateWeights(100)
    val totalExpectedScore = sumOf { it.target * targetStatToWeight.getValue(it) }
    val expectedScoreByCharacteristic: Map<TargetStat, Double> =
        associateWith { it.target * targetStatToWeight.getValue(it) }

    fun weight(targetStat: TargetStat): Double = targetStatToWeight.getValue(targetStat)

    val masteryElementsWanted =
        targetStats
            .firstOrNull { it.characteristic == Characteristic.MASTERY_ELEMENTARY }
            ?.let {
                mapOf(
                    Characteristic.MASTERY_ELEMENTARY_EARTH to it.target,
                    Characteristic.MASTERY_ELEMENTARY_WIND to it.target,
                    Characteristic.MASTERY_ELEMENTARY_WATER to it.target,
                    Characteristic.MASTERY_ELEMENTARY_FIRE to it.target
                )
            } ?: filter {
            it.characteristic in
                listOf(
                    Characteristic.MASTERY_ELEMENTARY_EARTH,
                    Characteristic.MASTERY_ELEMENTARY_WIND,
                    Characteristic.MASTERY_ELEMENTARY_WATER,
                    Characteristic.MASTERY_ELEMENTARY_FIRE
                )
        }.associate { it.characteristic to it.target }

    val resistanceElementsWanted =
        targetStats
            .firstOrNull { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }
            ?.let {
                mapOf(
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE to it.target
                )
            } ?: filter {
            it.characteristic in
                listOf(
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER,
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE
                )
        }.associate { it.characteristic to it.target }

    /**
     * Elements the "most-masteries" objective takes the *minimum* elemental mastery over. Specific
     * elements win: if the user asked for any of fire/earth/water/air, those define the set, so a
     * co-requested [Characteristic.MASTERY_ELEMENTARY] ("all elements") only lifts them via generic
     * gear instead of forcing the solver to also balance the elements they never asked for. The
     * aggregate expands to all four only when no specific element was requested. Always a subset of
     * [masteryElementsWanted]'s keys (which stays the full fold set), and mirrored by both the scorer
     * and the OR-Tools objective so the two engines optimise the same thing.
     */
    val masteryElementsToMinimize: List<Characteristic> =
        elementsToMinimizeOver(ELEMENTAL_MASTERIES, Characteristic.MASTERY_ELEMENTARY)

    private fun elementsToMinimizeOver(
        elements: List<Characteristic>,
        aggregate: Characteristic,
    ): List<Characteristic> {
        val requestedSpecifics = elements.filter { element -> any { it.characteristic == element } }
        return when {
            requestedSpecifics.isNotEmpty() -> requestedSpecifics
            any { it.characteristic == aggregate } -> elements
            else -> emptyList()
        }
    }

    companion object {
        private val ELEMENTAL_MASTERIES =
            listOf(
                Characteristic.MASTERY_ELEMENTARY_WATER,
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_ELEMENTARY_EARTH,
                Characteristic.MASTERY_ELEMENTARY_WIND
            )
    }
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
