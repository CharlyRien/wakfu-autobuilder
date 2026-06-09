package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
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
import kotlin.time.Duration.Companion.seconds

class WakfuBuildSolverTest {
    @Test
    fun `lp solver reaches the exhaustive optimum on a deterministic pool`(): Unit =
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

            val lpBest = WakfuBuildSolver.optimize(params, equipmentsByItemType).toList().maxByOrNull { it.matchPercentage }!!

            assertThat(lpBest.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            assertThat(lpBest.individual.isValid()).isTrue
        }

    @Test
    fun `lp solver reaches the exhaustive optimum when targets are impossible`(): Unit =
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

                val lpBest = WakfuBuildSolver.optimize(params, equipmentsByItemType).toList().maxByOrNull { it.matchPercentage }!!

                assertThat(lpBest.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            }
        }

    @Test
    fun `lp solver finds a valid feasible build on the level 245 dataset`(): Unit =
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
                    searchDuration = 15.seconds,
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

            // Deterministic, machine-independent solve (fixed worker + seed + deterministic-time
            // budget): the search reaches the *same proven optimum* on every machine. This is what
            // de-flakes the test — under the real wall-clock search a slow/loaded CI runner could stop
            // early on a sub-optimal feasible build that violated the assertions below.
            val results =
                WakfuBuildSolver
                    .optimize(params, equipmentsByItemType, WakfuBuildSolver.SolverTuning())
                    .toList()

            // Optimality must actually be *proven* within the deterministic-time budget — that proof
            // is what keeps every assertion below stable. A failure here is a loud, reproducible
            // "budget too small" signal, never a flake.
            val lpBest =
                results.lastOrNull { it.isOptimal }
            assertThat(lpBest)
                .describedAs("solver must prove optimality within the deterministic-time budget")
                .isNotNull

            // A real, valid, non-trivial build — not an empty no-op that could pass a bare ">= GA".
            assertThat(lpBest!!.individual.isValid()).isTrue()
            assertThat(lpBest.individual.equipments.size).isGreaterThanOrEqualTo(10)

            // Correctness on real data: every required hard constraint (AP/MP/range/crit) is actually met.
            val achieved =
                computeCharacteristicsValues(
                    buildCombination = lpBest.individual,
                    characterBaseCharacteristics = character.baseCharacteristicValues,
                    masteryElementsWanted = targetStats.masteryElementsWanted,
                    resistanceElementsWanted = targetStats.resistanceElementsWanted
                )
            targetStats
                .filter { it.characteristic.isRequiredMostMasteriesTarget() }
                .forEach { required ->
                    assertThat(achieved[required.characteristic] ?: 0)
                        .describedAs("required constraint ${required.characteristic}")
                        .isGreaterThanOrEqualTo(required.target)
                }
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

    // ---------------------------------------------------------------------------------------------
    // Regression: the generic "+all elements" mastery/resistance stat (carried by gear AND by the
    // Strength/Intelligence/Major skill lines) must fold into a *specific* element / resistance
    // target. It used to be invisible there, so the matching items and Major aptitudes were never
    // selected even with a free slot.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `precision solver folds generic mastery into a specific-element target`() {
        // Only "Generic" can move the fire target (its +40 is generic elemental mastery); "OffTarget"
        // contributes nothing to fire. Before the fold, the fire objective saw 0 from both and could
        // return the off-target (or empty) build, missing the exhaustive 100% optimum.
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "Generic", mapOf(Characteristic.MASTERY_ELEMENTARY to 40)),
                equipment(2, ItemType.AMULET, "OffTarget", mapOf(Characteristic.MASTERY_MELEE to 40))
            )
        assertSolverReachesExhaustiveOptimum(
            equipments = equipments,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 40))),
            mode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        )
    }

    @Test
    fun `precision solver folds generic resistance into a specific-resistance target`(): Unit =
        runBlocking {
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "GenericRes", mapOf(Characteristic.RESISTANCE_ELEMENTARY to 30)),
                    equipment(2, ItemType.AMULET, "OffTarget", mapOf(Characteristic.MASTERY_MELEE to 40))
                )
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 30)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType })
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // The generic-resistance amulet must be chosen: its +30 folds entirely into fire resistance.
            assertThat(best.individual.equipments.map { it.name.fr }).contains("GenericRes")
            val offTargetOnly =
                FindClosestBuildFromInputScoring.computeScore(
                    targetStats,
                    BuildCombination(listOf(equipments[1]), characterSkills),
                    character.baseCharacteristicValues
                )
            assertThat(best.matchPercentage).isGreaterThan(offTargetOnly)
        }

    @Test
    fun `precision solver spends a free Major aptitude on mastery for a specific-element target`(): Unit =
        runBlocking {
            // Level 25 unlocks exactly one Major point. The fire target is unreachable, so every
            // mastery lever strictly helps — the solver must spend that free Major point on a
            // mastery-granting ("damage") aptitude, which all carry generic MASTERY_ELEMENTARY.
            val level = 25
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
            val equipments = listOf(equipment(1, ItemType.AMULET, "Filler", mapOf(Characteristic.MASTERY_MELEE to 5)))
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 9999)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType })
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val major = best.individual.characterSkills.major
            val masteryAptitudePoints =
                major.movementPointAndMasteryElementary.pointsAssigned +
                    major.rangeAndMasteryElementary.pointsAssigned +
                    major.controlAndMasteryElementary.pointsAssigned
            assertThat(masteryAptitudePoints)
                .describedAs("free Major point should be spent on a mastery aptitude")
                .isGreaterThan(0)

            // And the assigned skills genuinely raise the real score versus the same items unskilled.
            val unskilled =
                FindClosestBuildFromInputScoring.computeScore(
                    targetStats,
                    BuildCombination(best.individual.equipments, CharacterSkills(level)),
                    character.baseCharacteristicValues
                )
            assertThat(best.matchPercentage).isGreaterThan(unskilled)
        }

    @Test
    fun `solver maps Major and Intelligence resistance back without name collision`(): Unit =
        runBlocking {
            // Both Intelligence and Major carry a skill named "Resistance Elementary". The old
            // name-based back-mapping wrote Intelligence's (up to 10) resistance value onto Major's
            // (max 1) aptitude, throwing or corrupting the result. At level 75 an unreachable
            // resistance target makes the solver assign both, exercising the collision.
            val level = 75
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
            val equipments = listOf(equipment(1, ItemType.AMULET, "Filler", mapOf(Characteristic.MASTERY_MELEE to 5)))
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 9999)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType })
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val skills = best.individual.characterSkills
            // Each resistance skill must hold a value within its own bounds (not the other's).
            assertThat(skills.major.resistance.pointsAssigned).isBetween(0, 1)
            val intelligenceResistance = skills.intelligence.getCharacteristics().first { it.name == "Resistance Elementary" }
            assertThat(intelligenceResistance.pointsAssigned).isBetween(0, intelligenceResistance.maxPointsAssignable)
            // The reported match must match a fresh recompute of the returned build (self-consistent).
            val recomputed =
                FindClosestBuildFromInputScoring.computeScore(targetStats, best.individual, character.baseCharacteristicValues)
            assertThat(best.matchPercentage).isEqualByComparingTo(recomputed)
        }

    @Test
    fun `random-element stats are never treated as required most-masteries targets`() {
        // Defensive: random-element item stats are distributed onto concrete elements, never a hard
        // exact constraint — otherwise they would corrupt the required numerator/denominator.
        assertThat(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT.isRandomElementStat()).isTrue()
        assertThat(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT.isRequiredMostMasteriesTarget()).isFalse()
        assertThat(Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT.isRequiredMostMasteriesTarget()).isFalse()
        // Sanity: genuine required stats and maximizable masteries keep their classification.
        assertThat(Characteristic.HP.isRequiredMostMasteriesTarget()).isTrue()
        assertThat(Characteristic.RESISTANCE_ELEMENTARY.isRequiredMostMasteriesTarget()).isTrue()
        assertThat(Characteristic.MASTERY_MELEE.isRequiredMostMasteriesTarget()).isFalse()
        assertThat(Characteristic.MASTERY_ELEMENTARY.isRequiredMostMasteriesTarget()).isFalse()
    }

    // ---------------------------------------------------------------------------------------------
    // Regression: requesting the aggregate "all elements" mastery alongside specific elements must
    // not turn the most-masteries objective into "balance all four elements" (min over fire/water/
    // earth/air). The specific elements win; "all elements" only contributes by folding its generic
    // gear into them. Before the fix the min-over-four objective starved the requested fire/earth.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `most-masteries honours specific elements when all-elements is also requested`(): Unit =
        runBlocking {
            val equipments =
                listOf(
                    equipment(
                        1,
                        ItemType.AMULET,
                        "FireEarth",
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60, Characteristic.MASTERY_ELEMENTARY_EARTH to 60)
                    ),
                    equipment(
                        2,
                        ItemType.AMULET,
                        "Balanced",
                        mapOf(
                            Characteristic.MASTERY_ELEMENTARY_FIRE to 20,
                            Characteristic.MASTERY_ELEMENTARY_WATER to 20,
                            Characteristic.MASTERY_ELEMENTARY_EARTH to 20,
                            Characteristic.MASTERY_ELEMENTARY_WIND to 20
                        )
                    )
                )
            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_EARTH, 1)
                    )
                )

            // Lockstep: the solver still reaches the matching scorer's exhaustive optimum.
            assertSolverReachesExhaustiveOptimum(
                equipments,
                targetStats,
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )

            // And that optimum is the fire/earth-stacked amulet, not the spread-thin balanced one.
            val best = solve(equipments, targetStats)
            assertThat(best.individual.equipments.map { it.name.fr }).contains("FireEarth")
        }

    @Test
    fun `most-masteries with only all-elements still balances across the four elements`(): Unit =
        runBlocking {
            // Guard the other direction: with NO specific element requested, "all elements" keeps the
            // balanced (min-over-four) objective — the spread item that lifts every element wins over
            // one that stacks two and leaves the others at zero.
            val equipments =
                listOf(
                    equipment(
                        1,
                        ItemType.AMULET,
                        "FireEarth",
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60, Characteristic.MASTERY_ELEMENTARY_EARTH to 60)
                    ),
                    equipment(
                        2,
                        ItemType.AMULET,
                        "Balanced",
                        mapOf(
                            Characteristic.MASTERY_ELEMENTARY_FIRE to 20,
                            Characteristic.MASTERY_ELEMENTARY_WATER to 20,
                            Characteristic.MASTERY_ELEMENTARY_EARTH to 20,
                            Characteristic.MASTERY_ELEMENTARY_WIND to 20
                        )
                    )
                )
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 1)))

            assertSolverReachesExhaustiveOptimum(
                equipments,
                targetStats,
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )

            val best = solve(equipments, targetStats)
            assertThat(best.individual.equipments.map { it.name.fr }).contains("Balanced")
        }

    @Test
    fun `most-masteries folds generic all-elements gear into the requested specific elements`(): Unit =
        runBlocking {
            // The generic "+all elements" item must still be valued in the combined case: its +10
            // folds into both fire and earth, lifting the minimised pair, so it is selected on top of
            // the fire/earth amulet.
            val equipments =
                listOf(
                    equipment(
                        1,
                        ItemType.AMULET,
                        "FireEarth",
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 50, Characteristic.MASTERY_ELEMENTARY_EARTH to 50)
                    ),
                    equipment(2, ItemType.BELT, "Generic", mapOf(Characteristic.MASTERY_ELEMENTARY to 10))
                )
            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_EARTH, 1)
                    )
                )

            assertSolverReachesExhaustiveOptimum(
                equipments,
                targetStats,
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )

            val best = solve(equipments, targetStats)
            assertThat(best.individual.equipments.map { it.name.fr }).contains("FireEarth", "Generic")
        }

    @Test
    fun `most-masteries prefers a strictly stronger same-slot upgrade for the requested elements`(): Unit =
        runBlocking {
            // The reported "it picked a weaker item over its stronger evolution" case. With "all
            // elements" co-requested, the old min-over-four objective scored BOTH the base and its
            // upgrade at min = 0 (water/air stay 0), so the upgrade was objective-neutral and the
            // solver had no reason not to park the weaker one. Minimising over the requested
            // {fire, earth} makes the upgrade strictly better, so it must win.
            val equipments =
                listOf(
                    equipment(
                        1,
                        ItemType.AMULET,
                        "Base",
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 40, Characteristic.MASTERY_ELEMENTARY_EARTH to 40)
                    ),
                    equipment(
                        2,
                        ItemType.AMULET,
                        "Evolution",
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60, Characteristic.MASTERY_ELEMENTARY_EARTH to 60)
                    )
                )
            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_EARTH, 1)
                    )
                )

            assertSolverReachesExhaustiveOptimum(
                equipments,
                targetStats,
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )

            val best = solve(equipments, targetStats)
            assertThat(best.individual.equipments.map { it.name.fr }).contains("Evolution")
        }

    /** Runs the real most-masteries solver over the given pool and returns its best emitted build. */
    private fun solve(
        equipments: List<Equipment>,
        targetStats: TargetStats,
        level: Int = 1,
    ): GeneticAlgorithmResult<BuildCombination> =
        runBlocking {
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, CharacterSkills(level))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            WakfuBuildSolver
                .optimize(params, equipments.groupBy { it.itemType })
                .toList()
                .maxByOrNull { it.matchPercentage }!!
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
