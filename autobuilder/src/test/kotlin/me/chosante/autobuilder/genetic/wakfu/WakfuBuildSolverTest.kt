package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
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
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
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
    @Tag("slow")
    fun `lp solver finds a valid feasible build on the level 245 dataset`(): Unit =
        runBlocking {
            val level = 245
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)

            val equipments =
                this.javaClass.classLoader.getResourceAsStream("equipments.json")?.readAllBytes()!!.let {
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
    fun `most-masteries spends an objective-neutral skill point to overshoot a required target`(): Unit =
        runBlocking {
            // Issue #126: once HP is fully covered by gear, extra HP from skills is worth 0 to the
            // maximized masteries, so the solver used to leave those points unassigned. The lexicographic
            // overshoot tie-breaker now spends them — free in-game value — WITHOUT changing the (mastery)
            // score. A level-1 CRA has a single Intelligence point whose only scored lever is % HP, and
            // the HP target is met by gear alone, so assigning it is a pure overshoot gain.
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "HpAmulet", mapOf(Characteristic.HP to 200, Characteristic.MASTERY_MELEE to 10)),
                    equipment(2, ItemType.AMULET, "DmgAmulet", mapOf(Characteristic.MASTERY_MELEE to 100)),
                    equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_MELEE to 40))
                )
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.HP, 200), TargetStat(Characteristic.MASTERY_MELEE, 1)))
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

            // Deterministic solve so the *proven* optimum (carrying the tie-breaker's allocation) is emitted.
            val optimal =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .last { it.isOptimal }

            // The mastery score is still exactly the exhaustive (unskilled) optimum — overshoot never inflates it.
            val exhaustiveScore =
                allValidCombinations(equipments, characterSkills)
                    .maxOf { FindMostMasteriesFromInputScoring.computeScore(targetStats, it, character.baseCharacteristicValues) }
            assertThat(optimal.matchPercentage).isEqualByComparingTo(exhaustiveScore)
            assertThat(optimal.individual.equipments.map { it.name.fr }).containsExactlyInAnyOrder("HpAmulet", "Belt")

            // ...but the otherwise-idle Intelligence point is now spent overshooting HP (was 0 before #126).
            assertThat(optimal.individual.characterSkills.intelligence.hpPercentage.pointsAssigned)
                .describedAs("objective-neutral Intelligence point should be spent overshooting HP")
                .isGreaterThan(0)
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

    // ---------------------------------------------------------------------------------------------
    // Per-stat priority on the required CONSTRAINTS (#123): a higher userDefinedWeight makes a
    // constraint's shortfall hurt the weighted success ratio more, so when the targets can't all be met
    // the solver prefers satisfying the higher-priority one. The exhaustive oracle uses the SAME scorer,
    // so this also asserts solver/scorer lockstep. (Priority on the *maximized masteries* was reverted —
    // it produced winner-take-all builds that dropped lower-priority masteries; see AGENTS.md / memory.)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `most-masteries prefers the higher-priority constraint when they cannot all be met`(): Unit =
        runBlocking {
            // The issue author's case: an over-constrained request, priority decides which target is met.
            // A level-1 CRA has base AP 6 / MP 3. One amulet slot, two candidates each adding +1 to one
            // resource plus an *equal* melee bonus, so the maximized masteries tie and only constraint
            // satisfaction breaks it. AP target 7 and MP target 4 can't both be met from one slot. With AP
            // at priority 5 the solver satisfies AP; at equal priority MP's smaller target gives it the
            // higher per-unit weight and it would win instead — so the priority genuinely flips the choice.
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "ApAmulet", mapOf(Characteristic.ACTION_POINT to 1, Characteristic.MASTERY_MELEE to 10)),
                    equipment(2, ItemType.AMULET, "MpAmulet", mapOf(Characteristic.MOVEMENT_POINT to 1, Characteristic.MASTERY_MELEE to 10))
                )
            val targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 7, userDefinedWeight = 5),
                        TargetStat(Characteristic.MOVEMENT_POINT, 4, userDefinedWeight = 1),
                        TargetStat(Characteristic.MASTERY_MELEE, 1, userDefinedWeight = 1)
                    )
                )

            assertSolverReachesExhaustiveOptimum(equipments, targetStats, ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)

            val best = solve(equipments, targetStats)
            assertThat(best.individual.equipments.map { it.name.fr }).contains("ApAmulet")
        }

    // ---------------------------------------------------------------------------------------------
    // Runes: the solver socket-fills equipped items with the best runes for the requested stats
    // (best-achievable model: max rune level + WakForge doubling on favoured slots).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rune value uses the item-level-capped max level and doubles only on favoured slots`() {
        // Distance-mastery rune: favoured slots (doubleBonusPosition) are belt (rawId 10) and the
        // primary weapon (rawId 15). It doubles only there.
        val distanceRune =
            RuneType(
                id = 27098,
                name = I18nText("d", "d", "d", "d"),
                color = RuneColor.RED,
                characteristic = Characteristic.MASTERY_DISTANCE,
                doubleBonusPosition = listOf(10, 15),
                gfxId = 0
            )
        // Item level 245 -> max enchant level 11 -> base mastery value 33.
        assertThat(distanceRune.valueOn(ItemType.AMULET, 245)).isEqualTo(33)
        assertThat(distanceRune.valueOn(ItemType.BELT, 245)).isEqualTo(66)
        assertThat(distanceRune.valueOn(ItemType.TWO_HANDED_WEAPONS, 245)).isEqualTo(66)
        // Item-level gating: a level-1 item caps at enchant level 1 -> base value 1 (doubled to 2 on a belt).
        assertThat(distanceRune.valueOn(ItemType.AMULET, 1)).isEqualTo(1)
        assertThat(distanceRune.valueOn(ItemType.BELT, 1)).isEqualTo(2)
    }

    @Test
    fun `rune max level is capped by the carrier item level, not the character level`() {
        // Regression for the level-50 amulet getting a level-3 shard: the enchant level cap follows
        // the item's level per Ankama's table (lvl 2 needs item >= 36, lvl 3 needs item >= 51, ...).
        val rune =
            RuneType(27098, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)

        assertThat(rune.maxLevel(1)).isEqualTo(1)
        assertThat(rune.maxLevel(35)).isEqualTo(1)
        assertThat(rune.maxLevel(36)).isEqualTo(2)
        assertThat(rune.maxLevel(50)).isEqualTo(2) // the reported bug: a level-50 item must cap at 2, not 3
        assertThat(rune.maxLevel(51)).isEqualTo(3)
        assertThat(rune.maxLevel(65)).isEqualTo(3)
        assertThat(rune.maxLevel(66)).isEqualTo(4)
        assertThat(rune.maxLevel(81)).isEqualTo(5)
        assertThat(rune.maxLevel(96)).isEqualTo(6)
        assertThat(rune.maxLevel(126)).isEqualTo(7)
        assertThat(rune.maxLevel(141)).isEqualTo(8)
        assertThat(rune.maxLevel(171)).isEqualTo(9)
        assertThat(rune.maxLevel(186)).isEqualTo(10)
        assertThat(rune.maxLevel(216)).isEqualTo(11)
        assertThat(rune.maxLevel(245)).isEqualTo(11)
    }

    @Test
    fun `solver socket-fills runes and the recomputed score matches the objective`(): Unit =
        runBlocking {
            val level = 245
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val amulet = equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4, level = 245)
            val distanceRune =
                RuneType(27098, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
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

            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(amulet).groupBy { it.itemType }, listOf(distanceRune))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val socketedRunes =
                best.individual.runes.values
                    .flatten()
            // All four sockets are filled with the only beneficial (distance) rune.
            assertThat(socketedRunes).hasSize(4)
            assertThat(socketedRunes).allMatch { it.characteristic == Characteristic.MASTERY_DISTANCE }

            // Self-consistency: the rune-aware scorer recomputes exactly the solver's reported score.
            val recomputed =
                FindMostMasteriesFromInputScoring.computeScore(targetStats, best.individual, character.baseCharacteristicValues)
            assertThat(best.matchPercentage).isEqualByComparingTo(recomputed)

            // Item 50 + four amulet runes (not doubled on an amulet) at 33 each = 182.
            val achieved =
                computeCharacteristicsValues(best.individual, character.baseCharacteristicValues, emptyMap(), emptyMap())
            assertThat(achieved[Characteristic.MASTERY_DISTANCE]).isGreaterThanOrEqualTo(50 + 4 * 33)
        }

    @Test
    fun `solver doubles a rune on its favoured slot and respects the 4-socket cap`(): Unit =
        runBlocking {
            // Level 1 keeps skills inert (no distance from aptitudes), so the achieved value isolates
            // the runes: a belt (rawId 10, favoured by the distance rune) doubles each level-1 rune
            // 1 -> 2, and the 4-socket cap bounds it to exactly 4 * 2 = 8.
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val belt = equipment(1, ItemType.BELT, "Belt", emptyMap(), maxShardSlots = 4)
            val distanceRune =
                RuneType(27098, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
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

            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(belt).groupBy { it.itemType }, listOf(distanceRune))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.runes.values
                    .flatten()
            ).hasSize(4)
            val achieved =
                computeCharacteristicsValues(best.individual, character.baseCharacteristicValues, emptyMap(), emptyMap())
            assertThat(achieved[Characteristic.MASTERY_DISTANCE]).isEqualTo(8)
        }

    @Test
    fun `runes are disabled when useRunes is false`(): Unit =
        runBlocking {
            val level = 245
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val amulet = equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4, level = 245)
            val distanceRune =
                RuneType(27098, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(amulet).groupBy { it.itemType }, listOf(distanceRune))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.runes).isEmpty()
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

    // ---------------------------------------------------------------------------------------------
    // Max-damage mode: expected-damage scorer + solver objective (Wakfu formula).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `max-damage scorer matches a hand-computed non-crit hit`() {
        val level = 1
        val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
        val amulet =
            equipment(1, ItemType.AMULET, "A", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 50, Characteristic.MASTERY_DISTANCE to 100))
        val build = BuildCombination(listOf(amulet), CharacterSkills(level))
        // FIRE/DISTANCE, face (×1.0, no rear mastery), no crit: dmg = 100 × (1 + (50+100)/100) = 250.
        val scenario =
            DamageScenario(
                element = SpellElement.FIRE,
                rangeBand = RangeBand.DISTANCE,
                orientation = Orientation.FACE,
                critCapPercent = 0,
                baseDamage = 100
            )
        val score = FindMaxDamageScoring.computeScore(TargetStats(emptyList()), build, character.baseCharacteristicValues, scenario)
        assertThat(score).isEqualByComparingTo("250.0000")
    }

    @Test
    fun `max-damage scorer applies the crit expectation and critical mastery`() {
        val level = 1
        val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
        // +47 crit over the base 3 = 50% crit rate.
        val amulet =
            equipment(
                1,
                ItemType.AMULET,
                "A",
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100, Characteristic.MASTERY_CRITICAL to 20, Characteristic.CRITICAL_HIT to 47)
            )
        val build = BuildCombination(listOf(amulet), CharacterSkills(level))
        val scenario =
            DamageScenario(
                element = SpellElement.FIRE,
                rangeBand = RangeBand.DISTANCE,
                orientation = Orientation.FACE,
                critCapPercent = 100,
                baseDamage = 100
            )
        // nonCrit = 100×(1+100/100) = 200 ; crit = 100×1.25×(1+(100+20)/100) = 275 ; E = .5×200 + .5×275 = 237.5
        val score = FindMaxDamageScoring.computeScore(TargetStats(emptyList()), build, character.baseCharacteristicValues, scenario)
        assertThat(score).isEqualByComparingTo("237.5000")
    }

    @Test
    fun `max-damage solver stacks distance vs melee mastery according to the scenario`(): Unit =
        runBlocking {
            val level = 1
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            val distAmulet = equipment(1, ItemType.AMULET, "Dist", mapOf(Characteristic.MASTERY_DISTANCE to 100))
            val meleeAmulet = equipment(2, ItemType.AMULET, "Melee", mapOf(Characteristic.MASTERY_MELEE to 100))
            val pool = listOf(distAmulet, meleeAmulet).groupBy { it.itemType }

            fun bestFor(rangeBand: RangeBand): String {
                val params =
                    WakfuBestBuildParams(
                        character = character,
                        targetStats = TargetStats(emptyList()),
                        searchDuration = 5.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        useRunes = false,
                        damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = rangeBand, orientation = Orientation.FACE)
                    )
                return runBlocking {
                    WakfuBuildSolver
                        .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                        .toList()
                        .maxByOrNull { it.matchPercentage }!!
                        .individual.equipments
                        .single()
                        .name.fr
                }
            }

            assertThat(bestFor(RangeBand.DISTANCE)).isEqualTo("Dist")
            assertThat(bestFor(RangeBand.MELEE)).isEqualTo("Melee")
        }

    @Test
    fun `the max-damage AP target pins the build to exactly that many AP`(): Unit =
        runBlocking {
            // The external loop's probe mechanism: maxDamageApTarget hard-constrains the build's AP.
            val level = 200
            val character = Character(CharacterClass.SRAM, level, 1, CharacterSkills(level))
            val apAmulet = equipment(1, ItemType.AMULET, "ApStuff", mapOf(Characteristic.ACTION_POINT to 2))
            val masteryAmulet = equipment(2, ItemType.AMULET, "MasteryStuff", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            val pool = listOf(apAmulet, masteryAmulet).groupBy { it.itemType }

            fun apOf(build: BuildCombination): Int =
                computeCharacteristicsValues(build, character.baseCharacteristicValues, emptyMap(), emptyMap())[Characteristic.ACTION_POINT] ?: 0

            for (target in listOf(6, 8)) { // 6 = no AP gear (base), 8 = base 6 + the +2 amulet
                val params =
                    WakfuBestBuildParams(
                        character = character,
                        targetStats = TargetStats(emptyList()),
                        searchDuration = 5.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        useRunes = false,
                        damageScenario = DamageScenario(element = SpellElement.FIRE),
                        maxDamageApTarget = target
                    )
                val best =
                    WakfuBuildSolver
                        .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                        .toList()
                        .maxByOrNull { it.matchPercentage }!!
                assertThat(apOf(best.individual)).describedAs("AP pinned to $target").isEqualTo(target)
            }
        }

    @Test
    fun `max-damage solver reaches the exhaustive optimum on a small pool`(): Unit =
        runBlocking {
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "FireBig", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 120)),
                    equipment(2, ItemType.AMULET, "Crit", mapOf(Characteristic.CRITICAL_HIT to 40, Characteristic.MASTERY_CRITICAL to 30)),
                    equipment(3, ItemType.BELT, "Generic", mapOf(Characteristic.MASTERY_ELEMENTARY to 60)),
                    equipment(4, ItemType.BELT, "Distance", mapOf(Characteristic.MASTERY_DISTANCE to 80))
                )
            val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)
            val targetStats = TargetStats(emptyList())
            // The objective is now spell-aware (per-turn rotation damage), so the exhaustive reference
            // uses the same rotation scorer the solver does.
            val exhaustive =
                allValidCombinations(equipments, characterSkills)
                    .maxOf {
                        me.chosante.autobuilder.domain.SpellRotationOptimizer
                            .bestAcrossElements(it, character, character.clazz, scenario)
                            .totalExpectedDamage
                            .toBigDecimal()
                    }

            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = false,
                    damageScenario = scenario
                )
            val solverBest =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(solverBest.individual.isValid()).isTrue()
            assertThat(solverBest.matchPercentage).isGreaterThanOrEqualTo(exhaustive)
        }

    @Test
    fun `boss-aware max-damage refuses the boss's weak element when the class can't play it`(): Unit =
        runBlocking {
            // The headline validation: a Cra vs a WATER-weak boss must NOT be handed a Water build —
            // Cra has no Water spells. A naive per-hit objective would stack Water mastery to exploit
            // the weakness; the spell-aware objective gates Water out (zero throughput) and instead
            // plays an element the Cra actually has spells in.
            val level = 200
            val character = Character(CharacterClass.CRA, level, 1, CharacterSkills(level))
            val waterAmulet = equipment(1, ItemType.AMULET, "WaterStuff", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 400))
            val fireAmulet = equipment(2, ItemType.AMULET, "FireStuff", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200))
            val pool = listOf(waterAmulet, fireAmulet).groupBy { it.itemType }

            // Boss is hugely weak to Water (the naive trap) but heavily resists Earth & Air, so among the
            // elements Cra CAN play (Fire/Earth/Air) Fire is the best — the solver should land there.
            val scenario =
                DamageScenario(
                    rangeBand = RangeBand.DISTANCE,
                    orientation = Orientation.FACE,
                    elementResistances =
                        mapOf(
                            SpellElement.WATER to -90,
                            SpellElement.FIRE to 0,
                            SpellElement.EARTH to 90,
                            SpellElement.AIR to 90
                        )
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(emptyList()),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = false,
                    damageScenario = scenario
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // Despite the Water weakness + a strong Water-mastery item, the solver picks the Fire item,
            // because Cra has Fire spells and no Water spells.
            assertThat(
                best.individual.equipments
                    .single()
                    .name.fr
            ).isEqualTo("FireStuff")

            // And the chosen playable element is not Water.
            val chosen =
                me.chosante.autobuilder.domain.SpellRotationOptimizer
                    .bestAcrossElements(best.individual, character, CharacterClass.CRA, scenario)
            assertThat(chosen.element).isNotEqualTo(me.chosante.common.SpellElement.WATER)
            assertThat(chosen.totalExpectedDamage).isGreaterThan(0.0)
        }

    /** Max-damage params pinned to [totalAp] AP (used by the survivability + AP-pin tests). */
    private fun apPinnedMaxDamageParams(
        character: Character,
        scenario: DamageScenario,
        totalAp: Int,
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(emptyList()),
            searchDuration = 5.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            damageScenario = scenario,
            maxDamageApTarget = totalAp
        )

    // ----- Lot 5: survivability soft-floor -----

    /**
     * Two amulets with IDENTICAL fire mastery (⇒ identical damage core) but one also carries HP + elemental
     * resistance, so its effective-HP proxy clears the floor while the paper amulet's does not. With the
     * survivability floor ON, the tanky amulet must outrank the paper one (it keeps the full damage score;
     * the paper one is gently taxed) AND the solver, given both, must pick the tanky one. A control with the
     * floor OFF proves the two are otherwise objective-equal — so the gap is purely the survivability term.
     */
    @Test
    fun `survivability floor ranks an equal-damage tanky build above a paper build`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val apBelt = equipment(3, ItemType.BELT, "ApBelt", mapOf(Characteristic.ACTION_POINT to 6)) // base 6 + 6 ⇒ pins to 12 AP
            // Same fire mastery on both ⇒ identical per-hit damage; only HP + resist differ.
            val paperAmulet = equipment(1, ItemType.AMULET, "Paper", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200))
            val tankyAmulet =
                equipment(
                    2,
                    ItemType.AMULET,
                    "Tanky",
                    mapOf(
                        Characteristic.MASTERY_ELEMENTARY_FIRE to 200,
                        Characteristic.HP to 1000,
                        Characteristic.RESISTANCE_ELEMENTARY_FIRE to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_WATER to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_EARTH to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_WIND to 200
                    )
                )
            val a = 12
            val tuning = WakfuBuildSolver.SolverTuning()

            fun objective(
                pool: List<Equipment>,
                survival: Boolean,
            ): Long =
                WakfuBuildSolver.maxDamageObjectiveValueForTest(
                    apPinnedMaxDamageParams(
                        character,
                        DamageScenario(
                            element = SpellElement.FIRE,
                            rangeBand = RangeBand.DISTANCE,
                            orientation = Orientation.FACE,
                            survivabilityFloor = survival,
                            minEffectiveHp = 1500
                        ),
                        a
                    ),
                    pool.groupBy { it.itemType },
                    tuning
                )

            // Control (floor OFF): the two amulets are damage-twins, so each alone yields the same objective.
            val paperNoFloor = objective(listOf(paperAmulet, apBelt), survival = false)
            val tankyNoFloor = objective(listOf(tankyAmulet, apBelt), survival = false)
            assertThat(paperNoFloor).describedAs("sanity: damage is non-trivial").isGreaterThan(0)
            assertThat(tankyNoFloor).describedAs("without the floor the two amulets are damage-equal").isEqualTo(paperNoFloor)

            // Floor ON: the paper build is below the EHP floor ⇒ gently taxed; the tanky build clears it ⇒ untouched.
            val paperWithFloor = objective(listOf(paperAmulet, apBelt), survival = true)
            val tankyWithFloor = objective(listOf(tankyAmulet, apBelt), survival = true)
            assertThat(tankyWithFloor)
                .describedAs("the tanky build clears the floor and keeps its full damage score")
                .isGreaterThan(paperWithFloor)
            assertThat(tankyWithFloor)
                .describedAs("clearing the floor is a no-op ⇒ same objective as floor OFF")
                .isEqualTo(tankyNoFloor)

            // Given BOTH amulets and the floor, the solver must equip the tanky one (its objective is the max).
            val both = objective(listOf(paperAmulet, tankyAmulet, apBelt), survival = true)
            assertThat(both)
                .describedAs("with both available the survivability-aware optimum picks the tanky amulet")
                .isEqualTo(tankyWithFloor)
        }

    /**
     * The floor only *nudges*: it must not disturb the damage ranking among builds that already clear it.
     * Here a high-damage and a lower-damage amulet BOTH carry enough HP + resist to exceed the floor, so
     * neither is taxed and the survivability-aware objective collapses back to pure damage — the
     * higher-mastery amulet wins, exactly as it would with the floor off. This pins down that the penalty
     * is a true soft-floor (zero above it), not a survivability term that keeps biasing fully-tanky builds.
     */
    @Test
    fun `survivability floor does not disturb damage ranking among builds that clear it`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val apBelt = equipment(3, ItemType.BELT, "ApBelt", mapOf(Characteristic.ACTION_POINT to 6))
            // Shared tank stats (HP + resist) push both builds above the floor; only the fire mastery differs.
            val tankStats =
                mapOf(
                    Characteristic.HP to 1000,
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE to 200,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER to 200,
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH to 200,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND to 200
                )
            val highDamage = equipment(1, ItemType.AMULET, "HighDmg", tankStats + (Characteristic.MASTERY_ELEMENTARY_FIRE to 600))
            val lowDamage = equipment(2, ItemType.AMULET, "LowDmg", tankStats + (Characteristic.MASTERY_ELEMENTARY_FIRE to 200))
            val a = 12
            val tuning = WakfuBuildSolver.SolverTuning()
            val scenario =
                DamageScenario(
                    element = SpellElement.FIRE,
                    rangeBand = RangeBand.DISTANCE,
                    orientation = Orientation.FACE,
                    survivabilityFloor = true,
                    minEffectiveHp = 1500 // EHP ≈ 1060·1.8 ≈ 1908 for both ⇒ both clear it
                )

            fun objective(pool: List<Equipment>): Long =
                WakfuBuildSolver.maxDamageObjectiveValueForTest(
                    apPinnedMaxDamageParams(character, scenario, a),
                    pool.groupBy { it.itemType },
                    tuning
                )

            val both = objective(listOf(highDamage, lowDamage, apBelt))
            val highOnly = objective(listOf(highDamage, apBelt))
            assertThat(highOnly).isGreaterThan(0)
            assertThat(both)
                .describedAs("both builds clear the floor ⇒ no tax ⇒ the higher-damage amulet wins on damage alone")
                .isEqualTo(highOnly)
        }

    /**
     * Regression for the bucketed path (floor > MAX_POWER_TABLE_INDEX = 2000): clearing the floor must be an
     * EXACT no-op there too. The earlier tests use a 1500 floor (the un-bucketed path); a realistic floor is
     * larger, where integer bucketing used to tax a floor-clearing build. This uses a 3000 floor and a build
     * whose proxy (HP 2060 · 1.8 ≈ 3700) clears it, asserting floor-ON == floor-OFF.
     */
    @Test
    fun `survivability floor no-op is exact on the bucketed path above 2000`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val apBelt = equipment(3, ItemType.BELT, "ApBelt", mapOf(Characteristic.ACTION_POINT to 6))
            val tankyAmulet =
                equipment(
                    2,
                    ItemType.AMULET,
                    "Tanky",
                    mapOf(
                        Characteristic.MASTERY_ELEMENTARY_FIRE to 200,
                        Characteristic.HP to 2000,
                        Characteristic.RESISTANCE_ELEMENTARY_FIRE to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_WATER to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_EARTH to 200,
                        Characteristic.RESISTANCE_ELEMENTARY_WIND to 200
                    )
                )
            val tuning = WakfuBuildSolver.SolverTuning()

            fun objective(survival: Boolean): Long =
                WakfuBuildSolver.maxDamageObjectiveValueForTest(
                    apPinnedMaxDamageParams(
                        character,
                        DamageScenario(
                            element = SpellElement.FIRE,
                            rangeBand = RangeBand.DISTANCE,
                            orientation = Orientation.FACE,
                            survivabilityFloor = survival,
                            minEffectiveHp = 3000 // > 2000 ⇒ the bucketed path; the tanky proxy ≈ 3700 clears it
                        ),
                        12
                    ),
                    listOf(tankyAmulet, apBelt).groupBy { it.itemType },
                    tuning
                )

            assertThat(objective(survival = true))
                .describedAs("clearing a >2000 floor is an exact no-op (the clearsFloor override pins the multiplier to max)")
                .isEqualTo(objective(survival = false))
        }

    @Test
    fun `a forced rune is socketed at least once even when it matches no requested stat`(): Unit =
        runBlocking {
            val level = 245
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            // Request distance mastery only; force an HP rune that the auto-fill would never pick.
            val amulet = equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val distanceRune = RuneType(1, I18nText("Dist", "Dist", "Dist", "Dist"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val hpRune = RuneType(2, I18nText("Vita", "Vita", "Vita", "Vita"), RuneColor.GREEN, Characteristic.HP, emptyList(), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    forcedRunes = listOf("vita")
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(amulet).groupBy { it.itemType }, listOf(distanceRune, hpRune), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val socketed =
                best.individual.runes.values
                    .flatten()
            assertThat(socketed.count { it.characteristic == Characteristic.HP }).isGreaterThanOrEqualTo(1)
        }

    @Test
    fun `a per-item forced rune lands on its chosen carrier item and nowhere else`(): Unit =
        runBlocking {
            val level = 245
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            // Two socketed items in different slots; auto-fill off, so ONLY the forced rune is socketed.
            val amulet = equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val ring = equipment(2, ItemType.RING, "Bague", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val distanceRune = RuneType(1, I18nText("Dist", "Dist", "Dist", "Dist"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val hpRune = RuneType(2, I18nText("Vita", "Vita", "Vita", "Vita"), RuneColor.GREEN, Characteristic.HP, emptyList(), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    // Pin the HP rune (id 2) onto the ring specifically.
                    forcedRunesByItem = mapOf("Bague" to listOf(2))
                )

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(amulet, ring).groupBy { it.itemType },
                        listOf(distanceRune, hpRune),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            val equippedRing = best.individual.equipments.single { it.itemType == ItemType.RING }
            val equippedAmulet = best.individual.equipments.single { it.itemType == ItemType.AMULET }
            // The forced HP rune sits on the ring, and nowhere else (auto-fill is off).
            assertThat(
                best.individual.runes[equippedRing]
                    .orEmpty()
                    .count { it.characteristic == Characteristic.HP }
            ).isGreaterThanOrEqualTo(1)
            assertThat(
                best.individual.runes[equippedAmulet]
                    .orEmpty()
                    .count { it.characteristic == Characteristic.HP }
            ).isZero()
        }

    @Test
    fun `pinning a rune onto an item forces that item to be equipped even when it is worse`(): Unit =
        runBlocking {
            val level = 245
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            // Two amulets compete for the one slot: the solver would normally take the high-mastery one.
            val betterAmulet = equipment(1, ItemType.AMULET, "AmuBest", mapOf(Characteristic.MASTERY_DISTANCE to 200), maxShardSlots = 4)
            val worseAmulet = equipment(2, ItemType.AMULET, "AmuWorse", mapOf(Characteristic.MASTERY_DISTANCE to 10), maxShardSlots = 4)
            val hpRune = RuneType(2, I18nText("Vita", "Vita", "Vita", "Vita"), RuneColor.GREEN, Characteristic.HP, emptyList(), 0)
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    // Pin an HP rune onto the worse amulet — which must therefore be the one equipped.
                    forcedRunesByItem = mapOf("AmuWorse" to listOf(2))
                )

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(betterAmulet, worseAmulet).groupBy { it.itemType },
                        listOf(hpRune),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            val equippedAmulet = best.individual.equipments.single { it.itemType == ItemType.AMULET }
            assertThat(equippedAmulet.name.fr).isEqualTo("AmuWorse")
            assertThat(
                best.individual.runes[equippedAmulet]
                    .orEmpty()
                    .count { it.characteristic == Characteristic.HP }
            ).isGreaterThanOrEqualTo(1)
        }

    // ---------------------------------------------------------------------------------------------
    // Sublimations (Lot 3): the solver chooses statically-modelable subs (epic/relic/normal) and
    // applies forced ones. Effects fold into the same stat term loop as items/runes.
    // ---------------------------------------------------------------------------------------------

    private fun sublimation(
        stateId: Int,
        rarity: SublimationRarity,
        kind: SublimationKind,
        name: String,
        effects: List<SublimationEffect> = emptyList(),
        condition: SublimationCondition? = null,
        slotColorPattern: List<Int> = emptyList(),
        solverChoosable: Boolean = true,
    ): Sublimation =
        Sublimation(
            stateId = stateId,
            name = I18nText(name, name, name, name),
            rarity = rarity,
            slotColorPattern = slotColorPattern,
            maxLevel = 1,
            kind = kind,
            solverChoosable = solverChoosable,
            condition = condition,
            effects = effects,
            conversion = null,
            rawText = name
        )

    private fun maxDamageParams(
        character: Character,
        forcedSublimations: List<String> = emptyList(),
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(emptyList()),
            searchDuration = 5.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            forcedSublimations = forcedSublimations,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        )

    @Test
    fun `solver chooses a static-conditional DI epic when its condition is satisfiable`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // An epic sub can only be hosted by an epic ITEM (the slot comes from the carrier).
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            // Inflexible-like: AP <= 10 -> +15% damage inflicted. Base AP is 6, so condition holds.
            val inflexible =
                sublimation(
                    5073,
                    SublimationRarity.EPIC,
                    SublimationKind.STATIC_CONDITIONAL,
                    "Inflexibility",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 15)),
                    condition = SublimationCondition(SublimationConditionType.AP_AT_MOST, 10)
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet, epicCarrier).groupBy { it.itemType }, emptyList(), listOf(inflexible), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Inflexibility")
            val scenario = maxDamageParams(character).damageScenario
            val stats =
                computeCharacteristicsValues(
                    best.individual,
                    character.baseCharacteristicValues,
                    emptyMap(),
                    emptyMap(),
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    scenario
                )
            assertThat(stats[Characteristic.DAMAGE_INFLICTED]).isEqualTo(15)
        }

    @Test
    fun `solver declines a static-conditional sub whose condition is unreachable`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // Provide an epic carrier so the ONLY reason to decline is the condition, not a missing host.
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            // Requires AP >= 99, unreachable at level 1 (base 6, no AP gear) -> never chosen.
            val unreachable =
                sublimation(
                    9001,
                    SublimationRarity.EPIC,
                    SublimationKind.STATIC_CONDITIONAL,
                    "Unreachable",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 50)),
                    condition = SublimationCondition(SublimationConditionType.AP_AT_LEAST, 99)
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet, epicCarrier).groupBy { it.itemType }, emptyList(), listOf(unreachable), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.sublimations).isEmpty()
        }

    @Test
    fun `at most one epic sublimation is chosen`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // A single epic carrier item -> at most one epic sub can be hosted (Σ epicSub ≤ Σ epicItems = 1).
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val epicA =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicA", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)))
            val epicB =
                sublimation(2, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicB", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 12)))

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet, epicCarrier).groupBy { it.itemType }, emptyList(), listOf(epicA, epicB), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .count { it.rarity == SublimationRarity.EPIC }
            ).isEqualTo(1)
            // It picks the stronger one.
            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .single()
                    .name.en
            ).isEqualTo("EpicB")
        }

    @Test
    fun `a forced relic sublimation is always in the build and applies its flat effect`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // A relic sub needs a relic ITEM to host it; forcing the sub must pull its relic carrier in.
            val relicCarrier = equipment(2, ItemType.CAPE, "RelicCarrier", emptyMap(), rarity = Rarity.RELIC)
            val relic =
                sublimation(7, SublimationRarity.RELIC, SublimationKind.FLAT, "Directives", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 15)))

            val best =
                WakfuBuildSolver
                    .optimize(
                        maxDamageParams(character, forcedSublimations = listOf("directives")),
                        listOf(amulet, relicCarrier).groupBy {
                            it.itemType
                        },
                        emptyList(),
                        listOf(relic),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Directives")
            // Forcing the relic sub pulled its relic carrier item into the build.
            assertThat(best.individual.equipments.any { it.rarity == Rarity.RELIC }).isTrue()
        }

    @Test
    fun `a normal sublimation needs a carrier item with at least 3 sockets`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val normalDi =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DiNormal",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )

            // Carrier with 4 sockets: enough to lay down the sub's ordered 3-colour pattern -> chosen.
            val bigCarrier = equipment(1, ItemType.AMULET, "Fire4", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            val chosen =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(bigCarrier).groupBy { it.itemType }, emptyList(), listOf(normalDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(
                chosen.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("DiNormal")

            // Carrier with only 2 sockets: cannot host a 3-socket normal sub -> not chosen.
            val smallCarrier = equipment(1, ItemType.AMULET, "Fire2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 2)
            val declined =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(smallCarrier).groupBy { it.itemType }, emptyList(), listOf(normalDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(declined.individual.sublimations).isEmpty()
        }

    @Test
    fun `a normal sublimation does not steal rune sockets from its carrier (golden runes)`(): Unit =
        runBlocking {
            val level = 245
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            // A 4-socket carrier, a beneficial distance rune, and a FORCED normal sub needing a 3-colour pattern.
            // Golden runes form that pattern while still carrying their stat, so the sub must NOT cost rune sockets:
            // the carrier keeps all four runes. (The old "reserve 3 sockets" model would cap this at one rune.)
            val carrier = equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4, level = level)
            val distanceRune =
                RuneType(27098, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)
            val normalSub =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DistNormal",
                    effects = listOf(SublimationEffect(Characteristic.MASTERY_DISTANCE, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    forcedSublimations = listOf("DistNormal"),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, listOf(distanceRune), listOf(normalSub), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // The forced normal sub is hosted on the carrier...
            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("DistNormal")
            // ...and it does not consume rune sockets: all four sockets still hold the distance rune.
            val socketed =
                best.individual.runes.values
                    .flatten()
            assertThat(socketed).hasSize(4)
            assertThat(socketed).allMatch { it.characteristic == Characteristic.MASTERY_DISTANCE }
        }

    @Test
    fun `a chosen normal sublimation is keyed to its carrier item`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            val normalDi =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DiNormal",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(normalDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // The sub is recorded UNDER its carrier item (not floating) so the GUI can show it on that item.
            val (hostItem, hostedSubs) =
                best.individual.sublimations.entries
                    .single()
            assertThat(hostItem.name.en).isEqualTo("Carrier")
            assertThat(hostedSubs.map { it.name.en }).contains("DiNormal")
        }

    @Test
    fun `two normal sublimations cannot be hosted on a single item`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // A single 4-socket item physically hosts at most ONE 3-socket normal sublimation.
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            val diA =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DiA",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val diB =
                sublimation(
                    11,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DiB",
                    effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 12)),
                    slotColorPattern = listOf(1, 2, 3)
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(diA, diB), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
            ).hasSize(1)
        }

    @Test
    fun `an epic sublimation cannot be chosen without an epic item to host it`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // The only item is common — no epic carrier, so the epic sublimation slot does not exist.
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicDi =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicDI", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 50)))

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet).groupBy { it.itemType }, emptyList(), listOf(epicDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // Even a huge +50% DI epic cannot be hosted without an epic item -> never chosen (no impossible build).
            assertThat(best.individual.sublimations).isEmpty()
        }

    @Test
    fun `forcing an epic sublimation pulls its epic carrier item into the build`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // A blank epic item with no useful stat: the only reason to equip it is to host the forced epic sub.
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val epicSub =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "ForcedEpic", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)))

            val best =
                WakfuBuildSolver
                    .optimize(
                        maxDamageParams(character, forcedSublimations = listOf("forcedepic")),
                        listOf(amulet, epicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(epicSub),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("ForcedEpic")
            // The forced epic sub forced its epic carrier into the build (Σ epicSub ≤ Σ epicItems).
            assertThat(best.individual.equipments.any { it.rarity == Rarity.EPIC }).isTrue()
        }

    @Test
    fun `out-of-combat AP is capped at 16 even with abundant AP gear and a high AP target`(): Unit =
        runBlocking {
            val level = 245
            val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
            // Four different slots each granting +4 AP: uncapped the solver would stack all four
            // (6 base + 16 = 22 AP) to chase the AP target; the out-of-combat cap holds it to ≤16.
            val pool =
                listOf(
                    equipment(1, ItemType.AMULET, "Ap1", mapOf(Characteristic.MAX_ACTION_POINT to 4)),
                    equipment(2, ItemType.RING, "Ap2", mapOf(Characteristic.MAX_ACTION_POINT to 4)),
                    equipment(3, ItemType.CAPE, "Ap3", mapOf(Characteristic.MAX_ACTION_POINT to 4)),
                    equipment(4, ItemType.BELT, "Ap4", mapOf(Characteristic.MAX_ACTION_POINT to 4))
                ).groupBy { it.itemType }
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 20))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val ap =
                computeCharacteristicsValues(
                    best.individual,
                    character.baseCharacteristicValues,
                    emptyMap(),
                    emptyMap()
                )[Characteristic.ACTION_POINT] ?: 0
            assertThat(ap).isLessThanOrEqualTo(16)
            assertThat(ap).isGreaterThan(6) // it still stacked AP — it just respected the cap
        }

    @Test
    fun `precision mode chooses a sublimation that helps reach a requested stat`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // Request Lock; the only source is a normal sub hosted on a socketed carrier.
            val carrier = equipment(1, ItemType.AMULET, "Carrier", emptyMap(), maxShardSlots = 4)
            val lockSub =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "LockSub",
                    effects = listOf(SublimationEffect(Characteristic.LOCK, 250)),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.LOCK, 250))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(lockSub), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("LockSub")
        }

    @Test
    fun `most-masteries mode chooses a sublimation that adds a requested mastery`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val masterySub =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DistSub",
                    effects = listOf(SublimationEffect(Characteristic.MASTERY_DISTANCE, 100)),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(masterySub), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // A sub that raises the maximized mastery is taken; DI-only subs (not a maximized stat here) are not.
            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("DistSub")
        }

    private fun achievedStats(
        build: BuildCombination,
        character: Character,
    ): Map<Characteristic, Int> =
        computeCharacteristicsValues(
            build,
            character.baseCharacteristicValues,
            masteryElementsWanted = emptyMap(),
            resistanceElementsWanted = emptyMap(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        )

    @Test
    fun `most-masteries does not take a damage-reducing sublimation just to overshoot a met target`(): Unit =
        runBlocking {
            // Regression for the reported Enutrof case: most-masteries used to be blind to the % Damage Inflicted
            // multiplier, so it grabbed Swiftness II (+1 MP, −20% DI) purely to over-satisfy an already-met MP
            // target — inflating the mastery "match" while cutting ~20% of real damage. Now DI is folded into the
            // objective, so a −20% DI sub buys nothing the tiny overshoot bonus can justify.
            val character = Character(CharacterClass.ENUTROF, 1, 1, CharacterSkills(1))
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val swiftnessLike =
                sublimation(
                    20,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "Swiftness II",
                    effects =
                        listOf(
                            SublimationEffect(Characteristic.DAMAGE_INFLICTED, -20),
                            SublimationEffect(Characteristic.MOVEMENT_POINT, 1)
                        ),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    // MP base is 3, so the MP target is met WITHOUT the sub; the sub would only overshoot it
                    // (3→4) for a tiny tie-breaker bonus — far less than the 20% damage it costs.
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                                TargetStat(Characteristic.MOVEMENT_POINT, 3)
                            )
                        ),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(swiftnessLike), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).doesNotContain("Swiftness II")
            assertThat(achievedStats(best.individual, character)[Characteristic.DAMAGE_INFLICTED] ?: 0).isGreaterThanOrEqualTo(0)
        }

    @Test
    fun `most-masteries rejects a damage-reducing sublimation even when NO mastery is requested`(): Unit =
        runBlocking {
            // Backstop case: a most-masteries request with only a required target (MP) and no maximizable mastery.
            // The DI fold is then structurally 0 (nothing to multiply), so without the backstop the overshoot
            // tie-breaker would grab the +1 MP / −20% DI sub to over-satisfy MP — the original Enutrof bug in its
            // pure required-only form. The backstop drops the −DI sub when no mastery is being maximized.
            val character = Character(CharacterClass.ENUTROF, 1, 1, CharacterSkills(1))
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.HP to 50), maxShardSlots = 4)
            val swiftnessLike =
                sublimation(
                    20,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "Swiftness II",
                    effects =
                        listOf(
                            SublimationEffect(Characteristic.DAMAGE_INFLICTED, -20),
                            SublimationEffect(Characteristic.MOVEMENT_POINT, 1)
                        ),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MOVEMENT_POINT, 3))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(swiftnessLike), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).doesNotContain("Swiftness II")
            assertThat(achievedStats(best.individual, character)[Characteristic.DAMAGE_INFLICTED] ?: 0).isGreaterThanOrEqualTo(0)
        }

    @Test
    fun `most-masteries maximizes damage inflicted among equal-mastery builds`(): Unit =
        runBlocking {
            // DI is now a maximized factor: between two amulets with identical requested mastery, the one that
            // also carries +% Damage Inflicted wins, because mastery × (1 + DI/100) is higher.
            val character = Character(CharacterClass.CRA, 50, 50, CharacterSkills(50))
            val plain = equipment(1, ItemType.AMULET, "Plain", mapOf(Characteristic.MASTERY_DISTANCE to 200))
            val withDi = equipment(2, ItemType.AMULET, "WithDI", mapOf(Characteristic.MASTERY_DISTANCE to 200, Characteristic.DAMAGE_INFLICTED to 30))
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(plain, withDi).groupBy { it.itemType }, emptyList(), emptyList(), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // The +DI amulet wins (the AMULET slot holds at most one); the build can additionally route the
            // Major aptitude into DI now that it's maximized, so we assert the DI item was chosen, not an exact total.
            assertThat(best.individual.equipments.map { it.name.en }).contains("WithDI").doesNotContain("Plain")
        }

    @Test
    fun `most-masteries still takes a damage-reducing sublimation when its mastery gain outweighs the DI loss`(): Unit =
        runBlocking {
            // The fold is a nuanced tradeoff, not a blanket ban (the old safety filter wrongly rejected this):
            // a −20% DI sub that ALSO adds a large chunk of the maximized mastery is net-positive for real
            // damage, so it must be taken. 50 base distance → 550 with the sub; 550 × 0.8 = 440 > 50.
            val character = Character(CharacterClass.CRA, 50, 50, CharacterSkills(50))
            val carrier = equipment(1, ItemType.AMULET, "Carrier", mapOf(Characteristic.MASTERY_DISTANCE to 50), maxShardSlots = 4)
            val bigMasterySub =
                sublimation(
                    21,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "PowerfulButCostly",
                    effects =
                        listOf(
                            SublimationEffect(Characteristic.MASTERY_DISTANCE, 500),
                            SublimationEffect(Characteristic.DAMAGE_INFLICTED, -20)
                        ),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(bigMasterySub), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("PowerfulButCostly")
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

            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                FindMaxDamageScoring.computeScore(targetStats, build, character.baseCharacteristicValues, DamageScenario())
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

    // ----- Max-damage provable optimum (reachable-domain propagation) -----

    /** Production-equivalent embedded pool: rarity ≤ EPIC, level in `0..level` (+ pets/mounts), by itemType. */
    private fun fullEpicPool(level: Int): Map<ItemType, List<Equipment>> =
        WakfuBestBuildFinderAlgorithm.equipments
            .filter { it.rarity <= Rarity.EPIC }
            .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
            .groupBy { it.itemType }

    /**
     * A tiny, deliberately high-stat pool that drives the objective-chain vars (M, graw, perHit, throughput,
     * crit, DI) to end-game magnitudes across distinct slots — so the loose-domain solve is INSTANT (a handful
     * of items) yet stresses the reachable-domain bounds harder than the full pool (it reaches the extremes the
     * real pool may never combine). Exactly the regime an interval under-estimate would mis-bound.
     */
    private fun soundnessStressPool(): Map<ItemType, List<Equipment>> =
        listOf(
            equipment(
                1,
                ItemType.AMULET,
                "Amu",
                mapOf(
                    Characteristic.MASTERY_ELEMENTARY to 1000,
                    Characteristic.MASTERY_CRITICAL to 600,
                    Characteristic.CRITICAL_HIT to 50,
                    Characteristic.DAMAGE_INFLICTED to 200
                )
            ),
            equipment(2, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY to 1000, Characteristic.ACTION_POINT to 10)),
            equipment(3, ItemType.CHEST_PLATE, "Chest", mapOf(Characteristic.MASTERY_ELEMENTARY to 1000, Characteristic.DAMAGE_INFLICTED to 150)),
            equipment(4, ItemType.CAPE, "Cape", mapOf(Characteristic.MASTERY_ELEMENTARY to 800, Characteristic.MASTERY_CRITICAL to 400)),
            equipment(5, ItemType.BOOTS, "Boots", mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.MASTERY_MELEE to 1000)),
            equipment(6, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_BACK to 700, Characteristic.MASTERY_BERSERK to 700, Characteristic.CRITICAL_HIT to 50)),
            equipment(7, ItemType.RING, "Ring1", mapOf(Characteristic.MASTERY_ELEMENTARY to 500, Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 500)),
            equipment(8, ItemType.RING, "Ring2", mapOf(Characteristic.MASTERY_ELEMENTARY to 500))
        ).groupBy { it.itemType }

    /** Constraint-free CRA fire max-damage request at [level] (empty targets ⇒ the pure damage objective). */
    private fun fireMaxDamageParams(level: Int): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
            targetStats = TargetStats(emptyList()),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            useSublimations = false,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        )

    @Test
    @Tag("slow")
    fun `max-damage free solve proves OPTIMAL on the full level-110 pool`(): Unit =
        runBlocking {
            // The headline: with reachable-sized objective-chain domains the FREE solve (AP optimized jointly,
            // no AP-pin) proves OPTIMAL on the whole level-110 EPIC pool — what burned the budget at FEASIBLE
            // (~90× unclosed gap) before. See docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md (exp D).
            val results =
                WakfuBuildSolver
                    .optimize(fireMaxDamageParams(110), fullEpicPool(110), WakfuBuildSolver.SolverTuning(maxDeterministicTime = 300.0))
                    .toList()
            val proven = results.lastOrNull { it.isOptimal }
            assertThat(proven)
                .describedAs("reachable domains must let CP-SAT PROVE the free lvl-110 max-damage optimum, not stop at FEASIBLE")
                .isNotNull
            assertThat(proven!!.matchPercentage.signum()).describedAs("the proven build deals real damage").isGreaterThan(0)
        }

    @Test
    @Tag("slow")
    fun `max-damage free solve proves OPTIMAL on the full level-245 pool`(): Unit =
        runBlocking {
            // The end-game pool is larger (higher masteries ⇒ wider reachable domains), so it gets more
            // deterministic budget; the per-pool propagation must still bring every product envelope down
            // far enough to certify the bound.
            val results =
                WakfuBuildSolver
                    .optimize(fireMaxDamageParams(245), fullEpicPool(245), WakfuBuildSolver.SolverTuning(maxDeterministicTime = 600.0))
                    .toList()
            val proven = results.lastOrNull { it.isOptimal }
            assertThat(proven)
                .describedAs("reachable domains must let CP-SAT PROVE the free lvl-245 max-damage optimum")
                .isNotNull
            assertThat(proven!!.matchPercentage.signum()).isGreaterThan(0)
        }

    @Test
    @Tag("slow")
    fun `max-damage proves OPTIMAL with runes and sublimations on the full level-110 pool`(): Unit =
        runBlocking {
            // Runes were the provability wall: each socketed item added per-stat integer count vars that blew
            // up the branch space, so the full GUI config (runes + sublimations ON) never proved. The
            // single-type rune FOLD (createRuneModel) collapses that to one boolean pick per item, restoring a
            // PROVEN optimum here — the regression guard for the fold. Real embedded runes + sublimations.
            val params = fireMaxDamageParams(110).copy(useRunes = true, useSublimations = true)
            val results =
                WakfuBuildSolver
                    .optimize(
                        params,
                        fullEpicPool(110),
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        WakfuBuildSolver.SolverTuning(maxDeterministicTime = 600.0)
                    ).toList()
            val proven = results.lastOrNull { it.isOptimal }
            assertThat(proven)
                .describedAs("the single-type rune fold must let CP-SAT PROVE the runes+subs lvl-110 optimum (was never proving)")
                .isNotNull
            assertThat(proven!!.matchPercentage.signum()).describedAs("the proven build deals real damage").isGreaterThan(0)
        }

    /** Constraint-free max-damage request for [clazz] at [level] against [scenario] (empty targets ⇒ pure damage). */
    private fun maxDamageShape(
        clazz: CharacterClass,
        level: Int,
        scenario: DamageScenario,
        useRunes: Boolean = false,
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(clazz, level, 0, CharacterSkills(level)),
            targetStats = TargetStats(emptyList()),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = useRunes,
            useSublimations = false,
            damageScenario = scenario
        )

    /**
     * A socketed, high-stat pool + a mastery/crit-mastery rune set (some colour-doubled) for the rune-aware
     * soundness lock. The runes let a loose solve fill every socket and drive the scenario-mastery M to its
     * end-game max — exactly the path the socket-aware [WakfuBuildSolver] rune-overcount tightening bounds, so
     * an under-estimate there (a silently cut optimum) surfaces as an out-of-range tracked var.
     */
    private fun soundnessSocketedPool(): Map<ItemType, List<Equipment>> =
        listOf(
            equipment(
                1,
                ItemType.AMULET,
                "Amu",
                mapOf(Characteristic.MASTERY_ELEMENTARY to 1000, Characteristic.MASTERY_CRITICAL to 600, Characteristic.CRITICAL_HIT to 50),
                maxShardSlots = 4,
                level = 245
            ),
            equipment(2, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY to 1000, Characteristic.ACTION_POINT to 10), maxShardSlots = 4, level = 245),
            equipment(3, ItemType.CHEST_PLATE, "Chest", mapOf(Characteristic.MASTERY_DISTANCE to 900, Characteristic.MASTERY_MELEE to 900), maxShardSlots = 4, level = 245),
            equipment(4, ItemType.CAPE, "Cape", mapOf(Characteristic.MASTERY_BACK to 800, Characteristic.MASTERY_BERSERK to 800), maxShardSlots = 4, level = 245),
            equipment(5, ItemType.BOOTS, "Boots", mapOf(Characteristic.MASTERY_ELEMENTARY to 700), maxShardSlots = 3, level = 245),
            equipment(6, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_CRITICAL to 700, Characteristic.CRITICAL_HIT to 50), maxShardSlots = 4, level = 245),
            equipment(7, ItemType.RING, "Ring1", mapOf(Characteristic.MASTERY_ELEMENTARY to 500), maxShardSlots = 4, level = 245),
            equipment(8, ItemType.RING, "Ring2", mapOf(Characteristic.MASTERY_DISTANCE to 500), maxShardSlots = 4, level = 245)
        ).groupBy { it.itemType }

    /** Mastery + crit-mastery runes spanning colours (some doubled on the pool's slots) for the rune soundness lock. */
    private fun soundnessRunes(): List<RuneType> =
        listOf(
            RuneType(1, I18nText("elem", "elem", "elem", "elem"), RuneColor.RED, Characteristic.MASTERY_ELEMENTARY, listOf(10, 15), 0),
            RuneType(2, I18nText("dist", "dist", "dist", "dist"), RuneColor.GREEN, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0),
            RuneType(3, I18nText("melee", "melee", "melee", "melee"), RuneColor.BLUE, Characteristic.MASTERY_MELEE, listOf(10, 15), 0),
            RuneType(4, I18nText("back", "back", "back", "back"), RuneColor.RED, Characteristic.MASTERY_BACK, listOf(10, 15), 0),
            RuneType(5, I18nText("zerk", "zerk", "zerk", "zerk"), RuneColor.GREEN, Characteristic.MASTERY_BERSERK, listOf(10, 15), 0),
            RuneType(6, I18nText("crit", "crit", "crit", "crit"), RuneColor.BLUE, Characteristic.MASTERY_CRITICAL, listOf(10, 15), 0)
        )

    @Test
    fun `max-damage reachable bounds hold across classes, elements, scenarios and levels`() {
        // THE soundness lock: solve each shape with LOOSE guard domains (so the solver drives every objective-
        // chain var as high as the build allows) and check every var's solved value against the tight reachable
        // [lo,hi] the production build would DECLARE — a value outside = an interval under-estimate that would
        // silently cut the optimum. The tight reachable domains run for EVERY class / level / scenario in
        // production (and the boss path now solves SEVERAL elements), so the panel spans classes, elements, the
        // rear/berserk mastery terms, a multi-element boss, and lvl-245 — an under-estimate can't hide in a shape
        // one fixed case never exercises. Solved on the tiny high-stat [soundnessStressPool]: instant, yet it
        // drives the chain to end-game magnitudes — STRONGER than the real pool, which may never combine those
        // extremes (this replaces the old single full-pool CRA-fire-110 lock, which took ~100s for less coverage).
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessStressPool()
        val shapes: List<Pair<String, WakfuBestBuildParams>> =
            listOf(
                "cra-fire-110" to
                    maxDamageShape(CharacterClass.CRA, 110, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)),
                "cra-earth-110" to
                    maxDamageShape(CharacterClass.CRA, 110, DamageScenario(element = SpellElement.EARTH, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)),
                "cra-air-110" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        110,
                        DamageScenario(element = SpellElement.AIR, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                    ),
                "iop-fire-110" to
                    maxDamageShape(
                        CharacterClass.IOP,
                        110,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.MELEE, orientation = Orientation.FACE)
                    ),
                "sacrieur-earth-110" to
                    maxDamageShape(CharacterClass.SACRIEUR, 110, DamageScenario(element = SpellElement.EARTH, rangeBand = RangeBand.MELEE, orientation = Orientation.FACE)),
                "xelor-water-110" to
                    maxDamageShape(CharacterClass.XELOR, 110, DamageScenario(element = SpellElement.WATER, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)),
                // Rear + berserk fold the MASTERY_BACK / MASTERY_BERSERK terms into the per-hit core.
                "cra-rear-berserk-110" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        110,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK, berserk = true)
                    ),
                // Boss (multi-candidate) exercises the in-model max over several per-element damage terms.
                "cra-boss-110" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        110,
                        DamageScenario(
                            rangeBand = RangeBand.DISTANCE,
                            orientation = Orientation.FACE,
                            elementResistances =
                                mapOf(
                                    SpellElement.FIRE to 20,
                                    SpellElement.EARTH to 50,
                                    SpellElement.AIR to 30,
                                    SpellElement.WATER to 10
                                )
                        )
                    ),
                // End-game magnitudes (higher masteries ⇒ wider reachable ranges).
                "cra-fire-245" to
                    maxDamageShape(CharacterClass.CRA, 245, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE))
            )

        shapes.forEach { (name, params) ->
            val bounds = WakfuBuildSolver.maxDamageVarBoundsForTest(params, pool, tuning)
            assertThat(bounds).describedAs("$name: the objective chain must be tracked").isNotEmpty
            assertThat(bounds.filterNot { it.withinBound })
                .describedAs("$name: each is a var whose reachable bound under-estimated its real value — a silently cut optimum")
                .isEmpty()
        }

        // RUNE-AWARE shapes: runes are what the socket-aware scenario-mastery bound (runeMasteryOverCount)
        // tightens, so a loose solve must fill every socket and still land within the tightened reachable M.
        // Spans face/rear+berserk (different mastery-term sets) and lvl-245 magnitudes. Without these the
        // rune-overcount tightening would be unguarded — an under-estimate there silently cuts the optimum.
        val socketed = soundnessSocketedPool()
        val runes = soundnessRunes()
        val runeShapes: List<Pair<String, WakfuBestBuildParams>> =
            listOf(
                "cra-fire-110-runes" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        110,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE),
                        useRunes = true
                    ),
                "iop-fire-110-runes-melee" to
                    maxDamageShape(
                        CharacterClass.IOP,
                        110,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.MELEE, orientation = Orientation.FACE),
                        useRunes = true
                    ),
                "cra-rear-berserk-110-runes" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        110,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK, berserk = true),
                        useRunes = true
                    ),
                "cra-fire-245-runes" to
                    maxDamageShape(
                        CharacterClass.CRA,
                        245,
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE),
                        useRunes = true
                    )
            )
        runeShapes.forEach { (name, params) ->
            val bounds = WakfuBuildSolver.maxDamageVarBoundsForTest(params, socketed, tuning, runes)
            assertThat(bounds).describedAs("$name: the objective chain must be tracked").isNotEmpty
            assertThat(bounds.filterNot { it.withinBound })
                .describedAs("$name: a rune-fed var exceeded its socket-aware reachable bound — runeMasteryOverCount cut the optimum")
                .isEmpty()
        }
    }

    @Test
    fun `single-type rune fold preserves the max-damage optimum`() {
        // The fold fills each item with ONE rune type (a boolean pick) instead of a free per-stat count.
        // Because a rune's value is uniform across an item's sockets (RuneType.valueOn doubles per item-SLOT,
        // not per socket), single-type fill is provably ≥ any intra-item mix — so the folded optimum must
        // EQUAL the per-stat COUNT model's optimum (forceRuneCountModel) on the same rune pool. If the fold
        // ever cut the optimum (e.g. if the model gained per-socket colours), these would diverge. Spans a
        // face build and a rear+berserk build (more secondary mastery terms competing for the sockets).
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessSocketedPool()
        val runes = soundnessRunes()
        listOf(
            "face" to
                maxDamageShape(
                    CharacterClass.CRA,
                    110,
                    DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE),
                    useRunes = true
                ),
            "rear-berserk" to
                maxDamageShape(
                    CharacterClass.CRA,
                    110,
                    DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK, berserk = true),
                    useRunes = true
                )
        ).forEach { (name, params) ->
            val fold = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes)
            val count = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes, forceRuneCountModel = true)
            assertThat(fold.isOptimal).describedAs("$name: the folded model proves OPTIMAL").isTrue()
            assertThat(count.isOptimal).describedAs("$name: the count model proves OPTIMAL").isTrue()
            assertThat(fold.objective)
                .describedAs("$name: the single-type rune fold must not change the optimum (fold == count)")
                .isEqualTo(count.objective)
        }
    }

    @Test
    fun `tight reachable domains preserve the exact max-damage optimum`() {
        // On a small pool BOTH the tight and the loose model prove OPTIMAL, so their optima are directly
        // comparable: tightening the declared domains must change ONLY provability/speed, never the answer.
        val level = 1
        val character = Character(CharacterClass.CRA, level, level, CharacterSkills(level))
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "FireBig", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 120)),
                equipment(2, ItemType.AMULET, "Crit", mapOf(Characteristic.CRITICAL_HIT to 40, Characteristic.MASTERY_CRITICAL to 30)),
                equipment(3, ItemType.BELT, "Generic", mapOf(Characteristic.MASTERY_ELEMENTARY to 60)),
                equipment(4, ItemType.BELT, "Distance", mapOf(Characteristic.MASTERY_DISTANCE to 80))
            )
        val params =
            WakfuBestBuildParams(
                character = character,
                targetStats = TargetStats(emptyList()),
                searchDuration = 5.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = false,
                useSublimations = false,
                damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
            )
        val pool = equipments.groupBy { it.itemType }
        val tuning = WakfuBuildSolver.SolverTuning()
        val tight = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true)
        val loose = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = false)

        assertThat(tight.isOptimal).describedAs("the tight (production) model must prove OPTIMAL").isTrue()
        assertThat(loose.isOptimal).describedAs("the loose reference also proves on this small pool").isTrue()
        assertThat(tight.objective)
            .describedAs("tightening declared domains must not change the optimum — only how fast it is proven")
            .isEqualTo(loose.objective)
    }

    // ----- Multi-element random-element assignment: freed CP-SAT must match the exact-scorer optimum -----

    /**
     * Deterministically asserts the most-masteries solver's optimum re-scores (exact scorer) to the exhaustive
     * optimum. Level 1 keeps skill points out of it (the solver optimizes skills, the exhaustive doesn't), so any
     * remaining gap is purely the random-element ASSIGNMENT — exactly what we want to lock.
     */
    private fun assertMostMasteriesMatchesExhaustive(
        equipments: List<Equipment>,
        targetStats: TargetStats,
        level: Int = 1,
    ): Unit =
        runBlocking {
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val score = { build: BuildCombination -> FindMostMasteriesFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues) }
            val exhaustive = allValidCombinations(equipments, characterSkills).maxOf { score(it) }
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
            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .last { it.isOptimal }
            // The freed CP-SAT random assignment must reach the SAME optimum the exact-scorer exhaustive does
            // (consistency): a divergence means the model and the scorer disagree on the random roll placement.
            assertThat(best.matchPercentage).isEqualByComparingTo(exhaustive)
        }

    @Test
    fun `most-masteries aggregate matches the exhaustive optimum with random-element mastery items`() {
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "Rand2", mapOf(Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 80)),
                equipment(2, ItemType.AMULET, "FireBig", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 130)),
                equipment(3, ItemType.BELT, "Rand1", mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 60)),
                equipment(4, ItemType.BELT, "Generic", mapOf(Characteristic.MASTERY_ELEMENTARY to 40)),
                equipment(5, ItemType.BOOTS, "Rand3", mapOf(Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 50)),
                equipment(6, ItemType.BOOTS, "WaterBig", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 95))
            )
        assertMostMasteriesMatchesExhaustive(equipments, TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 1))))
    }

    @Test
    fun `most-masteries two unequal-target elements matches the exhaustive optimum (greedy was worst here)`() {
        // Unequal targets are exactly where the old deficit-greedy starved the lowest element; the freed model
        // + exact max-min scorer must agree on the true optimum.
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "Rand1", mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 70)),
                equipment(2, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60)),
                equipment(3, ItemType.BELT, "Rand2", mapOf(Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 50)),
                equipment(4, ItemType.BELT, "Water", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 40))
            )
        assertMostMasteriesMatchesExhaustive(
            equipments,
            TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 200), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 90)))
        )
    }

    @Test
    fun `most-masteries aggregate resistance matches the exhaustive optimum with random-resistance items`() {
        val equipments =
            listOf(
                equipment(1, ItemType.AMULET, "ResRand2", mapOf(Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to 30)),
                equipment(2, ItemType.AMULET, "ResFire", mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 40)),
                equipment(3, ItemType.BELT, "ResRand1", mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 25)),
                equipment(4, ItemType.BOOTS, "Dist", mapOf(Characteristic.MASTERY_DISTANCE to 50))
            )
        assertMostMasteriesMatchesExhaustive(
            equipments,
            TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 60), TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
        )
    }

    @Test
    fun `precision aggregate matches the exhaustive optimum with random-element items`(): Unit =
        runBlocking {
            // Precision frees its assignment too (capped objective); the exact max-capped scorer must agree with
            // the freed model on the optimum. Level 1 so skills don't confound the comparison.
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "Rand2", mapOf(Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 80)),
                    equipment(2, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60)),
                    equipment(3, ItemType.BELT, "Rand1", mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 50)),
                    equipment(4, ItemType.BELT, "Water", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 40)),
                    equipment(5, ItemType.BOOTS, "Rand3", mapOf(Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 40))
                )
            val targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 100)))
            val score = { build: BuildCombination -> FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues) }
            val exhaustive = allValidCombinations(equipments, characterSkills).maxOf { score(it) }
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
                    .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .last { it.isOptimal }
            assertThat(best.matchPercentage).isEqualByComparingTo(exhaustive)
        }

    // ----- Prefilter scope: single-element requests now solve the full pool (proven global optimum) -----

    private fun masteryParams(
        level: Int,
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    @Test
    @Tag("slow")
    fun `a single-element mastery request is no longer prefiltered (full pool, proven optimum)`() {
        // The prefilter is a top-N-per-stat HEURISTIC that can miss the optimum; for a SINGLE specific element
        // the full-pool model stays small and proves the true optimum fast (measured ~1-4s on lvl 245), so the
        // heuristic is removed there. The gate must return false ⇒ the FULL distinct-item pool is solved.
        val pool = fullEpicPool(110)
        val fullDistinct =
            WakfuBuildSolver.gatedPoolSizeForTest(
                masteryParams(110, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 800), TargetStat(Characteristic.ACTION_POINT, 11))),
                pool
            )
        assertThat(fullDistinct.first).describedAs("a single specific element must NOT trigger the prefilter").isFalse()

        // And it stays tractable: the full-pool single-element solve proves OPTIMAL.
        val r =
            WakfuBuildSolver.solveForBenchmark(
                masteryParams(110, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 800), TargetStat(Characteristic.ACTION_POINT, 11))),
                pool,
                WakfuBuildSolver.SolverTuning(maxDeterministicTime = 120.0),
                forceFullPool = false
            )
        assertThat(r.status).describedAs("single-element full pool must prove OPTIMAL, not stop at FEASIBLE").isEqualTo("OPTIMAL")
        assertThat(r.score.signum()).isGreaterThan(0)
        assertThat(r.poolSize).describedAs("solved on the full pool, not a prefiltered subset").isEqualTo(fullDistinct.second)
    }

    @Test
    @Tag("slow")
    fun `a multi-element aggregate request still prefilters the pool`() {
        // The O(elements^2) random-element modelling explodes the full pool for multi-element requests (even
        // prefiltered, the aggregate solve is hard), so the prefilter stays load-bearing there. No solve here.
        val pool = fullEpicPool(110)
        val full = WakfuBuildSolver.gatedPoolSizeForTest(masteryParams(110, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 800))), pool)
        val aggregate = WakfuBuildSolver.gatedPoolSizeForTest(masteryParams(110, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 800))), pool)
        val twoElement =
            WakfuBuildSolver.gatedPoolSizeForTest(
                masteryParams(110, listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 800), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 800))),
                pool
            )
        assertThat(aggregate.first).describedAs("aggregate (all elements) must still prefilter").isTrue()
        assertThat(twoElement.first).describedAs("two specific elements must still prefilter").isTrue()
        assertThat(aggregate.second).describedAs("prefiltered pool is strictly smaller than the full single-element pool").isLessThan(full.second)
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
        maxShardSlots: Int = 0,
        level: Int = 1,
        rarity: Rarity = Rarity.COMMON,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = level,
            name = I18nText(name, name, name, name),
            rarity = rarity,
            itemType = type,
            characteristics = stats,
            maxShardSlots = maxShardSlots
        )
}
