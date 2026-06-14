package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/**
 * Scores a build by the **expected in-game damage** of a fixed attack [DamageScenario], using Wakfu's
 * exact damage formula (docs/ENCHANTMENTS_PLAN.md §8):
 *
 * ```
 * dmg = Base × (1 + ΣMastery/100) × Orientation × Crit × (1 + ΣDI/100) × (1 − Res%/100)
 * E[dmg] = (1 − p)·dmg(non-crit) + p·dmg(crit)      // p = crit rate; a crit also adds critical mastery
 * ```
 *
 * `ΣMastery` is the sum of the masteries that apply in the scenario: the spell element's mastery
 * (with generic elemental mastery folded in), the distance/melee secondary, rear mastery on a back hit,
 * and the optional berserk / healing masteries. `% Damage Inflicted` is a separate multiplicative
 * factor (floored at −50%). Any required AP/MP/range/etc. targets are enforced with the same
 * shortfall penalty as the most-masteries scorer, so the damage score is divided down when a hard
 * constraint is missed.
 */
object FindMaxDamageScoring {
    fun computeScore(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
        scenario: DamageScenario,
    ): BigDecimal {
        val stats =
            computeCharacteristicsValues(
                buildCombination,
                characterBaseCharacteristics,
                // Fold generic elemental mastery into the scenario's element so the read below already
                // includes both the specific-element and the "+all elements" contributions.
                masteryElementsWanted = mapOf(scenario.element.masteryCharacteristic to 1),
                // Real resistance targets so the penalty's stats include RESISTANCE_ELEMENTARY / per-element
                // resistances (an emptyMap read them as 0, so a required resistance couldn't rank builds).
                resistanceElementsWanted = targetStats.resistanceElementsWanted,
                // Mode + scenario let the sublimation fold gate scenario-specific effects (and apply the
                // build-static conditional ones) for the chosen build's stats.
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                damageScenario = scenario
            )

        val expectedDamage = expectedDamage(stats, scenario)
        val penaltyFactor = requiredConstraintPenaltyFactor(targetStats, stats)
        return expectedDamage.divide(penaltyFactor, 4, RoundingMode.FLOOR)
    }

    /** Expected damage of a single hit for [scenario] given the build's resolved [stats]. */
    fun expectedDamage(
        stats: Map<Characteristic, Int>,
        scenario: DamageScenario,
    ): BigDecimal {
        fun value(characteristic: Characteristic): Int = stats[characteristic] ?: 0

        var masteryBase = value(scenario.element.masteryCharacteristic)
        masteryBase += value(scenario.rangeBand.masteryCharacteristic)
        if (scenario.orientation.grantsRearMastery) masteryBase += value(Characteristic.MASTERY_BACK)
        if (scenario.berserk) masteryBase += value(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) masteryBase += value(Characteristic.MASTERY_HEALING)

        val critMastery = value(Characteristic.MASTERY_CRITICAL)
        val damageInflicted = max(value(Characteristic.DAMAGE_INFLICTED), -DAMAGE_INFLICTED_FLOOR)
        val critRate = value(Characteristic.CRITICAL_HIT).coerceIn(0, 100).coerceAtMost(scenario.critCapPercent) / 100.0

        val constantFactor =
            scenario.baseDamage.toDouble() *
                (scenario.orientation.multiplierPercent / 100.0) *
                (1.0 + damageInflicted / 100.0) *
                // Resistance ∈ [−100, +90]% (weakness raises damage, capped at 2.0×) — matches both
                // SpellDamage.expectedDamage and the CP-SAT objective's resistance-factor bounds.
                (1.0 - scenario.targetResistancePercent.coerceIn(-100, DamageScenario.MAX_RESISTANCE_PERCENT) / 100.0)

        val nonCrit = constantFactor * (1.0 + masteryBase / 100.0)
        val crit = constantFactor * 1.25 * (1.0 + (masteryBase + critMastery) / 100.0)
        val expected = (1.0 - critRate) * nonCrit + critRate * crit
        return expected.toBigDecimal()
    }

    /**
     * Replicates the most-masteries shortfall penalty: builds that fall short of the required hard
     * targets (AP/MP/range/HP/…) are divided down by `(100 / successPercentage)^6`, so the solver and
     * scorer both prefer constraint-satisfying builds. Returns 1 when every required target is met (or
     * none are requested).
     */
    internal fun requiredConstraintPenaltyFactor(
        targetStats: TargetStats,
        stats: Map<Characteristic, Int>,
    ): BigDecimal {
        val totalActual =
            targetStats
                .sumOf { targetStat ->
                    if (targetStat.characteristic.isRequiredMostMasteriesTarget()) {
                        val weight = targetStats.weight(targetStat)
                        ((stats[targetStat.characteristic] ?: 0) * weight)
                            .coerceAtMost(targetStats.expectedScoreByCharacteristic[targetStat] ?: 0.0)
                    } else {
                        0.0
                    }
                }.toBigDecimal()
                .setScale(4, RoundingMode.FLOOR)

        val totalExpected =
            targetStats
                .filter { it.characteristic.isRequiredMostMasteriesTarget() }
                .sumOf { it.target * targetStats.weight(it) }
                .toBigDecimal()
                .setScale(4, RoundingMode.FLOOR)

        if (totalExpected <= BigDecimal.ONE) return BigDecimal.ONE

        val successPercentage =
            ((totalActual.coerceAtLeast(BigDecimal.ONE) / totalExpected.coerceAtLeast(BigDecimal.ONE)) * BigDecimal(100))
                .coerceAtMost(BigDecimal(100))
        return (BigDecimal(100).setScale(4) / successPercentage.coerceAtLeast(BigDecimal.ONE)).pow(6)
    }

    private const val DAMAGE_INFLICTED_FLOOR = 50
}
