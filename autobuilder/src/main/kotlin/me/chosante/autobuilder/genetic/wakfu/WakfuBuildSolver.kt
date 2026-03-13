package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.Loader
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverSolutionCallback
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.min

object WakfuBuildSolver {
    private const val STAT_ABS_MAX = 10_000_000L
    private const val PERCENT_ABS_MAX = 10_000L
    private const val PRODUCT_ABS_MAX = STAT_ABS_MAX * PERCENT_ABS_MAX
    private const val STAT_WITH_PERCENT_ABS_MAX = STAT_ABS_MAX + (PRODUCT_ABS_MAX / 100) + 10
    private const val MASTERY_SCORE_ABS_MAX = 100_000_000L
    private const val MAX_POWER_TABLE_INDEX = 50_000
    private const val MAX_PENALTY_MULTIPLIER = 1_000_000L

    private val PRE_MASTERY_STATS =
        listOf(
            Characteristic.ACTION_POINT,
            Characteristic.CONTROL,
            Characteristic.MOVEMENT_POINT,
            Characteristic.RANGE,
            Characteristic.WAKFU_POINT,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP,
            Characteristic.LOCK,
            Characteristic.DODGE,
            Characteristic.BLOCK_PERCENTAGE,
            Characteristic.GIVEN_ARMOR_PERCENTAGE,
            Characteristic.RECEIVED_ARMOR_PERCENTAGE,
            Characteristic.INITIATIVE,
            Characteristic.RESISTANCE_BACK,
            Characteristic.RESISTANCE_CRITICAL
        )

