package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
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
                    .optimize(maxDamageParams(character), listOf(amulet).groupBy { it.itemType }, emptyList(), listOf(inflexible), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.sublimations.map { it.name.en }).contains("Inflexibility")
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
                    .optimize(maxDamageParams(character), listOf(amulet).groupBy { it.itemType }, emptyList(), listOf(unreachable), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.sublimations).isEmpty()
        }

    @Test
    fun `at most one epic sublimation is chosen`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicA =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicA", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 10)))
            val epicB =
                sublimation(2, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicB", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 12)))

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet).groupBy { it.itemType }, emptyList(), listOf(epicA, epicB), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.sublimations.count { it.rarity == SublimationRarity.EPIC }).isEqualTo(1)
            // It picks the stronger one.
            assertThat(
                best.individual.sublimations
                    .single()
                    .name.en
            ).isEqualTo("EpicB")
        }

    @Test
    fun `a forced relic sublimation is always in the build and applies its flat effect`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val relic =
                sublimation(7, SublimationRarity.RELIC, SublimationKind.FLAT, "Directives", effects = listOf(SublimationEffect(Characteristic.DAMAGE_INFLICTED, 15)))

            val best =
                WakfuBuildSolver
                    .optimize(
                        maxDamageParams(character, forcedSublimations = listOf("directives")),
                        listOf(amulet).groupBy {
                            it.itemType
                        },
                        emptyList(),
                        listOf(relic),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(best.individual.sublimations.map { it.name.en }).contains("Directives")
        }

    @Test
    fun `a normal sublimation is chosen only when enough sockets are free (socket budget)`(): Unit =
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

            // Carrier with 4 sockets: 3 reserved for the normal sub is fine -> chosen.
            val bigCarrier = equipment(1, ItemType.AMULET, "Fire4", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            val chosen =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(bigCarrier).groupBy { it.itemType }, emptyList(), listOf(normalDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(chosen.individual.sublimations.map { it.name.en }).contains("DiNormal")

            // Carrier with only 2 sockets: cannot host a 3-socket normal sub -> not chosen.
            val smallCarrier = equipment(1, ItemType.AMULET, "Fire2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 2)
            val declined =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(smallCarrier).groupBy { it.itemType }, emptyList(), listOf(normalDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(declined.individual.sublimations).isEmpty()
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
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = level,
            name = I18nText(name, name, name, name),
            rarity = Rarity.COMMON,
            itemType = type,
            characteristics = stats,
            maxShardSlots = maxShardSlots
        )
}
