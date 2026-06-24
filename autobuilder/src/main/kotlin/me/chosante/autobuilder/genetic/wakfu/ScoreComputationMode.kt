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

/**
 * % Damage Inflicted clamp bounds, shared by the CP-SAT objective ([me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver])
 * and the re-scorers ([FindMostMasteriesFromInputScoring], [FindMaxDamageScoring]) so the damage-faithful score
 * stays in lockstep. [DAMAGE_DI_FLOOR] is Wakfu's −50% damage floor (DI can't reduce a hit below ×0.5);
 * [DAMAGE_DI_MAX] is a CP-SAT domain safety bound far above any real build. (common-lib's [me.chosante.common.SpellDamage]
 * keeps its own copy of the floor — it cannot depend on this module.)
 */
internal const val DAMAGE_DI_FLOOR = 50L
internal const val DAMAGE_DI_MAX = 5_000L

/**
 * Absolute bound of the "most-masteries" mastery-score domain, shared by the CP-SAT objective and the
 * re-scorer so the DI-folded score is clamped onto the same range on both sides (a no-op for any real build,
 * whose mastery sums are ≤ ~1e5).
 */
internal const val MASTERY_SCORE_ABS_MAX = 100_000_000L

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
