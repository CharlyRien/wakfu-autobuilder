package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationKind
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.min
import kotlin.math.roundToInt

object FindClosestBuildFromInputScoring {
    fun computeScore(
        targetStats: TargetStats,
        buildCombination: BuildCombination,
        characterBaseCharacteristics: Map<Characteristic, Int>,
    ): BigDecimal {
        val actualCharacteristicsValues =
            computeCharacteristicsValues(
                buildCombination,
                characterBaseCharacteristics,
                targetStats.masteryElementsWanted,
                targetStats.resistanceElementsWanted,
                scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
            )

        var totalActualScore = calculateTotalActualScore(targetStats, actualCharacteristicsValues, targetStats.expectedScoreByCharacteristic, canExceedPerfectScore = false)

        // Apply penalty on asked characteristics with target 0 and negative value on the current build
        targetStats
            .filter { it.target == 0 }
            .forEach { targetStat ->
                actualCharacteristicsValues[targetStat.characteristic]?.let {
                    if (it < 0) {
                        totalActualScore /= 2
                    }
                }
            }

        val successPercentage = (totalActualScore / targetStats.totalExpectedScore) * 100.0

        // try to find better build when we have found build maximizing every characteristic asked
        if (successPercentage.toBigDecimal() == BigDecimal("100.0")) {
            val calculateTotalActualScoreExceedingPerfectScore =
                calculateTotalActualScore(targetStats, actualCharacteristicsValues, targetStats.expectedScoreByCharacteristic, canExceedPerfectScore = true)
            return ((calculateTotalActualScoreExceedingPerfectScore / targetStats.totalExpectedScore) * 100).toBigDecimal(MathContext(4, RoundingMode.FLOOR))
        }
        return successPercentage.toBigDecimal(MathContext(4, RoundingMode.FLOOR))
    }

