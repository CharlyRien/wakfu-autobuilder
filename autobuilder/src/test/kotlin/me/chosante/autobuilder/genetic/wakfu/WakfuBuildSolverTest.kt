package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithm
import me.chosante.autobuilder.genetic.ScoredIndividual
import me.chosante.autobuilder.genetic.tournamentSelection
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WakfuBuildSolverTest {
    @Test
    fun `lp solver matches genetic algorithm scoring on a deterministic pool`(): Unit =
        runBlocking {
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)

            val equipments =
                listOf(
                    equipment(
                        id = 1,
                        type = ItemType.AMULET,
                        name = "Amulet A",
                        stats =
                            mapOf(
                                Characteristic.ACTION_POINT to 1,
                                Characteristic.MASTERY_MELEE to 10,
                                Characteristic.MASTERY_ELEMENTARY_FIRE to 6
                            )
                    ),
                    equipment(
                        id = 2,
                        type = ItemType.AMULET,
                        name = "Amulet B",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_MELEE to 30,
                                Characteristic.MASTERY_ELEMENTARY_FIRE to 6
                            )
                    ),
                    equipment(
                        id = 3,
                        type = ItemType.BELT,
                        name = "Belt A",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_BACK to 20
                            )
                    ),
                    equipment(
                        id = 4,
                        type = ItemType.BELT,
                        name = "Belt B",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_CRITICAL to -10
                            )
                    ),
                    equipment(
                        id = 5,
                        type = ItemType.CAPE,
                        name = "Cape A",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY_WATER to 6
                            )
                    ),
                    equipment(
                        id = 6,
                        type = ItemType.CAPE,
                        name = "Cape B",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY to 4
                            )
                    ),
                    equipment(
                        id = 7,
                        type = ItemType.ONE_HANDED_WEAPONS,
                        name = "Weapon A",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 5
                            )
                    ),
                    equipment(
                        id = 8,
                        type = ItemType.ONE_HANDED_WEAPONS,
                        name = "Weapon B",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY to 4
                            )
                    )
                )

            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 7),
                        TargetStat(Characteristic.MASTERY_MELEE, 1),
                        TargetStat(Characteristic.MASTERY_BACK, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY, 1)
                    )
                )

            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 2.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )

            val equipmentsByItemType = equipments.groupBy { it.itemType }

            val allCombinations = allValidCombinations(equipments, characterSkills)
            val scoreFn = { build: BuildCombination ->
                FindMostMasteriesFromInputScoring.computeScore(
                    targetStats = targetStats,
                    buildCombination = build,
                    characterBaseCharacteristics = character.baseCharacteristicValues
                )
            }

            val exhaustiveBest = allCombinations.maxByOrNull { scoreFn(it) }!!
            val exhaustiveScore = scoreFn(exhaustiveBest)

            val gaResult = runDeterministicGeneticAlgorithm(allCombinations, scoreFn)

            val lpBest = WakfuBuildSolver.optimize(params, equipmentsByItemType).toList().maxByOrNull { it.matchPercentage }!!

            assertThat(gaResult.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            assertThat(lpBest.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            assertThat(lpBest.individual.isValid()).isTrue
        }

    @Test
    fun `lp solver matches genetic algorithm when targets are impossible`(): Unit =
        runBlocking {
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)

            val equipments =
                listOf(
                    equipment(
                        id = 1,
                        type = ItemType.AMULET,
                        name = "Amulet A",
                        stats =
                            mapOf(
                                Characteristic.ACTION_POINT to 1,
                                Characteristic.MASTERY_MELEE to 10
                            )
                    ),
                    equipment(
                        id = 2,
                        type = ItemType.BELT,
                        name = "Belt A",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_BACK to 20
                            )
                    ),
                    equipment(
                        id = 3,
                        type = ItemType.CAPE,
                        name = "Cape A",
                        stats =
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY_WATER to 6
                            )
                    )
                )

            val impossibleTargets =
                listOf(
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 99),
                            TargetStat(Characteristic.MOVEMENT_POINT, 30),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY, 9999)
                        )
                    ),
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.RANGE, 20),
                            TargetStat(Characteristic.CRITICAL_HIT, 150),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 5000),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 5000)
                        )
                    )
                )

            val equipmentsByItemType = equipments.groupBy { it.itemType }
            val allCombinations = allValidCombinations(equipments, characterSkills)

            for (targetStats in impossibleTargets) {
                val params =
                    WakfuBestBuildParams(
                        character = character,
                        targetStats = targetStats,
                        searchDuration = 2.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                    )

                val scoreFn = scoreFn(targetStats, character)
                val exhaustiveBest = allCombinations.maxByOrNull { scoreFn(it) }!!
                val exhaustiveScore = scoreFn(exhaustiveBest)

                val gaResult = runDeterministicGeneticAlgorithm(allCombinations, scoreFn)
                val lpBest = WakfuBuildSolver.optimize(params, equipmentsByItemType).toList().maxByOrNull { it.matchPercentage }!!

                assertThat(gaResult.matchPercentage).isEqualByComparingTo(exhaustiveScore)
                assertThat(lpBest.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            }
        }

    @Test
    fun `lp solver matches genetic algorithm on level 245 dataset`(): Unit =
        runBlocking {
            val level = 245
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)

            val equipments =
                this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!.let {
                    Json.decodeFromString<List<Equipment>>(String(it))
                }

            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.MOVEMENT_POINT, 5),
                        TargetStat(Characteristic.RANGE, 5),
                        TargetStat(Characteristic.CRITICAL_HIT, 50),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1)
                    )
                )

            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 30.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )

            val equipmentsByItemType =
                equipments
                    .filter { it.rarity <= params.maxRarity }
                    .filter {
                        (it.level <= level && it.level >= character.minLevel) ||
                            it.itemType == ItemType.PETS ||
                            it.itemType == ItemType.MOUNTS
                    }.groupBy { it.itemType }

            val lpBest = WakfuBuildSolver.optimize(params, equipmentsByItemType).toList().maxByOrNull { it.matchPercentage }!!
            val gaBest =
                runSequentialGeneticSearch(
                    params = params.copy(searchDuration = 5.seconds),
                    equipmentsByItemType = equipmentsByItemType,
                    populationSize = 1000,
                    mutationProbability = 0.2,
                    seed = 42L
                )

            val lpScore = lpBest.matchPercentage
            val gaScore = gaBest.score
            println("Level 245 LP score: $lpScore | GA score: $gaScore")
            assertThat(lpScore).isGreaterThanOrEqualTo(gaScore)
        }

    // ---------------------------------------------------------------------------------------------
    // Equivalence suite: on small synthetic pools the brute-force optimum is the ground truth, so we
    // assert the solver reaches the *exact* optimum of the matching GA scorer (stronger than ">= GA").
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `most-masteries solver honours an HP constraint above 100 (weight no longer truncated)`() {
        // HP target 200 -> GA weight 100/200 = 0.5, which the old .toLong() collapsed to 0; the
        // solver then ignored HP and grabbed the high-mastery amulet that misses the constraint.
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "HpAmulet", mapOf(Characteristic.HP to 200, Characteristic.MASTERY_MELEE to 10)),
                equipment(2, ItemType.AMULET, "DmgAmulet", mapOf(Characteristic.MASTERY_MELEE to 100)),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_MELEE to 40))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.HP, 200), TargetStat(Characteristic.MASTERY_MELEE, 1))),
            mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        )
    }

    @Test
    fun `most-masteries solver honours a critical-hit constraint above 100`() {
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "CritAmulet", mapOf(Characteristic.CRITICAL_HIT to 200, Characteristic.MASTERY_MELEE to 10)),
                equipment(2, ItemType.AMULET, "DmgAmulet", mapOf(Characteristic.MASTERY_MELEE to 100)),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_MELEE to 40))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.CRITICAL_HIT, 150), TargetStat(Characteristic.MASTERY_MELEE, 1))),
            mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        )
    }

    @Test
    fun `precision solver hits exact AP and mastery targets`() {
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "Ap", mapOf(Characteristic.ACTION_POINT to 1, Characteristic.MASTERY_MELEE to 20)),
                equipment(2, ItemType.AMULET, "Dmg", mapOf(Characteristic.MASTERY_MELEE to 60)),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_MELEE to 30)),
                equipment(4, ItemType.BOOTS, "Boots", mapOf(Characteristic.MASTERY_MELEE to 10))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7), TargetStat(Characteristic.MASTERY_MELEE, 40))),
            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        )
    }

    @Test
    fun `precision solver respects user-defined weights when arbitrating`() {
        // One amulet slot, two candidates; MP weighted 5x AP, so the solver should keep MP and drop AP.
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "ApAmulet", mapOf(Characteristic.ACTION_POINT to 2)),
                equipment(2, ItemType.AMULET, "MpAmulet", mapOf(Characteristic.MOVEMENT_POINT to 2))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 8, userDefinedWeight = 1),
                        TargetStat(Characteristic.MOVEMENT_POINT, 5, userDefinedWeight = 5)
                    )
                ),
            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        )
    }

    @Test
    fun `precision solver averages the four elements for elementary mastery`() {
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 40)),
                equipment(2, ItemType.BELT, "Water", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 40)),
                equipment(3, ItemType.BOOTS, "AllRound", mapOf(Characteristic.MASTERY_ELEMENTARY to 15))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 20))),
            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        )
    }

    @Test
    fun `precision solver penalises a negative target-0 stat`() {
        // GoodDmg hits the melee target but drags MASTERY_BACK negative; with a target of 0 that
        // halves the score, so the cleaner amulet must win.
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "GoodDmg", mapOf(Characteristic.MASTERY_MELEE to 50, Characteristic.MASTERY_BACK to -30)),
                equipment(2, ItemType.AMULET, "CleanDmg", mapOf(Characteristic.MASTERY_MELEE to 40))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_MELEE, 50), TargetStat(Characteristic.MASTERY_BACK, 0))),
            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        )
    }

    private fun assertSolverReachesExhaustiveOptimum(
        equipments: List<Equipment>,
        targetStats: TargetStats,
        mode: ScoreComputationMode,
        level: Int = 1,
    ): Unit =
        runBlocking {
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
            val score = { build: BuildCombination -> exactScore(mode, targetStats, character, build) }

            val exhaustiveScore = allValidCombinations(equipments, characterSkills).maxOf { score(it) }

            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = mode
                )

            val solverBest =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType })
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(solverBest.individual.isValid()).isTrue()
            assertThat(solverBest.matchPercentage).isEqualByComparingTo(exhaustiveScore)
        }

    private fun exactScore(
        mode: ScoreComputationMode,
        targetStats: TargetStats,
        character: Character,
        build: BuildCombination,
    ): BigDecimal =
        when (mode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                FindMostMasteriesFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues)

            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues)
        }

    private fun runDeterministicGeneticAlgorithm(
        population: List<BuildCombination>,
        scoreFn: (BuildCombination) -> BigDecimal,
    ) = runBlocking {
        val ga =
            GeneticAlgorithm(
                population = population,
                score = scoreFn,
                cross = { parents -> parents.first },
                mutate = { it },
                select = { scoredPopulation -> scoredPopulation.first().individual }
            )

        ga.run(Duration.ZERO, stopWhenBuildMatch = false).toList().last()
    }

    private data class ScoredBuild(
        val build: BuildCombination,
        val score: BigDecimal,
    )

    private fun runSequentialGeneticSearch(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        populationSize: Int,
        mutationProbability: Double,
        seed: Long,
    ): ScoredBuild {
        val random = Random(seed)
        val population =
            generateRandomPopulations(
                numberOfIndividual = populationSize,
                equipmentsByItemType = equipmentsByItemType,
                character = params.character,
                targetStats = params.targetStats,
                random = random
            )

        val scoreFn = scoreFn(params.targetStats, params.character)
        var scoredPopulation =
            population
                .map { ScoredIndividual(scoreFn(it), it) }
                .sortedByDescending { it.score }
        var best = scoredPopulation.first()
        val endTime = System.currentTimeMillis() + params.searchDuration.inWholeMilliseconds

        while (System.currentTimeMillis() < endTime) {
            scoredPopulation =
                scoredPopulation
                    .map {
                        val parents = tournamentSelection(scoredPopulation, random) to tournamentSelection(scoredPopulation, random)
                        val crossed = cross(parents, random)
                        val mutated =
                            mutateCombination(
                                individual = crossed,
                                mutationProbability = mutationProbability,
                                equipmentsByItemType = equipmentsByItemType,
                                targetStats = params.targetStats,
                                random = random
                            )
                        ScoredIndividual(scoreFn(mutated), mutated)
                    }.sortedByDescending { it.score }
            if (scoredPopulation.first().score > best.score) {
                best = scoredPopulation.first()
            }
        }

        return ScoredBuild(best.individual, best.score)
    }

    private fun scoreFn(
        targetStats: TargetStats,
        character: Character,
    ): (BuildCombination) -> BigDecimal =
        { build ->
            FindMostMasteriesFromInputScoring.computeScore(
                targetStats = targetStats,
                buildCombination = build,
                characterBaseCharacteristics = character.baseCharacteristicValues
            )
        }

    private fun allValidCombinations(
        equipments: List<Equipment>,
        characterSkills: CharacterSkills,
    ): List<BuildCombination> {
        val results = mutableListOf<BuildCombination>()
        val count = equipments.size
        val totalMasks = 1 shl count

        for (mask in 0 until totalMasks) {
            val selected = mutableListOf<Equipment>()
            for (index in 0 until count) {
                if ((mask and (1 shl index)) != 0) {
                    selected.add(equipments[index])
                }
            }
            val combination = BuildCombination(selected, characterSkills)
            if (combination.isValid()) {
                results.add(combination)
            }
        }

        return results
    }

    private fun equipment(
        id: Int,
        type: ItemType,
        name: String,
        stats: Map<Characteristic, Int>,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = 1,
            name = I18nText(name, name, name, name),
            rarity = Rarity.COMMON,
            itemType = type,
            characteristics = stats
        )
}
