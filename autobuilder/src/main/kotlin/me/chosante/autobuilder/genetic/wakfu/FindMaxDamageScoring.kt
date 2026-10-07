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
        val stats = penaltyStats(targetStats, buildCombination, characterBaseCharacteristics, scenario)
        val expectedDamage = expectedDamage(stats, scenario)
        val penaltyFactor = requiredConstraintPenaltyFactor(targetStats, stats)
        return expectedDamage.divide(penaltyFactor, 4, RoundingMode.FLOOR)
    }

    /**
     * The stats a max-damage build is scored on — the ONE resolution every max-damage reader shares (this scorer, the search's
     * ranking and proof gate — `MaxDamageSearch.sequencedScore` / `fullyMeetsRequiredTargets` — the solver's rotation score, the
     * GUI's stats column), so none can read a required row or a floor differently from the others or from the solver:
     *  - the generic elemental mastery folded into [scenario]'s element, so the mastery read already includes both the
     *    specific-element and the "+all elements" contributions;
     *  - the real resistance targets (an empty map read RESISTANCE_ELEMENTARY / per-element resistances as 0, so a required
     *    resistance could not rank builds), their random rolls — and a floor's — placed where the solver's joint fold places
     *    them ([elementRowObjectives]);
     *  - the max-damage mode and [scenario], which gate the sublimation effects tied to it (berserk, orientation, range…) as
     *    the solver's terms do (`SublimationTerms`) and apply the build-static conditional ones. Without them a gated effect
     *    was dropped: "Esquive Berserk III" kept dodge ≥ 0 for the solver while this read −100 and halved the build.
     */
    fun penaltyStats(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
        scenario: DamageScenario,
    ): Map<Characteristic, Int> =
        computeCharacteristicsValues(
            buildCombination,
            characterBaseCharacteristics,
            masteryElementsWanted = mapOf(scenario.element.masteryCharacteristic to 1),
            resistanceElementsWanted = targetStats.resistanceElementsWanted,
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            damageScenario = scenario,
            elementRows = targetStats.elementRowObjectives(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
        )

    /**
     * Expected damage of a single hit for [scenario] given the build's resolved [stats].
     *
     * This is the **CP-SAT objective's mirror** — it matches `WakfuBuildSolver.perHitDamageScore`'s `graw`:
     * crit is modelled as `base × 1.25`, deliberately NOT the level-scaled `critDamage` the GUI display uses
     * (`SpellDamage.expectedDamage`). Keep it that way: re-aligning this to the display's per-spell `critDamage`
     * would make it diverge from what the solver actually optimizes and re-introduce the objective↔display
     * mismatch (the proven optimum would no longer be the displayed best). The two converge at realistic levels
     * anyway (`critDamage ≈ base × 1.25` once damage numbers are large).
     */
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
        val damageInflicted = max(value(Characteristic.DAMAGE_INFLICTED), -DAMAGE_DI_FLOOR.toInt())
        val critRate = value(Characteristic.CRITICAL_HIT).coerceIn(0, 100).coerceAtMost(scenario.critCapPercent) / 100.0

        val constantFactor =
            scenario.baseDamage.toDouble() *
                (scenario.orientation.multiplierPercent / 100.0) *
                (1.0 + damageInflicted / 100.0) *
                // Resistance ∈ [−100, +90]% (weakness raises damage, capped at 2.0×) — matches both
                // SpellDamage.expectedDamage and the CP-SAT objective's resistance-factor bounds.
                (1.0 - scenario.targetResistancePercent.coerceIn(-100, DamageScenario.MAX_RESISTANCE_PERCENT) / 100.0)

        val nonCrit = constantFactor * (1.0 + masteryBase / 100.0)
        // Crit = base × 1.25 (the objective's model — see the kdoc; NOT the display's level-scaled critDamage).
        val crit = constantFactor * 1.25 * (1.0 + (masteryBase + critMastery) / 100.0)
        val expected = (1.0 - critRate) * nonCrit + critRate * crit
        return expected.toBigDecimal()
    }

    /**
     * The most-masteries shortfall penalty ([requiredPenaltyFactor]): builds that fall short of the required hard targets
     * (AP/MP/range/HP/…) are divided down by `(100 / successPercentage)^6`, so the solver and scorer both prefer
     * constraint-satisfying builds; a floor below 0 (a required row of target 0 — see [floorBroken]) doubles the divisor, as
     * the solver's soft leg halves its objective. Returns 1 when every required target is met and no floor is broken (or none
     * are requested). Capped at [MAX_PENALTY_MULTIPLIER] like the solver's floored multiplier ([penaltyMultiplier]):
     * far-out-of-reach builds (< ~10%) keep their damage gradient — the external loop ranks probe results by this score, so an
     * uncapped ~1e12 divisor read every such build as 0. [stats] must come from [penaltyStats].
     */
    internal fun requiredConstraintPenaltyFactor(
        targetStats: TargetStats,
        stats: Map<Characteristic, Int>,
    ): BigDecimal = targetStats.requiredPenaltyFactor(stats)
}