    private fun calculateTotalActualScore(
        targetStats: TargetStats,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val totalActualScore =
            targetStats.sumOf { targetStat ->
                val weight = targetStats.weight(targetStat)
                val actualScore =
                    when (targetStat.characteristic) {
                        Characteristic.MASTERY_ELEMENTARY ->
                            calculateElementaryMasteryScore(
                                targetStat,
                                actualCharacteristicsValues,
                                weight,
                                expectedScoreByCharacteristic,
                                canExceedPerfectScore
                            )

                        Characteristic.RESISTANCE_ELEMENTARY ->
                            calculateElementaryResistanceScore(
                                targetStat,
                                actualCharacteristicsValues,
                                weight,
                                expectedScoreByCharacteristic,
                                canExceedPerfectScore
                            )

                        else -> (actualCharacteristicsValues[targetStat.characteristic] ?: 0) * weight
                    }
                if (canExceedPerfectScore) actualScore else min(actualScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalActualScore
    }

    private fun calculateElementaryMasteryScore(
        targetStat: TargetStat,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        weight: Double,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val elements =
            listOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_ELEMENTARY_WATER,
                Characteristic.MASTERY_ELEMENTARY_EARTH,
                Characteristic.MASTERY_ELEMENTARY_WIND
            )
        val totalScore =
            elements.sumOf { element ->
                val elementValue = actualCharacteristicsValues[element] ?: 0
                val actualElementScore = elementValue * weight
                if (canExceedPerfectScore) actualElementScore else min(actualElementScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalScore / 4
    }

    private fun calculateElementaryResistanceScore(
        targetStat: TargetStat,
        actualCharacteristicsValues: Map<Characteristic, Int>,
        weight: Double,
        expectedScoreByCharacteristic: Map<TargetStat, Double>,
        canExceedPerfectScore: Boolean,
    ): Double {
        val elements =
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                Characteristic.RESISTANCE_ELEMENTARY_WATER,
                Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                Characteristic.RESISTANCE_ELEMENTARY_WIND
            )
        val totalScore =
            elements.sumOf { element ->
                val elementValue = actualCharacteristicsValues[element] ?: 0
                val actualElementScore = elementValue * weight
                if (canExceedPerfectScore) actualElementScore else min(actualElementScore, expectedScoreByCharacteristic.getValue(targetStat))
            }
        return totalScore / 4
    }
}

fun computeCharacteristicsValues(
    buildCombination: BuildCombination,
    characterBaseCharacteristics: Map<Characteristic, Int>,
    masteryElementsWanted: Map<Characteristic, Int>,
    resistanceElementsWanted: Map<Characteristic, Int>,
    scoreComputationMode: ScoreComputationMode? = null,
    damageScenario: DamageScenario? = null,
    // most-masteries only: the elements its objective takes the MINIMUM over (a subset of masteryElementsWanted).
    // When set + mode is most-masteries, random mastery rolls are assigned to MAXIMIZE that minimum (optimal,
    // matching the freed CP-SAT model) instead of the deficit-greedy used by precision / max-damage. Null ⇒ greedy.
    masteryElementsToMinimize: List<Characteristic>? = null,
    // Same, for aggregate RESISTANCE_ELEMENTARY in most-masteries (its objective is the min over these). Null ⇒ greedy.
    resistanceElementsToMinimize: List<Characteristic>? = null,
): Map<Characteristic, Int> {
    val eachCharacteristicValueLineByEquipment =
        buildCombination.equipments
            .flatMap { it.characteristics.entries }
            .groupBy({ it.key }, { it.value })

    val characteristicsGivenByEquipmentCombination: Map<Characteristic, Int> =
        eachCharacteristicValueLineByEquipment
            .mapValues { (_, values) -> values.sum() }

    val edgeCasesCharacteristicsGivenByEquipment =
        mapOf(
            Characteristic.ACTION_POINT to (
                characteristicsGivenByEquipmentCombination[Characteristic.MAX_ACTION_POINT]
                    ?: 0
            ),
            Characteristic.MOVEMENT_POINT to (
                characteristicsGivenByEquipmentCombination[Characteristic.MAX_MOVEMENT_POINT]
                    ?: 0
            ),
            Characteristic.WAKFU_POINT to (characteristicsGivenByEquipmentCombination[Characteristic.MAX_WAKFU_POINTS] ?: 0)
        )

    val (characteristicGivenBySkillsFixedValues, characteristicGivenBySkillsPercentValues) =
        buildCombination
            .characterSkills
            .allCharacteristicValues

    // Runes are flat per-stat values (best-achievable: max rune level + WakForge doubling on the
    // carrier item's favoured slots), folded into the fixed sum before the elemental folding and the
    // percent pass — exactly as the OR-Tools solver adds them in StatBuilder.prePercentStat, so the
    // recomputed score matches the solver's objective.
    val runeContributions =
        buildMap<Characteristic, Int> {
            buildCombination.runes.forEach { (equipment, runes) ->
                runes.forEach { rune ->
                    merge(rune.characteristic, rune.valueOn(equipment.itemType, equipment.level), Int::plus)
                }
            }
        }

    val sumOfCharacteristicFixedValuesWithoutSublimations =
        mergeAndSumCharacteristicValues(
            characteristicsGivenByEquipmentCombination,
            characteristicGivenBySkillsFixedValues,
            edgeCasesCharacteristicsGivenByEquipment,
            characterBaseCharacteristics,
            runeContributions
        )

    val characterLevel = buildCombination.characterSkills.level
    // Sublimation contributions fold into the fixed sum exactly as the OR-Tools solver adds them in
    // StatBuilder.prePercentStat (FLAT always, STATIC only when the condition holds on the pre-sub
    // stats, CONVERSION moving value, scenario gates only in max-damage). Conditions are evaluated on
    // the sub-excluded stats above, mirroring the solver's preSubStat, so scoreFor matches the objective.
    val sublimationContributions =
        sublimationFixedContributions(
            buildCombination.sublimations.values.flatten(),
            sumOfCharacteristicFixedValuesWithoutSublimations,
            scoreComputationMode,
            damageScenario,
            characterLevel
        )
    val sumOfCharacteristicFixedValues =
        if (sublimationContributions.isEmpty()) {
            sumOfCharacteristicFixedValuesWithoutSublimations
        } else {
            mergeAndSumCharacteristicValues(sumOfCharacteristicFixedValuesWithoutSublimations, sublimationContributions)
        }

    // Selected passives' flat stats fold into the fixed sum exactly as the solver adds them in
    // StatBuilder.buildPassiveTerms (always-on constants), so the recomputed stats match the objective.
    // MAX_* fold to their usable stat via the same helper the sublimation fold uses.
    val passiveContributions =
        buildMap<Characteristic, Int> {
            buildCombination.passives.forEach { passive ->
                passive.flatStats.forEach { (characteristic, value) ->
                    merge(characteristic.foldedForSublimation(), value, Int::plus)
                }
            }
        }
    val sumWithPassives =
        if (passiveContributions.isEmpty()) {
            sumOfCharacteristicFixedValues
        } else {
            mergeAndSumCharacteristicValues(sumOfCharacteristicFixedValues, passiveContributions)
        }

    val mutableActualCharacteristics = sumWithPassives.toMutableMap()
    if (masteryElementsWanted.isNotEmpty()) {
        val currentSpecificMasteryElements = currentStatSpecificElements(masteryElementsWanted, sumWithPassives, Characteristic.MASTERY_ELEMENTARY)
        val masteryRandoms = getMasteryRandoms(eachCharacteristicValueLineByEquipment)
        val specificMasteryElementsWithRandomValuesAssigned =
            when {
                // Most-masteries maximizes the MIN over [masteryElementsToMinimize]; precision maximizes the capped
                // sum. Both objectives have a provably-suboptimal deficit-greedy, so each gets its EXACT assignment
                // (consistent with the correspondingly-freed CP-SAT model). max-damage (m=1) falls through to greedy.
                scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT && masteryElementsToMinimize != null ->
                    assignMaxMinMasteryRandomValues(masteryRandoms, currentSpecificMasteryElements, masteryElementsWanted, masteryElementsToMinimize)

                scoreComputationMode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                    assignMaxCappedMasteryRandomValues(masteryRandoms, currentSpecificMasteryElements, masteryElementsWanted)

                else ->
                    assignUniformlyMasteryRandomValues(masteryRandoms, currentSpecificMasteryElements, masteryElementsWanted)
            }
        specificMasteryElementsWithRandomValuesAssigned.forEach {
            mutableActualCharacteristics[it.key] = it.value
        }
        mutableActualCharacteristics[Characteristic.MASTERY_ELEMENTARY] = specificMasteryElementsWithRandomValuesAssigned.minOfOrNull { it.value } ?: 0
    }

    val resistanceElementsCurrent = currentStatSpecificElements(resistanceElementsWanted, sumWithPassives, Characteristic.RESISTANCE_ELEMENTARY)
    if (resistanceElementsWanted.isNotEmpty()) {
        val resistanceRandoms = getResistanceRandoms(eachCharacteristicValueLineByEquipment)
        val specificResistanceElementsWithRandomValuesAssigned =
            when {
                scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT && resistanceElementsToMinimize != null ->
                    // Aggregate RESISTANCE_ELEMENTARY in most-masteries maximizes the MIN resistance (exact max-min).
                    assignMaxMinResistanceRandomValues(resistanceRandoms, resistanceElementsCurrent, resistanceElementsWanted, resistanceElementsToMinimize)

                scoreComputationMode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                    // Precision maximizes the capped resistance sum (exact max-capped).
                    assignMaxCappedResistanceRandomValues(resistanceRandoms, resistanceElementsCurrent, resistanceElementsWanted)

                else ->
                    assignUniformlyResistanceRandomValues(resistanceRandoms, resistanceElementsCurrent, resistanceElementsWanted)
            }
        specificResistanceElementsWithRandomValuesAssigned.forEach {
            mutableActualCharacteristics[it.key] = it.value
        }

        specificResistanceElementsWithRandomValuesAssigned.minOfOrNull { it.value }?.let {
            mutableActualCharacteristics[Characteristic.RESISTANCE_ELEMENTARY] = it
        }
    }

    // Percent skills are applied per characteristic key, at the very end, on the accumulated value.
    // NOTE: the Major "% Inflicted Damage" aptitude is modeled as a FIXED contribution to the dedicated
    // DAMAGE_INFLICTED stat (not as a percent on mastery), because in the Wakfu damage formula "% damage"
    // is a separate multiplicative factor from mastery. DAMAGE_INFLICTED is only read by the max-damage
    // scoring mode (FindMaxDamageScoring), so this aptitude stays inert in the most-masteries / precision
    // modes — which is faithful: a flat "% damage inflicted" does not change displayed cumulated mastery.
    val actualCharacteristics =
        mutableActualCharacteristics.mapValues { (key, value) ->
            characteristicGivenBySkillsPercentValues[key]?.let { percent ->
                (value + value * (percent.toDouble() / 100)).roundToInt()
            } ?: return@mapValues value
        }

    return actualCharacteristics
}

fun mergeAndSumCharacteristicValues(vararg characteristicValuesMaps: Map<Characteristic, Int>): Map<Characteristic, Int> =
    characteristicValuesMaps
        .flatMap { it.entries }
        .groupingBy { it.key }
        .fold(0) { accumulator, element -> accumulator + element.value }

fun assignUniformlyMasteryRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = characteristicToValueCurrent[characteristic] ?: 0
    }