    private val NON_ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    private val NEGATIVE_MASTERY_PENALTY =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BERSERK
        )

    private val RANDOM_MASTERY_COUNTS =
        mapOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    init {
        Loader.loadNativeLibraries()
    }

    fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> =
        callbackFlow {
            require(params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                "Only FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT is supported in the LP solver"
            }

            withContext(Dispatchers.IO) {
                val model = CpModel()

                val allEquips = orderEquipments(equipmentsByItemType)
                val equipVars = model.createEquipmentVariables(allEquips)
                val skillVars = model.createSkillVariables(params.character.characterSkills)

                model.addBuildValidityConstraints(allEquips, equipVars)

                val objective =
                    model.buildMostMasteriesObjective(
                        params = params,
                        allEquips = allEquips,
                        equipVars = equipVars,
                        skillVars = skillVars
                    )
                model.maximize(objective)

                executeSolverAndEmitResults(model, params, allEquips, equipVars, skillVars, this@callbackFlow)
            }
            close()
            awaitClose { }
        }

    private fun CpModel.createSkillVariables(characterSkills: CharacterSkills): Map<SkillCharacteristic, IntVar> {
        val skillVars = mutableMapOf<SkillCharacteristic, IntVar>()

        fun addCategory(
            assignable: Assignable<*>,
            namePrefix: String,
        ) {
            val sumExpr = LinearExpr.newBuilder()
            for (skill in assignable.getCharacteristics()) {
                val varName = "skill_${namePrefix}_${skill.name.toIdentifier()}"
                val skillVar = newIntVar(0, skill.maxPointsAssignable.toLong(), varName)
                skillVars[skill] = skillVar
                sumExpr.addTerm(skillVar, 1)
            }
            addLessOrEqual(sumExpr.build(), assignable.maxPointsToAssign.toLong())
        }

        addCategory(characterSkills.intelligence, "intel")
        addCategory(characterSkills.strength, "strength")
        addCategory(characterSkills.agility, "agility")
        addCategory(characterSkills.luck, "luck")
        addCategory(characterSkills.major, "major")

        return skillVars
    }

    private fun CpModel.createEquipmentVariables(allEquips: List<Equipment>): Map<Equipment, IntVar> =
        allEquips.associateWith { equip ->
            newBoolVar("equip_${equip.equipmentId}")
        }

    private fun CpModel.addBuildValidityConstraints(
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
    ) {
        val itemTypesLimits =
            mapOf(
                ItemType.AMULET to 1L,
                ItemType.EMBLEM to 1L,
                ItemType.SHOULDER_PADS to 1L,
                ItemType.RING to 2L,
                ItemType.BOOTS to 1L,
                ItemType.CHEST_PLATE to 1L,
                ItemType.CAPE to 1L,
                ItemType.HELMET to 1L,
                ItemType.PETS to 1L,
                ItemType.MOUNTS to 1L,
                ItemType.BELT to 1L
            )

        for ((type, limit) in itemTypesLimits) {
            val typeEquips = allEquips.filter { it.itemType == type }
            if (typeEquips.isNotEmpty()) {
                val sumExpr = LinearExpr.sum(typeEquips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, limit)
            }
        }

        // Weapons rules
        val twoHanded = allEquips.filter { it.itemType == ItemType.TWO_HANDED_WEAPONS }
        val oneHanded = allEquips.filter { it.itemType == ItemType.ONE_HANDED_WEAPONS }
        val offHand = allEquips.filter { it.itemType == ItemType.OFF_HAND_WEAPONS }

        val sumTwoHanded = LinearExpr.sum(twoHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOneHanded = LinearExpr.sum(oneHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOffHand = LinearExpr.sum(offHand.map { equipVars.getValue(it) }.toTypedArray())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOneHanded)
                .build(),
            1L
        )
        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOffHand)
                .build(),
            1L
        )

        // Rarity rules
        val relics = allEquips.filter { it.rarity == Rarity.RELIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (relics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(relics), 1L)
        }

        val epics = allEquips.filter { it.rarity == Rarity.EPIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (epics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(epics), 1L)
        }

        // Same ring name is not allowed
        val ringsByName =
            allEquips
                .filter { it.itemType == ItemType.RING }
                .groupBy { it.name.fr.lowercase() }
        for ((_, equips) in ringsByName) {
            if (equips.size > 1) {
                val sumExpr = LinearExpr.sum(equips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, 1L)
            }
        }
    }

    private fun CpModel.buildMostMasteriesObjective(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
    ): IntVar {
        val statBuilder = StatBuilder(this, params, allEquips, equipVars, skillVars)
        val targetStats = params.targetStats
        val targetCharacteristics = targetStats.map { it.characteristic }.toSet()

        val requiredTargets = targetStats.filter { it.characteristic in PRE_MASTERY_STATS }
        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.weight(it).toLong() }
                .coerceAtLeast(1L)

        val masteryScore = statBuilder.finalMasteryScore(targetStats, targetCharacteristics)

        if (requiredTargets.isEmpty()) {
            return masteryScore
        }

        val totalActualScore = statBuilder.totalActualScore(requiredTargets, totalExpectedScore, targetStats)
        val totalActualScoreForPenalty = maxVar(totalActualScore, 1L, totalExpectedScore, "totalActualScoreForPenalty")

        val (indexVar, maxIndex) = bucketedIndex(totalActualScoreForPenalty, totalExpectedScore)
        val powerTable = buildPowerTable(maxIndex.toLong(), MASTERY_SCORE_ABS_MAX)

        val multiplier = newIntVar(0, powerTable.maxValue, "penaltyMultiplier")
        addElement(indexVar, powerTable.values, multiplier)

        val maxObjective = safeMultiply(MASTERY_SCORE_ABS_MAX, powerTable.maxValue)
        val objectiveBound = maxObjective.coerceAtMost(Long.MAX_VALUE / 2)
        val objective = newIntVar(-objectiveBound, objectiveBound, "objectiveScore")
        addMultiplicationEquality(objective, masteryScore, multiplier)

        return objective
    }

    private fun executeSolverAndEmitResults(
        model: CpModel,
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        scope: ProducerScope<GeneticAlgorithmResult<BuildCombination>>,
    ) {
        val solver = CpSolver()
        solver.parameters.maxTimeInSeconds = params.searchDuration.inWholeSeconds.toDouble()
        solver.parameters.logSearchProgress = true

        val startTime = System.currentTimeMillis()

        val cb =
            object : CpSolverSolutionCallback() {
                var bestScore = BigDecimal.ZERO

                override fun onSolutionCallback() {
                    val equippedItems = mutableListOf<Equipment>()
                    for (equip in allEquips) {
                        if (value(equipVars.getValue(equip)) > 0L) {
                            equippedItems.add(equip)
                        }
                    }

                    val optimizedSkills = CharacterSkills(params.character.level)
                    for (skill in optimizedSkills.allCharacteristic) {
                        val matchedVar = skillVars.entries.find { it.key.name == skill.name }?.value
                        if (matchedVar != null) {
                            skill.setPointAssigned(value(matchedVar).toInt())
                        }
                    }

                    val combination = BuildCombination(equippedItems, optimizedSkills)
                    val actualScore =
                        FindMostMasteriesFromInputScoring.computeScore(
                            targetStats = params.targetStats,
                            buildCombination = combination,
                            characterBaseCharacteristics = params.character.baseCharacteristicValues
                        )

                    if (actualScore > bestScore) {
                        bestScore = actualScore
                    }

                    val progress = ((System.currentTimeMillis() - startTime).toDouble() / params.searchDuration.inWholeMilliseconds.toDouble() * 100).toInt()
                    scope.trySend(GeneticAlgorithmResult(combination, actualScore, progress.coerceAtMost(100)))
                }
            }

        try {
            val status = solver.solve(model, cb)
            println("Solver status returned: $status")
            println("Solver response stats: \n${solver.responseStats()}")

            if (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
                val equippedItems = mutableListOf<Equipment>()
                for (equip in allEquips) {
                    if (solver.value(equipVars.getValue(equip)) > 0L) {
                        equippedItems.add(equip)
                    }
                }
                val optimizedSkills = CharacterSkills(params.character.level)
                for (skill in optimizedSkills.allCharacteristic) {
                    val matchedVar = skillVars.entries.find { it.key.name == skill.name }?.value
                    if (matchedVar != null) {
                        skill.setPointAssigned(solver.value(matchedVar).toInt())
                    }
                }
                val finalComb = BuildCombination(equippedItems, optimizedSkills)
                val finalScore =
                    FindMostMasteriesFromInputScoring.computeScore(
                        targetStats = params.targetStats,
                        buildCombination = finalComb,
                        characterBaseCharacteristics = params.character.baseCharacteristicValues
                    )
                scope.trySend(GeneticAlgorithmResult(finalComb, finalScore, 100))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class StatBuilder(
        private val model: CpModel,
        private val params: WakfuBestBuildParams,
        private val allEquips: List<Equipment>,
        private val equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
    ) {
        private val baseValues = params.character.baseCharacteristicValues
        private val skillTerms = buildSkillTerms(skillVars)

        private val prePercentCache = mutableMapOf<Characteristic, IntVar>()
        private val actualCache = mutableMapOf<Characteristic, IntVar>()
        private val elementCache = mutableMapOf<List<Characteristic>, Map<Characteristic, IntVar>>()

        fun totalActualScore(
            requiredTargets: List<TargetStat>,
            totalExpectedScore: Long,
            targetStats: TargetStats,
        ): IntVar {
            val cappedScores =
                requiredTargets.map { targetStat ->
                    val actual = actualStat(targetStat.characteristic)
                    val weight = targetStats.weight(targetStat).toLong()
                    val weighted =
                        if (weight == 1L) {
                            actual
                        } else {
                            val weightedVar = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX * weight, STAT_WITH_PERCENT_ABS_MAX * weight, "weighted_${targetStat.characteristic.name}")
                            model.addEquality(weightedVar, LinearExpr.term(actual, weight))
                            weightedVar
                        }

                    val expectedScore = targetStat.target.toLong() * weight
                    val capped = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX * weight, expectedScore, "capped_${targetStat.characteristic.name}")
                    model.addMinEquality(capped, arrayOf(weighted, model.newConstant(expectedScore)))
                    capped
                }

            val maxWeight = requiredTargets.maxOfOrNull { targetStats.weight(it).toLong() } ?: 1L
            val minSum = -STAT_WITH_PERCENT_ABS_MAX * maxWeight * requiredTargets.size
            return model.sumVar("totalActualScore", cappedScores, minSum, totalExpectedScore)
        }

        fun finalMasteryScore(
            targetStats: TargetStats,
            targetCharacteristics: Set<Characteristic>,
        ): IntVar {
            val nonElementaries =
                targetStats
                    .filter { it.characteristic in NON_ELEMENTARY_MASTERIES }
                    .map { actualStat(it.characteristic) }
            val nonElemSum = model.sumVar("nonElemMastery", nonElementaries, -MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX)

            val negativePenaltyVars =
                NEGATIVE_MASTERY_PENALTY
                    .filter { it !in targetCharacteristics }
                    .map { char ->
                        val actual = actualStat(char)
                        val negativePart = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, 0, "neg_${char.name}")
                        model.addMinEquality(negativePart, arrayOf(actual, model.newConstant(0L)))
                        negativePart
                    }
            val negativePenalty = model.sumVar("negMasteryPenalty", negativePenaltyVars, -MASTERY_SCORE_ABS_MAX, 0)

            val wantedElements = targetStats.masteryElementsWanted.keys.toList()

            val lowestElementMastery =
                if (wantedElements.isEmpty()) {
                    model.newConstant(0L)
                } else {
                    val elementVars = elementMasteryVars(wantedElements)
                    val minVar = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "minElementMastery")
                    model.addMinEquality(minVar, elementVars.values.toTypedArray())
                    minVar
                }

            val total = model.newIntVar(-MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX, "masteryScore")
            val sumExpr =
                LinearExpr
                    .newBuilder()
                    .addTerm(nonElemSum, 1)
                    .addTerm(negativePenalty, 1)
                    .addTerm(lowestElementMastery, 1)
                    .build()
            model.addEquality(total, sumExpr)
            return total
        }

        private fun elementMasteryVars(wantedElements: List<Characteristic>): Map<Characteristic, IntVar> {
            val key = wantedElements.toList()
            return elementCache.getOrPut(key) {
                val masteryElementaryBase = prePercentStat(Characteristic.MASTERY_ELEMENTARY)
                val baseElements =
                    wantedElements.associateWith { element ->
                        val baseElement = prePercentStat(element)
                        model.sumVar(
                            name = "pre_${element.name}",
                            terms =
                                listOf(
                                    Term(baseElement, 1L),
                                    Term(masteryElementaryBase, 1L)
                                ),
                            constant = 0L,
                            min = -STAT_WITH_PERCENT_ABS_MAX,
                            max = STAT_WITH_PERCENT_ABS_MAX
                        )
                    }

                val prePercentElements = applyGreedyRandomMastery(wantedElements, baseElements)

                prePercentElements.mapValues { (element, preElement) ->
                    val percentTerms = skillTerms.percent[element].orEmpty()
                    if (percentTerms.isEmpty()) {
                        preElement
                    } else {
                        val percent =
                            model.sumVar(
                                name = "pct_${element.name}",
                                terms = percentTerms,
                                constant = 0L,
                                min = -PERCENT_ABS_MAX,
                                max = PERCENT_ABS_MAX
                            )
                        model.applyPercent(preElement, percent, "stat_${element.name}")
                    }
                }
            }
        }

        private fun applyGreedyRandomMastery(
            wantedElements: List<Characteristic>,
            baseElements: Map<Characteristic, IntVar>,
        ): Map<Characteristic, IntVar> {
            if (wantedElements.isEmpty()) return baseElements

            val targets = params.targetStats.masteryElementsWanted
            if (targets.isEmpty()) return baseElements

            val randomEntries = buildRandomMasteryEntries()
            if (randomEntries.isEmpty()) return baseElements

            val priorities =
                wantedElements
                    .mapIndexed { index, element ->
                        element to (wantedElements.size - index)
                    }.toMap()
            val priorityScale = 10L
            val elementCount = wantedElements.size

            var current = baseElements
            randomEntries.forEachIndexed { index, entry ->
                val effectiveCount = min(entry.count, elementCount)
                if (effectiveCount == 0) return@forEachIndexed

                if (effectiveCount == elementCount) {
                    current =
                        wantedElements.associateWith { element ->
                            val next =
                                model.newIntVar(
                                    -STAT_WITH_PERCENT_ABS_MAX,
                                    STAT_WITH_PERCENT_ABS_MAX,
                                    "rand_${index}_${element.name}"
                                )
                            model.addEquality(
                                next,
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(current.getValue(element), 1)
                                    .addTerm(entry.equipVar, entry.value.toLong())
                                    .build()
                            )
                            next
                        }
                    return@forEachIndexed
                }

                val assigns =
                    wantedElements.associateWith { element ->
                        model.newBoolVar("assign_${index}_${entry.nameSuffix}_${element.name}")
                    }

                model.addEquality(
                    LinearExpr.sum(assigns.values.toTypedArray()),
                    LinearExpr.term(entry.equipVar, effectiveCount.toLong())
                )

                val adjustedDeficits =
                    wantedElements.associateWith { element ->
                        val currentValue = current.getValue(element)
                        val target = targets.getValue(element).toLong()
                        val deficit =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX - 10_000L,
                                STAT_WITH_PERCENT_ABS_MAX + 10_000L,
                                "def_${index}_${element.name}"
                            )
                        model.addEquality(
                            deficit,
                            LinearExpr
                                .newBuilder()
                                .add(target)
                                .addTerm(currentValue, -1)
                                .build()
                        )

                        val adjusted =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX * priorityScale,
                                STAT_WITH_PERCENT_ABS_MAX * priorityScale + priorityScale,
                                "adj_${index}_${element.name}"
                            )
                        model.addEquality(
                            adjusted,
                            LinearExpr
                                .newBuilder()
                                .addTerm(deficit, priorityScale)
                                .add(priorities.getValue(element).toLong())
                                .build()
                        )
                        adjusted
                    }

                for (i in wantedElements) {
                    for (j in wantedElements) {
                        if (i == j) continue
                        model
                            .addGreaterOrEqual(adjustedDeficits.getValue(i), adjustedDeficits.getValue(j))
                            .onlyEnforceIf(arrayOf(assigns.getValue(i), assigns.getValue(j).not()))
                    }
                }

                current =
                    wantedElements.associateWith { element ->
                        val next =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX,
                                STAT_WITH_PERCENT_ABS_MAX,
                                "rand_${index}_${element.name}"
                            )
                        model.addEquality(
                            next,
                            LinearExpr
                                .newBuilder()
                                .addTerm(current.getValue(element), 1)
                                .addTerm(assigns.getValue(element), entry.value.toLong())
                                .build()
                        )
                        next
                    }
            }

            return current
        }

        private fun buildRandomMasteryEntries(): List<RandomEntry> {
            val ones = mutableListOf<RandomEntry>()
            val twos = mutableListOf<RandomEntry>()
            val threes = mutableListOf<RandomEntry>()

            for (equip in allEquips) {
                val equipVar = equipVars.getValue(equip)
                val one = equip.characteristics[Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT] ?: 0
                if (one != 0) {
                    ones.add(RandomEntry(equipVar, one, 1, "one_${equip.equipmentId}"))
                }
                val two = equip.characteristics[Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT] ?: 0
                if (two != 0) {
                    twos.add(RandomEntry(equipVar, two, 2, "two_${equip.equipmentId}"))
                }
                val three = equip.characteristics[Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT] ?: 0
                if (three != 0) {
                    threes.add(RandomEntry(equipVar, three, 3, "three_${equip.equipmentId}"))
                }
            }

            return ones + twos + threes
        }

        private fun actualStat(char: Characteristic): IntVar =
            actualCache.getOrPut(char) {
                val pre = prePercentStat(char)
                val percentTerms = skillTerms.percent[char].orEmpty()
                if (percentTerms.isEmpty()) {
                    pre
                } else {
                    val percent =
                        model.sumVar(
                            name = "pct_${char.name}",
                            terms = percentTerms,
                            constant = 0L,
                            min = -PERCENT_ABS_MAX,
                            max = PERCENT_ABS_MAX
                        )
                    model.applyPercent(pre, percent, "stat_${char.name}")
                }
            }

        private fun prePercentStat(char: Characteristic): IntVar =
            prePercentCache.getOrPut(char) {
                val terms = mutableListOf<Term>()

                for (equip in allEquips) {
                    val value = equip.valueFor(char)
                    if (value != 0) {
                        terms.add(Term(equipVars.getValue(equip), value.toLong()))
                    }
                }

                terms.addAll(skillTerms.fixed[char].orEmpty())

                val base = baseValues[char]?.toLong() ?: 0L
                model.sumVar("pre_${char.name}", terms, base, -STAT_ABS_MAX, STAT_ABS_MAX)
            }
    }

    private data class Term(
        val variable: IntVar,
        val coefficient: Long,
    )

    private data class RandomEntry(
        val equipVar: IntVar,
        val value: Int,
        val count: Int,
        val nameSuffix: String,
    )

    private data class SkillTerms(
        val fixed: Map<Characteristic, List<Term>>,
        val percent: Map<Characteristic, List<Term>>,
    )

    private data class PowerTable(
        val values: LongArray,
        val maxValue: Long,
    )

    private fun buildSkillTerms(skillVars: Map<SkillCharacteristic, IntVar>): SkillTerms {
        val fixed = mutableMapOf<Characteristic, MutableList<Term>>()
        val percent = mutableMapOf<Characteristic, MutableList<Term>>()

        fun addTerm(
            char: Characteristic?,
            variable: IntVar,
            unitValue: Int,
            unitType: UnitType,
        ) {
            if (char == null || unitValue == 0) return
            val target = if (unitType == UnitType.FIXED) fixed else percent
            target.getOrPut(char) { mutableListOf() }.add(Term(variable, unitValue.toLong()))
        }

        for ((skill, variable) in skillVars) {
            when (skill) {
                is SkillCharacteristic.PairedCharacteristic -> {
                    addTerm(skill.first.characteristic, variable, skill.first.unitValue, skill.first.unitType)
                    addTerm(skill.second.characteristic, variable, skill.second.unitValue, skill.second.unitType)
                }
                else -> addTerm(skill.characteristic, variable, skill.unitValue, skill.unitType)
            }
        }

        return SkillTerms(
            fixed = fixed.mapValues { it.value.toList() },
            percent = percent.mapValues { it.value.toList() }
        )
    }

    private fun Equipment.valueFor(char: Characteristic): Int {
        val base = characteristics[char] ?: 0
        return when (char) {
            Characteristic.ACTION_POINT -> base + (characteristics[Characteristic.MAX_ACTION_POINT] ?: 0)
            Characteristic.MOVEMENT_POINT -> base + (characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0)
            Characteristic.WAKFU_POINT -> base + (characteristics[Characteristic.MAX_WAKFU_POINTS] ?: 0)
            else -> base
        }
    }

    private fun String.toIdentifier(): String =
        lowercase()
            .replace(" ", "_")
            .replace("-", "_")

    private fun CpModel.sumVar(
        name: String,
        vars: List<IntVar>,
        min: Long,
        max: Long,
    ): IntVar {
        if (vars.isEmpty()) return newConstant(0L)
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, LinearExpr.sum(vars.toTypedArray()))
        return sumVar
    }

    private fun CpModel.sumVar(
        name: String,
        terms: List<Term>,
        constant: Long,
        min: Long,
        max: Long,
    ): IntVar {
        if (terms.isEmpty()) return newConstant(constant)
        val builder = LinearExpr.newBuilder().add(constant)
        terms.forEach { builder.addTerm(it.variable, it.coefficient) }
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, builder.build())
        return sumVar
    }

    private fun CpModel.applyPercent(
        value: IntVar,
        percent: IntVar,
        name: String,
    ): IntVar {
        val product = newIntVar(-PRODUCT_ABS_MAX, PRODUCT_ABS_MAX, "${name}_prod")
        addMultiplicationEquality(product, arrayOf(value, percent))

        val quotient = newIntVar(-(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1, "${name}_quot")
        addDivisionEquality(quotient, product, newConstant(100L))

        val remainder = newIntVar(-99, 99, "${name}_rem")
        addModuloEquality(remainder, product, 100L)

        val inc = newBoolVar("${name}_inc")
        addGreaterOrEqual(remainder, 50).onlyEnforceIf(inc)
        addLessOrEqual(remainder, 49).onlyEnforceIf(inc.not())

        val dec = newBoolVar("${name}_dec")
        addLessOrEqual(remainder, -51).onlyEnforceIf(dec)
        addGreaterOrEqual(remainder, -50).onlyEnforceIf(dec.not())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(inc, 1)
                .addTerm(dec, 1)
                .build(),
            1
        )

        val rounded = newIntVar(-(PRODUCT_ABS_MAX / 100) - 2, (PRODUCT_ABS_MAX / 100) + 2, "${name}_rounded")
        addEquality(
            rounded,
            LinearExpr
                .newBuilder()
                .addTerm(quotient, 1)
                .addTerm(inc, 1)
                .addTerm(dec, -1)
                .build()
        )

        val withPercent = newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, name)
        addEquality(
            withPercent,
            LinearExpr
                .newBuilder()
                .addTerm(value, 1)
                .addTerm(rounded, 1)
                .build()
        )
        return withPercent
    }

    private fun CpModel.maxVar(
        value: IntVar,
        minValue: Long,
        maxValue: Long,
        name: String,
    ): IntVar {
        val maxVar = newIntVar(minValue, maxValue, name)
        addMaxEquality(maxVar, arrayOf(value, newConstant(minValue)))
        return maxVar
    }

    private fun CpModel.bucketedIndex(
        totalActualScore: IntVar,
        totalExpectedScore: Long,
    ): Pair<IntVar, Int> {
        if (totalExpectedScore <= MAX_POWER_TABLE_INDEX) {
            return totalActualScore to totalExpectedScore.toInt()
        }

        val bucketSize = ceil(totalExpectedScore.toDouble() / MAX_POWER_TABLE_INDEX.toDouble()).toLong()
        val maxIndex = ((totalExpectedScore + bucketSize - 1) / bucketSize).toInt()
        val bucketVar = newIntVar(0, maxIndex.toLong(), "scoreBucket")
        addDivisionEquality(bucketVar, totalActualScore, newConstant(bucketSize))
        return bucketVar to maxIndex
    }

    private fun buildPowerTable(
        maxIndex: Long,
        maxMasteryAbs: Long,
    ): PowerTable {
        val maxMultiplierTarget = MAX_PENALTY_MULTIPLIER
        val maxPow = BigInteger.valueOf(maxIndex).pow(6)
        val powScale =
            if (maxPow > BigInteger.valueOf(maxMultiplierTarget)) {
                maxPow.divide(BigInteger.valueOf(maxMultiplierTarget))
            } else {
                BigInteger.ONE
            }

        val table =
            LongArray(maxIndex.toInt() + 1) { index ->
                BigInteger
                    .valueOf(index.toLong())
                    .pow(6)
                    .divide(powScale)
                    .toLong()
            }

        return PowerTable(
            values = table,
            maxValue = table.last()
        )
    }

    private fun safeMultiply(
        a: Long,
        b: Long,
    ): Long {
        val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val maxSafe = BigInteger.valueOf(Long.MAX_VALUE / 2)
        return if (product > maxSafe) (Long.MAX_VALUE / 2) else product.toLong()
    }

    private fun orderEquipments(equipmentsByItemType: Map<ItemType, List<Equipment>>): List<Equipment> =
        ItemType.entries
            .flatMap { type ->
                equipmentsByItemType[type].orEmpty().sortedBy { it.equipmentId }
            }.distinctBy { it.equipmentId }
}
