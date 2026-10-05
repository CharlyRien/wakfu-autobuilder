package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import java.math.BigDecimal

object FindMostMasteriesFromInputScoring {
    private val masteryCharacteristicsWithoutElementaries =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    fun computeScore(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
    ): BigDecimal {
        // Aggregate RESISTANCE_ELEMENTARY alone makes the resistance score a min over the four elements (water-fill
        // optimally). Per-element resistance rows over several elements read the solver's joint fold instead: their rolls
        // are placed at the exact optimum of the rows' penalty total ([elementRows], which takes precedence).
        val resistanceElementsToMinimize =
            if (targetStats.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }) {
                targetStats.resistanceElementsWanted.keys.toList()
            } else {
                null
            }
        val elementRows = targetStats.elementRowObjectives(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
        val minElements = targetStats.masteryElementsToMinimize
        val targetCharacteristics = targetStats.map { it.characteristic }
        val masteriesStatsWithoutElementary = targetStats.filter { it.characteristic in masteryCharacteristicsWithoutElementaries }

        // The element-independent base shared by every per-element damage line (mirrors the solver's nonElemNeg):
        // non-element masteries (distance/melee/crit/…) + the negative-mastery penalty. Roll-INDEPENDENT (random-
        // element rolls only feed elemental masteries), so it is identical across the two passes below.
        fun nonElementBase(stats: Map<Characteristic, Int>): Int {
            val sumWithoutElem = masteriesStatsWithoutElementary.sumOf { stats[it.characteristic] ?: 0 }
            val removeNeg =
                stats
                    .filterKeys { it in listOf(Characteristic.MASTERY_BACK, Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_BERSERK) && it !in targetCharacteristics }
                    .filterValues { it < 0 }
                    .values
                    .sum()
            return sumWithoutElem + removeNeg
        }

        fun stats(
            masteryRollWeights: Map<Characteristic, Long>?,
            masteryRollOffset: Long,
        ) = computeCharacteristicsValues(
            buildCombination,
            characterBaseCharacteristics,
            targetStats.masteryElementsWanted,
            targetStats.resistanceElementsWanted,
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            masteryElementsToMinimize = minElements,
            resistanceElementsToMinimize = resistanceElementsToMinimize,
            masteryRollWeights = masteryRollWeights,
            masteryRollOffset = masteryRollOffset,
            elementRows = elementRows
        )

        // First pass (unweighted rolls). Its non-element masteries + DI are roll-independent, so they yield the
        // per-element factors; the element masteries are re-derived WEIGHTED below when a per-element DI sub makes
        // the factors unequal (so the random-element roll placement matches the solver's freed weighted objective).
        val firstPass = stats(null, 0L)
        val nonElemNeg = nonElementBase(firstPass)
        val globalDi = (firstPass[Characteristic.DAMAGE_INFLICTED] ?: 0).coerceIn(-DAMAGE_DI_FLOOR.toInt(), DAMAGE_DI_MAX.toInt())
        val elementDi =
            perElementDiContributions(
                buildCombination.sublimations.values.flatten(),
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                null,
                buildCombination.characterSkills.level,
                targetStats.masteryElementsWanted.keys
            )
        // factor_e = 100 + clamp(globalDI + elementDI_e) — matches the solver's combinedDi clamp EXACTLY.
        val masteryRollWeights =
            if (minElements.isNotEmpty() && elementDi.isNotEmpty()) {
                minElements.associateWith { e -> 100L + (globalDi + (elementDi[e] ?: 0)).coerceIn(-DAMAGE_DI_FLOOR.toInt(), DAMAGE_DI_MAX.toInt()).toLong() }
            } else {
                null
            }
        val actualCharacteristicsValues = if (masteryRollWeights != null) stats(masteryRollWeights, nonElemNeg.toLong()) else firstPass

        // The penalty for the stats asked that the build falls short of: the shortfall factor `(100 / success%)⁶` (capped at
        // MAX_PENALTY_MULTIPLIER like the solver's floored multiplier — [penaltyMultiplier] — so a build whose targets are far out of
        // reach keeps its core's gradient), doubled while a floor is below 0 (a required row of target 0: "air resistance 0", "dodge
        // 0"…) — the solver's soft leg halves its penalized objective the same way (applyConstraintPenalty); its hard leg never
        // returns such a build.
        val penaltyFactor = targetStats.requiredPenaltyFactor(actualCharacteristicsValues)

        // Per-element fold mirroring StatBuilder.diAdjustedPerElementMasteryScore: maximize mastery × (1 + DI/100)
        // so the proxy is damage-faithful, but EACH requested element's damage line uses its OWN per-element DI
        // inside the factor, then the weakest element governs (the balance philosophy). The combined DI is clamped
        // as ONE sum (mirroring the old mono path that folded the +12 into the global DI bucket); mastery is clamped
        // ≥ 0 before the multiply; integer `/100` truncation + clamp mirror CP-SAT's addDivisionEquality/clampVar so
        // the two engines never drift. BRANCH A (no element requested) is one global product, byte-identical in
        // value to the previous objective (where the lowest-element term was 0). The element masteries here came
        // from the WEIGHTED roll assignment when [masteryRollWeights] was set, so they match the solver's placement.
        val diAdjustedScore =
            if (minElements.isEmpty()) {
                (maxOf(nonElemNeg.toLong(), 0L) * (100L + globalDi) / 100L).coerceIn(0L, MASTERY_SCORE_ABS_MAX)
            } else {
                minElements.minOf { e ->
                    val tier = nonElemNeg + (actualCharacteristicsValues[e] ?: 0)
                    val combinedDi = (globalDi + (elementDi[e] ?: 0)).coerceIn(-DAMAGE_DI_FLOOR.toInt(), DAMAGE_DI_MAX.toInt())
                    (maxOf(tier.toLong(), 0L) * (100L + combinedDi) / 100L).coerceIn(0L, MASTERY_SCORE_ABS_MAX)
                }
            }

        return (diAdjustedScore.toBigDecimal() / penaltyFactor)
    }
}
