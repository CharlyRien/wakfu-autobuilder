package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.math.BigDecimal

// The FLOORS of a request — its rows of target 0 on a required stat ([TargetStats.floorCharacteristics],
// [TargetStats.resistanceFloorElements]): "never below 0", in every mode. The ONE reading of a floor, shared by the solver
// ([StatBuilder.floorReads]) and the scorers (here), so the two can never disagree on whether a floor holds:
//  - most-masteries / max-damage HARD leg: `actual ≥ 0` ([StatBuilder.addRequiredTargetHardConstraints]);
//  - their SOFT legs: the penalized objective is HALVED while a floor is below 0 (`applyConstraintPenalty`; the scorers divide
//    their score by 2 — [FindMostMasteriesFromInputScoring], [FindMaxDamageScoring.requiredConstraintPenaltyFactor]). Once,
//    however many floors are below 0 — like precision's halving, which it mirrors. The leg stays feasible (it is the fallback
//    for unreachable targets), and a factor of 1/2 composes with the power-6 shortfall multiplier as an extra divisor: the
//    certificates, which ignore the floors, keep bounding the halved objective from above;
//  - precision: its halving ([precisionHalves]), which also reads the request's masteries of target 0
//    ([TargetStats.zeroMasteries]).
// A floor of an elemental resistance is read WITHOUT the random-element rolls (they only land on wanted elements): its element's
// own lines plus the "+all elements" ones — what [computeCharacteristicsValues] returns for it when handed
// [TargetStats.resistanceFloorElements].

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