    val valueToNumberOfCharacteristicAssignable = mutableListOf<Pair<Int, Int>>()
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 1)
    }
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 2)
    }
    for (value in randomElements[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 3)
    }

    return result.assignValues(valueToNumberOfCharacteristicAssignable, characteristicToValueWanted)
}

fun assignUniformlyResistanceRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in characteristicToValueWanted) {
        result[characteristic] = characteristicToValueCurrent[characteristic] ?: 0
    }

    val valueToNumberOfCharacteristicAssignable = mutableListOf<Pair<Int, Int>>()
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 1)
    }
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 2)
    }
    for (value in randomElements[Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: listOf()) {
        valueToNumberOfCharacteristicAssignable.add(value to 3)
    }

    return result.assignValues(valueToNumberOfCharacteristicAssignable, characteristicToValueWanted)
}

/**
 * Optimal random-element assignment for the "most-masteries" objective, which maximizes the MINIMUM mastery
 * over [elementsToMaximizeMinOver] (a subset of [characteristicToValueWanted]). Each random roll adds its value
 * to exactly `min(count, #wanted)` distinct elements; to lift the minimum we water-fill — every roll goes to the
 * currently-lowest elements of the subset first (then spills any surplus onto non-subset wanted elements, which
 * is free for the objective). This is provably optimal for atomic equal-value-to-k rolls (an exchange argument:
 * moving a unit off one of the chosen lowest onto a higher element can only lower or keep the min), unlike the
 * deficit-sorted [assignValues] greedy which is misaligned with a max-min objective. The freed CP-SAT model
 * (WakfuBuildSolver.applyGreedyRandom, most-masteries path) reaches the SAME optimal minimum, so the two engines
 * stay consistent. The per-element distribution may differ from the solver's — only the resulting minimum is
 * observable by the objective, which both maximize.
 */
