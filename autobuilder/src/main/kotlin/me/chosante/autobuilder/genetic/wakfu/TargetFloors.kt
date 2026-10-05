package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.math.BigDecimal
import java.math.RoundingMode

// The FLOORS of a request — its rows of target 0 on a required stat ([TargetStats.floorCharacteristics],
// [TargetStats.resistanceFloorElements]): "never below 0", in every mode. The ONE reading of a floor, shared by the solver
// ([StatBuilder.floorReads]) and the scorers (here), so the two can never disagree on whether a floor holds:
//  - most-masteries / max-damage HARD leg: `actual ≥ 0` ([StatBuilder.addRequiredTargetHardConstraints]);
//  - their SOFT legs: the penalized objective is HALVED while a floor is below 0 (`applyConstraintPenalty`; the scorers divide
//    their score by 2 — [requiredPenaltyFactor]). Once, however many floors are below 0 — like precision's halving, which it
//    mirrors. The leg stays feasible (it is the fallback for unreachable targets), and a factor of 1/2 composes with the power-6
//    shortfall multiplier as an extra divisor: the certificates, which ignore the floors, keep bounding the halved objective;
//  - precision: its halving ([precisionHalves]), which also reads the request's masteries of target 0
//    ([TargetStats.zeroMasteries]).
// A floor of an elemental resistance reads its element as the game does: its own lines, the "+all elements" ones and the
// random-element rolls the player puts there. In a request with such a floor the resistance family's rolls are placed over
// the wanted AND the floored elements together ([foldElements]): each roll lands on as many of them as the game lets it, the
// elements no row names absorbing the rest (so a positive roll can lift a floor, and a negative one only hits it when it has
// nowhere else to go) — in the solver's model and in the scorers' exact placement ([ElementRowObjective]) alike.

/** What the most-masteries / max-damage scorers divide their score by, on top of the shortfall penalty, while a floor is broken. */
internal val FLOOR_BROKEN_DIVISOR: BigDecimal = BigDecimal(2)

/** Whether a floor of this request reads below 0 in [stats], the scorers' resolved stats (resistance floors included). */
internal fun TargetStats.floorBroken(stats: Map<Characteristic, Int>): Boolean =
    floorCharacteristics.any { (stats[it] ?: 0) < 0 } || resistanceFloorElements.any { (stats[it] ?: 0) < 0 }

/**
 * Whether precision halves its score on [stats]: a floor below 0 ([floorBroken]), or a mastery of target 0 below 0
 * ([TargetStats.zeroMasteries], an element read on its fold). Mirrors the solver's `StatBuilder.negativeTargetPenalty`.
 */
internal fun TargetStats.precisionHalves(stats: Map<Characteristic, Int>): Boolean = floorBroken(stats) || zeroMasteries.any { (stats[it] ?: 0) < 0 }

/**
 * The most-masteries / max-damage scorers' shortfall penalty on [stats]: `(100 / success%)⁶` over the required rows (AP, MP,
 * range, HP, resistances…; each row's weighted value capped at its expected score), 1 when every required row is met or none is
 * requested, capped at [MAX_PENALTY_MULTIPLIER] like the solver's floored multiplier ([penaltyMultiplier]): a build whose targets
 * are far out of reach (< ~10%) keeps its core's gradient instead of dividing down by up to 1e12. The continuous counterpart of
 * the solver's bucketed power-6 table. The ONE definition both scorers use.
 */
internal fun TargetStats.requiredShortfallFactor(stats: Map<Characteristic, Int>): BigDecimal {
    val totalActual =
        sumOf { targetStat ->
            if (targetStat.characteristic.isRequiredMostMasteriesTarget()) {
                ((stats[targetStat.characteristic] ?: 0) * weight(targetStat)).coerceAtMost(expectedScoreByCharacteristic[targetStat] ?: 0.0)
            } else {
                0.0
            }
        }.toBigDecimal()
            .setScale(4, RoundingMode.FLOOR)
    val totalExpected =
        filter { it.characteristic.isRequiredMostMasteriesTarget() }
            .sumOf { it.target * weight(it) }
            .toBigDecimal()
            .setScale(4, RoundingMode.FLOOR)
    if (totalExpected <= BigDecimal.ONE) return BigDecimal.ONE
    val successPercentage =
        ((totalActual.coerceAtLeast(BigDecimal.ONE) / totalExpected.coerceAtLeast(BigDecimal.ONE)) * BigDecimal(100))
            .coerceAtMost(BigDecimal(100))
    return (BigDecimal(100).setScale(4) / successPercentage.coerceAtLeast(BigDecimal.ONE))
        .pow(6)
        .coerceAtMost(MAX_PENALTY_MULTIPLIER.toBigDecimal())
}

/**
 * What the most-masteries / max-damage scorers divide their score by on [stats]: the [requiredShortfallFactor], doubled while a
 * floor is broken ([floorBroken]) — the solver's soft leg halves its objective then. 1 iff every required row is met and every
 * floor holds. [stats] must place the resistance rolls as the request reads them (`elementRows`).
 */
internal fun TargetStats.requiredPenaltyFactor(stats: Map<Characteristic, Int>): BigDecimal {
    val shortfall = requiredShortfallFactor(stats)
    return if (floorBroken(stats)) shortfall * FLOOR_BROKEN_DIVISOR else shortfall
}
