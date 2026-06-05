package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic

enum class ScoreComputationMode(
    val marketingName: String,
) {
    FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT("most-masteries"),
    FIND_CLOSEST_BUILD_FROM_INPUT("precision"),
    ;

    companion object {
        fun from(marketingName: String): ScoreComputationMode? = entries.firstOrNull { it.marketingName == marketingName }
    }
}

fun Characteristic.isMaximizableMastery(): Boolean =
    this in
        setOf(
            Characteristic.MASTERY_ELEMENTARY,
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_WIND,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_HEALING
        )