fun assignMaxMinMasteryRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
    elementsToMaximizeMinOver: List<Characteristic>,
): Map<Characteristic, Int> =
    seededFrom(characteristicToValueCurrent, characteristicToValueWanted)
        .assignMaxMin(masteryRolls(randomElements), characteristicToValueWanted.keys.toList(), elementsToMaximizeMinOver)

/** Resistance analogue of [assignMaxMinMasteryRandomValues] (aggregate RESISTANCE_ELEMENTARY in most-masteries). */
fun assignMaxMinResistanceRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
    elementsToMaximizeMinOver: List<Characteristic>,
): Map<Characteristic, Int> =
    seededFrom(characteristicToValueCurrent, characteristicToValueWanted)
        .assignMaxMin(resistanceRolls(randomElements), characteristicToValueWanted.keys.toList(), elementsToMaximizeMinOver)

/**
 * Optimal random-element assignment for the PRECISION objective, which maximizes `Σ min(value_e, target_e)` over
 * the requested elements (each capped at its target). As with max-min there is no optimal greedy (the deficit-sort
 * is beatable — RandomElementAssignmentTest), so we solve it exactly; this matches precision's freed CP-SAT model.
 */
fun assignMaxCappedMasteryRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> =
    seededFrom(characteristicToValueCurrent, characteristicToValueWanted)
        .assignMaxCapped(masteryRolls(randomElements), characteristicToValueWanted.keys.toList(), characteristicToValueWanted)

