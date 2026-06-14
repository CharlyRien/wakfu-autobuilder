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
                targetStats.resistanceElementsWanted
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
            buildCombination.sublimations,
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

    val mutableActualCharacteristics = sumOfCharacteristicFixedValues.toMutableMap()
    if (masteryElementsWanted.isNotEmpty()) {
        val currentSpecificMasteryElements = currentStatSpecificElements(masteryElementsWanted, sumOfCharacteristicFixedValues, Characteristic.MASTERY_ELEMENTARY)
        val specificMasteryElementsWithRandomValuesAssigned =
            assignUniformlyMasteryRandomValues(
                getMasteryRandoms(eachCharacteristicValueLineByEquipment),
                currentSpecificMasteryElements,
                masteryElementsWanted
            )
        specificMasteryElementsWithRandomValuesAssigned.forEach {
            mutableActualCharacteristics[it.key] = it.value
        }
        mutableActualCharacteristics[Characteristic.MASTERY_ELEMENTARY] = specificMasteryElementsWithRandomValuesAssigned.minOfOrNull { it.value } ?: 0
    }

    val resistanceElementsCurrent = currentStatSpecificElements(resistanceElementsWanted, sumOfCharacteristicFixedValues, Characteristic.RESISTANCE_ELEMENTARY)
    if (resistanceElementsWanted.isNotEmpty()) {
        val specificResistanceElementsWithRandomValuesAssigned =
            assignUniformlyResistanceRandomValues(
                getResistanceRandoms(eachCharacteristicValueLineByEquipment),
                resistanceElementsCurrent,
                resistanceElementsWanted
            )
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
            v(Characteristic.MASTERY_MELEE) + v(Characteristic.MASTERY_DISTANCE) <= n
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
