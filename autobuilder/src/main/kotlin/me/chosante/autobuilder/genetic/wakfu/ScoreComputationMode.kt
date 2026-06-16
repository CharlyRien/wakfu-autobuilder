package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic

enum class ScoreComputationMode(
    val marketingName: String,
) {
    FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT("most-masteries"),
    FIND_CLOSEST_BUILD_FROM_INPUT("precision"),
    FIND_BUILD_WITH_MAX_DAMAGE("max-damage"),
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

private val RANDOM_ELEMENT_STATS =
    setOf(
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
    )

/**
 * Item-level "random element" stats: they are rolled onto concrete elements when a build is scored,
 * not stats a user meaningfully targets. They are not exposed as targets in the CLI or GUI, so this is
 * defensive — but if one ever reached [TargetStats] it must not be treated as a hard exact constraint.
 */
fun Characteristic.isRandomElementStat(): Boolean = this in RANDOM_ELEMENT_STATS

/**
 * True for characteristics that "most-masteries" mode enforces as exact required targets (the score's
 * numerator/denominator), as opposed to the masteries it maximizes or the random-element stats it
 * distributes. Used by both the scorer and the OR-Tools solver so they stay in lockstep.
 */
fun Characteristic.isRequiredMostMasteriesTarget(): Boolean = !isMaximizableMastery() && !isRandomElementStat()