/** Resistance analogue of [assignMaxCappedMasteryRandomValues] (precision). */
fun assignMaxCappedResistanceRandomValues(
    randomElements: Map<Characteristic, List<Int>>,
    characteristicToValueCurrent: Map<Characteristic, Int>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): Map<Characteristic, Int> =
    seededFrom(characteristicToValueCurrent, characteristicToValueWanted)
        .assignMaxCapped(resistanceRolls(randomElements), characteristicToValueWanted.keys.toList(), characteristicToValueWanted)

/** The wanted elements seeded with their current (non-random) values — the start state every assignment builds on. */
private fun seededFrom(
    current: Map<Characteristic, Int>,
    wanted: Map<Characteristic, Int>,
): MutableMap<Characteristic, Int> {
    val result = mutableMapOf<Characteristic, Int>()
    for ((characteristic, _) in wanted) {
        result[characteristic] = current[characteristic] ?: 0
    }
    return result
}

/** The `(value, count)` rolls for the three `*_RANDOM_ELEMENT` lines under [one]/[two]/[three]. */
private fun rollsFrom(
    randomElements: Map<Characteristic, List<Int>>,
    one: Characteristic,
    two: Characteristic,
    three: Characteristic,
): List<Pair<Int, Int>> {
    val rolls = mutableListOf<Pair<Int, Int>>()
    randomElements[one]?.forEach { rolls.add(it to 1) }
    randomElements[two]?.forEach { rolls.add(it to 2) }
    randomElements[three]?.forEach { rolls.add(it to 3) }
    return rolls
}

private fun masteryRolls(randomElements: Map<Characteristic, List<Int>>) =
    rollsFrom(
        randomElements,
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
    )

private fun resistanceRolls(randomElements: Map<Characteristic, List<Int>>) =
    rollsFrom(
        randomElements,
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
    )

/**
 * Assigns atomic [rolls] (value, count) to MAXIMIZE the minimum over [subset], EXACTLY. Each roll lands on
 * `min(count, allWanted.size)` distinct elements; of those, the `j = min(that, subset.size)` that land on subset
 * elements are what move the min — the remainder spill onto non-subset wanted elements (free for the objective).
 *
 * There is NO optimal greedy here (a deficit-sort or a per-roll water-fill is provably beatable — see
 * RandomElementAssignmentTest), so we solve the small problem exactly: branch & bound over the ≤4 subset values,
 * one roll at a time, pruning with the admissible bound `min ≤ (Σ subset + reachable remaining mass) / |subset|`.
 * This is exactly what the freed CP-SAT model computes, keeping the two engines consistent. Surplus is then
 * spilled onto the lowest non-subset wanted elements for a faithful per-element display.
 */
private fun MutableMap<Characteristic, Int>.assignMaxMin(
    rolls: List<Pair<Int, Int>>,
    allWanted: List<Characteristic>,
    subset: List<Characteristic>,
): MutableMap<Characteristic, Int> {
    if (subset.isEmpty() || rolls.isEmpty()) return this
    // Reduce to: subset values + per-roll (value, how many subset elements it covers). Process biggest first.
    val subsetBase = IntArray(subset.size) { this[subset[it]]!! }
    val subsetRolls =
        rolls
            .mapNotNull { (value, count) ->
                val eff = minOf(count, allWanted.size)
                val coversSubset = minOf(eff, subset.size)
                if (value == 0 || coversSubset == 0) null else value to coversSubset
            }.sortedByDescending { it.first.toLong() * it.second }
    if (subsetRolls.isEmpty()) return this

    val best = IntArray(subset.size) { subsetBase[it] }
    var bestMin = best.min()
    val current = subsetBase.copyOf()
    // Remaining reachable subset mass after roll index i (suffix sums), for the average bound.
    val suffixMass = IntArray(subsetRolls.size + 1)
    for (i in subsetRolls.indices.reversed()) suffixMass[i] = suffixMass[i + 1] + subsetRolls[i].first * subsetRolls[i].second
    val combosByCover = (0..subset.size).associateWith { k -> indexCombinations(subset.size, k) }

    fun recurse(rollIndex: Int) {
        if (rollIndex == subsetRolls.size) {
            val m = current.min()
            if (m > bestMin) {
                bestMin = m
                System.arraycopy(current, 0, best, 0, current.size)
            }
            return
        }
        // Admissible bound: the min can never exceed the subset average once all remaining mass is spread.
        val sum = current.sum()
        if ((sum + suffixMass[rollIndex]) / subset.size <= bestMin) return
        val (value, cover) = subsetRolls[rollIndex]
        for (combo in combosByCover.getValue(cover)) {
            for (idx in combo) current[idx] += value
            recurse(rollIndex + 1)
            for (idx in combo) current[idx] -= value
        }
    }
    recurse(0)

    subset.forEachIndexed { i, element -> this[element] = best[i] }
    // Spill (count > subset.size) onto the lowest non-subset wanted elements — display only, min is unchanged.
    val subsetSet = subset.toSet()
    val nonSubset = allWanted.filterNot { it in subsetSet }
    if (nonSubset.isNotEmpty()) {
        for ((value, count) in rolls.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })) {
            val spill = minOf(count, allWanted.size) - minOf(minOf(count, allWanted.size), subset.size)
            if (value == 0 || spill <= 0) continue
            nonSubset.sortedBy { this[it]!! }.take(spill).forEach { this[it] = this[it]!! + value }
        }
    }
    return this
}

/**
 * Assigns atomic [rolls] (value, count) to MAXIMIZE `Σ min(value_e, target_e)` over [wanted] (precision's capped
 * objective), EXACTLY — branch & bound over the ≤4 element values, pruning with the admissible bound
 * `capped ≤ current capped + min(remaining reachable mass, remaining room-to-target)`. Mirrors the freed CP-SAT model.
 */
private fun MutableMap<Characteristic, Int>.assignMaxCapped(
    rolls: List<Pair<Int, Int>>,
    wanted: List<Characteristic>,
    targets: Map<Characteristic, Int>,
): MutableMap<Characteristic, Int> {
    val n = wanted.size
    if (n == 0 || rolls.isEmpty()) return this
    val target = IntArray(n) { targets[wanted[it]] ?: Int.MAX_VALUE }
    val effRolls =
        rolls
            .mapNotNull { (value, count) ->
                val eff = minOf(count, n)
                if (value == 0 || eff == 0) null else value to eff
            }.sortedByDescending { it.first.toLong() * it.second }
    if (effRolls.isEmpty()) return this

    val current = IntArray(n) { this[wanted[it]]!! }
    val best = current.copyOf()

    fun cappedSum(arr: IntArray): Long {
        var s = 0L
        for (i in 0 until n) s += minOf(arr[i], target[i]).toLong()
        return s
    }
    var bestCapped = cappedSum(current)
    val suffixMass = LongArray(effRolls.size + 1)
    for (i in effRolls.indices.reversed()) suffixMass[i] = suffixMass[i + 1] + effRolls[i].first.toLong() * effRolls[i].second
    val combosByCover = (0..n).associateWith { k -> indexCombinations(n, k) }

    fun recurse(rollIndex: Int) {
        if (rollIndex == effRolls.size) {
            val c = cappedSum(current)
            if (c > bestCapped) {
                bestCapped = c
                System.arraycopy(current, 0, best, 0, n)
            }
            return
        }
        // Admissible: extra capped ≤ min(remaining mass, remaining room-to-target).
        var room = 0L
        for (i in 0 until n) room += maxOf(0, target[i] - current[i]).toLong()
        if (cappedSum(current) + minOf(suffixMass[rollIndex], room) <= bestCapped) return
        val (value, cover) = effRolls[rollIndex]
        for (combo in combosByCover.getValue(cover)) {
            for (idx in combo) current[idx] += value
            recurse(rollIndex + 1)
            for (idx in combo) current[idx] -= value
        }
    }
    recurse(0)

    wanted.forEachIndexed { i, element -> this[element] = best[i] }
    return this
}

/** All k-element index subsets of [0, n). */
private fun indexCombinations(
    n: Int,
    k: Int,
): List<IntArray> {
    val result = mutableListOf<IntArray>()
    val combo = IntArray(k)

    fun build(
        start: Int,
        depth: Int,
    ) {
        if (depth == k) {
            result.add(combo.copyOf())
            return
        }
        for (i in start..n - k + depth) {
            combo[depth] = i
            build(i + 1, depth + 1)
        }
    }
    if (k in 0..n) build(0, 0)
    return result
}

private fun MutableMap<Characteristic, Int>.assignValues(
    valueToNumberOfCharacteristicAssignable: List<Pair<Int, Int>>,
    characteristicToValueWanted: Map<Characteristic, Int>,
): MutableMap<Characteristic, Int> {
    for ((value, count) in valueToNumberOfCharacteristicAssignable) {
        val sortedCategories =
            characteristicToValueWanted.entries.sortedByDescending { entry ->
                val currentValue = this[entry.key]!!
                val target = entry.value
                val difference = target - currentValue
                difference
            }
        var assignedCount = 0
        for (entry in sortedCategories) {
            if (assignedCount >= count) break
            val characteristic = entry.key
            val currentValue = this[characteristic]!!
            this[characteristic] = currentValue + value
            assignedCount++
        }
    }
    return this
}

internal fun currentStatSpecificElements(
    elementsWanted: Map<Characteristic, Int>,
    actualCharacteristics: Map<Characteristic, Int>,
    characteristic: Characteristic,
): Map<Characteristic, Int> =
    elementsWanted.mapValues {
        (actualCharacteristics[it.key] ?: 0) + (actualCharacteristics[characteristic] ?: 0)
    }

internal fun getMasteryRandoms(eachCharacteristicValueLineByEquipment: Map<Characteristic, List<Int>>) =
    eachCharacteristicValueLineByEquipment.filterKeys {
        it in
            listOf(
                Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
                Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
                Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
            )
    }

internal fun getResistanceRandoms(eachCharacteristicValueLineByEquipment: Map<Characteristic, List<Int>>) =
    eachCharacteristicValueLineByEquipment.filterKeys {
        it in
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
                Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
                Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
            )
    }

/** AP/MP/WP folding for sublimation effect characteristics (mirrors StatBuilder.effectiveStat). */
private fun Characteristic.foldedForSublimation(): Characteristic =
    when (this) {
        Characteristic.MAX_ACTION_POINT -> Characteristic.ACTION_POINT
        Characteristic.MAX_MOVEMENT_POINT -> Characteristic.MOVEMENT_POINT
        Characteristic.MAX_WAKFU_POINTS -> Characteristic.WAKFU_POINT
        else -> this
    }

/** Condition types the solver models; the scorer must agree on which subs are conditionally applied. */
private val SCORE_SUPPORTED_SUB_CONDITIONS =
    setOf(
        SublimationConditionType.AP_AT_MOST,
        SublimationConditionType.AP_AT_LEAST,
        SublimationConditionType.AP_EXACT,
        SublimationConditionType.CRIT_AT_MOST,
        SublimationConditionType.CRIT_AT_LEAST,
        SublimationConditionType.BLOCK_AT_LEAST,
        SublimationConditionType.RANGE_AT_MOST,
        SublimationConditionType.RANGE_AT_LEAST,
        SublimationConditionType.RANGE_EXACT,
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL,
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
    )

private fun subConditionHolds(
    cond: SublimationCondition,
    preSub: Map<Characteristic, Int>,
    level: Int,
): Boolean {
    fun v(c: Characteristic) = preSub[c] ?: 0
    val n = cond.value ?: 0
    return when (cond.type) {
        SublimationConditionType.AP_AT_MOST -> v(Characteristic.ACTION_POINT) <= n
        SublimationConditionType.AP_AT_LEAST -> v(Characteristic.ACTION_POINT) >= n
        SublimationConditionType.AP_EXACT -> v(Characteristic.ACTION_POINT) == n
        SublimationConditionType.CRIT_AT_MOST -> v(Characteristic.CRITICAL_HIT) <= n
        SublimationConditionType.CRIT_AT_LEAST -> v(Characteristic.CRITICAL_HIT) >= n
        SublimationConditionType.BLOCK_AT_LEAST -> v(Characteristic.BLOCK_PERCENTAGE) >= n
        SublimationConditionType.RANGE_AT_MOST -> v(Characteristic.RANGE) <= n
        SublimationConditionType.RANGE_AT_LEAST -> v(Characteristic.RANGE) >= n
        SublimationConditionType.RANGE_EXACT -> v(Characteristic.RANGE) == n
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> v(Characteristic.DODGE) < (n * level) / 100
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
            me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
                .sumOf { v(it) } <= n
        else -> true
    }
}

private fun subScenarioGateMatches(
    gate: ScenarioGate?,
    mode: ScoreComputationMode?,
    scenario: DamageScenario?,
    level: Int,
): Boolean {
    if (gate == null) return true
    if (mode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE || scenario == null) return false
    gate.rangeBand?.let { if (scenario.rangeBand.name != it) return false }
    gate.orientation?.let { if (scenario.orientation.name != it) return false }
    if (gate.berserk == true && !scenario.berserk) return false
    if (gate.ranged == true && scenario.rangeBand.name != "DISTANCE") return false
    gate.minCharacterLevel?.let { if (level < it) return false }
    return true
}

/**
 * The flat per-characteristic value contributed by a build's chosen/forced sublimations, mirroring
 * [WakfuBuildSolver]'s StatBuilder so the recomputed score matches the solver objective. Combat-conditional
 * subs are not auto-credited (situational); static conditions are evaluated on [preSub] (the sub-excluded
 * stats); scenario-gated effects count only in max-damage with a matching scenario.
 */
fun sublimationFixedContributions(
    sublimations: List<Sublimation>,
    preSub: Map<Characteristic, Int>,
    mode: ScoreComputationMode?,
    scenario: DamageScenario?,
    level: Int,
): Map<Characteristic, Int> {
    if (sublimations.isEmpty()) return emptyMap()
    val out = mutableMapOf<Characteristic, Int>()
    for (sub in sublimations) {
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL) continue
        val cond = sub.condition
        val applies =
            cond == null || cond.type !in SCORE_SUPPORTED_SUB_CONDITIONS || subConditionHolds(cond, preSub, level)
        if (!applies) continue
        if (sub.kind == SublimationKind.CONVERSION) {
            val conv = sub.conversion ?: continue
            val moved = maxOf(0, (preSub[conv.from] ?: 0) * conv.percent / 100)
            out.merge(conv.to.foldedForSublimation(), moved, Int::plus)
            out.merge(conv.from.foldedForSublimation(), -moved, Int::plus)
            continue
        }
        for (effect in sub.effects) {
            if (!subScenarioGateMatches(effect.scenarioGate, mode, scenario, level)) continue
            out.merge(effect.characteristic.foldedForSublimation(), effect.value, Int::plus)
        }
    }
    return out
}
