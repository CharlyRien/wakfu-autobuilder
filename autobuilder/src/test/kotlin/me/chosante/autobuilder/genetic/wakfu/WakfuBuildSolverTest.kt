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
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.WakfuData
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class WakfuBuildSolverTest {
    private companion object {
        // P6.2 oracle: the game-data version the lvl-245 ledger below was banked against. The certifier is a
        // pure DP over the embedded item/rune/sub data — NOT an OR-Tools solve — so its per-cell output is
        // deterministic and version-pinned (by BOTH WakfuData.VERSION and WakfuBuildSolver.CERTIFIER_VERSION).
        // A data bump regenerates the pool, or a certifier change reshapes the bound ⇒ the ledger shifts ⇒ the
        // nightly test fails loudly (naming this version vs the current one); re-bank intentionally.
        const val LVL245_LEDGER_ORACLE_VERSION = "1.92.1.58" // re-banked under CERTIFIER_VERSION 12 (sub stacking)

        // A sound LOWER BOUND on the lvl-245 max-damage optimum: the FAST ledger's max must be ≥ this. It is the
        // PRE-STACKING proven optimum (16,909,590, certified before cumulable sub stacking landed). Stacking only
        // ADDS achievable value, so the true stacking optimum is HIGHER (bounded above by the fast max, 17,726,310)
        // and this stays a valid — if now loose — floor. Re-proving the exact stacking optimum at 245 is the
        // heavy nightly proof's job; here it only guards against a gross fast-tier under-count.
        const val LVL245_PROVEN_OPTIMUM = 16_909_590L

        // The lvl-245 tier-1 FAST certificate ledger for the production shape (runes + subs, full EPIC pool):
        // AP cell → the sound per-cell upper bound the two-tier orchestrator uses to ELIMINATE cells. This is
        // the FAST tier only (no exact confirmation) — deliberately: the full `forceTier2All` exact ledger is
        // ~1 h serial (≈ many hours on a CI runner), which a nightly test can't afford, whereas the fast pass
        // is ~80 s and still covers every cell. The exact tier is guarded instead by the (fast, CI) unit locks
        // (`… certifier matches pinned CP-SAT …`) plus the P6.1 fuzz lock's forceTier2All ledger on small pools.
        // These values are looser than (≥) the exact optimum — cell 16 fast = 17_718_840 vs pre-stacking exact
        // 16_909_590. History: every cell rose vs. the pre-stacking bank (cumulable subs now stack, adding value
        // at every AP); then every cell TIGHTENED slightly (−0.01–0.04 %) under CERTIFIER_VERSION 15 — the family
        // budgets price mono-axis subs at the harvest's EXACT per-c crit fold instead of the DP's segment-top
        // fold, removing pure fast-tier slack (still ≥ the exact optimum, as the assertions below lock).
        // Re-bank from `WAKFU_MAX_DAMAGE_CERT_LEDGER=1 …_LEVEL=245 …_INCUMBENT=99999999999999` on the manual
        // `certifyLedger end-to-end` test (a huge incumbent eliminates every cell ⇒ pure fast tier, ~80 s).
        val LVL245_FAST_LEDGER_ORACLE =
            mapOf(
                0 to 0L,
                1 to 0L,
                2 to 2_378_400L,
                3 to 3_964_100L,
                4 to 4_950_715L,
                5 to 6_537_250L,
                6 to 8_109_500L,
                7 to 9_044_700L,
                8 to 10_572_800L,
                9 to 12_052_530L,
                10 to 12_802_045L,
                11 to 14_036_100L,
                12 to 14_597_440L,
                13 to 15_655_875L,
                14 to 16_068_030L,
                15 to 17_090_500L,
                16 to 17_718_840L,
                17 to 0L,
                18 to 0L,
                19 to 0L,
                20 to 0L
            )
    }

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
    ): SolverResult<BuildCombination> =
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
            // matchPercentage (sequencedScore) FLOORs to 4 decimals, so floor the raw-double exhaustive the same
            // way before comparing — otherwise a tie is lost to sub-0.0001 representation noise (the solver finds
            // the identical build; here it landed on 22.7249 vs a raw 22.724999…).
            assertThat(solverBest.matchPercentage).isGreaterThanOrEqualTo(exhaustive.setScale(4, java.math.RoundingMode.FLOOR))
        }

    @Test
    fun `max-damage experiment variants reach the exhaustive optimum on a small pool`(): Unit =
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
            val exhaustive =
                allValidCombinations(equipments, characterSkills)
                    .maxOf {
                        me.chosante.autobuilder.domain.SpellRotationOptimizer
                            .bestAcrossElements(it, character, character.clazz, scenario)
                            .totalExpectedDamage
                            .toBigDecimal()
                    }.setScale(4, java.math.RoundingMode.FLOOR)
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
            val experiments =
                mapOf(
                    "ap-ceiling" to MaxDamageExperimentConfig(apCeiling = true),
                    "crit-generic" to MaxDamageExperimentConfig(critProduct = CritProductMode.GENERIC),
                    "d-binary-only" to
                        MaxDamageExperimentConfig(
                            dProduct = DProductMode.BINARY,
                            critProduct = CritProductMode.TABLE
                        ),
                    "crit-binary-only" to
                        MaxDamageExperimentConfig(
                            dProduct = DProductMode.TABLE,
                            critProduct = CritProductMode.BINARY
                        ),
                    "source-di" to MaxDamageExperimentConfig(dProduct = DProductMode.SOURCE_DI),
                    "same-name-ring-bound" to MaxDamageExperimentConfig(sameNameRingBound = true),
                    "per-ap-rotraw-cut" to MaxDamageExperimentConfig(perApRotRawCut = true),
                    "crit-binary" to MaxDamageExperimentConfig(critProduct = CritProductMode.BINARY),
                    "both-binary" to
                        MaxDamageExperimentConfig(
                            dProduct = DProductMode.BINARY,
                            critProduct = CritProductMode.BINARY
                        ),
                    // Soundness lock for the always-on AM-GM product bound: if its upper bound under-shot the true
                    // max D·Graw it would cap the objective below the exhaustive optimum and fail this assertion.
                    "joint-bound" to MaxDamageExperimentConfig(dGrawJointBound = true),
                    "joint+per-ap" to
                        MaxDamageExperimentConfig(
                            dGrawJointBound = true,
                            perApRotRawCut = true
                        ),
                    // Soundness lock for the certifier-cap feedback: an under-estimating cell cap would cut the
                    // exhaustive optimum out of the feasible set and fail this assertion.
                    "certifier-cell-cap" to MaxDamageExperimentConfig(certifierCellCap = true),
                    // E6 soundness lock: the crit-band disjunction is a REDUNDANT piecewise-McCormick cut on crit·diff.
                    // If a band's `term ≤ hi·diff` under-cut the true product it would cap the objective below the
                    // exhaustive optimum and fail here (the C7-style guard). Pair it with the joint bound too.
                    "crit-band" to MaxDamageExperimentConfig(critBandDisjunction = true),
                    "crit-band+joint" to MaxDamageExperimentConfig(critBandDisjunction = true, dGrawJointBound = true),
                    // C7 soundness lock: the crit·diff AM-GM cut is a CONSTANT cap on `term = crit·diff`. If its
                    // C(μ)²/(4μ) under-counted the true max (the reverted 2026-07-07 attempt priced the m/K lower
                    // clamps at zero) it would cap the objective below the exhaustive optimum and fail here. The
                    // dedicated firing fixture guarantees the cut also fires on an engineered competing pool.
                    "crit-diff-joint" to MaxDamageExperimentConfig(critDiffJointBound = true),
                    "crit-diff+joint" to MaxDamageExperimentConfig(critDiffJointBound = true, dGrawJointBound = true)
                )

            for ((name, experiment) in experiments) {
                val solverBest =
                    WakfuBuildSolver
                        .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning(maxDamageExperiment = experiment))
                        .toList()
                        .maxByOrNull { it.matchPercentage }!!

                assertThat(solverBest.matchPercentage)
                    .describedAs(name)
                    .isGreaterThanOrEqualTo(exhaustive)
            }
        }

    /**
     * C7 firing + soundness lock for the crit·diff AM-GM joint cut, on an ENGINEERED competing pool: the
     * amulet slot must CHOOSE between the only meaningful crit source and the biggest mastery source (so
     * crit and diff genuinely compete — the independent-max corner the cut attacks), a negative-crit ring
     * exercises the crit lower-clamp slack (`crit = clamp(rawCrit, 0, cap)` with reachable `rawCrit < 0`),
     * and `critCapPercent < 100` keeps the cap in the regime the derivation targets. Asserts BOTH halves:
     *  1. the cut actually FIRES (a constraint was added — bound strictly below `term`'s declared reach);
     *     without this, the exhaustive comparison would pass vacuously with the cut self-disabled;
     *  2. CP-SAT still reaches the exhaustive optimum — an under-counting C(μ) (the reverted 2026-07-07
     *     attempt priced the m/K lower clamps at zero) caps the objective below it and fails here.
     */
    @Test
    fun `crit-diff AM-GM joint cut fires and preserves the exhaustive optimum on a competing pool`(): Unit =
        runBlocking {
            val level = 1
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "CritAmulet", mapOf(Characteristic.CRITICAL_HIT to 50)),
                    equipment(2, ItemType.AMULET, "MasteryAmulet", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400)),
                    equipment(3, ItemType.RING, "FireRing", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100)),
                    equipment(
                        4,
                        ItemType.RING,
                        "NegCritRing",
                        mapOf(Characteristic.CRITICAL_HIT to -5, Characteristic.MASTERY_ELEMENTARY_FIRE to 160)
                    ),
                    equipment(5, ItemType.BELT, "FireBelt", mapOf(Characteristic.MASTERY_ELEMENTARY to 100))
                )
            val scenario =
                DamageScenario(
                    element = SpellElement.FIRE,
                    rangeBand = RangeBand.DISTANCE,
                    orientation = Orientation.BACK,
                    critCapPercent = 40
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
            val pool = equipments.groupBy { it.itemType }
            val experiment = MaxDamageExperimentConfig(critDiffJointBound = true)

            val fired = WakfuBuildSolver.maxDamageCritDiffCutBoundForTest(params, pool, experiment = experiment)
            assertThat(fired)
                .describedAs("the crit·diff joint cut must FIRE on the engineered competing pool (else this lock is vacuous)")
                .isNotNull()

            val exhaustive =
                allValidCombinations(equipments, characterSkills)
                    .maxOf {
                        me.chosante.autobuilder.domain.SpellRotationOptimizer
                            .bestAcrossElements(it, character, character.clazz, scenario)
                            .totalExpectedDamage
                            .toBigDecimal()
                    }.setScale(4, java.math.RoundingMode.FLOOR)
            val solverBest =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning(maxDamageExperiment = experiment))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(solverBest.matchPercentage)
                .describedAs("with the crit·diff cut firing, CP-SAT must still reach the exhaustive optimum")
                .isGreaterThanOrEqualTo(exhaustive)
        }

    /**
     * Ships-the-cut lock: the production config [MaxDamageExperimentConfig.DEFAULT] must keep the AM-GM joint
     * bound ON — it closes CP-SAT's dual gap on the hard sub-heavy free solve (measured). The bare constructor
     * stays the tuning tests' neutral baseline (cut OFF), so existing `SolverTuning()` solves are unchanged.
     * A silent flip of either would regress the free-solve proof or perturb every deterministic test.
     */
    @Test
    fun `production max-damage config ships the AM-GM joint cut and same-name-ring fix`() {
        assertThat(MaxDamageExperimentConfig.DEFAULT.dGrawJointBound)
            .describedAs("production DEFAULT must keep the joint cut ON")
            .isTrue()
        assertThat(MaxDamageExperimentConfig.DEFAULT.sameNameRingBound)
            .describedAs("production DEFAULT must keep the same-name-ring domain fix ON")
            .isTrue()
        assertThat(MaxDamageExperimentConfig.DEFAULT.perApRotRawCut)
            .describedAs("production DEFAULT must keep the per-AP rotation bound ON (plan §1.4 re-screen win)")
            .isTrue()
        assertThat(MaxDamageExperimentConfig.DEFAULT.conversionConservationCut)
            .describedAs("production DEFAULT must keep the E2 conversion-conservation cut ON (unsticks the lvl-245 dual bound)")
            .isTrue()
        assertThat(MaxDamageExperimentConfig.DEFAULT.critDiffJointBound)
            .describedAs("C7 crit·diff joint cut stays OFF in production until the deterministic F2 A/B verdict (perf-review-backlog §C7)")
            .isFalse()
        assertThat(MaxDamageExperimentConfig())
            .describedAs("the bare constructor stays the neutral cut-OFF test baseline")
            .isEqualTo(MaxDamageExperimentConfig(dGrawJointBound = false, sameNameRingBound = false, perApRotRawCut = false))
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
     * Soundness guard for the per-slot domination pre-filter under the survivability floor. With the floor ON
     * the objective ALSO depends on effective-HP (HP + the four elemental resistances), so those MUST be in the
     * domination `compared` set — otherwise a higher-damage / lower-EHP item would dominate and evict the item a
     * floor-clearing build needs, pruning the true optimum out of the pool while still reporting OPTIMAL.
     */
    @Test
    fun `domination compares HP and elemental resistances when the survivability floor is active`() {
        val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
        val floorScenario =
            DamageScenario(
                element = SpellElement.FIRE,
                rangeBand = RangeBand.DISTANCE,
                orientation = Orientation.FACE,
                survivabilityFloor = true,
                minEffectiveHp = 1500
            )

        val withFloor = dominationShape(apPinnedMaxDamageParams(character, floorScenario, 12), emptyList())
        assertThat(withFloor?.compared)
            .describedAs("the floor makes the objective depend on HP + resistances ⇒ domination must compare them")
            .isNotNull()
            .contains(
                Characteristic.HP,
                Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                Characteristic.RESISTANCE_ELEMENTARY_WATER,
                Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                Characteristic.RESISTANCE_ELEMENTARY_WIND,
                Characteristic.RESISTANCE_ELEMENTARY
            )

        // Floor OFF: HP/resist are not damage stats ⇒ (correctly) NOT compared. This is the exact state that made
        // the prune unsound while the floor was on — the regression this test locks.
        val noFloor =
            dominationShape(
                apPinnedMaxDamageParams(character, floorScenario.copy(survivabilityFloor = false), 12),
                emptyList()
            )
        assertThat(noFloor?.compared)
            .describedAs("without the floor HP/resist are not damage stats ⇒ not compared")
            .isNotNull()
            .doesNotContain(Characteristic.HP, Characteristic.RESISTANCE_ELEMENTARY_FIRE)
    }

    /**
     * A1 lock (part 1): a required RESISTANCE target is enforced on [StatBuilder.requiredActualStat], which folds
     * the GENERIC `RESISTANCE_ELEMENTARY` line into the constrained actual. So an item carrying its resistance via
     * that generic line is the one that lets the build meet the target — but the generic line is NOT one of the
     * target characteristics, so unless it is added to domination's `compared` set the item reads 0-vs-0 against a
     * higher-mastery item and is wrongly dominated away, pruning the true constrained optimum (a false "proven
     * optimal" badge or a false INFEASIBLE hard leg). The fix adds the resistance feeders to `compared` WHEN a
     * resistance target exists — and only then. This test locks both directions.
     */
    @Test
    fun `domination keeps a generic-resistance item when a resistance target is present, drops it otherwise`() {
        // Same slot (AMULET, capacity 1): A is pure mastery; B carries its resistance on the GENERIC all-elements
        // line (the mechanism — a SPECIFIC resistance line would already be a target char and thus compared).
        val a = equipment(1, ItemType.AMULET, "MasteryOnly", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
        val b =
            equipment(
                2,
                ItemType.AMULET,
                "GenericResist",
                mapOf(Characteristic.RESISTANCE_ELEMENTARY to 50, Characteristic.MASTERY_ELEMENTARY_FIRE to 200)
            )
        val pool = mapOf(ItemType.AMULET to listOf(a, b))

        // WITH a fire-resistance target: B must SURVIVE (A does not dominate it — the generic-resistance feeder is
        // now compared, so A's 0 does not beat B's 50).
        val withTarget =
            fireMaxDamageParams(50)
                .copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 40))))
        val shapeWith = dominationShape(withTarget, emptyList())!!
        val keptWith =
            filterDominatedPool(pool, shapeWith.pinned, shapeWith.compared, shapeWith.minimized)[ItemType.AMULET]!!
                .map { it.name.fr }
                .toSet()
        assertThat(keptWith)
            .describedAs("with a resistance target the generic-resistance item is optimum-relevant ⇒ not dominated")
            .containsExactlyInAnyOrder("MasteryOnly", "GenericResist")

        // WITHOUT a resistance target: the generic resistance is irrelevant to the objective ⇒ A (more mastery,
        // everything else equal) dominates B ⇒ B is correctly dropped. Proves the feeder add is CONDITIONAL and
        // the default-request domination win is untouched.
        val shapeNo = dominationShape(fireMaxDamageParams(50), emptyList())!!
        val keptNo =
            filterDominatedPool(pool, shapeNo.pinned, shapeNo.compared, shapeNo.minimized)[ItemType.AMULET]!!
                .map { it.name.fr }
                .toSet()
        assertThat(keptNo)
            .describedAs("without a resistance target the generic-resistance item is dominated by the higher-mastery item")
            .containsExactly("MasteryOnly")
    }

    /**
     * The joint / per-AP McCormick bounds derive a HARD CP-SAT ceiling from a product of two Longs. Computing it
     * as a Double loses precision once the product exceeds 2^53, and a ceiling rounded the wrong way cuts the
     * optimum — so [WakfuBuildSolver.clampedProductQuotient] must return the exact BigInteger floor.
     */
    @Test
    fun `clampedProductQuotient is an exact floor where a double product would lose precision`() {
        // a·a is a ~60-bit ODD value ⇒ not exactly representable as a Double (53-bit mantissa), so the previous
        // `(a.toDouble() * a / …).toLong()` bound rounded. The exact floor must be returned instead.
        val a = 1_234_567_891L
        val exact = java.math.BigInteger.valueOf(a) * java.math.BigInteger.valueOf(a)
        assertThat(WakfuBuildSolver.clampedProductQuotient(a, a, 1L, Long.MAX_VALUE)).isEqualTo(exact.toLong())
        assertThat(WakfuBuildSolver.clampedProductQuotient(a, a, 1L, Long.MAX_VALUE))
            .describedAs("must NOT be the lossy double computation the fix replaced")
            .isNotEqualTo((a.toDouble() * a).toLong())

        // Plain floor semantics and the [0, cap] clamp (the clamp also keeps the toLong() in range).
        assertThat(WakfuBuildSolver.clampedProductQuotient(7L, 3L, 2L, Long.MAX_VALUE)).isEqualTo(10L) // floor(21/2)
        assertThat(WakfuBuildSolver.clampedProductQuotient(a, a, 1L, 1_000L)).isEqualTo(1_000L) // clamped to cap
        assertThat(WakfuBuildSolver.clampedProductQuotient(-5L, 3L, 2L, Long.MAX_VALUE)).isEqualTo(0L) // clamped to ≥ 0
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
        maxTier: Int = 1,
        maxStackLevel: Int = 1,
        cumulable: Boolean = false,
        solverChoosable: Boolean = true,
        perStatStep: SublimationEffect.PerStatStep? = null,
        conversion: SublimationEffect.Conversion? = null,
        bestElementConcentration: SublimationEffect.BestElementConcentration? = null,
        zeroesElementalMastery: Boolean = false,
    ): Sublimation =
        Sublimation(
            stateId = stateId,
            name = I18nText(name, name, name, name),
            rarity = rarity,
            slotColorPattern = slotColorPattern,
            maxTier = maxTier,
            maxStackLevel = maxStackLevel,
            cumulable = cumulable,
            kind = kind,
            solverChoosable = solverChoosable,
            condition = condition,
            effects = effects + listOfNotNull(perStatStep, conversion, bestElementConcentration),
            zeroesElementalMastery = zeroesElementalMastery,
            rawText = name
        )

    private fun maxDamageParams(
        character: Character,
        forcedSublimations: List<String> = emptyList(),
        forcedItems: List<String> = emptyList(),
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(emptyList()),
            searchDuration = 5.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = forcedItems,
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 15)),
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 50)),
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
    fun `Devastate-style multi-secondary-mastery sub credits only the scenario's range-band mastery`(): Unit =
        runBlocking {
            // Devastate (5982): +15% of level to elemental + EVERY secondary mastery (here: both distance AND melee).
            // It was forced-only under the old multiMastery guard for fear of summing mutually-exclusive masteries.
            // This locks the invariant that justified removing that guard: the objective and re-scorer credit only
            // the scenario's range-band mastery (distance XOR melee, never both), so a multi-mastery sub never
            // over-values. Level 100 ⇒ each "% of level" resolves to a clean 15.
            val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)

            fun devastate(withMelee: Boolean) =
                sublimation(
                    5982,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Devastate",
                    effects =
                        listOfNotNull(
                            SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, 15),
                            SublimationEffect.PercentOfLevel(Characteristic.MASTERY_DISTANCE, 15),
                            SublimationEffect.PercentOfLevel(Characteristic.MASTERY_MELEE, 15).takeIf { withMelee }
                        )
                )

            suspend fun run(
                withMelee: Boolean,
                rangeBand: RangeBand,
            ) = WakfuBuildSolver
                .optimize(
                    maxDamageParams(character).copy(
                        damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = rangeBand, orientation = Orientation.FACE)
                    ),
                    listOf(amulet, epicCarrier).groupBy { it.itemType },
                    emptyList(),
                    listOf(devastate(withMelee)),
                    WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                ).toList()
                .maxByOrNull { it.matchPercentage }!!

            fun chose(best: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>) =
                best.individual.sublimations.values
                    .flatten()
                    .any { it.name.en == "Devastate" }

            // DISTANCE scenario: melee mastery must NOT be credited → adding it leaves the optimum score unchanged.
            val distWithMelee = run(withMelee = true, RangeBand.DISTANCE)
            val distNoMelee = run(withMelee = false, RangeBand.DISTANCE)
            assertThat(chose(distWithMelee)).describedAs("Devastate chosen (distance + elemental help the fire-distance hit)").isTrue()
            assertThat(chose(distNoMelee)).isTrue()
            assertThat(distWithMelee.matchPercentage)
                .describedAs("melee mastery is gated out of a distance scenario — the multi-mastery sub never over-values")
                .isEqualByComparingTo(distNoMelee.matchPercentage)

            // MELEE scenario: the melee mastery now applies → it strictly raises the score. Proves the gating is
            // scenario-correct (the sub's masteries are credited when applicable), not a blanket drop of melee.
            val meleeWithMelee = run(withMelee = true, RangeBand.MELEE)
            val meleeNoMelee = run(withMelee = false, RangeBand.MELEE)
            assertThat(meleeWithMelee.matchPercentage)
                .describedAs("melee mastery is credited in a melee scenario")
                .isGreaterThan(meleeNoMelee.matchPercentage)
        }

    @Test
    fun `solver credits a Featherweight-style perStatStep DI ramp driven by the build's MP`(): Unit =
        runBlocking {
            // Featherweight: +6% Damage Inflicted per MP above 4 (max 24). source=MP, so the MP item is useless for
            // damage EXCEPT via the ramp — the solver equips it only if its objective credits the ramp (proving the
            // wiring), and the re-scorer must reproduce the exact clamp. Base CRA MP (3) + 3 = 6 ⇒ DI = 6·(6−4) = 12.
            val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val mpBoots = equipment(2, ItemType.BOOTS, "MPBoots", mapOf(Characteristic.MOVEMENT_POINT to 3))
            val epicCarrier = equipment(3, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val featherweight =
                sublimation(
                    7088,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Featherweight",
                    perStatStep =
                        SublimationEffect.PerStatStep(
                            source = Characteristic.MOVEMENT_POINT,
                            threshold = 4,
                            perStep = 6,
                            cap = 24,
                            target = Characteristic.DAMAGE_INFLICTED
                        )
                )
            val params = maxDamageParams(character)

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(amulet, mpBoots, epicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(featherweight),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Featherweight")
            val stats =
                computeCharacteristicsValues(
                    best.individual,
                    character.baseCharacteristicValues,
                    emptyMap(),
                    emptyMap(),
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    params.damageScenario
                )
            val mp = stats[Characteristic.MOVEMENT_POINT] ?: 0
            assertThat(mp).describedAs("the solver equipped the MP item to unlock the ramp").isGreaterThan(4)
            // Exact mirror of both engines: DI = clamp(6·(MP − 4), 0, 24).
            assertThat(stats[Characteristic.DAMAGE_INFLICTED]).isEqualTo((6 * (mp - 4)).coerceIn(0, 24))
        }

    @Test
    fun `solver applies an Unraveling-style crit-mastery to elemental conversion when the crit condition holds`(): Unit =
        runBlocking {
            // Unraveling: convert 100% crit mastery → elemental mastery, if Crit ≥ 40%. With crit ≥ 40 (condition
            // holds) and crit mastery to convert, moving it to always-on elemental raises max-damage — so the solver
            // picks it (the FIRST real use of the dormant SublimationEffect.Conversion path) and the re-scorer reflects the
            // move (crit mastery → 0, elemental gains it).
            val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val critRing = equipment(2, ItemType.RING, "Crit", mapOf(Characteristic.CRITICAL_HIT to 40))
            val critMasteryBelt = equipment(3, ItemType.BELT, "CritMastery", mapOf(Characteristic.MASTERY_CRITICAL to 200))
            val epicCarrier = equipment(4, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val unraveling =
                sublimation(
                    5077,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "Unraveling",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                )
            val params = maxDamageParams(character)

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(amulet, critRing, critMasteryBelt, epicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(unraveling),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Unraveling")
            val stats =
                computeCharacteristicsValues(
                    best.individual,
                    character.baseCharacteristicValues,
                    emptyMap(),
                    emptyMap(),
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    params.damageScenario
                )
            // 100% of the 200 crit mastery is moved to elemental mastery: crit mastery → 0, elemental gains ≥ 200.
            assertThat(stats[Characteristic.MASTERY_CRITICAL]).describedAs("crit mastery converted away").isEqualTo(0)
            assertThat(stats[Characteristic.MASTERY_ELEMENTARY]).describedAs("elemental gained the converted crit mastery").isGreaterThanOrEqualTo(200)
        }

    @Test
    fun `solver credits an Elemental Concentration DI bonus when the scenario element is strongest`(): Unit =
        runBlocking {
            // Elemental Concentration: +20% Damage Inflicted, sound-gated so the scenario (fire) element is the
            // build's strongest. A mono-fire build makes fire strongest, so the solver picks it and credits +20% DI.
            // Level 1 (like the per-element-DI test) so no skill-point DI confounds the isolated +20 assertion.
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val elementalConcentration =
                sublimation(
                    5449,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Elemental Concentration",
                    bestElementConcentration = SublimationEffect.BestElementConcentration(damageInflictedBonus = 20, masteryPenaltyPercent = 30)
                )
            val params = maxDamageParams(character)

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(amulet, epicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(elementalConcentration),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Elemental Concentration")
            val stats =
                computeCharacteristicsValues(
                    best.individual,
                    character.baseCharacteristicValues,
                    emptyMap(),
                    emptyMap(),
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    params.damageScenario
                )
            assertThat(stats[Characteristic.DAMAGE_INFLICTED]).describedAs("+20% DI credited when fire is strongest").isEqualTo(20)
        }

    @Test
    fun `solver declines Elemental Concentration when the scenario element is not the strongest`(): Unit =
        runBlocking {
            // A forced water-heavy epic carrier makes WATER strongest in a FIRE scenario, so the strongest-guard
            // (subVar ≤ fire-is-strongest) forbids picking Elemental Concentration — its +DI would over-credit a
            // build the in-game −30% penalty would actually hit. The solve stays feasible; EC is simply not chosen.
            val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))
            val fireAmulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val waterEpicCarrier =
                equipment(2, ItemType.CAPE, "EauForce", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 400), rarity = Rarity.EPIC)
            val elementalConcentration =
                sublimation(
                    5449,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Elemental Concentration",
                    bestElementConcentration = SublimationEffect.BestElementConcentration(damageInflictedBonus = 20, masteryPenaltyPercent = 30)
                )
            val params = maxDamageParams(character, forcedItems = listOf("EauForce"))

            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        listOf(fireAmulet, waterEpicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(elementalConcentration),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).describedAs("EC declined when fire is not the strongest element").doesNotContain("Elemental Concentration")
        }

    @Test
    fun `solver takes Anatomy in a BACK scenario for net rear DI but declines it face-on`(): Unit =
        runBlocking {
            // Anatomy: −20% DI (always) + +40% DI (rear). In a BACK scenario that nets +20% DI ⇒ the solver takes it;
            // face-on only the −20% applies ⇒ it is declined. Its self-condition ("elem mastery + %dmg > rear
            // mastery") is optimistically ignored in the decoder, so here it is a plain monotone DI sub the existing
            // machinery handles. Level 100 (a real fire spell ⇒ non-degenerate damage, so the +20% DI actually moves
            // the objective — at level 1 there is no fire spell and every build ties at 0 damage).
            val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 3000))
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val anatomy =
                sublimation(
                    5445,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Anatomy",
                    effects =
                        listOf(
                            SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, -20),
                            SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 40, ScenarioGate(orientation = "BACK"))
                        )
                )
            val pool = listOf(amulet, epicCarrier).groupBy { it.itemType }

            val backParams =
                maxDamageParams(character).copy(
                    damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)
                )
            val back =
                WakfuBuildSolver
                    .optimize(backParams, pool, emptyList(), listOf(anatomy), WakfuBuildSolver.SolverTuning(numSearchWorkers = 1))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(
                back.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).describedAs("Anatomy taken from the rear (net +20% DI)")
                .contains("Anatomy")

            // FACE scenario (maxDamageParams default) → only the −20% applies → declined.
            val face =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), pool, emptyList(), listOf(anatomy), WakfuBuildSolver.SolverTuning(numSearchWorkers = 1))
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!
            assertThat(
                face.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).describedAs("Anatomy declined face-on (net −20% DI)")
                .doesNotContain("Anatomy")
        }

    @Test
    fun `solver chooses a per-element DI sub in its own element's scenario`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            // Brûlure-like: "+12% Fire damage". The request is a FIRE scenario, so the element gate fires.
            val fireDamage =
                sublimation(
                    8518,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Burn",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "FIRE")))
                )

            val best =
                WakfuBuildSolver
                    .optimize(
                        maxDamageParams(character),
                        listOf(amulet, epicCarrier).groupBy { it.itemType },
                        emptyList(),
                        listOf(fireDamage),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1)
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Burn")
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
            assertThat(stats[Characteristic.DAMAGE_INFLICTED]).isEqualTo(12)
        }

    @Test
    fun `solver ignores a per-element DI sub in a different element's scenario`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            // Earthbound-like: "+12% Earth damage". The request is a FIRE scenario, so the gate never fires and the
            // sub has no usable effect — it is not modeled and never chosen (no spurious DI credit for fire).
            val earthDamage =
                sublimation(
                    8520,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "Earthbound",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "EARTH")))
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), listOf(amulet, epicCarrier).groupBy { it.itemType }, emptyList(), listOf(earthDamage), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).doesNotContain("Earthbound")
        }

    @Test
    fun `at most one epic sublimation is chosen`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            // A single epic carrier item -> at most one epic sub can be hosted (Σ epicSub ≤ Σ epicItems = 1).
            val epicCarrier = equipment(2, ItemType.CAPE, "EpicCarrier", emptyMap(), rarity = Rarity.EPIC)
            val epicA =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicA", effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)))
            val epicB =
                sublimation(2, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicB", effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12)))

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
                sublimation(7, SublimationRarity.RELIC, SublimationKind.FLAT, "Directives", effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 15)))

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
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_DISTANCE, 10)),
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val diB =
                sublimation(
                    11,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "DiB",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12)),
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
    fun `a cumulable normal sublimation stacks up to maxCopies across carriers`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // THREE ≥3-socket carriers — one more than the sub's 2-copy cap, so the cap (not carrier count) binds.
            val carriers =
                listOf(
                    equipment(1, ItemType.AMULET, "C1", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                    equipment(2, ItemType.CAPE, "C2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                    equipment(3, ItemType.BELT, "C3", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3)
                )
            // Cumulable: maxStackLevel 6 / maxTier 3 ⇒ maxCopies floor(6/3)=2. +10% Damage Inflicted per copy — always worth stacking.
            val stackDi =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "StackDi",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3),
                    maxTier = 3,
                    maxStackLevel = 6,
                    cumulable = true
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), carriers.groupBy { it.itemType }, emptyList(), listOf(stackDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            // The cumulable sub is socketed EXACTLY twice — its maxCopies cap — each copy on its own carrier.
            val hosting = best.individual.sublimations.filterValues { hosted -> hosted.any { it.name.en == "StackDi" } }
            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .count { it.name.en == "StackDi" }
            ).describedAs("a cumulable maxStackLevel-6 / tier-3 sub stacks to floor(6/3)=2 copies, capped below the 3 available carriers")
                .isEqualTo(2)
            assertThat(hosting).describedAs("the two copies occupy two DISTINCT carrier items").hasSize(2)
        }

    @Test
    fun `a non-cumulable normal sublimation is socketed at most once even with spare carriers`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // Same two-carrier setup, but the sub is NOT cumulable ⇒ a single 0/1 copy, never stacked.
            val carriers =
                listOf(
                    equipment(1, ItemType.AMULET, "C1", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                    equipment(2, ItemType.CAPE, "C2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3)
                )
            val plainDi =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "PlainDi",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3)
                )

            val best =
                WakfuBuildSolver
                    .optimize(maxDamageParams(character), carriers.groupBy { it.itemType }, emptyList(), listOf(plainDi), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .count { it.name.en == "PlainDi" }
            ).describedAs("a non-cumulable sub is single-copy — the spare carrier does not let it stack")
                .isEqualTo(1)
        }

    /**
     * A FORCED cumulable sub must still stack: its base var is pinned, but the copy vars stay free, so the solver
     * adds a copy when the normal slot it charges is worth its value. Before this, forcing a cumulable sub was
     * strictly WORSE than leaving it choosable (1 copy instead of 2) — a trap, since forcing a sub means "I want
     * this sub", not "I want less of it".
     */
    @Test
    fun `a FORCED cumulable sublimation still stacks to its maxCopies`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            val carriers =
                listOf(
                    equipment(1, ItemType.AMULET, "C1", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                    equipment(2, ItemType.CAPE, "C2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                    equipment(3, ItemType.BELT, "C3", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3)
                )
            val stackDi =
                sublimation(
                    10,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "StackDi",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)),
                    slotColorPattern = listOf(1, 2, 3),
                    maxTier = 3,
                    maxStackLevel = 6,
                    cumulable = true
                )

            val best =
                WakfuBuildSolver
                    .optimize(
                        maxDamageParams(character, forcedSublimations = listOf("StackDi")),
                        carriers.groupBy { it.itemType },
                        emptyList(),
                        listOf(stackDi),
                        WakfuBuildSolver.SolverTuning()
                    ).toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .count { it.name.en == "StackDi" }
            ).describedAs("forcing a cumulable sub pins its base copy but must NOT forbid the second one")
                .isEqualTo(2)
        }

    /**
     * Regression lock for the copy-var DOMAIN leak. A max-damage request carries no required targets, so its model
     * is always satisfiable — equipping nothing is a solution — and INFEASIBLE can only mean a DECLARED domain is
     * too small for the values the chain must represent.
     *
     * The cumulable-sub copy vars are minted on the raw `CpModel`, so nothing seeded them into the [DomainTracker];
     * `of()` fell back to `±STAT_ABS_MAX` and every copy-var term spanned `coefficient · (±1e7)` while
     * `reachableSumDomain` sized the objective chain. At level 245 with a BACK scenario (the largest positional
     * multiplier) the blown-up boxes made the whole model INFEASIBLE and the CLI/GUI answered "no build found" for
     * every subs-on end-game request; where it stayed feasible the loose boxes still cost ~23% of the objective by
     * wrecking propagation. Exercised at 245 + BACK because no other shape reproduced it: the fuzz runs at level 50
     * and every other max-damage fixture faces FACE.
     */
    @Test
    fun `lvl-245 back-facing max-damage with the shipped sublimations is never infeasible`() {
        val params =
            fireMaxDamageParams(245).copy(
                useSublimations = true,
                damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)
            )
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params,
                fullEpicPool(245),
                emptyList(),
                WakfuBestBuildFinderAlgorithm.activeSublimations(params),
                workers = 1,
                seconds = 8.0,
                applyDomination = true,
                deterministicLimit = 8.0
            )
        assertThat(profile.status)
            .describedAs("a target-free max-damage model is always satisfiable — INFEASIBLE means a declared domain is too small (got %s)", profile.status)
            .isNotEqualTo("INFEASIBLE")
    }

    @Test
    fun `an epic sublimation cannot be chosen without an epic item to host it`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // The only item is common — no epic carrier, so the epic sublimation slot does not exist.
            val amulet = equipment(1, ItemType.AMULET, "Fire", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
            val epicDi =
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicDI", effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 50)))

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
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "ForcedEpic", effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10)))

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
                    effects = listOf(SublimationEffect.Flat(Characteristic.LOCK, 250)),
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
                    effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_DISTANCE, 100)),
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

    @Test
    fun `most-masteries mode chooses a per-element DI sub for a mono-element request`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // Fire mastery on the carrier so there is fire damage for the +12% fire DI to multiply (the most-masteries
            // objective maximizes `mastery × (1 + DI/100)`); 3+ sockets so the normal sub can be hosted.
            val carrier = equipment(1, ItemType.AMULET, "FireCarrier", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            // Brûlure-like "+12% Fire damage". The request targets ONLY fire mastery, so the build is mono-fire and
            // the element gate fires even outside max-damage.
            val fireDiSub =
                sublimation(
                    8518,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "Burn",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "FIRE"))),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val best =
                WakfuBuildSolver
                    .optimize(params, listOf(carrier).groupBy { it.itemType }, emptyList(), listOf(fireDiSub), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Burn")
        }

    @Test
    fun `most-masteries multi-element request credits a per-element DI sub for EACH element`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1))
            // BALANCED fire + water carriers (equal mastery) ⇒ both elements are co-bottlenecks of the min-over-
            // elements objective. A per-element DI sub only lifts the weakest element, so socketing just ONE leaves
            // the min pinned to the other (no gain) — only socketing BOTH raises the min. So the optimum takes both,
            // each crediting ONLY its own element's damage (the multi-element fold). Two 3+-socket carriers host them.
            val fireCarrier = equipment(1, ItemType.AMULET, "FireCarrier", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 4)
            val waterCarrier = equipment(2, ItemType.BELT, "WaterCarrier", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 100), maxShardSlots = 4)
            val burn =
                sublimation(
                    8518,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "Burn",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "FIRE"))),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val freeze =
                sublimation(
                    8519,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "Freeze",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "WATER"))),
                    slotColorPattern = listOf(1, 2, 3)
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                                TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1)
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
                    .optimize(params, listOf(fireCarrier, waterCarrier).groupBy { it.itemType }, emptyList(), listOf(burn, freeze), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            assertThat(
                best.individual.sublimations.values
                    .flatten()
                    .map { it.name.en }
            ).contains("Burn", "Freeze")
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
                            SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, -20),
                            SublimationEffect.Flat(Characteristic.MOVEMENT_POINT, 1)
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
                            SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, -20),
                            SublimationEffect.Flat(Characteristic.MOVEMENT_POINT, 1)
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
                            SublimationEffect.Flat(Characteristic.MASTERY_DISTANCE, 500),
                            SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, -20)
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
            // D3: 1 worker + interleave + fixed seed — the canonical DETERMINISTIC protocol. The old 8-worker
            // run made "proves by det-deadline" a worker-race lottery that flaked on oversubscribed CI.
            val results =
                WakfuBuildSolver
                    .optimize(
                        fireMaxDamageParams(110),
                        fullEpicPool(110),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 600.0)
                    ).toList()
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
            // far enough to certify the bound. D3: deterministic 1w+interleave protocol (see the 110 twin).
            val results =
                WakfuBuildSolver
                    .optimize(
                        fireMaxDamageParams(245),
                        fullEpicPool(245),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 1200.0)
                    ).toList()
            val proven = results.lastOrNull { it.isOptimal }
            assertThat(proven)
                .describedAs("reachable domains must let CP-SAT PROVE the free lvl-245 max-damage optimum")
                .isNotNull
            assertThat(proven!!.matchPercentage.signum()).isGreaterThan(0)
        }

    /**
     * D3 re-specification: with the expanded sublimation catalog, CP-SAT's in-model dual bound no longer closes
     * on the runes+subs shapes at ANY worker count (the crit×diff LP relaxation — see SOLVER_PERFORMANCE §7), so
     * the old "CP-SAT reports OPTIMAL by the det deadline" assert was structurally red. The PRODUCTION guarantee
     * is what these guard now: search → certificate badge ([MaxDamageSearch.proveOptimality]) → and when the
     * search's incumbent fell short, the E8 construct rescue ([WakfuBuildSolver.dpConstructProvenOptimum]) must
     * deliver the proven optimum. Deterministic in OUTCOME regardless of worker luck: whichever incumbent the
     * search reaches, the user ends with a proven optimum.
     */
    private fun assertRunesSubsProvenViaCertificate(
        level: Int,
        // A SMALL search budget on purpose: the incumbent's quality is irrelevant to the guarantee —
        // the certificate + E8 rescue deliver the proven optimum for ANY incumbent — so this only needs
        // a reasonable starting build, not a proof attempt (det 600 here was pure CI waste).
        searchTuning: WakfuBuildSolver.SolverTuning = WakfuBuildSolver.SolverTuning(maxDeterministicTime = 120.0),
    ) = runBlocking {
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)
        val runes = WakfuBestBuildFinderAlgorithm.runes
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        MaxDamageCertificateCache.clear()
        val results =
            WakfuBuildSolver
                .optimize(params, pool, runes, subs, searchTuning)
                .toList()
        val best = results.lastOrNull()
        assertThat(best).describedAs("the search must produce a build").isNotNull
        assertThat(best!!.matchPercentage.signum()).describedAs("the build deals real damage").isGreaterThan(0)
        when (val proof = MaxDamageSearch.proveOptimality(params, pool, runes, subs, best)) {
            is MaxDamageSearch.MaxDamageProof.ProvenOptimal -> Unit // badge won — the production happy path
            is MaxDamageSearch.MaxDamageProof.ProvenWithin -> {
                // The incumbent fell short of the certificate optimum — the E8 rescue must construct it.
                val incumbent = best.maxDamageRawProxy ?: best.maxDamageObjective
                val constructed =
                    WakfuBuildSolver.dpConstructProvenOptimum(params, pool, runes, subs, incumbentObjective = incumbent)
                assertThat(constructed)
                    .describedAs("lvl $level: ProvenWithin ${"%.2f".format(proof.fraction * 100)}% — the E8 construct rescue must deliver the proven optimum")
                    .isNotNull
                assertThat(constructed!!.isOptimal).isTrue()
            }
            MaxDamageSearch.MaxDamageProof.Unavailable ->
                org.junit.jupiter.api.Assertions
                    .fail<Unit>("lvl $level: the certificate must back the runes+subs shape (got Unavailable)")
        }
    }

    /**
     * C8(2) — most-masteries param A/B: production pins `maxPresolveIterations = 1` + `linearizationLevel = 1`
     * for the non-max-damage modes; max-damage runs 3/2. This measures whether the 3/2 combination also wins for
     * most-masteries (the slowest mode) under the canonical deterministic protocol (1 worker + interleave + fixed
     * seed — wall-clock on a QUIET machine; both variants share one JVM/run). `WAKFU_MM_PARAM_AB=1`; level via
     * `WAKFU_MM_PARAM_AB_LEVEL` (245); det budget via `WAKFU_MM_PARAM_AB_DET` (600). Ship the production flip only
     * on a faster-time-to-OPTIMAL (or better objective at equal status) verdict.
     */
    @Test
    @Tag("manual")
    fun `manual most-masteries param ab`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_PARAM_AB") == "1")
            val level = System.getenv("WAKFU_MM_PARAM_AB_LEVEL")?.toIntOrNull() ?: 245
            val det = System.getenv("WAKFU_MM_PARAM_AB_DET")?.toDoubleOrNull() ?: 600.0
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 6),
                                TargetStat(Characteristic.HP, 2000),
                                TargetStat(Characteristic.CRITICAL_HIT, 30)
                            )
                        ),
                    searchDuration = 600.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val pool = fullEpicPool(level)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.sublimations
            for ((name, presolve, lin) in listOf(Triple("baseline(p1,l1)", 1, 1), Triple("candidate(p3,l2)", 3, 2))) {
                val tuning =
                    WakfuBuildSolver.SolverTuning(
                        numSearchWorkers = 1,
                        interleaveSearch = true,
                        maxDeterministicTime = det,
                        maxPresolveIterationsOverride = presolve,
                        linearizationLevelOverride = lin
                    )
                val t0 = System.nanoTime()
                val last = WakfuBuildSolver.optimize(params, pool, runes, subs, tuning).toList().lastOrNull()
                val ms = (System.nanoTime() - t0) / 1_000_000
                println("MM_PARAM_AB lvl$level det=$det $name wallMs=$ms optimal=${last?.isOptimal} objective=${last?.matchPercentage}")
            }
        }

    /**
     * Cap-pin soundness lock for NON-max-damage domination: the 16 AP / 8 MP / 20 WP out-of-combat sheet
     * caps are HARD constraints in every mode, so an item with strictly more AP must NOT dominate a
     * lower-AP twin — a cap-tight optimum may need exactly the lower-AP one (the higher-AP swap breaks the
     * cap the evicted item respected). Previously most-masteries/precision compared AP like any other stat
     * (dominator ≥), silently pruning that optimum; AP/MP/WP are now PINNED in all three modes.
     */
    @Test
    fun `most-masteries domination pins AP so a higher-AP twin cannot evict the cap-safe item`() {
        val lowAp = equipment(1, ItemType.AMULET, "LowAp", mapOf(Characteristic.ACTION_POINT to 2, Characteristic.MASTERY_DISTANCE to 100))
        val highAp = equipment(2, ItemType.AMULET, "HighAp", mapOf(Characteristic.ACTION_POINT to 3, Characteristic.MASTERY_DISTANCE to 100))
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 50, 0, CharacterSkills(50)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))),
                searchDuration = 5.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )
        val shape = dominationShape(params, emptyList())
        assertThat(shape).isNotNull
        val filtered = filterDominatedPool(mapOf(ItemType.AMULET to listOf(lowAp, highAp)), shape!!.pinned, shape.compared, shape.minimized)
        assertThat(filtered.getValue(ItemType.AMULET))
            .describedAs("AP is pinned: the +3 AP amulet must not evict the +2 AP one (a 16-AP-cap-tight optimum may need it)")
            .containsExactlyInAnyOrder(lowAp, highAp)
    }

    /**
     * C8(3) greedy warm-start locks. With [WakfuBuildSolver.SolverTuning.greedyWarmStart] on:
     *  1. the FIRST streamed result is the greedy build — valid, scored by the production scorer
     *     (matchPercentage == computeScore(individual)), delivered before any CP-SAT solution;
     *  2. every later emission scores ≥ the greedy (consumers keep the LAST emission — a worse snapshot
     *     would visibly regress the displayed build; the suppression floor enforces monotonicity);
     *  3. the final result equals the flag-OFF final (same proven optimum, same isOptimal) — the warm
     *     start is optimality-neutral (an extra emission + a hint can never change the optimum).
     */
    @Test
    fun `most-masteries greedy warm start streams a valid first build and preserves the optimum`(): Unit =
        runBlocking {
            val level = 20
            val characterSkills = CharacterSkills(level)
            val character = Character(CharacterClass.CRA, level, level, characterSkills)
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "DistA", mapOf(Characteristic.MASTERY_DISTANCE to 120)),
                    equipment(2, ItemType.AMULET, "DistB", mapOf(Characteristic.MASTERY_DISTANCE to 80, Characteristic.ACTION_POINT to 1)),
                    equipment(3, ItemType.BELT, "HpBelt", mapOf(Characteristic.HP to 300)),
                    equipment(4, ItemType.BELT, "DistBelt", mapOf(Characteristic.MASTERY_DISTANCE to 60)),
                    equipment(5, ItemType.RING, "RingA", mapOf(Characteristic.MASTERY_DISTANCE to 40)),
                    equipment(6, ItemType.RING, "RingB", mapOf(Characteristic.MASTERY_DISTANCE to 30, Characteristic.HP to 50)),
                    equipment(7, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_DISTANCE to 70))
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.HP, 300)
                            )
                        ),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false
                )
            val pool = equipments.groupBy { it.itemType }

            val withGreedy =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning(greedyWarmStart = true))
                    .toList()
            val baseline =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                    .toList()

            val first = withGreedy.first()
            assertThat(first.individual.isValid()).describedAs("greedy first emission must be a valid build").isTrue()
            assertThat(first.isOptimal).isFalse()
            assertThat(first.matchPercentage)
                .describedAs("greedy emission must carry the production scorer's score for its own build")
                .isEqualByComparingTo(
                    FindMostMasteriesFromInputScoring.computeScore(
                        targetStats = params.targetStats,
                        buildCombination = first.individual,
                        characterBaseCharacteristics = character.baseCharacteristicValues
                    )
                )
            withGreedy.forEach {
                assertThat(it.matchPercentage)
                    .describedAs("no emission may score below the greedy floor (consumers keep the last emission)")
                    .isGreaterThanOrEqualTo(first.matchPercentage)
            }
            assertThat(withGreedy.last().isOptimal).describedAs("warm-started solve still proves").isTrue()
            assertThat(withGreedy.last().matchPercentage)
                .describedAs("warm start must not change the proven optimum")
                .isEqualByComparingTo(baseline.last().matchPercentage)
        }

    /**
     * Most-masteries PRODUCTION-path profile (the C8(2)-era tuned runs measured the FULL pool — the
     * `tuning == null` coupling silently disabled domination). Reports, on the F5 shape:
     *  1. pool sizes — full vs today's all-stat domination (`compared = null`) vs a TARGETED-compare proxy
     *     (the max-damage shape on the same params/subs), to size the prospective most-masteries
     *     targeted-compare win before building its soundness argument;
     *  2. the incumbent trajectory (wallMs → objective) and final status for domination OFF vs ON under the
     *     canonical protocol (1 worker + interleave, fixed seed, det budget), via the new
     *     [WakfuBuildSolver.SolverTuning.applyDominationOverride] seam.
     * `WAKFU_MM_PROFILE=1`; level via `WAKFU_MM_PROFILE_LEVEL` (245); det via `WAKFU_MM_PROFILE_DET` (600).
     */
    @Test
    @Tag("manual")
    fun `manual most-masteries profile`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_PROFILE") == "1")
            val level = System.getenv("WAKFU_MM_PROFILE_LEVEL")?.toIntOrNull() ?: 245
            val det = System.getenv("WAKFU_MM_PROFILE_DET")?.toDoubleOrNull() ?: 600.0
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 6),
                                TargetStat(Characteristic.HP, 2000),
                                TargetStat(Characteristic.CRITICAL_HIT, 30)
                            )
                        ),
                    searchDuration = 600.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val pool = fullEpicPool(level)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.sublimations

            fun poolSize(p: Map<ItemType, List<Equipment>>) = p.values.sumOf { it.size }
            val allStatShape = dominationShape(params, subs)
            val allStatPool = allStatShape?.let { WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, it) }
            // Targeted-compare PROXY: the max-damage shape on the same params/subs (targets + sub/condition
            // stats + scenario masteries). NOT claimed sound for most-masteries — a pool-size estimate only.
            val proxyShape =
                dominationShape(
                    params.copy(scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE),
                    subs
                )
            val proxyPool = proxyShape?.let { WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, it) }
            println(
                "MM_PROFILE lvl$level pools: full=${poolSize(pool)} " +
                    "allStat=${allStatPool?.let { poolSize(it) } ?: "gated-off"} " +
                    "targetedProxy=${proxyPool?.let { poolSize(it) } ?: "gated-off"}"
            )

            // Split the pre-first-emission time: MODEL BUILD alone (Kotlin side), via the mode-agnostic
            // buildModel seam — twice, so the second read shows the warm (memoized-domination) cost.
            for (domination in listOf(false, true)) {
                repeat(2) { round ->
                    val tb = System.nanoTime()
                    WakfuBuildSolver.maxDamageReachableRangesForTest(params, pool, runes, subs, applyDomination = domination)
                    println("MM_PROFILE buildModel dom=$domination round=$round wallMs=${(System.nanoTime() - tb) / 1_000_000}")
                }
            }

            val greedyAxis =
                when (System.getenv("WAKFU_MM_PROFILE_GREEDY")) {
                    "1" -> listOf(true)
                    "ab" -> listOf(false, true)
                    else -> listOf(false)
                }
            for (domination in listOf(false, true)) {
                for (greedy in greedyAxis) {
                    val tuning =
                        WakfuBuildSolver.SolverTuning(
                            numSearchWorkers = 1,
                            interleaveSearch = true,
                            maxDeterministicTime = det,
                            applyDominationOverride = domination,
                            greedyWarmStart = greedy,
                            // Mirror PRODUCTION's non-max-damage solver params (presolve=1, linearization=1) —
                            // the tuned path otherwise runs FULL presolve, which is ~2× slower on this shape
                            // and does not represent what the GUI/CLI actually run.
                            maxPresolveIterationsOverride = 1,
                            linearizationLevelOverride = 1
                        )
                    val t0 = System.nanoTime()
                    var last: SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                    var firstDiagnosed = false
                    WakfuBuildSolver.optimize(params, pool, runes, subs, tuning).collect { r ->
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        println("MM_PROFILE dom=$domination greedy=$greedy emit t=${ms}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                        if (!firstDiagnosed && greedy) {
                            firstDiagnosed = true
                            val achieved =
                                computeCharacteristicsValues(
                                    buildCombination = r.individual,
                                    characterBaseCharacteristics = params.character.baseCharacteristicValues,
                                    masteryElementsWanted = params.targetStats.masteryElementsWanted,
                                    resistanceElementsWanted = params.targetStats.resistanceElementsWanted
                                )
                            println(
                                "MM_PROFILE greedyAchieved AP=${achieved[Characteristic.ACTION_POINT]} MP=${achieved[Characteristic.MOVEMENT_POINT]} " +
                                    "HP=${achieved[Characteristic.HP]} CRIT=${achieved[Characteristic.CRITICAL_HIT]} DIST=${achieved[Characteristic.MASTERY_DISTANCE]} " +
                                    "DI=${achieved[Characteristic.DAMAGE_INFLICTED]} items=${r.individual.equipments.size}"
                            )
                        }
                        last = r
                    }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    println(
                        "MM_PROFILE lvl$level det=$det dom=$domination greedy=$greedy FINAL wallMs=$ms " +
                            "optimal=${last?.isOptimal} objective=${last?.matchPercentage}"
                    )
                }
            }
        }

    /** The F5 production shape (distance mastery + AP/MP/HP/crit, runes + subs on) used by every
     *  most-masteries perf harness — see docs/MOST_MASTERIES_PERF_PLAN.md. */
    private fun mmF5Params(level: Int): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
            targetStats =
                TargetStats(
                    listOf(
                        TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6),
                        TargetStat(Characteristic.HP, 2000),
                        TargetStat(Characteristic.CRITICAL_HIT, 30)
                    )
                ),
            searchDuration = 600.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            useRunes = true,
            useSublimations = true
        )

    /** The production-path tuning every most-masteries perf harness runs under (canonical protocol:
     *  1 worker + interleave + fixed seed, presolve=1/linearization=1 + domination like the GUI/CLI). */
    private fun mmProductionTuning(
        det: Double,
        greedy: Boolean,
    ): WakfuBuildSolver.SolverTuning =
        WakfuBuildSolver.SolverTuning(
            numSearchWorkers = 1,
            interleaveSearch = true,
            maxDeterministicTime = det,
            applyDominationOverride = true,
            greedyWarmStart = greedy,
            maxPresolveIterationsOverride = 1,
            linearizationLevelOverride = 1
        )

    /**
     * P0 measurement harness of the most-masteries perf campaign (docs/MOST_MASTERIES_PERF_PLAN.md §1):
     * M1 = time-to-incumbent-equals-final-optimum vs M2 = time-to-OPTIMAL on the F5 shape, both
     * warm-start arms. Measured 2026-07-11 at 245: M1 = M2 to within milliseconds in all runs
     * (147-149 s off / 138.5-138.9 s on) — the certificate early stop's NO-GO evidence.
     * `WAKFU_MM_P0=1`; level via `WAKFU_MM_P0_LEVEL` (245); det via `WAKFU_MM_P0_DET` (600);
     * repeats per config via `WAKFU_MM_P0_RUNS` (2).
     */
    @Test
    fun `manual most-masteries P0 gate`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_P0") == "1")
            val level = System.getenv("WAKFU_MM_P0_LEVEL")?.toIntOrNull() ?: 245
            val det = System.getenv("WAKFU_MM_P0_DET")?.toDoubleOrNull() ?: 600.0
            val runsPerConfig = System.getenv("WAKFU_MM_P0_RUNS")?.toIntOrNull() ?: 2
            val params = mmF5Params(level)
            val pool = fullEpicPool(level)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.sublimations
            for (greedy in listOf(false, true)) {
                repeat(runsPerConfig) { run ->
                    val t0 = System.nanoTime()

                    data class Emission(
                        val ms: Long,
                        val objective: java.math.BigDecimal,
                        val optimal: Boolean,
                    )
                    val emissions = mutableListOf<Emission>()
                    WakfuBuildSolver.optimize(params, pool, runes, subs, mmProductionTuning(det, greedy)).collect { r ->
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        emissions += Emission(ms, r.matchPercentage, r.isOptimal)
                        println("MM_P0 greedy=$greedy run=$run emit t=${ms}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                    }
                    val totalMs = (System.nanoTime() - t0) / 1_000_000
                    val last = emissions.lastOrNull()
                    val m1Ms = last?.let { fin -> emissions.firstOrNull { it.objective.compareTo(fin.objective) == 0 }?.ms }
                    println(
                        "MM_P0 lvl$level det=$det greedy=$greedy run=$run SUMMARY firstEmitMs=${emissions.firstOrNull()?.ms} " +
                            "incumbentEqualsOptimumMs=$m1Ms timeToOptimalMs=$totalMs " +
                            "finalOptimal=${last?.optimal} finalObjective=${last?.objective} emissions=${emissions.size}"
                    )
                }
            }
        }

    /**
     * P0.5 diagnostic bundle (docs/MOST_MASTERIES_PERF_PLAN.md §3): decides primal-wall vs dual-wall
     * for the terminal ~22-24 s plateau the P0 gate exposed. Two serialized solves on the F5 shape:
     *  1. TRAJECTORY — production-representative run (greedy hint on) with CP-SAT's own search log
     *     streamed (`SolverTuning.logSearchProgress`): the `#Bound` lines give the dual-bound
     *     trajectory + per-subsolver attribution the solution callback cannot see; the final full
     *     assignment is captured by var name.
     *  2. ORACLE — a fresh solve hinted with the captured optimum's FULL assignment (equip + skills +
     *     runes + subs, `SolverTuning.assignmentHint`, greedy off so no var is hinted twice): its
     *     time-to-OPTIMAL is the hard ceiling of every primal-heuristic investment.
     * `WAKFU_MM_P05=1`; level via `WAKFU_MM_P05_LEVEL` (245); det via `WAKFU_MM_P05_DET` (600).
     */
    @Test
    fun `manual most-masteries P05 diagnostic`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_P05") == "1")
            val level = System.getenv("WAKFU_MM_P05_LEVEL")?.toIntOrNull() ?: 245
            val det = System.getenv("WAKFU_MM_P05_DET")?.toDoubleOrNull() ?: 600.0
            val params = mmF5Params(level)
            val pool = fullEpicPool(level)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.sublimations

            // Leg 1 — trajectory + capture. The CP-SAT log goes to a scratch file (it is large and its
            // native-stdout path bypasses JUnit capture anyway); the path is printed for the analysis.
            val logFile = java.io.File.createTempFile("mm-p05-cpsat", ".log")
            val logWriter = java.io.PrintWriter(java.io.BufferedWriter(java.io.FileWriter(logFile)))
            println("MM_P05 cpsatLog=${logFile.absolutePath}")
            var captured: Map<String, Long>? = null
            val trajectoryTuning =
                mmProductionTuning(det, greedy = true).copy(
                    searchLogSink = { line -> synchronized(logWriter) { logWriter.println(line) } },
                    captureAssignment = { captured = it }
                )
            var t0 = System.nanoTime()
            var last: SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
            WakfuBuildSolver.optimize(params, pool, runes, subs, trajectoryTuning).collect { r ->
                println("MM_P05 leg=trajectory emit t=${(System.nanoTime() - t0) / 1_000_000}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                last = r
            }
            logWriter.flush()
            println(
                "MM_P05 lvl$level det=$det leg=trajectory SUMMARY wallMs=${(System.nanoTime() - t0) / 1_000_000} " +
                    "optimal=${last?.isOptimal} objective=${last?.matchPercentage} capturedVars=${captured?.size ?: 0} " +
                    "cpsatLogLines=${logFile.readLines().size}"
            )
            val hint = captured
            assumeTrue(hint != null && last?.isOptimal == true)

            // Leg 2 — oracle: full-assignment hint of the KNOWN optimum, greedy off (no double hint).
            val oracleTuning = mmProductionTuning(det, greedy = false).copy(assignmentHint = hint)
            t0 = System.nanoTime()
            var oracleLast: SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
            var oracleM1: Long? = null
            WakfuBuildSolver.optimize(params, pool, runes, subs, oracleTuning).collect { r ->
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (oracleM1 == null && r.matchPercentage.compareTo(last!!.matchPercentage) == 0) oracleM1 = ms
                println("MM_P05 leg=oracle emit t=${ms}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                oracleLast = r
            }
            println(
                "MM_P05 lvl$level det=$det leg=oracle SUMMARY wallMs=${(System.nanoTime() - t0) / 1_000_000} " +
                    "incumbentEqualsOptimumMs=$oracleM1 optimal=${oracleLast?.isOptimal} objective=${oracleLast?.matchPercentage}"
            )
        }

    /** Manual probe: time-to-first-emission of a production max-damage search (C8(3) applicability). */
    @Test
    fun `manual max-damage first-emission latency`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MAXDMG_FIRST_EMIT") == "1")
            val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
            val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true, searchDuration = 15.seconds)
            val pool = fullEpicPool(level)
            MaxDamageCertificateCache.clear()
            val startMs = System.currentTimeMillis()
            var firstMs = -1L
            var count = 0
            MaxDamageSearch
                .run(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                .collect {
                    count++
                    if (firstMs < 0) firstMs = System.currentTimeMillis() - startMs
                }
            println("FIRST_EMIT_MS=$firstMs emissions=$count totalMs=${System.currentTimeMillis() - startMs}")
        }

    /**
     * The SHORT-SEARCH rescue (cascade + E8 construct): a deliberately tiny budget leaves a WEAK
     * incumbent, so the certificate ceiling sits far above it and the flow legitimately ends UNPROVEN
     * at its duration. The async proof path (what the CLI/GUI run next) must then confirm the argmax
     * through the CASCADED certificate — one cell at a time, not a tier-1.5 pass per survivor — and
     * CONSTRUCT the proven-optimal build. Production path (tuning = null); generous wall asserts.
     */
    @Test
    @Tag("slow")
    fun `a short max-damage search still ends proven via the cascaded construct`(): Unit =
        runBlocking {
            val params =
                fireMaxDamageParams(110).copy(
                    useRunes = true,
                    useSublimations = true,
                    searchDuration = 3.seconds
                )
            val pool = fullEpicPool(110)
            MaxDamageCertificateCache.clear()
            val startMs = System.currentTimeMillis()
            val results =
                MaxDamageSearch
                    .run(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    .toList()
            val best = results.last()
            // The async proof path, exactly as the CLI/GUI run it after the flow closes.
            val proof = MaxDamageSearch.proveOptimality(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations, best)
            val proven =
                when (proof) {
                    is MaxDamageSearch.MaxDamageProof.ProvenOptimal -> true
                    is MaxDamageSearch.MaxDamageProof.ProvenWithin -> {
                        val incumbent = best.maxDamageRawProxy ?: best.maxDamageObjective
                        val constructed =
                            WakfuBuildSolver.dpConstructProvenOptimum(
                                params,
                                pool,
                                WakfuBestBuildFinderAlgorithm.runes,
                                WakfuBestBuildFinderAlgorithm.sublimations,
                                incumbentObjective = incumbent
                            )
                        constructed != null
                    }
                    else -> false
                }
            val elapsedMs = System.currentTimeMillis() - startMs
            assertThat(proven)
                .describedAs("a 3 s search must still end PROVEN via the async cascaded construct (proof=%s)", proof)
                .isTrue
            assertThat(elapsedMs)
                .describedAs("the rescue must not degenerate into the full batch (took %d ms)", elapsedMs)
                .isLessThan(150_000L)
        }

    /**
     * The certificate EARLY STOP (the restored "stops when proven" behavior): on the full subs-on pool
     * CP-SAT's dual bound stays open, so without the certificate the search burns its whole wall budget.
     * The warm-up certificate lands mid-search, the incumbent crosses the certified ceiling, and phase 1
     * is cancelled — the run must finish WELL under its 180 s budget and still emit a PROVEN result.
     * Production path on purpose (tuning = null — the warm-up only runs there), so timings are machine
     * dependent: the 150 s assert leaves ~2× headroom over the ~60 s expected on the dev machine.
     */
    @Test
    @Tag("slow")
    fun `max-damage search stops early once the certificate proves the incumbent`(): Unit =
        runBlocking {
            val params =
                fireMaxDamageParams(110).copy(
                    useRunes = true,
                    useSublimations = true,
                    searchDuration = 180.seconds
                )
            val pool = fullEpicPool(110)
            MaxDamageCertificateCache.clear()
            val startMs = System.currentTimeMillis()
            val results =
                MaxDamageSearch
                    .run(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    .toList()
            val elapsedMs = System.currentTimeMillis() - startMs
            val last = results.last()
            assertThat(last.isOptimal)
                .describedAs("the final emit must carry the certificate proof")
                .isTrue
            assertThat(elapsedMs)
                .describedAs("the search must stop well before its 180 s budget (took %d ms)", elapsedMs)
                .isLessThan(150_000L)
        }

    @Test
    @Tag("slow")
    fun `max-damage proves the runes+subs level-110 optimum via search plus certificate`() {
        // Deterministic, DELIBERATELY-short search (canonical 1-worker + interleave protocol): the incumbent
        // reliably falls short of the certificate optimum, so this exercises the E8 construct rescue —
        // provenance-restricted re-solve first, full-pool `rawScore ≥ bound` feasibility fallback second —
        // on EVERY run, instead of flaking on whether a multi-worker race happened to reach the optimum
        // by itself (with a budget that DOES reach it, the test only ever takes the ProvenOptimal arm).
        assertRunesSubsProvenViaCertificate(
            110,
            searchTuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 10.0)
        )
    }

    @Test
    @Tag("slow")
    fun `max-damage proves the runes+subs level-245 optimum via search plus certificate`() {
        assertRunesSubsProvenViaCertificate(245)
    }

    /** Explain-only probe (`WAKFU_E8_EXPLAIN_CELL=<ap>`): backtrack one lvl-110 runes+subs cell, no search/cache. */
    @Test
    @Tag("manual")
    fun `manual E8 explain single cell`() {
        val cell = System.getenv("WAKFU_E8_EXPLAIN_CELL")?.toIntOrNull()
        assumeTrue(cell != null)
        val params = fireMaxDamageParams(110).copy(useRunes = true, useSublimations = true)
        val explain =
            WakfuBuildSolver.certifierExplainForTest(
                params,
                fullEpicPool(110),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true,
                cell = cell!!
            )
        explain.forEach { System.err.println("E8_EXPLAIN[$cell]: $it") }
    }

    /**
     * P6.2 nightly ledger regression: the lvl-245 tier-1 FAST certificate ledger (runes + subs — the
     * production shape) must reproduce the banked oracle bit-for-bit. This is the end-to-end guard that no
     * certifier edit AND no game-data bump silently shifts a per-cell bound (which would move the badge). We
     * exercise the FAST tier (not the ~1 h `forceTier2All` exact ledger — far too slow for a CI runner) by
     * running the real two-tier orchestrator with a huge incumbent, so every cell is eliminated on its fast
     * bound alone: `cellObjectives` is then exactly the production fast ledger. The oracle is version-pinned
     * ([LVL245_LEDGER_ORACLE_VERSION]); a data update changes the pool ⇒ the ledger ⇒ this fails loudly (the
     * message names banked-vs-current). The certifier is a pure DP (no OR-Tools solve) so it is deterministic
     * and cannot flake. ~70 s serial, hence `@slow` (nightly only). The exact tier is guarded separately by
     * the fast `… certifier matches pinned CP-SAT …` unit locks + the P6.1 fuzz lock's forceTier2All ledger.
     */
    @Test
    @Tag("slow")
    fun `lvl-245 fast certifier ledger reproduces the banked oracle`() {
        val params = fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true)
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                fullEpicPool(245),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true,
                incumbentObjective = Long.MAX_VALUE / 2, // above every fast bound ⇒ no survivor ⇒ pure fast tier
                forceTier2All = false
            )
        assertThat(ledger.bailedCells).describedAs("the production lvl-245 shape must not bail").isEmpty()
        assertThat(ledger.tier2Cells).describedAs("a huge incumbent eliminates every cell ⇒ no exact tier-2").isEmpty()
        assertThat(ledger.cellObjectives.toSortedMap())
            .describedAs(
                "the lvl-245 FAST certifier ledger drifted from the oracle banked at WakfuData %s (current %s). " +
                    "If this is an INTENTIONAL certifier change or a game-data bump, re-bank " +
                    "LVL245_FAST_LEDGER_ORACLE (see the manual `certifyLedger end-to-end` test with a huge " +
                    "incumbent); otherwise it is an unintended certifier regression.",
                LVL245_LEDGER_ORACLE_VERSION,
                WakfuData.VERSION
            ).containsExactlyEntriesOf(LVL245_FAST_LEDGER_ORACLE.toSortedMap())
        // SOUNDNESS: the fast tier-1 bound must UPPER-BOUND the proven exact optimum — a fast value below it
        // would let the orchestrator eliminate the winning cell (a wrong "proven optimal" badge).
        assertThat(ledger.maxCellObjective)
            .describedAs("the fast ledger max (%s) must upper-bound the proven lvl-245 optimum (%d)", ledger.maxCellObjective, LVL245_PROVEN_OPTIMUM)
            .isNotNull
        assertThat(ledger.maxCellObjective!!)
            .describedAs("fast ledger max must be ≥ the proven optimum")
            .isGreaterThanOrEqualTo(LVL245_PROVEN_OPTIMUM)
    }

    @Test
    @Tag("manual")
    fun `manual max-damage level-245 incumbent shape`() =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_INCUMBENT_SHAPE") == "1")

            val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toLongOrNull() ?: 180L
            val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 8
            val detLimit = System.getenv("WAKFU_MAX_DAMAGE_DETTIME")?.toDoubleOrNull() ?: 100_000.0
            val params = fireMaxDamageParams(245).copy(searchDuration = seconds.seconds, useRunes = true, useSublimations = true)
            val best =
                WakfuBuildSolver
                    .optimize(
                        params,
                        fullEpicPool(245),
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        WakfuBuildSolver.SolverTuning(
                            numSearchWorkers = workers,
                            maxDeterministicTime = detLimit
                        )
                    ).toList()
                    .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))

            requireNotNull(best) { "No max-damage incumbent found" }
            val stats =
                computeCharacteristicsValues(
                    best.individual,
                    params.character.baseCharacteristicValues,
                    masteryElementsWanted = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1),
                    resistanceElementsWanted = emptyMap()
                )
            val rotation =
                me.chosante.autobuilder.domain.SpellRotationOptimizer.bestAcrossElements(
                    best.individual,
                    params.character,
                    params.character.clazz,
                    params.damageScenario
                )
            val itemSummary =
                best.individual.equipments
                    .sortedWith(compareBy<Equipment> { it.itemType.ordinal }.thenBy { it.name.fr })
                    .joinToString(" | ") { "${it.itemType}:${it.name.fr}#${it.equipmentId}" }
            println(
                "MAX_DAMAGE_INCUMBENT_SHAPE " +
                    "optimal=${best.isOptimal} score=${best.matchPercentage} " +
                    "ap=${stats[Characteristic.ACTION_POINT]} crit=${stats[Characteristic.CRITICAL_HIT]} " +
                    "di=${stats[Characteristic.DAMAGE_INFLICTED]} fire=${stats[Characteristic.MASTERY_ELEMENTARY_FIRE]} " +
                    "elem=${stats[Characteristic.MASTERY_ELEMENTARY]} critMastery=${stats[Characteristic.MASTERY_CRITICAL]} " +
                    "dist=${stats[Characteristic.MASTERY_DISTANCE]} apBudget=${rotation.apBudget} apUsed=${rotation.apUsed} " +
                    "damage=${rotation.totalExpectedDamage} casts=${rotation.casts.joinToString { "${it.spell.name}:${it.count}" }}"
            )
            println("MAX_DAMAGE_INCUMBENT_ITEMS $itemSummary")
        }

    @Test
    @Tag("manual")
    fun `manual max-damage level-245 two-worker experiment matrix`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENTS") == "1")

        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 60.0
        val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 2
        val detLimit = System.getenv("WAKFU_MAX_DAMAGE_DETTIME")?.toDoubleOrNull()
        val repeats = System.getenv("WAKFU_MAX_DAMAGE_REPEATS")?.toIntOrNull() ?: 1
        val objectiveCutoff = System.getenv("WAKFU_MAX_DAMAGE_OBJECTIVE_CUTOFF")?.toLongOrNull()
        val params = fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(245)
        // Default research set: the old one-hot encoding vs the production binary default, plus the gated
        // tightness cuts stacked on binary. Extra component variants are banked for targeted A/B via
        // WAKFU_MAX_DAMAGE_EXPERIMENT_NAMES. WAKFU_MAX_DAMAGE_DETTIME picks the deterministic-mode budget — the
        // ONLY reliable verdict; bestBound/ceiling is a vanity metric here.
        val allExperiments =
            listOf(
                "table-baseline" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.TABLE,
                        critProduct = CritProductMode.TABLE
                    ),
                "d-binary-only" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.BINARY,
                        critProduct = CritProductMode.TABLE
                    ),
                "crit-binary-only" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.TABLE,
                        critProduct = CritProductMode.BINARY
                    ),
                "crit-generic" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.BINARY,
                        critProduct = CritProductMode.GENERIC
                    ),
                "source-di" to MaxDamageExperimentConfig(dProduct = DProductMode.SOURCE_DI),
                "binary" to MaxDamageExperimentConfig.DEFAULT,
                "binary+apCeiling" to MaxDamageExperimentConfig(apCeiling = true),
                "binary+per-ap" to MaxDamageExperimentConfig(perApRotRawCut = true),
                "binary+ring-bound" to MaxDamageExperimentConfig(sameNameRingBound = true),
                "binary+ring+per-ap" to
                    MaxDamageExperimentConfig(
                        sameNameRingBound = true,
                        perApRotRawCut = true
                    ),
                "binary+ap+per-ap" to MaxDamageExperimentConfig(apCeiling = true, perApRotRawCut = true),
                "binary+ring+ap+per-ap" to
                    MaxDamageExperimentConfig(
                        apCeiling = true,
                        sameNameRingBound = true,
                        perApRotRawCut = true
                    ),
                // AM-GM joint product bound — the only cut that can lower the FREE objective's stuck ceiling
                // (it's a valid upper bound on D·Graw, unlike the lower-bound cutoff cuts).
                "binary+joint" to MaxDamageExperimentConfig(dGrawJointBound = true),
                "binary+joint+per-ap" to
                    MaxDamageExperimentConfig(
                        dGrawJointBound = true,
                        perApRotRawCut = true
                    ),
                // AP-cell certifier caps fed back as model constraints — per-cell (rotationAp==a ⟹ perHit ≤ U(a))
                // + the constant raw ceiling. Attacks the FREE objective's root gap with the certified bound.
                "binary+certcap" to MaxDamageExperimentConfig(certifierCellCap = true)
            )
        val defaultExperimentNames = setOf("table-baseline", "binary", "binary+apCeiling", "binary+per-ap")
        val selectedNames =
            System
                .getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_NAMES")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: defaultExperimentNames
        val selectedExperiments =
            allExperiments.filter { (name, _) -> name in selectedNames }
        require(selectedExperiments.isNotEmpty()) {
            "No max-damage experiments selected. Available: ${allExperiments.joinToString { it.first }}"
        }

        repeat(repeats) { rep ->
            for ((name, experiment) in selectedExperiments) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = workers,
                        seconds = seconds,
                        applyDomination = true,
                        experiment = experiment,
                        deterministicLimit = detLimit,
                        objectiveCutoff = objectiveCutoff
                    )
                println("MAX_DAMAGE_EXPERIMENT rep$rep $name $profile")
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage certifier cell audit`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_CERT_AUDIT") == "1")

        // Production-fixture certifier audit: per-AP-cell certified objective (-1 = certifier bailed), plus
        // total runtime. Diagnoses whether the certifier engages at all on the CURRENT choosable sub set
        // (a single choosable CONVERSION sub bails every cell) and how tight its cells are vs the incumbent.
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val t0 = System.nanoTime()
        val cells =
            WakfuBuildSolver.certifierCellObjectivesForTest(
                params,
                fullEpicPool(level),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true
            )
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("CERT_AUDIT lvl$level totalMs=$ms cells=${cells.toSortedMap()}")
    }

    /**
     * P3.2/P3.3 end-to-end orchestrator measurement: run [WakfuBuildSolver.certifyLedgerForTest] over the
     * production fixture and print the tier-2 survivor set, bailed cells, max objective and wall-clock. Env:
     * `WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL` (default 245), `WAKFU_MAX_DAMAGE_CERT_INCUMBENT` (objective; omit to
     * skip elimination), `WAKFU_MAX_DAMAGE_CERT_THREADS` (default 1), `WAKFU_MAX_DAMAGE_CERT_FORCE_TIER2` (=1).
     */
    @Test
    @Tag("manual")
    fun `manual max-damage certifyLedger end-to-end`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_CERT_LEDGER") == "1")

        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val incumbent = System.getenv("WAKFU_MAX_DAMAGE_CERT_INCUMBENT")?.toLongOrNull()
        val threads = System.getenv("WAKFU_MAX_DAMAGE_CERT_THREADS")?.toIntOrNull() ?: WakfuBuildSolver.certifierDefaultThreads()
        val forceTier2All = System.getenv("WAKFU_MAX_DAMAGE_CERT_FORCE_TIER2") == "1"
        // A/B seams: `=1` disables the exact-pass c-loop pruning / the tier-1.5 segment skip (the baselines).
        CertifierTuning.cLoopPruneEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_NO_CPRUNE") != "1"
        CertifierTuning.tier15SegmentSkipEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_NO_T15SKIP") != "1"
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        // Frontier-work instrumentation (WAKFU_MAX_DAMAGE_CERT_STATS=1): add() calls, Σ points scanned by
        // the per-add dominance/compaction loops, and the largest live frontier — the fast-tier hotspot data.
        val stats = System.getenv("WAKFU_MAX_DAMAGE_CERT_STATS") == "1"
        if (stats) {
            Frontier.statsEnabled = true
            Frontier.statsAddCalls = 0L
            Frontier.statsPointsScanned = 0L
            Frontier.statsRejected = 0L
            Frontier.statsMaxFrontier = 0
            Frontier.statsCopies = 0L
            Frontier.statsCopiedPoints = 0L
        }
        val t0 = System.nanoTime()
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                fullEpicPool(level),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true,
                incumbentObjective = incumbent,
                forceTier2All = forceTier2All,
                threads = threads
            )
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (stats) {
            println(
                "CERT_LEDGER_STATS adds=${Frontier.statsAddCalls} pointsScanned=${Frontier.statsPointsScanned} " +
                    "rejected=${Frontier.statsRejected} maxFrontier=${Frontier.statsMaxFrontier} " +
                    "copies=${Frontier.statsCopies} copiedPoints=${Frontier.statsCopiedPoints}"
            )
            Frontier.statsEnabled = false
        }
        println(
            "CERT_LEDGER lvl$level threads=$threads incumbent=$incumbent forceTier2All=$forceTier2All totalMs=$ms " +
                "tier2=${ledger.tier2Cells.toSortedSet()} bailed=${ledger.bailedCells.toSortedSet()} " +
                "max=${ledger.maxCellObjective} cells=${ledger.cellObjectives.toSortedMap()}"
        )
    }

    /**
     * P5.2 shape audit: run the FAST certifier ledger over a spread of DEFAULT shapes and print each cell's
     * sound upper bound + the bailed cells. Purpose: catch a silent shape gap — a `-1` (fast bail) on a shape
     * a real search would use means the certifier can't back the badge there (fix or document). Fast-only so
     * the whole sweep stays cheap (~20-70 s per shape); the exact tier is exercised by the banked 110/245
     * oracles and the P3.3 acceptance runs.
     */
    @Test
    @Tag("manual")
    fun `manual max-damage shape audit`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_SHAPE_AUDIT") == "1")

        data class Shape(
            val name: String,
            val clazz: CharacterClass,
            val level: Int,
            val scenario: DamageScenario,
        )
        val shapes =
            listOf(
                Shape("cra-fire-dist-110", CharacterClass.CRA, 110, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE)),
                Shape("cra-fire-dist-200", CharacterClass.CRA, 200, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE)),
                Shape("cra-fire-dist-230", CharacterClass.CRA, 230, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE)),
                Shape("iop-earth-melee-200", CharacterClass.IOP, 200, DamageScenario(element = SpellElement.EARTH, rangeBand = RangeBand.MELEE)),
                Shape("cra-fire-berserk-200", CharacterClass.CRA, 200, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, berserk = true)),
                Shape("cra-fire-heal-200", CharacterClass.CRA, 200, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, healing = true))
            )
        for (s in shapes) {
            val params =
                WakfuBestBuildParams(
                    character = Character(s.clazz, s.level, 0, CharacterSkills(s.level)),
                    targetStats = TargetStats(emptyList()),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = true,
                    useSublimations = true,
                    damageScenario = s.scenario
                )
            val t0 = System.nanoTime()
            val fast =
                WakfuBuildSolver.certifierFastLedgerForTest(
                    params,
                    fullEpicPool(s.level),
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    applyDomination = true
                )
            val ms = (System.nanoTime() - t0) / 1_000_000
            val bailed = fast.filterValues { it < 0 }.keys.toSortedSet()
            println("CERT_SHAPE_AUDIT ${s.name} totalMs=$ms bailed=$bailed cells=${fast.toSortedMap()}")
        }
    }

    /**
     * plan §2.2 bound-layer audit: build the production max-damage model, solve to a good incumbent, and for every
     * tracked objective-chain var print `declaredHi / valueAtIncumbent` (looseness). The layer with the biggest
     * ratio is where the LP relaxation has the most room to "float" — the surgery target for a dual-side cut
     * (E2/E6/E9). This systematizes how the §A4 conversion double-bank (+41 % Graw) was found by hand.
     * `WAKFU_BOUND_AUDIT=1`; level via `WAKFU_BOUND_AUDIT_LEVEL` (default 245 = F2), det via `WAKFU_BOUND_AUDIT_DET`
     * (default 800), workers via `WAKFU_BOUND_AUDIT_WORKERS` (default 8, for a fast incumbent). CSV to
     * `WAKFU_BOUND_AUDIT_OUT`.
     */
    @Test
    @Tag("manual")
    fun `manual bound-layer audit`() {
        assumeTrue(System.getenv("WAKFU_BOUND_AUDIT") == "1")
        val level = System.getenv("WAKFU_BOUND_AUDIT_LEVEL")?.toIntOrNull() ?: 245
        val det = System.getenv("WAKFU_BOUND_AUDIT_DET")?.toDoubleOrNull() ?: 800.0
        val workers = System.getenv("WAKFU_BOUND_AUDIT_WORKERS")?.toIntOrNull() ?: 8
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)
        val tuning =
            WakfuBuildSolver.SolverTuning(
                numSearchWorkers = workers,
                maxDeterministicTime = det,
                maxDamageExperiment = MaxDamageExperimentConfig.DEFAULT
            )
        val bounds =
            WakfuBuildSolver.maxDamageVarBoundsForTest(
                params,
                pool,
                tuning,
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                tightDomains = true,
                applyDomination = true
            )

        fun ratio(b: WakfuBuildSolver.MaxDamageVarBound): Double =
            if (b.value > 0L) {
                b.hi.toDouble() / b.value
            } else if (b.hi > 0L) {
                Double.POSITIVE_INFINITY
            } else {
                1.0
            }
        // The per-hit → per-turn objective chain, coarse to fine (name suffix = the element mastery char).
        val chainPrefixes =
            listOf(
                "dmgM_",
                "dmgCriticalMastery_",
                "dmgCrit_",
                "dmgDI_",
                "dmgD_",
                "dmgDiff_",
                "dmgCritTerm_",
                "dmgGraw_",
                "dmgScore_",
                "perHitScaled_",
                "rotRaw_",
                "rotDamage_"
            )
        println("BOUND_AUDIT lvl$level det$det workers$workers — objective chain (declaredHi / valueAtIncumbent):")
        for (prefix in chainPrefixes) {
            bounds.filter { it.name.startsWith(prefix) }.forEach {
                println("  BOUND_AUDIT chain ${"%-8.2f".format(ratio(it))}x ${it.name} value=${it.value} hi=${it.hi}")
            }
        }
        println("BOUND_AUDIT top-20 loosest tracked vars overall:")
        bounds
            .sortedByDescending { ratio(it) }
            .take(20)
            .forEach { println("  BOUND_AUDIT loose ${"%-8.2f".format(ratio(it))}x ${it.name} value=${it.value} hi=${it.hi}") }
        System.getenv("WAKFU_BOUND_AUDIT_OUT")?.let { path ->
            java.io.File(path).writeText(
                "ratio,name,value,hi\n" +
                    bounds
                        .sortedByDescending { ratio(it) }
                        .joinToString("\n") { "${"%.4f".format(ratio(it))},${it.name},${it.value},${it.hi}" }
            )
        }
    }

    /**
     * plan E8 measurement — the "DP-solvable fraction". The certificate DP is a sound upper bound whose argmax
     * cell, IF its winning ITEMS re-solve to the cell bound, is a constructive proof of the optimum (no CP-SAT →
     * DP-seconds instead of CP-SAT-minutes). Measured WITHOUT the structured-provenance seam: run the ledger
     * (exact all-cells), backtrack the argmax cell's provenance item NAMES, restrict the pool to those items,
     * re-solve pinned to the cell's AP (CP-SAT freely re-derives runes/subs/skills), and compare the re-solved
     * objective (same scale as the ledger cell bound) to the bound. `reOpt == bound` ⇒ the DP's item set IS the
     * optimum for that shape. Provenance parsing is best-effort, so a name miss can only UNDER-count (the measured
     * fraction is a lower bound). `WAKFU_E8=1`; `WAKFU_E8_LEVEL` (245).
     */
    @Test
    @Tag("manual")
    fun `manual E8 DP-solvable measurement`() {
        assumeTrue(System.getenv("WAKFU_E8") == "1")
        val level = System.getenv("WAKFU_E8_LEVEL")?.toIntOrNull() ?: 245
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)
        val runes = WakfuBestBuildFinderAlgorithm.runes
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        val outFile = System.getenv("WAKFU_E8_OUT")?.let { java.io.File(it) }

        fun emit(s: String) {
            println(s)
            outFile?.appendText(s + "\n")
        }
        // WAKFU_E8_INCUMBENT prunes cells that can't beat it (sound — the argmax SURVIVOR is still exacted tightly),
        // which makes the lvl-245 all-cells exact pass tractable. No incumbent ⇒ forceTier2All (exact every cell).
        val incumbent = System.getenv("WAKFU_E8_INCUMBENT")?.toLongOrNull()
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                runes,
                subs,
                applyDomination = true,
                incumbentObjective = incumbent,
                forceTier2All = incumbent == null,
                threads = System.getenv("WAKFU_E8_THREADS")?.toIntOrNull() ?: 1
            )
        val argmax =
            ledger.cellObjectives.entries
                .filter { it.value >= 0 }
                .maxByOrNull { it.value }
        if (argmax == null) {
            emit("E8 lvl$level — no argmax (bailed=${ledger.bailedCells})")
            return
        }
        val cell = argmax.key
        val bound = argmax.value
        emit("E8 lvl$level argmaxCell=$cell bound=$bound bailed=${ledger.bailedCells.toSortedSet()}")
        val prov = WakfuBuildSolver.certifierExplainForTest(params, pool, runes, subs, applyDomination = true, cell = cell)
        prov.forEach { println("E8_PROV $it") }
        val labels =
            prov
                .filter { it.startsWith("slot:") }
                .mapNotNull { Regex("""^slot:[^:]*: (.+?)\s+\(di\+""").find(it)?.groupValues?.get(1) }
                .flatMap { it.split(" + ") }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        val restricted =
            pool
                .mapValues { (_, items) -> items.filter { it.name.en.lowercase() in labels || it.name.fr.lowercase() in labels } }
                .filterValues { it.isNotEmpty() }
        val matched = restricted.values.sumOf { it.size }
        emit("E8 parsedLabels(${labels.size}) matchedItems=$matched labels=$labels")
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params.copy(maxDamageApTarget = cell),
                restricted,
                runes,
                subs,
                workers = 8,
                seconds = 180.0,
                applyDomination = false,
                deterministicLimit = 120.0
            )
        val reOpt = if (profile.objective != Long.MIN_VALUE) profile.objective else 0L
        val ratio = if (bound > 0) reOpt.toDouble() / bound else 0.0
        emit(
            "E8 RESULT lvl$level cell=$cell bound=$bound reOpt=$reOpt status=${profile.status} " +
                "ratio=${"%.4f".format(ratio)} DP_SOLVABLE=${ratio >= 0.999}"
        )
    }

    /**
     * plan E8 — the fast-path seam (`WakfuBuildSolver.dpConstructProvenOptimum`) end-to-end: run the DP, backtrack the
     * argmax items, re-solve the tiny restricted pool, accept ONLY when the re-solved proxy reaches the DP bound AND
     * the build is `isValid()` (⇒ proven global optimum). Asserts the seam FIRES: a non-null `isOptimal` result whose
     * proxy ≥ the DP bound. Since the sub-carrier socket-budget fix (isValid no longer double-books a normal sub's
     * pattern against runes — golden-rune model, commit 54761dc6), the constructed full-catalog build validates and
     * the seam returns it instead of falling back. `WAKFU_E8_CONSTRUCT=1`.
     */
    @Test
    @Tag("manual")
    fun `manual E8 construct proven optimum`() {
        assumeTrue(System.getenv("WAKFU_E8_CONSTRUCT") == "1")
        val level = System.getenv("WAKFU_E8_LEVEL")?.toIntOrNull() ?: 110
        val incumbent = System.getenv("WAKFU_E8_INCUMBENT")?.toLongOrNull()
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)
        val runes = WakfuBestBuildFinderAlgorithm.runes
        val subs = WakfuBestBuildFinderAlgorithm.sublimations

        val built =
            runBlocking {
                WakfuBuildSolver.dpConstructProvenOptimum(params, pool, runes, subs, incumbentObjective = incumbent)
            }
        println(
            "E8_CONSTRUCT lvl$level fired=${built != null} optimal=${built?.isOptimal} " +
                "proxy=${built?.maxDamageRawProxy ?: built?.maxDamageObjective} valid=${built?.individual?.isValid()}"
        )

        // The seam returns non-null ONLY when the re-solved proxy reaches the DP bound AND the build is valid, so a
        // non-null isOptimal result IS the proof: the DP-constructed build is the proven global optimum. (Asserting
        // isOptimal subsumes an independent `proxy ≥ bound` check without a second — expensive — ledger pass; the
        // full cross-check `proxy == bound == 1,310,980` was confirmed once when the seam first fired.)
        assertThat(built).describedAs("dpConstructProvenOptimum fires (constructed build is valid + reaches the bound)").isNotNull
        assertThat(built!!.isOptimal).isTrue()
        assertThat(built.individual.isValid()).isTrue()
    }

    /**
     * E8 item A **Phase 2** soundness lock (perf win: the badge's exact pass captures each cell's winning
     * (world, crit-step) for free, so the fast-path skips the ~minutes N-worlds re-scan). The one invariant is
     * EQUIVALENCE: replaying the cached [CellProvenance] must produce byte-identical items to the full scan for
     * every exactly-confirmed cell — otherwise the fast-path would restrict to a different pool. Also asserts the
     * map only ever keys tier-2 cells (a fast/tier-1.5 cell has no exact provenance, so the fast-path falls back).
     */
    @Test
    fun `certificate cell provenance replays the same backtrack as the full world scan`() {
        val (params, pool, subs) = couplingPanel()

        // Run for both worker counts so BOTH exactForCells paths are covered: threads=1 = the serial (production)
        // capture; threads=2 = the parallel (cell × world) capture. Both must pick the same argmax world/c as the
        // full scan, and must agree with each other (determinism).
        val ledgers =
            listOf(1, 2).associateWith { threads ->
                // forceTier2All confirms every non-bailed cell exactly, so provenance is captured for each.
                WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, forceTier2All = true, threads = threads)
            }

        assertThat(ledgers.getValue(1).cellProvenance)
            .describedAs("serial and parallel captures agree on provenance (determinism)")
            .isEqualTo(ledgers.getValue(2).cellProvenance)

        for ((threads, ledger) in ledgers) {
            assertThat(ledger.cellProvenance).describedAs("threads=$threads: some cell was exactly confirmed, so it carries provenance").isNotEmpty
            assertThat(ledger.tier2Cells)
                .describedAs("threads=$threads: provenance is only ever captured for exactly-confirmed (tier-2) cells")
                .containsAll(ledger.cellProvenance.keys)

            // The argmax cell is the one the E8 fast-path actually replays; it must carry provenance AND real items.
            val argmaxCell =
                ledger.cellObjectives.entries
                    .filter { it.value >= 0 }
                    .maxByOrNull { it.value }!!
                    .key
            assertThat(ledger.cellProvenance).describedAs("threads=$threads: the argmax cell carries captured provenance").containsKey(argmaxCell)

            // The invariant: the cheap replay equals the full N-worlds scan, item-for-item, for EVERY captured cell.
            // (A high-AP cell infeasible with this tiny pool is confirmed at 0 and backtracks to no items — the equivalence
            // still holds; only the argmax cell, which E8 uses, is asserted to reach real items.)
            for ((cell, prov) in ledger.cellProvenance) {
                val viaScan =
                    WakfuBuildSolver.certifierExplainItemIdsForTest(params, pool, sublimations = subs, applyDomination = false, cell = cell)
                val viaProvenance =
                    WakfuBuildSolver.certifierExplainItemIdsFromProvenanceForTest(
                        params,
                        pool,
                        sublimations = subs,
                        applyDomination = false,
                        cell = cell,
                        provenance = prov
                    )
                assertThat(viaProvenance)
                    .describedAs("threads=$threads cell $cell: provenance replay (world=${prov.worldIndex} c=${prov.c}) matches the full scan")
                    .isEqualTo(viaScan)
            }
            assertThat(
                WakfuBuildSolver.certifierExplainItemIdsFromProvenanceForTest(
                    params,
                    pool,
                    sublimations = subs,
                    applyDomination = false,
                    cell = argmaxCell,
                    provenance = ledger.cellProvenance.getValue(argmaxCell)
                )
            ).describedAs("threads=$threads: the argmax cell backtracks to real items via its captured provenance").isNotEmpty
        }
    }

    /**
     * Exact-pass c-loop pruning soundness lock (two-tier speed): on the production badge path, tier-1.5's
     * step-1 per-crit-step harvest rows are threaded into the exact pass, which seeds its c-loop with the
     * argmax-bound step and SKIPS steps whose bound is strictly below the best so far. The invariant is
     * BYTE-IDENTITY: the pruned ledger must equal the unpruned one — same per-cell values AND the same winning
     * (world, crit-step) provenance — pruning may only ever change how many DPs run. Each fixture is driven at
     * `incumbent = argmax − 1` (the rescue shape: the argmax survivor cannot be cleared by tier-1.5, so its
     * exact pass runs WITH the free bounds). Locked over the coupling panel (conversion / Critical-Secret /
     * budget-sub worlds) plus seeded fuzz shapes, and the pruning must actually FIRE.
     */
    @Test
    fun `exact c-loop pruning is value- and provenance-identical to the unpruned loop`() {
        val fixtures =
            buildList {
                add(couplingPanel())
                for (iteration in 0 until 4) add(fuzzScenario(iteration))
            }
        CertifierTuning.cPruneSkippedForTest.set(0)
        try {
            for ((i, fixture) in fixtures.withIndex()) {
                val (params, pool, subs) = fixture
                // Reference max (the oracle path is unpruned by design — no tier-1.5 bounds there).
                val reference =
                    WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, forceTier2All = true)
                val max = reference.maxCellObjective ?: continue
                if (max <= 1L) continue // degenerate fixture — no exact work to prune
                val incumbent = max - 1 // the rescue shape: the argmax survivor must be confirmed exactly
                CertifierTuning.cLoopPruneEnabled = true
                val pruned =
                    WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, incumbentObjective = incumbent)
                CertifierTuning.cLoopPruneEnabled = false
                val plain =
                    WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, incumbentObjective = incumbent)
                // CertLedger is a value type: this compares every cell bound, tier sets AND cellProvenance
                // (the winning world/crit-step per exact cell) in one shot.
                assertThat(pruned).describedAs("fixture $i: the pruned ledger is byte-identical to the plain one").isEqualTo(plain)
                assertThat(pruned.tier2Cells).describedAs("fixture $i: the rescue shape exercises the exact tier").isNotEmpty
            }
        } finally {
            CertifierTuning.cLoopPruneEnabled = true
        }
        assertThat(CertifierTuning.cPruneSkippedForTest.get())
            .describedAs("the pruning must actually skip crit steps somewhere across the fixtures (else this lock is vacuous)")
            .isGreaterThan(0L)
    }

    /**
     * Tier-1.5 segment-skip soundness lock (floor speed): tier-1's step-8 per-(cell, crit-step) bounds let
     * tier-1.5 SKIP every step-1 segment that provably cannot cross the incumbent (step-1 ≤ step-8 per c) —
     * the badge floor's dominant cost. The skip is DECISION-identical, not byte-identical: a cleared cell may
     * record the (sound, looser) step-8 bound instead of the step-1 one. So the lock asserts what the badge
     * actually depends on, for BOTH the won-badge (incumbent = optimum) and lost-badge (incumbent just below)
     * shapes: same tier-2 cells with the same exact values and provenance, the same badge verdict, every
     * cleared survivor still `≤ incumbent`, the sandwich `cellObjective ≤ fast` everywhere — and the skip FIRES.
     */
    @Test
    fun `tier-15 segment skip is decision-identical to the full step-1 pass`() {
        val fixtures =
            buildList {
                add(couplingPanel())
                for (iteration in 0 until 4) add(fuzzScenario(iteration))
            }
        CertifierTuning.tier15SegmentsSkippedForTest.set(0)
        try {
            for ((i, fixture) in fixtures.withIndex()) {
                val (params, pool, subs) = fixture
                val reference =
                    WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, forceTier2All = true)
                val max = reference.maxCellObjective ?: continue
                if (max <= 1L) continue
                for (incumbent in listOf(max, max - 1)) { // won-badge and lost-badge shapes
                    CertifierTuning.tier15SegmentSkipEnabled = true
                    val skipped =
                        WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, incumbentObjective = incumbent)
                    CertifierTuning.tier15SegmentSkipEnabled = false
                    val full =
                        WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, incumbentObjective = incumbent)

                    val label = "fixture $i incumbent=$incumbent"
                    assertThat(skipped.tier2Cells).describedAs("$label: same exactly-confirmed cells").isEqualTo(full.tier2Cells)
                    for (cell in full.tier2Cells) {
                        assertThat(skipped.cellObjectives[cell]).describedAs("$label cell $cell: same exact value").isEqualTo(full.cellObjectives[cell])
                    }
                    assertThat(skipped.cellProvenance).describedAs("$label: same winning provenance").isEqualTo(full.cellProvenance)
                    assertThat(skipped.maxCellObjective!! <= incumbent)
                        .describedAs("$label: same badge verdict")
                        .isEqualTo(full.maxCellObjective!! <= incumbent)
                    // Soundness sandwich: every recorded bound stays ≤ its fast ceiling, and a cell NOT
                    // exactly confirmed (cleared / eliminated) stays ≤ the incumbent in both runs.
                    for ((cell, obj) in skipped.cellObjectives) {
                        assertThat(obj).describedAs("$label cell $cell: bound ≤ fast").isLessThanOrEqualTo(skipped.fastObjectives.getValue(cell))
                        if (cell !in skipped.tier2Cells) {
                            assertThat(obj).describedAs("$label cell $cell: non-exact cell cleared under the incumbent").isLessThanOrEqualTo(incumbent)
                        }
                    }
                }
            }
        } finally {
            CertifierTuning.tier15SegmentSkipEnabled = true
        }
        assertThat(CertifierTuning.tier15SegmentsSkippedForTest.get())
            .describedAs("the segment skip must actually fire somewhere across the fixtures (else this lock is vacuous)")
            .isGreaterThan(0L)
    }

    /**
     * E10 single-flight lock: concurrent [MaxDamageCertificateCache.certificate] calls for the SAME shape run
     * exactly ONE compute — the rest wait on the in-flight latch and reconstruct from the merged entry. This is
     * what lets the post-search proof JOIN the search-time warm-up instead of duplicating a minutes-long DP.
     */
    @Test
    fun `certificate cache single-flights concurrent computes of the same shape`() {
        MaxDamageCertificateCache.clear()
        MaxDamageCertificateCache.computeCountForTest.set(0)
        val (params, pool, subs) = couplingPanel()
        val callers = 4
        val poolExec =
            java.util.concurrent.Executors
                .newFixedThreadPool(callers)
        val ledgers =
            try {
                poolExec
                    .invokeAll(
                        (1..callers).map {
                            java.util.concurrent.Callable {
                                MaxDamageCertificateCache.certificate(
                                    params,
                                    pool,
                                    emptyList(),
                                    subs,
                                    applyDomination = false,
                                    incumbentObjective = null,
                                    threads = 1
                                )
                            }
                        }
                    ).map { it.get() }
            } finally {
                poolExec.shutdown()
            }
        assertThat(ledgers).describedAs("every concurrent caller gets a ledger").doesNotContainNull()
        assertThat(ledgers.toSet()).describedAs("all callers see the same ledger").hasSize(1)
        assertThat(MaxDamageCertificateCache.computeCountForTest.get())
            .describedAs("the DP ran exactly once — the other callers single-flighted onto it")
            .isEqualTo(1L)
        MaxDamageCertificateCache.clear()
    }

    /**
     * E10 warm-up wiring lock: a PRODUCTION-path (tuning == null) single-element max-damage search launches the
     * background certificate warm-up off its first streamed incumbent, so the cache is populated by the time the
     * post-search proof asks — and an ineligible shape (forced runes) launches nothing. Asserts wiring only
     * (job launched, one compute, cache populated), not solve results, so it stays CI-stable.
     */
    @Test
    fun `production search warms the certificate cache in the background`() {
        MaxDamageCertificateCache.clear()
        MaxDamageCertificateCache.computeCountForTest.set(0)
        MaxDamageSearch.warmupJobForTest.set(null)
        val (params, pool, subs) = couplingPanel()
        try {
            // Eligible shape, production path (tuning == null, short wall-clock budget on the tiny panel).
            runBlocking {
                MaxDamageSearch.run(params.copy(searchDuration = 2.seconds), pool, emptyList(), subs, tuning = null).toList()
                MaxDamageSearch.warmupJobForTest.get()?.join()
            }
            assertThat(MaxDamageSearch.warmupJobForTest.get()).describedAs("the warm-up launched off the first incumbent").isNotNull
            assertThat(MaxDamageCertificateCache.size).describedAs("the warm-up populated the certificate cache").isEqualTo(1)
            assertThat(MaxDamageCertificateCache.computeCountForTest.get()).describedAs("exactly one warm-up compute").isEqualTo(1L)

            // Ineligible shape (forced runes ⇒ the proof never consults the certificate) launches nothing.
            MaxDamageSearch.warmupJobForTest.set(null)
            runBlocking {
                MaxDamageSearch
                    .run(params.copy(searchDuration = 2.seconds, forcedRunes = listOf("R")), pool, emptyList(), subs, tuning = null)
                    .toList()
            }
            assertThat(MaxDamageSearch.warmupJobForTest.get()).describedAs("an ineligible shape launches no warm-up").isNull()
        } finally {
            MaxDamageCertificateCache.clear()
            MaxDamageSearch.warmupJobForTest.set(null)
        }
    }

    /**
     * plan §1.2 / §5 reusable **experiment A/B** under the canonical protocol (1 worker + `interleaveSearch`, fixed
     * seed, det sweep, trajectory-vs-trajectory). Variants are named [MaxDamageExperimentConfig]s, so one harness
     * drives the §1.4 belief re-screens (`perApRotRawCut`, `certifierCellCap`, `dGrawCutoff`) and future model cuts
     * (E6/E2 — add a registry entry) without a rebuild. `WAKFU_EXP_AB=1`;
     * `WAKFU_EXP_AB_VARIANTS=baseline,perApRotRaw`; `WAKFU_EXP_AB_LEVEL` (110); `WAKFU_EXP_AB_DET` (200,400,800);
     * `WAKFU_EXP_AB_CONSTRAINED=1` for the F4 hard leg; `WAKFU_EXP_AB_REPEATS` (1); CSV via `WAKFU_EXP_AB_OUT`.
     * Verdict = a variant's `bound` (lower = tighter) below baseline at most checkpoints, status no worse.
     */
    @Test
    @Tag("manual")
    fun `manual max-damage experiment ab`() {
        assumeTrue(System.getenv("WAKFU_EXP_AB") == "1")
        val level = System.getenv("WAKFU_EXP_AB_LEVEL")?.toIntOrNull() ?: 110
        val dets =
            System
                .getenv("WAKFU_EXP_AB_DET")
                ?.split(',')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(200.0, 400.0, 800.0)
        val repeats = System.getenv("WAKFU_EXP_AB_REPEATS")?.toIntOrNull() ?: 1
        val constrained = System.getenv("WAKFU_EXP_AB_CONSTRAINED") == "1"
        val base = MaxDamageExperimentConfig.DEFAULT
        val registry =
            linkedMapOf(
                "baseline" to base,
                "perApRotRaw" to base.copy(perApRotRawCut = true),
                "critBand" to base.copy(critBandDisjunction = true),
                "perApRaw+critBand" to base.copy(perApRotRawCut = true, critBandDisjunction = true),
                "certCellCap" to base.copy(certifierCellCap = true),
                "dGrawCutoff" to base.copy(dGrawCutoff = true),
                "apCeiling" to base.copy(apCeiling = true),
                "convConservation" to base.copy(conversionConservationCut = true),
                "critDiff" to base.copy(critDiffJointBound = true)
            )
        val variants =
            System
                .getenv("WAKFU_EXP_AB_VARIANTS")
                ?.split(',')
                ?.map { it.trim() }
                ?.let { wanted -> registry.filterKeys { it in wanted } }
                ?: registry
        val params0 = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val params =
            if (constrained) {
                params0.copy(
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.ACTION_POINT, 11),
                                TargetStat(Characteristic.MOVEMENT_POINT, 4),
                                TargetStat(Characteristic.RANGE, 4)
                            )
                        )
                )
            } else {
                params0
            }
        val pool = fullEpicPool(level)
        val shape = if (constrained) "pa11pm4po4" else "free"
        repeat(repeats) { rep ->
            for (det in dets) {
                for ((name, exp) in variants) {
                    val profile =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            params,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            workers = 1,
                            seconds = 3000.0,
                            applyDomination = true,
                            experiment = exp,
                            deterministicLimit = det,
                            interleave = true,
                            hardConstraints = constrained
                        )
                    val line =
                        "EXP_AB rep$rep lvl$level $shape det$det $name status=${profile.status} obj=${profile.objective} " +
                            "bound=${profile.bestBound} det=${profile.deterministicTime}"
                    println(line)
                    System.getenv("WAKFU_EXP_AB_OUT")?.let { java.io.File(it).appendText(line + "\n") }
                }
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage certifier provenance`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_CERT_EXPLAIN") != null)

        // PROVENANCE: backtrack the winning certificate state of one AP cell into its concrete composition
        // (items, subs, skill allocation, crit-budget cover). The decisive diagnostic: if the composition is
        // constructible, it is a REAL better build (feed it to the hunt); if not, the constraint it violates
        // is the next certificate cut.
        val cell = System.getenv("WAKFU_MAX_DAMAGE_CERT_EXPLAIN")!!.toInt()
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val lines =
            WakfuBuildSolver.certifierExplainForTest(
                params,
                fullEpicPool(level),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true,
                cell = cell
            )
        for (l in lines) println("CERT_EXPLAIN $l")
    }

    @Test
    @Tag("manual")
    fun `manual max-damage provenance build verification`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_PROVENANCE_VERIFY") == "1")

        // Verify a provenance-named composition: restrict the pool to exactly those items and prove the
        // micro-pool's optimum — the composition's true model value (solver still optimizes runes/subs/
        // skills freely, so it may even improve on the certifier's sub picks).
        val names =
            setOf(
                "harmonie ancestrale",
                "emblème du vil ii",
                "les rouages du temps",
                "bottes de riebeck",
                "torse funeste",
                "l'accapée ancestrale",
                "couronne du roi seuleil",
                "bébé pandawa",
                "mulou féroxe",
                "tour hyste",
                "epée de brâkmar",
                "coiffeuse mortelle",
                "anneau chuchotis ancestral"
            )
        val cell = System.getenv("WAKFU_MAX_DAMAGE_AP_TARGET")?.toIntOrNull() ?: 16
        val pool =
            fullEpicPool(245)
                .mapValues { (_, items) -> items.filter { it.name.fr.lowercase() in names } }
                .filterValues { it.isNotEmpty() }
        val params = fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true, maxDamageApTarget = cell)
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params,
                pool,
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                workers = 8,
                seconds = 300.0,
                applyDomination = false,
                deterministicLimit = 120.0
            )
        println("PROVENANCE_VERIFY cell=$cell $profile")

        // Axis-level diff against the certificate state: solve the same micro pool with the streaming
        // solver and print the proven build's composition + resolved stats (c / DI / M / K / MP / graw),
        // so the certificate's claimed axes can be compared joint-vs-joint, not just objective-vs-objective.
        val results =
            runBlocking {
                WakfuBuildSolver
                    .optimize(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        WakfuBuildSolver.SolverTuning(maxDeterministicTime = 120.0)
                    ).toList()
            }
        val best = results.lastOrNull { it.isOptimal } ?: results.last()
        val build = best.individual
        val scenario = params.damageScenario
        for (e in build.equipments.sortedBy { it.itemType.name }) {
            val runeList =
                build.runes[e]
                    .orEmpty()
                    .groupingBy { it.characteristic.name }
                    .eachCount()
            val subList = build.sublimations[e].orEmpty().map { "${it.name.en}(${it.rarity})" }
            println("PROVENANCE_BUILD item ${e.itemType} ${e.name.fr} [${e.rarity} lvl${e.level} sockets=${e.maxShardSlots}] runes=$runeList subs=$subList")
        }
        for (skill in build.characterSkills.allCharacteristic.filter { it.pointsAssigned > 0 }) {
            println("PROVENANCE_BUILD skill ${skill.name} = ${skill.pointsAssigned}")
        }
        val stats =
            computeCharacteristicsValues(
                build,
                params.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(scenario.element.masteryCharacteristic to 1),
                resistanceElementsWanted = emptyMap(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                damageScenario = scenario
            )

        fun v(c: Characteristic) = stats[c] ?: 0
        val masteryBase = v(scenario.element.masteryCharacteristic) + v(scenario.rangeBand.masteryCharacteristic)
        val m = 100L + masteryBase
        val k = v(Characteristic.MASTERY_CRITICAL).toLong()
        val c = v(Characteristic.CRITICAL_HIT).coerceIn(0, 100)
        val di = 100L + v(Characteristic.DAMAGE_INFLICTED)
        val graw = (400L + c) * m + 5L * c * k
        println(
            "PROVENANCE_BUILD axes optimal=${best.isOptimal} ap=${v(Characteristic.ACTION_POINT)} mp=${v(Characteristic.MOVEMENT_POINT)} " +
                "crit=$c fireM=${v(scenario.element.masteryCharacteristic)} distM=${v(scenario.rangeBand.masteryCharacteristic)} " +
                "M=$m K=$k D=$di graw=$graw perHitProxy=${graw * di}"
        )
    }

    @Test
    @Tag("manual")
    fun `manual max-damage portfolio composition matrix`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_PORTFOLIO") == "1")

        // Same production model; vary ONLY the worker-portfolio composition (parameter-only, soundness-safe).
        // Motivated by the measured profile: the proof is pure B&B (lpIterations=0 reported), cracked by
        // portfolio DIVERSITY (8w ~8k branches vs 1w 2.7M) — so which subsolvers actually earn their core?
        // `logsearch` dumps CP-SAT's per-subsolver tables to settle it (and the lpIterations anomaly).
        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 300.0
        val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 8
        val detLimit = System.getenv("WAKFU_MAX_DAMAGE_DETTIME")?.toDoubleOrNull() ?: 600.0
        val repeats = System.getenv("WAKFU_MAX_DAMAGE_REPEATS")?.toIntOrNull() ?: 1
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)

        data class Portfolio(
            val logSearch: Boolean = false,
            val sharedTree: Int? = null,
            val ignore: List<String> = emptyList(),
            val extra: List<String> = emptyList(),
            val interleave: Boolean = false,
            val fullSubsolvers: Int? = null,
            val detectProduct: Boolean = false,
        )
        val variants =
            listOf(
                "baseline" to Portfolio(),
                "logsearch" to Portfolio(logSearch = true),
                "sharedTree4" to Portfolio(sharedTree = 4),
                "sharedTree8" to Portfolio(sharedTree = 8),
                "noLp" to Portfolio(ignore = listOf("default_lp", "max_lp", "reduced_costs")),
                "extraQr" to Portfolio(extra = listOf("quick_restart_no_lp", "quick_restart")),
                "interleave" to Portfolio(interleave = true),
                // At 8 workers CP-SAT reserves 2 for primal-only heuristics (feasibility_jump/LNS) — useless
                // here where the incumbent lands almost instantly and ALL the cost is dual-bound descent.
                "fullSub8" to Portfolio(fullSubsolvers = 8),
                // detect_linearized_product defaults false while RLT (product) cuts default true — the cuts
                // designed for a bilinear D·Graw objective likely never fire on the hand-rolled encoding.
                "detectProd" to Portfolio(detectProduct = true),
                "fullSub8+detectProd" to Portfolio(fullSubsolvers = 8, detectProduct = true),
                // Dual-bound-dedicated subsolvers never scheduled at 8 workers by default.
                "lbSearch" to Portfolio(extra = listOf("objective_lb_search", "lb_tree_search"), fullSubsolvers = 8)
            )
        val selectedNames =
            System
                .getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_NAMES")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: setOf("baseline", "sharedTree4", "noLp", "extraQr")
        val selected = variants.filter { (name, _) -> name in selectedNames }
        require(selected.isNotEmpty()) { "No portfolio variants selected. Available: ${variants.joinToString { it.first }}" }

        repeat(repeats) { rep ->
            for ((name, p) in selected) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = workers,
                        seconds = seconds,
                        applyDomination = true,
                        deterministicLimit = detLimit,
                        logSearch = p.logSearch,
                        sharedTreeWorkers = p.sharedTree,
                        ignoreSubsolvers = p.ignore,
                        extraSubsolvers = p.extra,
                        interleave = p.interleave,
                        numFullSubsolvers = p.fullSubsolvers,
                        detectLinearizedProduct = p.detectProduct
                    )
                println("MAX_DAMAGE_PORTFOLIO rep$rep lvl$level $name $profile")
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage worker-count sweep`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_WORKER_SWEEP") == "1")

        // The rune+sub proof is cracked by CP-SAT's multi-worker portfolio + cross-worker clause sharing (8
        // workers prove what 4 cannot — it's diversity, not raw parallelism). OPEN QUESTION: does OVERSUBSCRIBING
        // past cores-1 (e.g. 16/24 workers on a 10-core host) buy MORE portfolio diversity → a faster wall-clock
        // proof, even though each worker runs slower? Wall-mode (production's mode) + wall-time-to-OPTIMAL is the
        // verdict (det-time is not comparable across worker counts; it sums per worker). Production caps at cores-1
        // to leave the UI a core, so a win here is actionable only for headless/CLI — but the curve is worth knowing.
        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 300.0
        val repeats = System.getenv("WAKFU_MAX_DAMAGE_REPEATS")?.toIntOrNull() ?: 3
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val workerList =
            System
                .getenv("WAKFU_MAX_DAMAGE_WORKER_LIST")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(8, 12, 16, 24)
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)

        repeat(repeats) { rep ->
            for (w in workerList) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = w,
                        seconds = seconds,
                        applyDomination = true,
                        deterministicLimit = null
                    )
                println("MAX_DAMAGE_WORKER_SWEEP rep$rep lvl$level workers=$w $profile")
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage solver-mode comparison`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_SOLVER_MODE") == "1")

        // PRODUCTION QUESTION: production (tuning==null, WakfuBuildSolver.kt:~1941) bounds the solve with a
        // WALL-clock limit (maxTimeInSeconds) only; the fast @slow proof tests bound it with a DETERMINISTIC-time
        // limit (maxDeterministicTime). The audit note claims deterministic mode reaches OPTIMAL in ~8× less
        // *deterministic* work — but does that translate to less WALL time? If yes, production should also set a
        // (high) maxDeterministicTime to flip CP-SAT into the faster parallel mode while keeping its real wall cap.
        // VERDICT METRIC HERE = wallTimeSec at status==OPTIMAL (det-time is NOT comparable across the two modes).
        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 300.0
        val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 8
        val repeats = System.getenv("WAKFU_MAX_DAMAGE_REPEATS")?.toIntOrNull() ?: 3
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val params = fireMaxDamageParams(level).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(level)

        // det-mode uses a generous det budget that never binds before OPTIMAL (proof closes ~134-250 det @245);
        // both share the same 300s wall safety cap so a non-proving run can't hang.
        val modes = listOf("wall-mode" to null, "det-mode" to 100_000.0)
        repeat(repeats) { rep ->
            for ((name, detLimit) in modes) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = workers,
                        seconds = seconds,
                        applyDomination = true,
                        deterministicLimit = detLimit
                    )
                println("MAX_DAMAGE_SOLVER_MODE rep$rep lvl$level $name $profile")
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage solver-parameter matrix`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_SOLVER_PARAMS") == "1")

        // Same binary model as production; vary ONLY CP-SAT solver params. Soundness-safe by construction
        // (CP-SAT proves the same optimum regardless), so the only verdict is deterministic-time-to-OPTIMAL.
        // Screen on level 110 (faster, ~19s) then confirm winners on 245. Verdict metric = `deterministicTime`
        // in the printed profile when status == OPTIMAL; bestBound is the dual bound (an UPPER bound on the max
        // objective — lower = tighter), the metric to watch converge over the det sweep at 1 worker.
        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 300.0
        val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 8
        // WAKFU_MAX_DAMAGE_DETTIME accepts a comma-separated SWEEP: each variant is run once per det budget, so a
        // single invocation traces the dual-bound trajectory over det-time (reproducible at 1 worker + fixed seed).
        val detLimits =
            System
                .getenv("WAKFU_MAX_DAMAGE_DETTIME")
                ?.split(',')
                ?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: listOf(600.0)
        val repeats = System.getenv("WAKFU_MAX_DAMAGE_REPEATS")?.toIntOrNull() ?: 1
        // WAKFU_MAX_DAMAGE_INTERLEAVE=1 turns on CP-SAT's deterministic interleaved parallel search, making a
        // MULTI-worker solve REPRODUCIBLE (det-to-prove identical across repeats) — so a single baseline-vs-knob
        // pair becomes a definitive, variance-free comparison instead of a noisy distribution.
        val interleave = System.getenv("WAKFU_MAX_DAMAGE_INTERLEAVE") == "1"
        val level = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL")?.toIntOrNull() ?: 245
        val apTarget = System.getenv("WAKFU_MAX_DAMAGE_AP_TARGET")?.toIntOrNull()
        val objectiveCutoff = System.getenv("WAKFU_MAX_DAMAGE_OBJECTIVE_CUTOFF")?.toLongOrNull()
        val params =
            fireMaxDamageParams(level)
                .copy(useRunes = true, useSublimations = true, maxDamageApTarget = apTarget)
        val pool = fullEpicPool(level)

        data class Knobs(
            val symmetryLevel: Int? = null,
            val probingLevel: Int? = null,
            val objectiveShaving: Boolean = false,
            val maxPresolveIterations: Int = 3,
            val linearizationLevel: Int = 2,
            val searchBranching: Int? = null,
            val sharedTreeWorkers: Int? = null,
        )
        val variants =
            listOf(
                "baseline" to Knobs(),
                "linear1" to Knobs(linearizationLevel = 1),
                "linear0" to Knobs(linearizationLevel = 0),
                "probing3" to Knobs(probingLevel = 3),
                "presolve6" to Knobs(maxPresolveIterations = 6),
                "objShaving" to Knobs(objectiveShaving = true),
                "symmetry3" to Knobs(symmetryLevel = 3),
                // search_branching: 5 = PORTFOLIO_WITH_QUICK_RESTART (a portfolio that restarts stuck workers —
                // the only override that doesn't collapse the multi-worker diversity the proof relies on);
                // 2 = PORTFOLIO (plain). Other values (FIXED/LP/PSEUDO_COST) would force one strategy and are
                // expected to regress, so they are not screened.
                "portfolioQuickRestart" to Knobs(searchBranching = 5),
                "portfolio" to Knobs(searchBranching = 2),
                // C4: shared-tree search across some workers (splits the dual-bound proof) — worth a screen on the
                // dual-bound-bound max-damage objective. (The subsolver-list knobs are wired on the seam too but
                // left out of this table as niche; add `extraSubsolvers`/`ignoreSubsolvers` variants ad hoc.)
                "sharedTree4" to Knobs(sharedTreeWorkers = 4)
            ).let { all ->
                // WAKFU_MAX_DAMAGE_VARIANTS=baseline,probing3,... restricts the screen to a focused subset (a fair
                // A/B on a fixed det budget over a handful of variants, rather than paying for all of them).
                System
                    .getenv("WAKFU_MAX_DAMAGE_VARIANTS")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.let { wanted -> all.filter { it.first in wanted } }
                    ?: all
            }

        // C4: an optional CONSTRAINED hard-leg fixture (--pa 11 --pm 4 --po 4 shape) — the bilinear-dual-gap shape
        // C6 targets — enabled with WAKFU_MAX_DAMAGE_CONSTRAINED=1. The binary question that gates C6: does any
        // knob (esp. objShaving, CP-SAT's dual-bound closer) flip this shape's status to OPTIMAL within budget?
        val constrained = System.getenv("WAKFU_MAX_DAMAGE_CONSTRAINED") == "1"
        val runParams =
            if (constrained) {
                params.copy(
                    maxDamageApTarget = null,
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.ACTION_POINT, 11),
                                TargetStat(Characteristic.MOVEMENT_POINT, 4),
                                TargetStat(Characteristic.RANGE, 4)
                            )
                        )
                )
            } else {
                params
            }

        repeat(repeats) { rep ->
            for (detLimit in detLimits) {
                for ((name, k) in variants) {
                    val profile =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            runParams,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            workers = workers,
                            seconds = seconds,
                            applyDomination = true,
                            maxPresolveIterations = k.maxPresolveIterations,
                            linearizationLevel = k.linearizationLevel,
                            deterministicLimit = detLimit,
                            symmetryLevel = k.symmetryLevel,
                            probingLevel = k.probingLevel,
                            objectiveShaving = k.objectiveShaving,
                            searchBranching = k.searchBranching,
                            sharedTreeWorkers = k.sharedTreeWorkers,
                            objectiveCutoff = objectiveCutoff,
                            hardConstraints = constrained,
                            interleave = interleave
                        )
                    val shape = if (constrained) "pa11pm4po4" else "ap=${apTarget ?: "free"}"
                    val line =
                        "MAX_DAMAGE_SOLVER_PARAM rep$rep lvl$level $shape det$detLimit w$workers il${if (interleave) 1 else 0} $name " +
                            "status=${profile.status} obj=${profile.objective} bound=${profile.bestBound} det=${profile.deterministicTime}"
                    println(line)
                    // WAKFU_MAX_DAMAGE_OUT=<path> appends each result line as it completes, so a long sweep is
                    // monitorable live and partial data survives an early stop (the XML only flushes at method end).
                    System.getenv("WAKFU_MAX_DAMAGE_OUT")?.let { java.io.File(it).appendText(line + "\n") }
                }
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage level-245 AP-pinned profile matrix`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_AP_EXPERIMENTS") == "1")

        val seconds = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_SECONDS")?.toDoubleOrNull() ?: 30.0
        val workers = System.getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS")?.toIntOrNull() ?: 2
        val detLimit = System.getenv("WAKFU_MAX_DAMAGE_DETTIME")?.toDoubleOrNull()
        val objectiveCutoff = System.getenv("WAKFU_MAX_DAMAGE_OBJECTIVE_CUTOFF")?.toLongOrNull()
        val apTargets =
            System
                .getenv("WAKFU_MAX_DAMAGE_AP_TARGETS")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: (10..17).toList()
        val params = fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true)
        val pool = fullEpicPool(245)
        val allExperiments =
            listOf(
                "table-baseline" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.TABLE,
                        critProduct = CritProductMode.TABLE
                    ),
                "binary" to MaxDamageExperimentConfig.DEFAULT,
                "perhit-binary" to MaxDamageExperimentConfig(perHitOnlyObjective = true),
                "perhit-table" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.TABLE,
                        critProduct = CritProductMode.TABLE,
                        perHitOnlyObjective = true
                    ),
                "perhit-source-di" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.SOURCE_DI,
                        perHitOnlyObjective = true
                    ),
                "perhit-crit-generic" to
                    MaxDamageExperimentConfig(
                        critProduct = CritProductMode.GENERIC,
                        perHitOnlyObjective = true
                    ),
                "d-binary-only" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.BINARY,
                        critProduct = CritProductMode.TABLE
                    ),
                "crit-binary-only" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.TABLE,
                        critProduct = CritProductMode.BINARY
                    ),
                "crit-generic" to
                    MaxDamageExperimentConfig(
                        dProduct = DProductMode.BINARY,
                        critProduct = CritProductMode.GENERIC
                    ),
                "source-di" to MaxDamageExperimentConfig(dProduct = DProductMode.SOURCE_DI),
                "binary+apCeiling" to MaxDamageExperimentConfig(apCeiling = true),
                "binary+per-ap" to MaxDamageExperimentConfig(perApRotRawCut = true),
                "binary+ring-bound" to MaxDamageExperimentConfig(sameNameRingBound = true),
                "binary+ring+per-ap" to
                    MaxDamageExperimentConfig(
                        sameNameRingBound = true,
                        perApRotRawCut = true
                    ),
                "binary+ap+per-ap" to MaxDamageExperimentConfig(apCeiling = true, perApRotRawCut = true),
                "binary+ring+ap+per-ap" to
                    MaxDamageExperimentConfig(
                        apCeiling = true,
                        sameNameRingBound = true,
                        perApRotRawCut = true
                    ),
                // dGrawCutoff: exact per-D-value Graw disjunction on the cutoff (needs an AP target + objective
                // cutoff). Attacks the D·Graw McCormick looseness directly — the AP16 lock.
                "binary+dgraw" to MaxDamageExperimentConfig(dGrawCutoff = true),
                "binary+dgraw+per-ap" to
                    MaxDamageExperimentConfig(
                        dGrawCutoff = true,
                        perApRotRawCut = true
                    ),
                "binary+ring+dgraw" to
                    MaxDamageExperimentConfig(
                        sameNameRingBound = true,
                        dGrawCutoff = true
                    ),
                "binary+ring+dgraw+per-ap" to
                    MaxDamageExperimentConfig(
                        sameNameRingBound = true,
                        dGrawCutoff = true,
                        perApRotRawCut = true
                    ),
                // dGrawJointBound: constant AM-GM upper bound on perHit = D·Graw via the joint (μ·D + grawLin)
                // reachable sum — cuts the independent-max corner the McCormick exploits. Sound for any build
                // (works without a cutoff), so it can also tighten the free production proof.
                "binary+joint" to MaxDamageExperimentConfig(dGrawJointBound = true),
                "binary+joint+dgraw" to
                    MaxDamageExperimentConfig(
                        dGrawJointBound = true,
                        dGrawCutoff = true
                    ),
                "binary+joint+per-ap" to
                    MaxDamageExperimentConfig(
                        dGrawJointBound = true,
                        perApRotRawCut = true
                    ),
                // AP-cell certifier caps fed back as model constraints (per-cell perHit caps + the constant
                // raw ceiling) — combines with the AP pin + objective cutoff for per-cell INFEASIBLE proofs.
                "binary+certcap" to MaxDamageExperimentConfig(certifierCellCap = true)
            )
        val selectedNames =
            System
                .getenv("WAKFU_MAX_DAMAGE_EXPERIMENT_NAMES")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: setOf("binary")
        val selectedExperiments =
            allExperiments.filter { (name, _) -> name in selectedNames }
        require(selectedExperiments.isNotEmpty()) {
            "No max-damage AP experiments selected. Available: ${allExperiments.joinToString { it.first }}"
        }

        for (ap in apTargets) {
            for ((name, experiment) in selectedExperiments) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params.copy(maxDamageApTarget = ap),
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = workers,
                        seconds = seconds,
                        applyDomination = true,
                        experiment = experiment,
                        deterministicLimit = detLimit,
                        objectiveCutoff = objectiveCutoff
                    )
                println("MAX_DAMAGE_AP_EXPERIMENT AP=$ap $name $profile")
            }
        }
    }

    @Test
    @Tag("manual")
    fun `manual max-damage level-245 reachable range audit`() {
        assumeTrue(System.getenv("WAKFU_MAX_DAMAGE_RANGE_AUDIT") == "1")

        val params = fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true)
        WakfuBuildSolver
            .maxDamageReachableRangesForTest(
                params,
                fullEpicPool(245),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true
            ).filter {
                (
                    it.name.contains("pre_") ||
                        it.name.contains("rand_") ||
                        it.name.contains("dmg") ||
                        it.name.contains("rot") ||
                        it.name.contains("stat_") ||
                        it.name.contains("pct_")
                ) &&
                    !it.name.contains("_gate_") &&
                    !it.name.contains("_idx_")
            }.sortedByDescending { it.span }
            .take(80)
            .forEach { println("MAX_DAMAGE_RANGE ${it.name} ${it.lo}..${it.hi} span=${it.span}") }
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

        // RUNE-AWARE shapes: runes are what the socket-aware scenario-mastery bound (reachableSumDomain's
        // per-carrier socket aggregation) tightens, so a loose solve must fill every socket and still land
        // within the tightened reachable M.
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
                .describedAs("$name: a rune-fed var exceeded its socket-aware reachable bound — the bound cut the optimum")
                .isEmpty()
        }

        // C1 lock: with the ONE conversion sublimation (Unraveling — 100% crit-mastery → elemental mastery under a
        // crit floor), the moved var lands in the mastery term list (+1) AND the crit-mastery term list (−1). C1
        // aggregates those per variable so the declared Graw/mastery reach TIGHTENS (before C1 it double-banked the
        // moved var, inflating the box +41%). This asserts the tightened reach stays a SOUND over-estimate — the
        // freely-solved (loose-domain) value still fits, so an over-tightening regression fails here.
        val conversionPool =
            listOf(
                equipment(1, ItemType.HELMET, "CritMHelm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.MASTERY_CRITICAL to 3000)),
                equipment(2, ItemType.BELT, "CritBelt", mapOf(Characteristic.CRITICAL_HIT to 40)),
                equipment(3, ItemType.RING, "MRing", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            ).groupBy { it.itemType }
        val conversionSub =
            listOf(
                sublimation(
                    9201,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "UnravelForce",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                )
            )
        val conversionParams = fireMaxDamageParams(50).copy(useSublimations = true)
        val conversionBounds = WakfuBuildSolver.maxDamageVarBoundsForTest(conversionParams, conversionPool, tuning, sublimations = conversionSub)
        assertThat(conversionBounds).describedAs("conversion-sub: the objective chain must be tracked").isNotEmpty
        assertThat(conversionBounds.filterNot { it.withinBound })
            .describedAs("conversion-sub: C1's per-variable aggregation over-tightened a reach below the freely-solved value")
            .isEmpty()
    }

    // E2 (plan §4 Tier 1) conversion-conservation clamp-slack cut. C1 nets the CONVERSION `moved` var in the COMBINED
    // mastery/crit reach, but the per-stat `clampSlack` still counts the `−moved` at full domain — crediting a
    // clamp-restoration for a source driven negative by `moved`, unreachable since `moved ∈ [0, percent%·preSubCrit]`.
    // `conversionConservationCut` drops the moved vars from the clampSlack. This locks BOTH halves of the C7 lesson:
    // the cut is NON-VACUOUS (the dmgDiff_/dmgGraw_ reach strictly shrinks) AND SOUND (the freely-solved objective-chain
    // values still fit the tightened box — an under-count = a silently cut optimum, caught by `withinBound`).
    @Test
    fun `E2 conversion-conservation cut tightens the mastery-crit reach and stays sound`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "CritMHelm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.MASTERY_CRITICAL to 3000)),
                equipment(2, ItemType.BELT, "CritBelt", mapOf(Characteristic.CRITICAL_HIT to 40)),
                equipment(3, ItemType.RING, "MRing", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300)),
                // High-mastery but NEGATIVE-crit item: exercises the real gear-driven clamp slack the cut must KEEP
                // (only the conversion `moved` phantom is excluded, not a genuinely reachable negative source).
                equipment(4, ItemType.CAPE, "NegCritCape", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.MASTERY_CRITICAL to -200))
            ).groupBy { it.itemType }
        val conversionSub =
            listOf(
                sublimation(
                    9201,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "UnravelForce",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                )
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        // `on` IS the production DEFAULT (the cut ships ON); `off` explicitly disables just this cut — so the
        // comparison stays valid regardless of DEFAULT's composition (which now already includes the cut).
        val on = MaxDamageExperimentConfig.DEFAULT
        val off = on.copy(conversionConservationCut = false)

        fun reachHi(
            exp: MaxDamageExperimentConfig,
            prefix: String,
        ): Long =
            WakfuBuildSolver
                .maxDamageReachableRangesForTest(params, pool, sublimations = conversionSub, experiment = exp)
                .filter { it.name.startsWith(prefix) }
                .also { assertThat(it).describedAs("$prefix must be tracked").isNotEmpty }
                .maxOf { it.hi }

        // (1) Non-vacuous: the cut strictly tightens BOTH the diff and the graw reach on a conversion-carrying pool.
        assertThat(reachHi(on, "dmgDiff_")).describedAs("E2 must tighten the dmgDiff_ reach").isLessThan(reachHi(off, "dmgDiff_"))
        assertThat(reachHi(on, "dmgGraw_")).describedAs("E2 must tighten the dmgGraw_ reach").isLessThan(reachHi(off, "dmgGraw_"))

        // (2) Sound: with loose guard domains the solver pushes every objective-chain var to its true max; the
        // E2-tightened reach must still contain all of them (an under-count here would silently cut the optimum).
        val bounds =
            WakfuBuildSolver.maxDamageVarBoundsForTest(
                params,
                pool,
                WakfuBuildSolver.SolverTuning(maxDamageExperiment = on),
                sublimations = conversionSub
            )
        assertThat(bounds).describedAs("objective chain must be tracked").isNotEmpty
        assertThat(bounds.filterNot { it.withinBound })
            .describedAs("E2 conversion-conservation cut UNDER-counted a reachable value — it cut the optimum")
            .isEmpty()
    }

    /** Constraint-rich precision request for [clazz] at [level] hitting [targets] — drives the cap/min/average/overflow chain. */
    private fun precisionShape(
        clazz: CharacterClass,
        level: Int,
        targets: List<TargetStat>,
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(clazz, level, 0, CharacterSkills(level)),
            targetStats = TargetStats(targets),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
            useRunes = false,
            useSublimations = false
        )

    /** Shapes spanning specific + generic (multi-element) mastery, resistance, AP/MP/HP/crit and lvl-245 magnitudes. */
    private fun precisionSoundnessShapes(): List<Pair<String, WakfuBestBuildParams>> =
        listOf(
            "cra-mastery-ap-crit-110" to
                precisionShape(
                    CharacterClass.CRA,
                    110,
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1500),
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.CRITICAL_HIT, 40)
                    )
                ),
            "cra-generic-mastery-di-110" to
                precisionShape(
                    CharacterClass.CRA,
                    110,
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY, 2000),
                        TargetStat(Characteristic.DAMAGE_INFLICTED, 100)
                    )
                ),
            "iop-melee-back-hp-110" to
                precisionShape(
                    CharacterClass.IOP,
                    110,
                    listOf(
                        TargetStat(Characteristic.MASTERY_MELEE, 800),
                        TargetStat(Characteristic.MASTERY_BACK, 600),
                        TargetStat(Characteristic.HP, 500)
                    )
                ),
            "cra-mastery-ap-245" to
                precisionShape(
                    CharacterClass.CRA,
                    245,
                    listOf(
                        TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 3000),
                        TargetStat(Characteristic.ACTION_POINT, 14)
                    )
                )
        )

    @Test
    fun `precision reachable bounds hold across classes, targets and levels`() {
        // Precision soundness lock (mirrors the max-damage one): now that the precision stat chain is declared on
        // its reachable domains (tight=true), solve each shape with LOOSE guard domains and check every var's
        // solved value against the tight reachable [lo,hi] the production build DECLARES. A value outside = an
        // interval under-estimate that would silently cut the precision optimum. The tiny high-stat stress pool
        // drives the cap/min/average/overflow chain to end-game magnitudes — stronger than the real pool.
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessStressPool()
        precisionSoundnessShapes().forEach { (name, params) ->
            val bounds = WakfuBuildSolver.precisionVarBoundsForTest(params, pool, tuning)
            assertThat(bounds).describedAs("$name: the precision chain must be tracked").isNotEmpty
            assertThat(bounds.filterNot { it.withinBound })
                .describedAs("$name: a precision var whose reachable bound under-estimated its real value — a silently cut optimum")
                .isEmpty()
        }
    }

    @Test
    fun `precision tight reachable domains keep the same optimum as loose guards`() {
        // Output-preservation lock for the precision tight=true switch: tight reachable domains must declare the
        // SAME optimum as loose guards (tight only changes provability/speed, never the answer), and the tight
        // build is the one that proves it. Same shapes as the soundness lock, on the tiny pool both prove.
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessStressPool()
        precisionSoundnessShapes().forEach { (name, params) ->
            val tight = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true)
            val loose = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = false)
            assertThat(tight.isOptimal).describedAs("$name: the tight (production) precision model must prove OPTIMAL").isTrue()
            assertThat(loose.isOptimal).describedAs("$name: the loose reference also proves on this small pool").isTrue()
            assertThat(tight.objective)
                .describedAs("$name: tightening declared domains must not change the precision optimum — only how fast it is proven")
                .isEqualTo(loose.objective)
        }
    }

    @Test
    fun `most-masteries exact fill stays sound with a secondary-cap sublimation in play`() {
        // The exact scenario the gate is about: a choosable SECONDARY_MASTERIES_AT_MOST=0 sub puts the secondary
        // masteries in subPinnedStats (a ≤ condition forcing more is dangerous). Exact-fill is STILL sound here
        // because the per-stat distribution is free — every socket can take the elemental (safe filler) rune
        // instead of a secondary rune, so the cap is never pushed over. So exact-fill fires (a safe filler exists)
        // and must still equal the ≤ optimum. (It is gated off only if the SOLE modeled rune were a capped
        // secondary — a self-contradictory request.)
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessSocketedPool()
        val runes = soundnessRunes()
        val secondaryCapSub =
            sublimation(
                9001,
                SublimationRarity.EPIC,
                SublimationKind.STATIC_CONDITIONAL,
                "ElementalUnderSecondaryCap",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY, 50)),
                condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0)
            )
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 9999), TargetStat(Characteristic.ACTION_POINT, 11))),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )
        val subs = listOf(secondaryCapSub)
        val exact = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes, sublimations = subs)
        val leq = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes, sublimations = subs, forceRuneLeq = true)
        assertThat(exact.isOptimal && leq.isOptimal).describedAs("both prove OPTIMAL").isTrue()
        assertThat(exact.objective)
            .describedAs("exact fill stays sound with a secondary-cap sub — elemental (safe filler) runes fill sockets without breaking the cap")
            .isEqualTo(leq.objective)
    }

    @Test
    fun `most-masteries exact fill proves the runes-on optimum fast on the full level-110 pool`() {
        // Regression guard for the exact-fill win — NOT @slow on purpose: it proves in ~1.7s and CI (`test`)
        // excludes @slow, so a @slow guard would never actually run. The ≤ model could NOT prove this case
        // (full lvl-110 pool, runes ON, distance + AP/MP/Range) — it ran ~656s and returned FEASIBLE because
        // every socket was an independent 0..slots underfill choice. Exact-fill pins Σ=slots so the single
        // relevant rune (distance) is forced full, collapsing the search to a fast proof. If exact-fill ever
        // stops firing for this shape, the proof can't close within the bounded det-time and this fails (in
        // bounded time, not a hang). Single worker (the low-thread target) ⇒ deterministic; det-time is
        // hardware-independent so the budget is reproducible on CI.
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 40.0)
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.MOVEMENT_POINT, 4),
                            TargetStat(Characteristic.RANGE, 4)
                        )
                    ),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = false
            )
        val result =
            WakfuBuildSolver.maxDamageSolveForTest(
                params,
                fullEpicPool(110),
                tuning,
                tightDomains = true,
                runes = WakfuBestBuildFinderAlgorithm.runes
            )
        assertThat(result.isOptimal)
            .describedAs("exact socket fill must prove the runes-on optimum (the ≤ model could not — ~656s FEASIBLE)")
            .isTrue()
    }

    @Test
    fun `domination relation respects rarity budget, sockets and ring multiplicity`() {
        val pool =
            listOf(
                // AMULET: Junk is dominated by NonEpic (both rare, ≤ on all) ⇒ dropped. The EPIC can't dominate
                // the non-epic (keeping it may free the single epic slot for a stronger epic elsewhere) ⇒ both kept.
                equipment(1, ItemType.AMULET, "NonEpic", mapOf(Characteristic.MASTERY_ELEMENTARY to 100), rarity = Rarity.RARE),
                equipment(2, ItemType.AMULET, "Epic", mapOf(Characteristic.MASTERY_ELEMENTARY to 150), rarity = Rarity.EPIC),
                equipment(3, ItemType.AMULET, "Junk", mapOf(Characteristic.MASTERY_ELEMENTARY to 80), rarity = Rarity.RARE),
                // BELT: a COMMON beating a RELIC on every stat DOES dominate it (a relic beaten everywhere is useless).
                equipment(4, ItemType.BELT, "CommonBig", mapOf(Characteristic.MASTERY_ELEMENTARY to 300), rarity = Rarity.COMMON),
                equipment(5, ItemType.BELT, "RelicSmall", mapOf(Characteristic.MASTERY_ELEMENTARY to 200), rarity = Rarity.RELIC),
                // HELMET: more stats can't dominate fewer sockets (≥ maxShardSlots required) ⇒ both kept.
                equipment(6, ItemType.HELMET, "HiStatNoSock", mapOf(Characteristic.MASTERY_ELEMENTARY to 200), maxShardSlots = 0, rarity = Rarity.RARE),
                equipment(7, ItemType.HELMET, "LoStat3Sock", mapOf(Characteristic.MASTERY_ELEMENTARY to 50), maxShardSlots = 3, rarity = Rarity.RARE),
                // RING keeps 2: R3 has TWO dominators (R1, R2) ⇒ removed; R2 has only ONE (R1) ⇒ kept; R1 kept.
                equipment(8, ItemType.RING, "R1", mapOf(Characteristic.MASTERY_ELEMENTARY to 100), rarity = Rarity.RARE),
                equipment(9, ItemType.RING, "R2", mapOf(Characteristic.MASTERY_ELEMENTARY to 90), rarity = Rarity.RARE),
                equipment(10, ItemType.RING, "R3", mapOf(Characteristic.MASTERY_ELEMENTARY to 10), rarity = Rarity.RARE)
            ).groupBy { it.itemType }

        val kept = WakfuBuildSolver.filterDominatedPoolForTest(pool).mapValues { (_, v) -> v.map { it.name.fr }.toSet() }

        assertThat(kept[ItemType.AMULET]).describedAs("epic can't dominate the non-epic; junk dropped").containsExactlyInAnyOrder("NonEpic", "Epic")
        assertThat(kept[ItemType.BELT]).describedAs("a common beating a relic on every stat dominates it").containsExactly("CommonBig")
        assertThat(kept[ItemType.HELMET]).describedAs("more stats can't dominate fewer sockets").containsExactlyInAnyOrder("HiStatNoSock", "LoStat3Sock")
        assertThat(kept[ItemType.RING]).describedAs("ring keeps 2: R3 has two dominators, R2 only one").containsExactlyInAnyOrder("R1", "R2")
    }

    @Test
    fun `domination keeps a non-epic that frees the epic budget — same optimum`() {
        // The trap a naive stats-only filter falls into: drop the weaker NON-epic amulet for the stronger epic.
        // But keeping it is exactly what lets the build spend its one epic on the far stronger epic ring. The
        // filtered optimum must equal the full-pool optimum (if the non-epic were wrongly removed it would drop).
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "NonEpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY to 100), rarity = Rarity.RARE),
                equipment(2, ItemType.AMULET, "EpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY to 150), rarity = Rarity.EPIC),
                equipment(3, ItemType.RING, "EpicRing", mapOf(Characteristic.MASTERY_ELEMENTARY to 400), rarity = Rarity.EPIC),
                equipment(4, ItemType.RING, "WeakRing", mapOf(Characteristic.MASTERY_ELEMENTARY to 10), rarity = Rarity.RARE)
            ).groupBy { it.itemType }
        val params = maxDamageShape(CharacterClass.CRA, 110, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE))
        val tuning = WakfuBuildSolver.SolverTuning()
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL").isTrue()
        assertThat(filtered.objective)
            .describedAs("domination must keep the non-epic that frees the epic slot — optimum unchanged")
            .isEqualTo(full.objective)
    }

    @Test
    fun `a choosable no-offhand sub no longer gates domination off — same optimum`() {
        // Regression guard: once a NO_OFFHAND_OR_TWO_HANDED sub (Light Weapons Expert II) became
        // solver-choosable, dominationShape returned null and silently disabled the domination pre-filter
        // for EVERY subs-on request (pool 7884 vs 6567 at lvl-245 — the ~2.8× domination win lost on the
        // default path). The condition is swap-invariant — domination swaps stay within one ItemType, so
        // the off-hand/2H pick sum is unchanged — so domination must stay ON and preserve the optimum.
        val lightWeapons =
            sublimation(
                9901,
                SublimationRarity.NORMAL,
                SublimationKind.STATIC_CONDITIONAL,
                "LightWeaponsLike",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 120)),
                condition = SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
            )
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), maxShardSlots = 3),
                // BeltBig strictly dominates BeltSmall — the filter must actually fire (shape non-null).
                equipment(2, ItemType.BELT, "BeltBig", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 80), maxShardSlots = 3),
                equipment(3, ItemType.BELT, "BeltSmall", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 40), maxShardSlots = 3),
                // The trade the sub creates: equipping the shield beats an empty slot on raw stats but
                // costs the (larger) conditional sub bonus — the optimum leaves the off-hand empty.
                equipment(4, ItemType.OFF_HAND_WEAPONS, "Shield", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 30))
            ).groupBy { it.itemType }
        val character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110))
        val params = maxDamageParams(character).copy(useSublimations = true)

        assertThat(dominationShape(params, listOf(lightWeapons)))
            .describedAs("a choosable NO_OFFHAND_OR_TWO_HANDED sub must not gate domination off")
            .isNotNull()

        val tuning = WakfuBuildSolver.SolverTuning()
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, sublimations = listOf(lightWeapons), applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, sublimations = listOf(lightWeapons), applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL").isTrue()
        assertThat(filtered.objective)
            .describedAs("domination with a choosable no-offhand sub must preserve the proven optimum")
            .isEqualTo(full.objective)
    }

    @Test
    @Tag("slow")
    fun `domination preserves the max-damage optimum on the full level-110 pool`() {
        // The breadth guard: on the real EPIC pool, the per-slot domination pre-filter must reach the SAME
        // proven optimum as the full pool — across every slot's real rarity / socket / stat mix. Runes are OFF
        // here so the FULL solve still proves within budget (runes ~double the model and blow past it); the
        // rune-capacity half of the relation (maxShardSlots ≥) is locked by the pure-relation unit test above.
        val tuning = WakfuBuildSolver.SolverTuning(maxDeterministicTime = 600.0)
        val params =
            maxDamageShape(
                CharacterClass.CRA,
                110,
                DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
            )
        val pool = fullEpicPool(110)
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL on the full pool").isTrue()
        assertThat(filtered.objective).describedAs("domination must preserve the proven optimum").isEqualTo(full.objective)
    }

    @Test
    fun `domination pins conditioned stats to equality`() {
        // With a conditional sub in play (e.g. SECONDARY_MASTERIES_AT_MOST), the stats it reads are pinned: two
        // items differing only on a pinned stat can't dominate each other (the weaker might be the one kept to
        // satisfy the cap). But pure ELEMENTAL gear still dominates — no condition reads elemental mastery.
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "DistA", mapOf(Characteristic.MASTERY_DISTANCE to 100), rarity = Rarity.RARE),
                equipment(2, ItemType.AMULET, "DistB", mapOf(Characteristic.MASTERY_DISTANCE to 50), rarity = Rarity.RARE),
                equipment(3, ItemType.BELT, "ElemBig", mapOf(Characteristic.MASTERY_ELEMENTARY to 100), rarity = Rarity.RARE),
                equipment(4, ItemType.BELT, "ElemSmall", mapOf(Characteristic.MASTERY_ELEMENTARY to 50), rarity = Rarity.RARE)
            ).groupBy { it.itemType }
        val kept =
            WakfuBuildSolver
                .filterDominatedPoolForTest(pool, setOf(Characteristic.MASTERY_DISTANCE))
                .mapValues { (_, v) -> v.map { it.name.fr }.toSet() }
        assertThat(kept[ItemType.AMULET]).describedAs("pinned stat ⇒ no domination across it").containsExactlyInAnyOrder("DistA", "DistB")
        assertThat(kept[ItemType.BELT]).describedAs("unpinned elemental still dominates").containsExactly("ElemBig")
    }

    @Test
    @Tag("slow")
    fun `domination preserves the max-damage optimum on the full level-110 pool with sublimations`() {
        // The smart-pinning guard — the case the default GUI search actually runs. With sublimations ON, the
        // 9 dangerous conditional choosable subs pin AP/crit/dodge/range/secondaries, so domination can't flip
        // any cap; it must still reach the SAME proven optimum as the full pool.
        val tuning = WakfuBuildSolver.SolverTuning(maxDeterministicTime = 900.0)
        val params =
            maxDamageShape(CharacterClass.CRA, 110, DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE))
                .copy(useSublimations = true)
        val pool = fullEpicPool(110)
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, sublimations = subs, applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, sublimations = subs, applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL with subs on").isTrue()
        assertThat(filtered.objective).describedAs("pinned domination must preserve the proven optimum with subs on").isEqualTo(full.objective)
    }

    @Test
    fun `domination preserves the most-masteries optimum on the full level-110 pool`() {
        // most-masteries soundness guard: its objective is the bilinear masteryScore × penaltyMultiplier (the
        // required AP/MP/HP/crit targets drive the multiplier), so this exercises the sign argument — the optimum
        // has masteryScore >= 0, making the dominance swap monotone. Full pool must reach the same proven optimum.
        val tuning = WakfuBuildSolver.SolverTuning(maxDeterministicTime = 600.0)
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.MOVEMENT_POINT, 6),
                            TargetStat(Characteristic.HP, 2000),
                            TargetStat(Characteristic.CRITICAL_HIT, 30)
                        )
                    ),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = false,
                useSublimations = false
            )
        val pool = fullEpicPool(110)
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL").isTrue()
        assertThat(filtered.objective).describedAs("domination must preserve the most-masteries optimum").isEqualTo(full.objective)
    }

    @Test
    fun `domination preserves the precision optimum on the full level-110 pool`() {
        // precision soundness guard: its objective is a sum of min(actual, target) terms (capped, monotone), so
        // componentwise->= domination must reach the same proven optimum on the full pool.
        val tuning = WakfuBuildSolver.SolverTuning(maxDeterministicTime = 600.0)
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 800),
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.HP, 2000),
                            TargetStat(Characteristic.CRITICAL_HIT, 30),
                            TargetStat(Characteristic.RANGE, 2)
                        )
                    ),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                useRunes = false,
                useSublimations = false
            )
        val pool = fullEpicPool(110)
        val full = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = false)
        val filtered = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, applyDomination = true)
        assertThat(full.isOptimal && filtered.isOptimal).describedAs("both prove OPTIMAL").isTrue()
        assertThat(filtered.objective).describedAs("domination must preserve the precision optimum").isEqualTo(full.objective)
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
    fun `most-masteries exact socket fill preserves the leq-model optimum`() {
        // Exact fill pins Σ runeCount = slots (no empty sockets) instead of ≤. The ≤ model allows underfill — a
        // SUPERSET of feasible rune assignments — so its optimum is an upper bound on exact-fill's. Because the
        // most-masteries objective is monotone non-decreasing in every modeled rune stat (masteries, shortfall-
        // only required targets, the DI fold, the min-over-elements), underfill is never optimal, so the two must
        // be EQUAL: exact-fill is a pure search-space cut. forceRuneLeq=true builds the ≤ reference. If exact-fill
        // ever cut the optimum, exact < leq here. Spans a specific-mastery and a generic-elemental request.
        val tuning = WakfuBuildSolver.SolverTuning()
        val pool = soundnessSocketedPool()
        val runes = soundnessRunes()

        fun mm(targets: List<TargetStat>) =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 110, 0, CharacterSkills(110)),
                targetStats = TargetStats(targets),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = false
            )
        listOf(
            "distance-ap" to mm(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 12))),
            "generic-elem-ap" to mm(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 9999), TargetStat(Characteristic.ACTION_POINT, 11)))
        ).forEach { (name, params) ->
            val exact = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes)
            val leq = WakfuBuildSolver.maxDamageSolveForTest(params, pool, tuning, tightDomains = true, runes = runes, forceRuneLeq = true)
            assertThat(exact.isOptimal).describedAs("$name: the exact-fill model proves OPTIMAL").isTrue()
            assertThat(leq.isOptimal).describedAs("$name: the ≤ reference proves OPTIMAL").isTrue()
            assertThat(exact.objective)
                .describedAs("$name: exact socket fill must not change the most-masteries optimum (exact == ≤)")
                .isEqualTo(leq.objective)
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

    // ENG-1/ENG-2: a search request is validated up front, and validateRequest returns ALL problems at once so
    // the GUI shows them together in a pop-up. An imposed item the character can't equip (level/rarity) replaces
    // the old "Archiemblème" swap; >1 epic/relic forced sublimation replaces the misleading "no result".
    // PETS/MOUNTS have no level requirement (eligibility-filter parity).
    @Test
    fun `validateRequest reports a forced item above the character level`() {
        val level = 50
        val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, CharacterSkills(level))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.EMBLEM, name = "Forced Emblem", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 110, rarity = Rarity.LEGENDARY)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Forced Emblem")),
                allEquipments = pool
            )
        assertThat(problems).hasSize(1)
        assertThat(problems.single()).isInstanceOf(RequestValidationProblem.ForcedItemNotEquippable::class.java)
    }

    @Test
    fun `validateRequest reports a forced item above the search max rarity`() {
        val level = 110
        val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = 1, CharacterSkills(level))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.AMULET, name = "Too Rare", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.EPIC)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Too Rare"), maxRarity = Rarity.RARE),
                allEquipments = pool
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedItemNotEquippable>()).hasSize(1)
    }

    @Test
    fun `validateRequest accepts an in-range forced item and a low-level forced mount`() {
        val level = 110
        val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = 50, CharacterSkills(level))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.AMULET, name = "In Range Amulet", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.LEGENDARY),
                // A level-1 mount is equippable at any character level (PETS/MOUNTS are level-exempt).
                equipment(id = 2, type = ItemType.MOUNTS, name = "Low Mount", stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 40), level = 1, rarity = Rarity.RARE)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("In Range Amulet", "Low Mount")),
                allEquipments = pool
            )
        assertThat(problems).isEmpty()
    }

    @Test
    fun `validateRequest reports forcing two epic sublimations`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val subs =
            listOf(
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicA"),
                sublimation(2, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicB")
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = emptyList(), forcedSublimations = listOf("EpicA", "EpicB")),
                allSublimations = subs
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedSublimationRarityExceeded>()).hasSize(1)
    }

    @Test
    fun `validateRequest reports forcing two relic sublimations`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val subs =
            listOf(
                sublimation(1, SublimationRarity.RELIC, SublimationKind.FLAT, "RelicA"),
                sublimation(2, SublimationRarity.RELIC, SublimationKind.FLAT, "RelicB")
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = emptyList(), forcedSublimations = listOf("RelicA", "RelicB")),
                allSublimations = subs
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedSublimationRarityExceeded>()).hasSize(1)
    }

    @Test
    fun `validateRequest reports a sublimation both forced and excluded`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val subs = listOf(sublimation(1, SublimationRarity.NORMAL, SublimationKind.FLAT, "Carnage II"))
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = emptyList(), forcedSublimations = listOf("Carnage II"))
                    .copy(excludedSublimations = listOf("carnage ii")),
                allSublimations = subs
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.SublimationForcedAndExcluded>()).hasSize(1)
    }

    @Test
    fun `activeSublimations drops excluded sublimations by French or English name`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val keep = sublimation(1, SublimationRarity.NORMAL, SublimationKind.FLAT, "Ravage II")
        val excludedFr = sublimation(2, SublimationRarity.NORMAL, SublimationKind.FLAT, "Carnage II")
        val excludedEn =
            sublimation(3, SublimationRarity.NORMAL, SublimationKind.FLAT, "Vivacité II")
                .copy(name = I18nText("Vivacité II", "Vivacity II", "", ""))
        val params =
            forcedItemsParams(character, forced = emptyList())
                .copy(excludedSublimations = listOf("carnage ii", "VIVACITY II"))

        val active = WakfuBestBuildFinderAlgorithm.activeSublimations(params, listOf(keep, excludedFr, excludedEn))

        assertThat(active).containsExactly(keep)
    }

    @Test
    fun `activeSublimations caps auto-picked sublimations by item tier but keeps forced ones`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val cheap = sublimation(1, SublimationRarity.NORMAL, SublimationKind.FLAT, "Cheap I", maxTier = 1)
        val expensive = sublimation(2, SublimationRarity.NORMAL, SublimationKind.FLAT, "Expensive III", maxTier = 3)
        val forcedExpensive = sublimation(3, SublimationRarity.NORMAL, SublimationKind.FLAT, "Forced III", maxTier = 3)
        val params =
            forcedItemsParams(character, forced = emptyList(), forcedSublimations = listOf("Forced III"))
                .copy(maxSublimationTier = 1)

        val active = WakfuBestBuildFinderAlgorithm.activeSublimations(params, listOf(cheap, expensive, forcedExpensive))

        assertThat(active).containsExactly(cheap, forcedExpensive)
    }

    @Test
    fun `committed sublimation catalog carries item tier separately from state max level`() {
        val byId = WakfuBestBuildFinderAlgorithm.sublimations.associateBy { it.stateId }

        assertThat(byId.getValue(6026).maxStackLevel).describedAs("Influence state max level").isEqualTo(6)
        assertThat(byId.getValue(6026).maxTier).describedAs("Influence item tier").isEqualTo(3)
        assertThat(byId.getValue(7077).maxTier).describedAs("Heavy Armor item tier").isEqualTo(2)
    }

    @Test
    fun `validateRequest accumulates several problems at once`() {
        val character = Character(CharacterClass.CRA, 50, 1, CharacterSkills(50))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.EMBLEM, name = "Forced Emblem", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 110, rarity = Rarity.LEGENDARY)
            )
        val subs =
            listOf(
                sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicA"),
                sublimation(2, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicB")
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Forced Emblem"), forcedSublimations = listOf("EpicA", "EpicB")),
                allEquipments = pool,
                allSublimations = subs
            )
        // Both problems reported together — the whole point of the errors pop-up.
        assertThat(problems).hasSize(2)
        assertThat(problems.any { it is RequestValidationProblem.ForcedItemNotEquippable }).isTrue
        assertThat(problems.any { it is RequestValidationProblem.ForcedSublimationRarityExceeded }).isTrue
    }

    // Invalid-by-construction forced combinations must be rejected BEFORE any search starts (pop-up in the
    // GUI, InvalidRequestException in the CLI) — the CP-SAT model would otherwise just report INFEASIBLE
    // ("no build"), which reads as a solver failure instead of a contradictory request.
    @Test
    fun `validateRequest reports two forced items competing for one slot`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.AMULET, name = "Amulet A", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.LEGENDARY),
                equipment(id = 2, type = ItemType.AMULET, name = "Amulet B", stats = mapOf(Characteristic.MASTERY_MELEE to 60), level = 100, rarity = Rarity.LEGENDARY)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Amulet A", "Amulet B")),
                allEquipments = pool
            )
        val conflict = problems.filterIsInstance<RequestValidationProblem.ForcedItemsSlotConflict>().single()
        assertThat(conflict.itemType).isEqualTo(ItemType.AMULET)
        assertThat(conflict.items).hasSize(2)
    }

    @Test
    fun `validateRequest allows two forced rings but rejects three`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val pool =
            (1..3).map {
                equipment(id = it, type = ItemType.RING, name = "Ring $it", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.LEGENDARY)
            }
        val two =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Ring 1", "Ring 2")),
                allEquipments = pool
            )
        assertThat(two).isEmpty()
        val three =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Ring 1", "Ring 2", "Ring 3")),
                allEquipments = pool
            )
        assertThat(three.filterIsInstance<RequestValidationProblem.ForcedItemsSlotConflict>()).hasSize(1)
    }

    @Test
    fun `validateRequest reports a forced two-handed weapon combined with a forced one-handed weapon`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.TWO_HANDED_WEAPONS, name = "Big Bow", stats = mapOf(Characteristic.MASTERY_MELEE to 90), level = 100, rarity = Rarity.LEGENDARY),
                equipment(
                    id = 2,
                    type = ItemType.ONE_HANDED_WEAPONS,
                    name = "Small Wand",
                    stats = mapOf(Characteristic.MASTERY_MELEE to 40),
                    level = 100,
                    rarity = Rarity.LEGENDARY
                )
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Big Bow", "Small Wand")),
                allEquipments = pool
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedWeaponsConflict>()).hasSize(1)
    }

    @Test
    fun `validateRequest reports two forced epic items`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.AMULET, name = "Epic Amulet", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.EPIC),
                equipment(id = 2, type = ItemType.BELT, name = "Epic Belt", stats = mapOf(Characteristic.MASTERY_MELEE to 60), level = 100, rarity = Rarity.EPIC)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Epic Amulet", "Epic Belt")),
                allEquipments = pool
            )
        val budget = problems.filterIsInstance<RequestValidationProblem.ForcedItemRarityBudgetExceeded>().single()
        assertThat(budget.rarity).isEqualTo(Rarity.EPIC)
        assertThat(budget.items).hasSize(2)
    }

    @Test
    fun `validateRequest reports an item both forced and excluded`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val pool =
            listOf(
                equipment(id = 1, type = ItemType.AMULET, name = "Torn Amulet", stats = mapOf(Characteristic.MASTERY_MELEE to 50), level = 100, rarity = Rarity.LEGENDARY)
            )
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = listOf("Torn Amulet"), excludedItems = listOf("Torn Amulet")),
                allEquipments = pool
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedItemAlsoExcluded>()).hasSize(1)
    }

    @Test
    fun `validateRequest reports a forced epic sublimation when epic items are excluded`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val subs = listOf(sublimation(1, SublimationRarity.EPIC, SublimationKind.FLAT, "EpicSub"))
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(
                    character,
                    forced = emptyList(),
                    forcedSublimations = listOf("EpicSub"),
                    excludedRarities = setOf(Rarity.EPIC)
                ),
                allSublimations = subs
            )
        val problem = problems.filterIsInstance<RequestValidationProblem.ForcedSublimationNoCarrier>().single()
        assertThat(problem.rarity).isEqualTo(SublimationRarity.EPIC)
        // maxRarity below RELIC excludes the relic carrier the same way.
        val relicSubs = listOf(sublimation(2, SublimationRarity.RELIC, SublimationKind.FLAT, "RelicSub"))
        val relicProblems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(
                    character,
                    forced = emptyList(),
                    forcedSublimations = listOf("RelicSub"),
                    maxRarity = Rarity.LEGENDARY
                ),
                allSublimations = relicSubs
            )
        assertThat(relicProblems.filterIsInstance<RequestValidationProblem.ForcedSublimationNoCarrier>().single().rarity)
            .isEqualTo(SublimationRarity.RELIC)
    }

    @Test
    fun `validateRequest reports more forced sublimations than a build can socket`() {
        val character = Character(CharacterClass.CRA, 110, 1, CharacterSkills(110))
        val subs = (1..11).map { sublimation(it, SublimationRarity.NORMAL, SublimationKind.FLAT, "Sub $it") }
        val problems =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                forcedItemsParams(character, forced = emptyList(), forcedSublimations = (1..11).map { "Sub $it" }),
                allSublimations = subs
            )
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedSublimationsExceedCapacity>()).hasSize(1)
    }

    private fun forcedItemsParams(
        character: Character,
        forced: List<String>,
        maxRarity: Rarity = Rarity.EPIC,
        forcedSublimations: List<String> = emptyList(),
        excludedItems: List<String> = emptyList(),
        excludedRarities: Set<Rarity> = emptySet(),
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_MELEE, 1))),
            searchDuration = 2.seconds,
            stopWhenBuildMatch = false,
            maxRarity = maxRarity,
            excludedRarities = excludedRarities,
            forcedItems = forced,
            excludedItems = excludedItems,
            forcedSublimations = forcedSublimations,
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        )

    // ENG-1: "forcer un objet" must EQUIP the item, not merely make it the only candidate — even when a strictly
    // better item exists in the same slot (here the un-narrowed pool keeps both, as the solver sees it).
    @Test
    fun `a forced item is equipped even when a better item exists in the same slot`(): Unit =
        runBlocking {
            val level = 1
            val character =
                Character(clazz = CharacterClass.CRA, level = level, minLevel = level, CharacterSkills(level))

            val equipments =
                listOf(
                    equipment(id = 1, type = ItemType.AMULET, name = "Weak Amulet", stats = mapOf(Characteristic.MASTERY_MELEE to 10)),
                    equipment(id = 2, type = ItemType.AMULET, name = "Strong Amulet", stats = mapOf(Characteristic.MASTERY_MELEE to 100))
                )

            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_MELEE, 1))),
                    searchDuration = 2.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = listOf("Weak Amulet"),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType }, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .maxByOrNull { it.matchPercentage }!!

            val equippedNames = best.individual.equipments.map { it.name.fr }
            assertThat(equippedNames).contains("Weak Amulet")
            assertThat(equippedNames).doesNotContain("Strong Amulet")
            assertThat(best.individual.isValid()).isTrue
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

    // ---- AP-cell certifier (certifyMaxPerHitAtAp) validation -----------------------------------------

    /**
     * Adversarial frontier: a pool whose optimum is a strict DI×mastery MIX — neither the highest-mastery
     * nor the highest-DI selection. A base-mastery anchor gives DI something to multiply; two slots each
     * pick mastery OR DI, tuned (ΔM=2000 / ΔDI=100) so one-of-each strictly beats both all-mastery and
     * all-DI. So the certifier MUST keep a ≥2-point (DI, graw) Pareto frontier and convolve it across slots:
     * a frontier collapsed to "max graw only" would pick all-mastery, "max DI only" would pick all-DI, and
     * either would disagree with CP-SAT. The pool carries no high-crit source, so the reachable crit stays
     * far below the c≈97-100 band where the certifier's known residual lives — it is exact here, and we
     * assert it equals the proven CP-SAT cell-max. No sublimations ⇒ this is unaffected by the in-flight
     * pre-combat-condition fix, so the `==` is durable.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT on a forced DI-vs-mastery frontier`() {
        val params = fireMaxDamageParams(50)
        val anchor = equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000))
        val amuMastery = equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
        val amuDi = equipment(3, ItemType.AMULET, "AmuDI", mapOf(Characteristic.DAMAGE_INFLICTED to 100))
        val beltMastery = equipment(4, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
        val beltDi = equipment(5, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 100))
        val full = listOf(anchor, amuMastery, amuDi, beltMastery, beltDi).groupBy { it.itemType }
        val masteryOnly = listOf(anchor, amuMastery, beltMastery).groupBy { it.itemType }
        val diOnly = listOf(anchor, amuDi, beltDi).groupBy { it.itemType }

        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, full, applyDomination = false)
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue // certifier bailed → CP-SAT path, no exact claim
            val fullObj = optimalMaxDamageObjective(params, full, ap) ?: continue // AP unreachable
            assertThat(certObj)
                .describedAs("AP=%d: the exact certifier must equal the proven CP-SAT cell-max", ap)
                .isEqualTo(fullObj)
            val masteryObj = optimalMaxDamageObjective(params, masteryOnly, ap)!!
            val diObj = optimalMaxDamageObjective(params, diOnly, ap)!!
            assertThat(fullObj)
                .describedAs("AP=%d: the optimum strictly beats all-mastery (a max-graw-only collapse)", ap)
                .isGreaterThan(masteryObj)
            assertThat(fullObj)
                .describedAs("AP=%d: the optimum strictly beats all-DI (a max-DI-only collapse)", ap)
                .isGreaterThan(diObj)
            compared++
        }
        assertThat(compared).describedAs("at least one reachable AP cell exercised the frontier").isGreaterThan(0)
    }

    /**
     * The certifier's crit DP assumes the usable crit cap is ≥ the always-on base+passive crit. A scenario
     * that caps usable crit BELOW the base (critCapPercent = 0 while CRA has 3 base crit) breaks that
     * assumption — without a guard the DP skips every crit state and silently returns 0 (an UNSOUND
     * under-count). It must instead BAIL (objective -1 here) so the caller falls back to CP-SAT, which still
     * finds a real positive build. Locks the `critCap < critConst` guard.
     */
    @Test
    fun `max-damage AP-cell certifier bails when the crit cap is below base crit`() {
        val params =
            fireMaxDamageParams(50)
                .copy(
                    damageScenario =
                        DamageScenario(
                            element = SpellElement.FIRE,
                            rangeBand = RangeBand.DISTANCE,
                            orientation = Orientation.FACE,
                            critCapPercent = 0
                        )
                )
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800))
            ).groupBy { it.itemType }
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        assertThat(cert).describedAs("the certifier runs over the AP cells").isNotEmpty
        assertThat(cert.values)
            .describedAs("crit cap below base crit ⇒ every cell bails (-1) instead of under-counting to 0")
            .allMatch { it == -1L }
        // The fallback path (CP-SAT) still proves a real, positive optimum for the same scenario.
        val obj = optimalMaxDamageObjective(params, pool, ap = 6)
        assertThat(obj).describedAs("CP-SAT still finds a positive build at AP=6").isNotNull
        assertThat(obj!!).isGreaterThan(0L)
    }

    /**
     * Soundness guard: [WakfuBuildSolver] `certifyMaxPerHitAtAp` claims to UPPER-BOUND the per-hit of every
     * feasible build at a pinned AP. On a pool that exercises the full coupling set — an epic item + an
     * epic-bound DI sub, four socket carriers + normal / conditional / crit-budget subs, two distinct rings,
     * AP + crit items, the level-50 skill pools — we assert the certifier objective ≥ the CP-SAT objective for
     * every AP cell. It also exercises the pre-combat-condition split: a PERMANENT crit sub (Influence-like,
     * `appliesBeforeCombat`) and a START-of-combat one (Secondary-Devastation-like) both feed the in-combat
     * crit, but only the permanent one feeds the `CondDi` `CRIT_AT_MOST` condition — so a wrong split would
     * make the certifier wrongly forbid (under-count) `CondDi` and trip this guard. Because it is `≥` (an upper
     * bound, not an exact match) it SURVIVES the condition fix unchanged: that fix only ever shrinks the
     * feasible set, so the certifier stays a valid bound; the dangerous UNDER-count direction is what trips it.
     */
    @Test
    fun `max-damage AP-cell certifier upper-bounds every CP-SAT cell-max`() {
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "EpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600), rarity = Rarity.EPIC),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.ACTION_POINT to 2), maxShardSlots = 4),
                equipment(4, ItemType.RING, "Ring1", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400, Characteristic.CRITICAL_HIT to 10)),
                equipment(5, ItemType.RING, "Ring2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400)),
                equipment(6, ItemType.CAPE, "Cape", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.DAMAGE_INFLICTED to 50)),
                equipment(7, ItemType.BOOTS, "Boots", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400, Characteristic.ACTION_POINT to 2), maxShardSlots = 3),
                // critM feeds the CONVERSION sub below — its 100% critM→elementary fold must stay ≥ CP-SAT.
                equipment(8, ItemType.CHEST_PLATE, "Chest", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_CRITICAL to 400), maxShardSlots = 3),
                // The weapon axis for the NO_OFFHAND sub below: the 2H likely wins here — the ≥ must hold
                // across BOTH weapon worlds.
                equipment(9, ItemType.TWO_HANDED_WEAPONS, "PanelStaff", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 700))
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(9001, "FlatDi", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 30)),
                sublimation(9002, "CritStart", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.CRITICAL_HIT to 20)),
                // Permanent (character-sheet) crit — appliesBeforeCombat ⇒ it DOES feed a CRIT_AT_MOST condition.
                sublimation(9005, "CritPermanent", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.CRITICAL_HIT to 15), permanent = true),
                sublimation(9003, "EpicDi", SublimationRarity.EPIC, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 40)),
                sublimation(
                    9004,
                    "CondDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.STATIC_CONDITIONAL,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 25),
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, 30)
                ),
                // The Unraveling shape (EPIC conversion under a crit-floor condition) — competes with EpicDi
                // for the one epic-sub slot and folds critM into elementary mastery when taken.
                sublimation(
                    9006,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "UnravelPanel",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                ),
                // The Featherweight shape (per-stat-step DI ramp): the certifier must treat it as a normal
                // sub transition — a slot-consuming CHOICE valued at its optimistic cap — not a free constant.
                sublimation(
                    9007,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "FeatherPanel",
                    perStatStep =
                        SublimationEffect.PerStatStep(
                            source = Characteristic.MOVEMENT_POINT,
                            threshold = 4,
                            perStep = 6,
                            cap = 24,
                            target = Characteristic.DAMAGE_INFLICTED
                        )
                ),
                // The Light Weapons Expert shape: mastery iff no off-hand/2H — split on the weapon axis.
                sublimation(
                    9009,
                    SublimationRarity.NORMAL,
                    SublimationKind.STATIC_CONDITIONAL,
                    "LwePanel",
                    effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 500)),
                    condition = SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
                ),
                // The Critical Secret shape: a crit BUDGET sub whose critM≤0 condition contradicts critM
                // stacking — certified via its own world split, never as free budget crit.
                sublimation(
                    9008,
                    SublimationRarity.EPIC,
                    SublimationKind.STATIC_CONDITIONAL,
                    "CritSecretPanel",
                    effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 30)),
                    condition = SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, 0)
                )
            )

        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(cert.values.any { it > 0 })
            .describedAs("the certifier must actually certify this pool (not bail on every cell)")
            .isTrue()
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue // certifier bailed → CP-SAT path, no claim to check
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue // AP unreachable → INFEASIBLE
            assertThat(certObj)
                .describedAs("AP=%d: certifier (%d) must upper-bound the CP-SAT objective (%d)", ap, certObj, profile.objective)
                .isGreaterThanOrEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell was compared against CP-SAT").isGreaterThan(0)
    }

    /**
     * P6.1 FUZZ LOCK (CI-runnable). 25 fixed-seed random small pools — 3–6 single-occupancy slots (+ optional
     * same-name ring twins), 2–4 items each drawn from {fire mastery, critM, crit ±, AP ±, MP, DI, sockets},
     * plus 0–3 synthetic solver-choosable FLAT sublimations (DI or fire-mastery, permanent or start-of-combat).
     * For every non-bailed AP cell it asserts the two release-blocking certifier invariants against a pinned
     * CP-SAT solve on the SAME pool + subs:
     *
     *   1. `certExact(cell) == CP-SAT OPTIMAL` — on this shape family (FLAT subs, modest crit far below the
     *      c≈97-100 residual band) the exact pass is provably exact, so equality is the tightness lock; a value
     *      ABOVE CP-SAT means the certifier drifted loose (badge coverage regression) and a value BELOW is the
     *      one fatal class (an UNDER-count — a wrong "proven optimal" badge).
     *   2. `certFast(cell) ≥ certExact(cell)` AND `certFast(cell) ≥ CP-SAT` — the fast tier-1 bound the
     *      production badge actually consumes must never under-count either.
     *
     * The seeds are fixed (no unseeded `Random()` — CI determinism), so this never flakes: it is green on every
     * run or red on every run. It guards every future certifier edit against a silent under-count on shapes the
     * hand-written panels don't happen to cover. Sublimations are kept FLAT here (conditional / conversion /
     * crit-secret exactness is locked separately by the coupling-panel `≥` + Inc 1–3 `==` tests) so equality is
     * durable; the fuzz's value is broad coverage of the item × crit × AP × MP × DI × ring-twin combinatorics.
     */
    @Test
    fun `max-damage certifier fuzz lock — exact equals CP-SAT and fast upper-bounds on seeded random pools`() {
        var totalCompared = 0
        var tightCells = 0
        var nonNullLedgers = 0
        var tightLedgers = 0
        repeat(25) { iteration ->
            val (params, pool, subs) = fuzzScenario(iteration)

            val (exact, fast, tier15) =
                WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
            assertThat(fast).describedAs("iter %d: the fast pass populates every exact cell", iteration).containsOnlyKeys(exact.keys)
            assertThat(tier15).describedAs("iter %d: the tier-1.5 pass (B7) populates every exact cell", iteration).containsOnlyKeys(exact.keys)

            // Per-cell CP-SAT ground truth for every reachable AP (null = that AP is infeasible on this pool).
            val cpsatByAp = exact.keys.sorted().associateWith { optimalMaxDamageObjectiveWithSubs(params, pool, subs, it) }
            for (ap in exact.keys.sorted()) {
                val cpsat = cpsatByAp[ap] ?: continue
                val exactObj = exact[ap] ?: -1L
                val fastObj = fast[ap] ?: -1L
                // SOUNDNESS (release-blocking): where either pass makes a numeric CLAIM (≥ 0; a negative value is
                // the bail sentinel — those cells punt to CP-SAT / the sound fast bound) it must UPPER-BOUND the
                // proven CP-SAT cell-max. An under-count (bound < CP-SAT) is the one fatal class — a wrong
                // "proven optimal" badge. Both passes are sound *upper bounds*, not exact: on most cells they
                // land exactly on CP-SAT (counted as `tightCells`), but a crit-band / coupling shape can make
                // them legitimately loose (>), so the invariant is `≥`, not `==`.
                if (exactObj >= 0) {
                    assertThat(exactObj)
                        .describedAs(
                            "iter %d AP=%d: exact certifier (%d) must upper-bound the proven CP-SAT cell-max (%d) — an under-count is fatal",
                            iteration,
                            ap,
                            exactObj,
                            cpsat
                        ).isGreaterThanOrEqualTo(cpsat)
                    if (exactObj == cpsat) tightCells++
                    if (fastObj >= 0) {
                        assertThat(fastObj)
                            .describedAs("iter %d AP=%d: fast (%d) must upper-bound exact (%d)", iteration, ap, fastObj, exactObj)
                            .isGreaterThanOrEqualTo(exactObj)
                    }
                    totalCompared++
                }
                if (fastObj >= 0) {
                    assertThat(fastObj)
                        .describedAs("iter %d AP=%d: fast certifier (%d) must upper-bound the proven CP-SAT cell-max (%d) — an under-count is fatal", iteration, ap, fastObj, cpsat)
                        .isGreaterThanOrEqualTo(cpsat)
                }
                // B7 SOUNDNESS (release-blocking): the sharpened tier-1.5 bound must UPPER-BOUND CP-SAT (an
                // under-count is a wrong badge) and sit within `fast ≥ tier1.5 ≥ exact` — tighter than the tier-1
                // fast pass, never below the exact pass.
                val tier15Obj = tier15[ap] ?: -1L
                if (tier15Obj >= 0) {
                    assertThat(tier15Obj)
                        .describedAs("iter %d AP=%d: tier-1.5 (%d) must upper-bound the proven CP-SAT cell-max (%d) — an under-count is fatal", iteration, ap, tier15Obj, cpsat)
                        .isGreaterThanOrEqualTo(cpsat)
                    if (exactObj >= 0) {
                        assertThat(tier15Obj)
                            .describedAs("iter %d AP=%d: tier-1.5 (%d) must upper-bound exact (%d)", iteration, ap, tier15Obj, exactObj)
                            .isGreaterThanOrEqualTo(exactObj)
                    }
                    if (fastObj >= 0) {
                        assertThat(fastObj)
                            .describedAs("iter %d AP=%d: fast (%d) must upper-bound tier-1.5 (%d)", iteration, ap, fastObj, tier15Obj)
                            .isGreaterThanOrEqualTo(tier15Obj)
                    }
                }
            }

            // PRODUCTION-FAITHFUL soundness: the two-tier ledger's headline number — `maxCellObjective`, what
            // `proveOptimality` compares to the incumbent — must never UNDER-count the true optimum (max CP-SAT
            // over all reachable cells). A shape-level bail (null) is sound (the badge is simply withheld — e.g.
            // more NORMAL subs than 3-socket carriers). When non-null it must be ≥ the true optimum; it is
            // usually EXACTLY equal (the optimum lives at an at/above-base cell where the exact pass is exact),
            // but can be legitimately LOOSE (>) on a weak pool whose optimum is so low a below-base cell's sound
            // fast bound pokes above it — sound, at worst a withheld badge, never a wrong one.
            val trueOptimum = cpsatByAp.values.filterNotNull().maxOrNull()
            if (trueOptimum != null && trueOptimum > 0) {
                val ledger =
                    WakfuBuildSolver.certifyLedgerForTest(
                        params,
                        pool,
                        sublimations = subs,
                        applyDomination = false,
                        forceTier2All = true
                    )
                val max = ledger.maxCellObjective
                if (max != null) {
                    nonNullLedgers++
                    assertThat(max)
                        .describedAs(
                            "iter %d: ledger maxCellObjective (%d) must NOT under-count the true optimum (%d) — an under-count is a wrong 'proven optimal' badge",
                            iteration,
                            max,
                            trueOptimum
                        ).isGreaterThanOrEqualTo(trueOptimum)
                    if (max == trueOptimum) tightLedgers++
                }
            }
        }
        assertThat(totalCompared).describedAs("the fuzz must actually exercise a healthy number of exact-certified cells").isGreaterThan(40)
        assertThat(tightCells)
            .describedAs("the exact certifier must be TIGHT (== CP-SAT) on most cells (%d/%d), not merely a loose bound", tightCells, totalCompared)
            .isGreaterThanOrEqualTo(totalCompared / 2)
        assertThat(nonNullLedgers).describedAs("a healthy number of pools must drive the production two-tier ledger (not all shape-bail)").isGreaterThanOrEqualTo(8)
        assertThat(
            tightLedgers
        ).describedAs("the certificate must be TIGHT (ledger == true optimum) on most certifying pools, not merely a loose upper bound").isGreaterThanOrEqualTo(5)
    }

    /**
     * The coupling panel fixture (level 50): a pool + sub set that exercises every certifier world — an epic
     * item + epic-bound DI sub, four socket carriers, two distinct rings, AP + crit items, a critM anchor for
     * the conversion sub, a 2H weapon for the weapon-split, and the conversion / crit-secret / light-weapons /
     * featherweight / permanent-crit shapes. Shared by the CP-SAT `≥` panel and the fast-pass `≥` locks.
     */
    private fun couplingPanel(): Triple<WakfuBestBuildParams, Map<ItemType, List<Equipment>>, List<Sublimation>> {
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "EpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600), rarity = Rarity.EPIC),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.ACTION_POINT to 2), maxShardSlots = 4),
                equipment(4, ItemType.RING, "Ring1", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400, Characteristic.CRITICAL_HIT to 10)),
                equipment(5, ItemType.RING, "Ring2", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400)),
                equipment(6, ItemType.CAPE, "Cape", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.DAMAGE_INFLICTED to 50)),
                equipment(7, ItemType.BOOTS, "Boots", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400, Characteristic.ACTION_POINT to 2), maxShardSlots = 3),
                equipment(8, ItemType.CHEST_PLATE, "Chest", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_CRITICAL to 400), maxShardSlots = 3),
                equipment(9, ItemType.TWO_HANDED_WEAPONS, "PanelStaff", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 700))
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(9001, "FlatDi", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 30)),
                sublimation(9002, "CritStart", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.CRITICAL_HIT to 20)),
                sublimation(9005, "CritPermanent", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.CRITICAL_HIT to 15), permanent = true),
                sublimation(9003, "EpicDi", SublimationRarity.EPIC, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 40)),
                sublimation(
                    9004,
                    "CondDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.STATIC_CONDITIONAL,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 25),
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, 30)
                ),
                sublimation(
                    9006,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "UnravelPanel",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                ),
                sublimation(
                    9007,
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    "FeatherPanel",
                    perStatStep =
                        SublimationEffect.PerStatStep(
                            source = Characteristic.MOVEMENT_POINT,
                            threshold = 4,
                            perStep = 6,
                            cap = 24,
                            target = Characteristic.DAMAGE_INFLICTED
                        )
                ),
                sublimation(
                    9009,
                    SublimationRarity.NORMAL,
                    SublimationKind.STATIC_CONDITIONAL,
                    "LwePanel",
                    effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 500)),
                    condition = SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
                ),
                sublimation(
                    9008,
                    SublimationRarity.EPIC,
                    SublimationKind.STATIC_CONDITIONAL,
                    "CritSecretPanel",
                    effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 30)),
                    condition = SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, 0)
                )
            )
        return Triple(params, pool, subs)
    }

    /**
     * FATAL-under-count lock (P2.5): the FAST tier-1 pass must UPPER-BOUND the exact pass on EVERY non-bailed
     * cell. A single `fast[a] < exact[a]` is release-blocking — the fast pass would certify a build below a
     * real one and void the production proof. Also prints the tightness canary `fast[max]/exact[max]` (the
     * over-count ratio; expected small once the ∃-gates are tuned). (While the fast pass delegates to the
     * exact pass this is `fast == exact`; it becomes load-bearing when the shared 4-D DP lands.)
     */
    @Test
    fun `max-damage fast certifier upper-bounds the exact certifier on every cell`() {
        val (params, pool, subs) = couplingPanel()
        val (exact, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(fast).describedAs("the fast pass must populate every exact cell").containsOnlyKeys(exact.keys)
        assertThat(exact.values.any { it > 0 })
            .describedAs("the panel must actually certify (not bail on every cell)")
            .isTrue()
        var maxExact = 0L
        var fastAtMax = 0L
        for ((ap, exactObj) in exact.toSortedMap()) {
            println("FAST_VS_EXACT ap=$ap exact=$exactObj fast=${fast[ap]} ${if ((fast[ap] ?: -1L) < exactObj && exactObj >= 0) "UNDER!" else ""}")
        }
        for ((ap, exactObj) in exact) {
            // Where the exact pass BAILS (-1, apHigh < -apOff), it punts to CP-SAT; the fast pass — which runs
            // at the loosest cell — instead harvests that cell as a real sound bound (0 if infeasible). That is
            // fast being MORE capable, not an under-count, so there is no exact value to check — skip it.
            if (exactObj < 0) continue
            val fastObj = fast[ap] ?: -1L
            assertThat(fastObj)
                .describedAs("AP=%d: fast (%d) must UPPER-BOUND exact (%d) — an under-count is fatal", ap, fastObj, exactObj)
                .isGreaterThanOrEqualTo(exactObj)
            if (exactObj > maxExact) {
                maxExact = exactObj
                fastAtMax = fastObj
            }
        }
        if (maxExact > 0) {
            println("FAST_TIGHTNESS panel max: exact=$maxExact fast=$fastAtMax ratio=${"%.4f".format(fastAtMax.toDouble() / maxExact)}")
        }
    }

    /**
     * B7 soundness lock: the sharpened TIER-1.5 pass must sit within `fast ≥ tier1.5 ≥ exact` on EVERY non-bailed
     * cell of the coupling panel (the shape that exercises every certifier world). tier-1.5 is a step-1 refinement
     * of the fast pass, so it must never fall below the exact pass (a fatal under-count → wrong badge) and never
     * rise above the tier-1 fast pass (it can only tighten). Also prints the tightness canary showing it lands
     * at-or-below the tier-1 bound.
     */
    @Test
    fun `max-damage tier-1_5 certifier sits between fast and exact on every cell`() {
        val (params, pool, subs) = couplingPanel()
        val (exact, fast, tier15) =
            WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(tier15).describedAs("tier-1.5 must populate every exact cell").containsOnlyKeys(exact.keys)
        assertThat(exact.values.any { it > 0 }).describedAs("the panel must actually certify (not bail on every cell)").isTrue()
        var tighterThanFast = 0
        for ((ap, exactObj) in exact) {
            val tier15Obj = tier15[ap] ?: -1L
            if (tier15Obj < 0) continue // tier-1.5 bailed on this cell ⇒ nothing to order
            val fastObj = fast[ap] ?: -1L
            if (exactObj >= 0) {
                assertThat(tier15Obj)
                    .describedAs("AP=%d: tier-1.5 (%d) must UPPER-BOUND exact (%d) — an under-count is fatal", ap, tier15Obj, exactObj)
                    .isGreaterThanOrEqualTo(exactObj)
            }
            if (fastObj >= 0) {
                assertThat(fastObj)
                    .describedAs("AP=%d: fast (%d) must UPPER-BOUND tier-1.5 (%d)", ap, fastObj, tier15Obj)
                    .isGreaterThanOrEqualTo(tier15Obj)
                if (tier15Obj < fastObj) tighterThanFast++
            }
        }
        // Non-vacuous: on this crit-banded panel the step-1 fold is STRICTLY tighter than the coarse tier-1 pass on
        // at least one cell (else B7 would never clear a survivor the fast pass could not).
        assertThat(tighterThanFast).describedAs("tier-1.5 must strictly tighten the fast bound on at least one panel cell").isGreaterThan(0)
    }

    /**
     * B7 behavioral lock: the tier-1.5 pre-filter must let the incumbent-driven ledger CLEAR at least one survivor
     * WITHOUT the exact pass — i.e. fewer exact confirmations than the pure-fast elimination would leave. With the
     * incumbent set just below a cell whose fast bound overshoots but whose tier-1.5 bound does not, that cell is
     * retired on tier-1.5 alone (not in [CertLedger.tier2Cells]), yet the ledger stays sound (≥ the true optimum).
     */
    @Test
    fun `max-damage certifyLedger clears a survivor via tier-1_5 without the exact pass`() {
        val (params, pool, subs) = couplingPanel()
        val (exact, fast, tier15) =
            WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        // Find a cell the fast pass CANNOT eliminate but tier-1.5 CAN, for an incumbent between the two bounds.
        val gapCell =
            exact.keys.firstOrNull { ap ->
                val f = fast[ap] ?: -1L
                val t = tier15[ap] ?: -1L
                f >= 0 && t in 0 until f
            }
        assertThat(gapCell).describedAs("the panel must have a cell where tier-1.5 < fast (else B7 is untested here)").isNotNull()
        val ap = gapCell!!
        val incumbent = tier15.getValue(ap) // fast[ap] > incumbent ⇒ survives fast; tier15[ap] ≤ incumbent ⇒ cleared
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = incumbent
            )
        assertThat(ledger.cellObjectives[ap])
            .describedAs("the gap cell keeps its (sound) tier-1.5 bound")
            .isEqualTo(tier15.getValue(ap))
        assertThat(ledger.tier2Cells)
            .describedAs("the gap cell must be retired by tier-1.5, NOT confirmed by the exact pass")
            .doesNotContain(ap)
        assertThat(ledger.tier15Objectives).describedAs("the cleared cell records its tier-1.5 bound for the cache").containsKey(ap)
    }

    /**
     * Soundness lock (P2.5): the FAST tier-1 pass must upper-bound the proven CP-SAT cell-max on the coupling
     * panel — the same guarantee the exact pass has, now for the fast bound the production badge will use.
     */
    @Test
    fun `max-damage fast certifier upper-bounds every CP-SAT cell-max`() {
        val (params, pool, subs) = couplingPanel()
        val (_, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        var compared = 0
        for ((ap, fastObj) in fast) {
            if (fastObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            assertThat(fastObj)
                .describedAs("AP=%d: fast (%d) must upper-bound the CP-SAT objective (%d)", ap, fastObj, profile.objective)
                .isGreaterThanOrEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell was compared against CP-SAT").isGreaterThan(0)
    }

    /**
     * P3.1 warm-once parallel-safety lock: the FAST pass computed with a thread pool must produce a ledger
     * BYTE-IDENTICAL to the serial pass, on EVERY iteration. A single mismatch means a lazy CpModel-var cache
     * MISS raced under parallelism (`CpModel` is not thread-safe). The warm-once design runs one world serially
     * first so every cache key the other worlds read is populated single-threaded; the rest are then pure
     * reads. The coupling panel exercises all 6 worlds, so it covers every cached term path. **If this ever
     * flakes, parallelism must ship OFF (threads = 1) — correctness first** (see CERTIFICATE_PROD_PLAN.md §P3.1).
     */
    @Test
    fun `max-damage fast certifier parallel pass equals the serial pass every iteration`() {
        val (params, pool, subs) = couplingPanel()
        val serial = WakfuBuildSolver.certifierFastLedgerForTest(params, pool, sublimations = subs, applyDomination = false, threads = 1)
        assertThat(serial.values.any { it > 0 })
            .describedAs("the panel must certify (not bail on every cell)")
            .isTrue()
        repeat(20) { i ->
            val parallel = WakfuBuildSolver.certifierFastLedgerForTest(params, pool, sublimations = subs, applyDomination = false, threads = 4)
            assertThat(parallel)
                .describedAs("iteration %d: the parallel fast ledger must byte-match the serial ledger", i)
                .isEqualTo(serial)
        }
    }

    /**
     * P3.2 Increment A lock #1: with `forceTier2All` the two-tier orchestrator confirms EVERY non-bailed cell
     * through the exact tier, so its ledger must reproduce the standalone exact per-cell map bit-for-bit. The
     * `tier2Cells` set is exactly the cells the exact pass confirms; cells where the exact pass itself bails
     * (-1 in the exact map) keep the sound fast bound and are deliberately NOT tier-2 (that is not a bail).
     */
    @Test
    fun `max-damage certifyLedger forceTier2All reproduces the exact per-cell map`() {
        val (params, pool, subs) = couplingPanel()
        val exact = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = null,
                forceTier2All = true
            )
        assertThat(ledger.bailedCells).describedAs("the panel shape is supported ⇒ the fast pass does not bail").isEmpty()
        val exactConfirmed = exact.filterValues { it >= 0 }.keys
        assertThat(exactConfirmed).describedAs("at least one cell is confirmed exactly").isNotEmpty
        assertThat(ledger.tier2Cells)
            .describedAs("tier-2 cells == the cells the exact pass confirms")
            .isEqualTo(exactConfirmed)
        for (a in exactConfirmed) {
            assertThat(ledger.cellObjectives[a])
                .describedAs("AP=%d: the forceTier2All ledger value equals the exact map", a)
                .isEqualTo(exact[a])
        }
        assertThat(ledger.maxCellObjective)
            .describedAs("maxCellObjective is the max exact-confirmed objective")
            .isEqualTo(exact.values.filter { it >= 0 }.max())
    }

    /**
     * P3.2 Increment A lock #2: with the incumbent set to the exact max, every cell whose (loose) fast bound is
     * `≤ incumbent` is eliminated on the fast value alone; the strict survivors (fast `>` incumbent) are the
     * only cells confirmed exactly. Verifies the elimination boundary, that survivors carry their exact value
     * (or a sound tier-1.5 bound ≤ fast when not confirmed exactly — B7), that eliminated cells keep fast and
     * never enter tier-2, and the badge-compatible `maxCellObjective ≥ incumbent`.
     */
    @Test
    fun `max-damage certifyLedger eliminates cells at or below the incumbent`() {
        val (params, pool, subs) = couplingPanel()
        val (exact, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        val incumbent = exact.values.filter { it >= 0 }.max()
        val ledger =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = incumbent,
                forceTier2All = false
            )
        assertThat(ledger.bailedCells).isEmpty()
        val survivors = fast.filterValues { it >= 0 && it > incumbent }.keys
        val eliminated = fast.keys - survivors
        assertThat(survivors).describedAs("the loose fast bound leaves at least one survivor above the incumbent").isNotEmpty
        assertThat(ledger.tier2Cells).describedAs("tier-2 cells are a subset of the survivors").isSubsetOf(survivors)
        for (a in survivors) {
            val cellObj = ledger.cellObjectives[a]!!
            if (a in ledger.tier2Cells) {
                assertThat(cellObj).describedAs("exact-confirmed survivor AP=%d carries its exact value", a).isEqualTo(exact[a])
            } else {
                // B7: a survivor NOT confirmed exactly was retired on its (sound, tighter) tier-1.5 bound — cleared
                // ≤ incumbent, or kept after an exact bail. Either way it sits within `exact ≤ cellObj ≤ fast`.
                assertThat(cellObj).describedAs("un-confirmed survivor AP=%d keeps a bound ≤ fast", a).isLessThanOrEqualTo(fast[a]!!)
                if ((exact[a] ?: -1L) >= 0) {
                    assertThat(cellObj).describedAs("un-confirmed survivor AP=%d bound ≥ its exact value", a).isGreaterThanOrEqualTo(exact[a]!!)
                }
            }
        }
        for (a in eliminated) {
            assertThat(fast[a]!!).describedAs("eliminated AP=%d has fast ≤ incumbent", a).isLessThanOrEqualTo(incumbent)
            assertThat(a).describedAs("eliminated AP=%d is not tier-2", a).isNotIn(ledger.tier2Cells)
            assertThat(ledger.cellObjectives[a]).describedAs("eliminated AP=%d keeps the fast bound", a).isEqualTo(fast[a])
        }
        assertThat(ledger.maxCellObjective).describedAs("no cell bailed ⇒ a max exists").isNotNull
        assertThat(ledger.maxCellObjective!!)
            .describedAs("maxCellObjective ≥ incumbent (the badge condition)")
            .isGreaterThanOrEqualTo(incumbent)
    }

    /**
     * B1 flood-control lock: for a required-target incumbent whose proxy sits far below the unconstrained cell
     * ceilings, elimination leaves MANY survivors, each a minutes-long exact DP — but the badge is already lost,
     * so `certifyLedger` must confirm survivors from the loosest fast bound down and STOP the exact tier the moment
     * one exact value exceeds the incumbent, keeping the rest at their (sound) fast bound. This asserts the exact
     * tier runs on far fewer cells than the full (no-incumbent) pass while the ledger stays a sound upper bound.
     * Verdict preservation (a ProvenOptimal request confirms every survivor exactly) is covered by the existing
     * `eliminates cells at or below the incumbent` test, whose incumbent = the exact max ⇒ no early stop.
     */
    @Test
    fun `max-damage certifyLedger flood-controls the exact tier once the badge is lost`() {
        val (params, pool, subs) = couplingPanel()
        val (exact, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)

        // Baseline: no incumbent ⇒ every non-bailed cell is confirmed exactly (the pre-B1 amount of exact work).
        val full =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = null,
                forceTier2All = false
            )
        assertThat(full.tier2Cells.size).describedAs("the panel confirms several cells without an incumbent").isGreaterThan(1)

        // A deeply-constrained incumbent (below every cell's ceiling) ⇒ the badge is lost. B1 stops the exact tier
        // at the FIRST survivor whose exact exceeds the incumbent instead of confirming them all.
        val lowIncumbent = 1L
        val flood =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = lowIncumbent,
                forceTier2All = false
            )
        // Flood control stops right after the FIRST survivor whose exact exceeds the incumbent, so at most one
        // cell is confirmed exactly (vs the many `full` confirms) — this is the assertion that fails if the
        // early-stop is removed (it would then confirm every survivor like `full`).
        assertThat(flood.tier2Cells.size)
            .describedAs("flood control confirms at most one cell exactly, far fewer than the full pass")
            .isLessThanOrEqualTo(1)

        // ...but the ledger stays SOUND: confirmed cells carry their exact value, the rest keep their (sound,
        // tier-1.5-tightened, still ≤ fast) bound, and the badge condition maxCellObjective ≥ incumbent holds.
        for (a in flood.cellObjectives.keys) {
            if (a in flood.tier2Cells) {
                assertThat(flood.cellObjectives[a]).describedAs("confirmed cell %d carries its exact value", a).isEqualTo(exact[a])
            } else {
                // B7: un-confirmed survivors carry their (sound, tighter) tier-1.5 bound — ≤ the fast bound, never above.
                assertThat(flood.cellObjectives[a]!!)
                    .describedAs("un-confirmed cell %d keeps a sound bound ≤ fast", a)
                    .isLessThanOrEqualTo(fast[a]!!)
            }
        }
        assertThat(flood.maxCellObjective!!)
            .describedAs("the ledger remains a sound global upper bound")
            .isGreaterThanOrEqualTo(lowIncumbent)
    }

    /**
     * B2 lock: the memory-aware certifier thread formula. A stock ~4 GiB heap must resolve to 1 (serial — no OOM),
     * a tiny heap floors at 1 (never 0), larger heaps open more workers, and the count is capped by both 6 and
     * `cores − 1` so it never starves the UI/OS. Pure function ⇒ deterministic, no native/OR-Tools dependency.
     */
    @Test
    fun `certifierThreadsForHeap is heap-aware, floors at 1, and respects the core cap`() {
        val gib = 1024L * 1024 * 1024
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(4 * gib, cores = 16)).describedAs("-Xmx4g ⇒ 1 (no OOM)").isEqualTo(1)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(1 * gib, cores = 16)).describedAs("tiny heap floors at 1").isEqualTo(1)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(8 * gib, cores = 16)).describedAs("8 GiB ⇒ (8-2)/1.25 = 4").isEqualTo(4)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(64 * gib, cores = 16)).describedAs("huge heap capped at 6").isEqualTo(6)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(64 * gib, cores = 4)).describedAs("cores−1 cap = 3").isEqualTo(3)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(64 * gib, cores = 2)).describedAs("cores−1 cap = 1").isEqualTo(1)
        assertThat(WakfuBuildSolver.certifierThreadsForHeap(64 * gib, cores = 1)).describedAs("single core ⇒ 1").isEqualTo(1)
    }

    /**
     * Tier-1.5's own sizing: its (cell × world) step-1 fast DPs are ~0.4 GiB each (measured at lvl-245), not
     * the ~1 GiB exact DPs — so a stock heap must open MULTIPLE tier-1.5 workers (the exact-tier formula left
     * production's dominant certificate stage serial), while tiny heaps still floor at 1 and the same
     * 6 / cores−1 caps hold.
     */
    @Test
    fun `certifierTier15ThreadsForHeap opens parallel workers on a stock heap`() {
        val gib = 1024L * 1024 * 1024
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(4 * gib, cores = 16)).describedAs("-Xmx4g ⇒ (4-1.5)/0.4 = 6").isEqualTo(6)
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(3 * gib, cores = 16)).describedAs("the packaged GUI's -Xmx3g ⇒ 3").isEqualTo(3)
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(1 * gib, cores = 16)).describedAs("tiny heap floors at 1").isEqualTo(1)
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(64 * gib, cores = 16)).describedAs("huge heap capped at 6").isEqualTo(6)
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(64 * gib, cores = 4)).describedAs("cores−1 cap = 3").isEqualTo(3)
        assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(64 * gib, cores = 1)).describedAs("single core ⇒ 1").isEqualTo(1)
    }

    /** Fast-world sizing: heavier than a tier-1.5 task (~0.6 GiB), at most 5 worlds remain after warm-once. */
    @Test
    fun `certifierFastWorldThreadsForHeap opens parallel worlds on a stock heap`() {
        val gib = 1024L * 1024 * 1024
        assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(4 * gib, cores = 16)).describedAs("-Xmx4g ⇒ (4-1.5)/0.6 = 4").isEqualTo(4)
        assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(3 * gib, cores = 16)).describedAs("-Xmx3g ⇒ 2").isEqualTo(2)
        assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(1 * gib, cores = 16)).describedAs("tiny heap floors at 1").isEqualTo(1)
        assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(64 * gib, cores = 16)).describedAs("huge heap capped at 5 (worlds)").isEqualTo(5)
        assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(64 * gib, cores = 2)).describedAs("cores−1 cap = 1").isEqualTo(1)
    }

    /**
     * B8 lock #1 (contract): a cancelled certificate bails (a sound over-count), returns null, and — crucially —
     * is NEVER cached, so a cancelled proof leaves no partial ledger behind. The same shape certifies and caches
     * normally when not cancelled, proving the null + empty-cache is caused by cancellation, not the shape.
     */
    @Test
    fun `a cancelled certificate returns null and is not cached`() {
        val (params, pool, subs) = couplingPanel()
        MaxDamageCertificateCache.clear()
        val cancelled =
            MaxDamageCertificateCache.certificate(
                params,
                pool,
                emptyList(),
                subs,
                applyDomination = false,
                incumbentObjective = null,
                threads = 1,
                isCancelled = { true }
            )
        assertThat(cancelled).describedAs("a cancelled proof returns null (no badge)").isNull()
        assertThat(MaxDamageCertificateCache.size).describedAs("a cancelled proof writes no cache entry").isZero()

        val normal =
            MaxDamageCertificateCache.certificate(
                params,
                pool,
                emptyList(),
                subs,
                applyDomination = false,
                incumbentObjective = null,
                threads = 1
            )
        assertThat(normal).describedAs("the same shape certifies fine when not cancelled").isNotNull
        assertThat(MaxDamageCertificateCache.size).describedAs("a completed proof IS cached").isEqualTo(1)
        MaxDamageCertificateCache.clear()
    }

    /**
     * B8 lock #2 (wiring reaches the DP): the certifier polls the cancellation flag once per DP STAGE — many
     * times over a full run — not merely once at the orchestrator boundary. A never-cancel counting probe over a
     * normal run must be polled far more than the ≈1 time the post-check alone would; removing the per-stage hook
     * drops the count to ~1, so the `> 5` margin fails. This is what makes cancellation actually PROMPT.
     */
    @Test
    fun `the certifier polls the cancellation flag once per DP stage`() {
        val (params, pool, subs) = couplingPanel()
        var polls = 0
        WakfuBuildSolver.maxDamageCertificate(
            params,
            pool,
            sublimations = subs,
            applyDomination = false,
            incumbentObjective = null,
            threads = 1,
            isCancelled = {
                polls++
                false
            }
        )
        assertThat(polls).describedAs("per-stage polling ⇒ many polls, not the ~1 of an orchestrator-only check").isGreaterThan(5)
    }

    /**
     * B4 lock: the session cache is INCUMBENT-FREE — the raw per-cell parts (fast array + exact-by-cell) are cached
     * under a key that excludes the incumbent, so a re-search of the same shape with a DIFFERENT incumbent
     * reconstructs its ledger from cache (no model build, no DP) instead of recomputing. Asserts: (a) a second
     * incumbent reuses the same key (cache stays size 1); (b) reconstruction never invokes the certifier (the B8
     * cancellation probe is polled zero times — it only runs inside the DP); (c) the reconstructed ledger is
     * byte-identical to a fresh compute for that incumbent.
     */
    @Test
    fun `certificate cache is incumbent-free and reconstructs a repeat proof without recomputing`() {
        val (params, pool, subs) = couplingPanel()
        MaxDamageCertificateCache.clear()
        // Cold run with no incumbent ⇒ every cell's exact value is computed and cached in the incumbent-free entry.
        val cold =
            MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = null, threads = 1)!!
        assertThat(MaxDamageCertificateCache.size).isEqualTo(1)

        val incumbent = cold.maxCellObjective!! / 2 // below the top ceiling ⇒ real survivors ⇒ a non-trivial elimination

        // B7: the incumbent-driven ledger runs the tier-1.5 pre-filter, whose per-survivor bounds are
        // incumbent-scoped raw parts the null cold run did not compute. The FIRST proof at this incumbent may
        // therefore recompute (merging tier-1.5 + the surviving exacts into the SAME incumbent-free entry — no new
        // key); a REPEAT of the same shape+incumbent is then a full cache hit.
        val primed =
            MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = incumbent, threads = 1)!!
        assertThat(MaxDamageCertificateCache.size).describedAs("a different incumbent reuses the SAME (incumbent-free) key — no new entry").isEqualTo(1)

        var polls = 0
        val reconstructed =
            MaxDamageCertificateCache.certificate(
                params,
                pool,
                emptyList(),
                subs,
                applyDomination = false,
                incumbentObjective = incumbent,
                threads = 1,
                isCancelled = {
                    polls++
                    false
                }
            )!!
        assertThat(MaxDamageCertificateCache.size).isEqualTo(1)
        assertThat(polls).describedAs("a repeat proof is a full cache hit — reconstructed without invoking the certifier DP").isZero()

        // The identity contract: reconstruct == the compute path AT EQUAL CACHE STATE. (Since the cold
        // run cached every exact value, both the priming compute and the reconstruction decide survivors
        // from those cached exacts — TIGHTER than a cache-less fresh compute's tier-1.5 bounds, so the
        // old fresh-compute comparison no longer applies; soundness vs fresh is asserted per cell below.)
        assertThat(reconstructed).describedAs("the reconstructed ledger is byte-identical to the primed compute").isEqualTo(primed)
        val fresh =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = incumbent,
                forceTier2All = false,
                threads = 1
            )
        for ((cell, bound) in reconstructed.cellObjectives) {
            assertThat(bound)
                .describedAs("cell %d: the cache-informed bound must be at most the fresh (looser) bound", cell)
                .isLessThanOrEqualTo(fresh.cellObjectives.getValue(cell))
        }
        MaxDamageCertificateCache.clear()
    }

    /**
     * B5 lock: with the disk cache enabled, a proof survives a cold in-memory cache (an "app restart"). Priming
     * the shape at an incumbent persists its raw bounds to disk; after wiping the in-memory cache, a repeat proof
     * loads them back and reconstructs a ledger byte-identical to a fresh compute WITHOUT invoking the certifier
     * DP (the B8 cancellation probe is polled zero times — it only runs inside the DP).
     */
    @Test
    fun `certificate disk cache reconstructs a proof across a cold in-memory cache`() {
        val (params, pool, subs) = couplingPanel()
        val tmp =
            java.nio.file.Files
                .createTempDirectory("certcache")
        val previousDir = MaxDamageCertificateDiskCache.directory
        MaxDamageCertificateDiskCache.directory = tmp
        MaxDamageCertificateCache.clear()
        try {
            val cold =
                MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = null, threads = 1)!!
            val incumbent = cold.maxCellObjective!! / 2 // a non-trivial elimination ⇒ real survivors
            // Prime the entry at this incumbent (decides survivors from the cold run's cached exacts, and
            // PERSISTS the merged entry).
            val primed =
                MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = incumbent, threads = 1)!!

            // Simulate an app restart: wipe the in-memory cache but keep the on-disk files.
            MaxDamageCertificateCache.clear()
            assertThat(MaxDamageCertificateCache.size).isZero()

            var polls = 0
            val reconstructed =
                MaxDamageCertificateCache.certificate(
                    params,
                    pool,
                    emptyList(),
                    subs,
                    applyDomination = false,
                    incumbentObjective = incumbent,
                    threads = 1,
                    isCancelled = {
                        polls++
                        false
                    }
                )!!
            assertThat(polls).describedAs("a disk hit reconstructs without invoking the certifier DP").isZero()
            assertThat(MaxDamageCertificateCache.size).describedAs("the disk record was loaded into the in-memory cache").isEqualTo(1)

            // Same contract as the in-memory lock: identity vs the compute at equal cache state, and
            // per-cell soundness (≤) vs a cache-less fresh compute.
            assertThat(reconstructed).describedAs("the disk-reconstructed ledger is byte-identical to the primed compute").isEqualTo(primed)
            val fresh =
                WakfuBuildSolver.certifyLedgerForTest(
                    params,
                    pool,
                    sublimations = subs,
                    applyDomination = false,
                    incumbentObjective = incumbent,
                    forceTier2All = false,
                    threads = 1
                )
            for ((cell, bound) in reconstructed.cellObjectives) {
                assertThat(bound)
                    .describedAs("cell %d: the cache-informed bound must be at most the fresh (looser) bound", cell)
                    .isLessThanOrEqualTo(fresh.cellObjectives.getValue(cell))
            }
        } finally {
            MaxDamageCertificateDiskCache.directory = previousDir
            MaxDamageCertificateCache.clear()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * B5 soundness lock: the disk layer round-trips a record, misses on a different fingerprint (a version bump or
     * any request change lands on a different file), and silently ignores a corrupted file — so a stale/garbage
     * file can only ever cost a recompute, never serve a wrong bound.
     */
    @Test
    fun `certificate disk cache round-trips a record and rejects a mismatched or corrupted file`() {
        val tmp =
            java.nio.file.Files
                .createTempDirectory("certcache")
        val previousDir = MaxDamageCertificateDiskCache.directory
        MaxDamageCertificateDiskCache.directory = tmp
        try {
            val record =
                DiskRecord(
                    fingerprint = "shape-A",
                    fastObjectives = mapOf(0 to 10L, 1 to 20L),
                    bailed = emptySet(),
                    exactByCell = mapOf(1 to 18L),
                    exactBailed = emptySet(),
                    tier15ByCell = mapOf(1 to 19L),
                    // E8 item A Phase 2: the winning (world, crit-step) provenance round-trips like every other field.
                    provByCell = mapOf(1 to CellProvenance(worldIndex = 0, c = 25))
                )
            MaxDamageCertificateDiskCache.store("shape-A", record)
            assertThat(MaxDamageCertificateDiskCache.load("shape-A")).describedAs("a stored record round-trips").isEqualTo(record)

            // A different fingerprint (e.g. after a CERTIFIER_VERSION / WakfuData.VERSION bump) lands on a different
            // file name ⇒ the old file is never read.
            assertThat(MaxDamageCertificateDiskCache.load("shape-B")).describedAs("a different fingerprint misses").isNull()

            // Corrupt shape-A's file ⇒ checksum/parse fails ⇒ silent miss (⇒ the caller recomputes; always sound).
            val files =
                java.nio.file.Files
                    .list(tmp)
                    .use { stream -> stream.toList() }
            assertThat(files).hasSize(1)
            java.nio.file.Files
                .writeString(files.single(), "not-a-valid-cache-file{{{")
            assertThat(MaxDamageCertificateDiskCache.load("shape-A")).describedAs("a corrupt file is ignored").isNull()
        } finally {
            MaxDamageCertificateDiskCache.directory = previousDir
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * E8 item A Phase 2 backward-compat lock: a record written BEFORE [DiskRecord.provByCell] existed (its JSON has no
     * `provByCell` key) still decodes — the field defaults to empty, so the fast-path just falls back to the full
     * provenance scan for that shape. This additive-optional decoding is exactly why Phase 2 needs NO CERTIFIER_VERSION
     * bump and no oracle re-run: an old on-disk certificate stays valid, only losing the provenance shortcut.
     */
    @Test
    fun `disk record predating provenance decodes with an empty provByCell`() {
        val legacyBody =
            """{"fingerprint":"legacy","fastObjectives":{"0":10},"bailed":[],"exactByCell":{"0":10},"exactBailed":[],"tier15ByCell":{}}"""
        val decoded =
            kotlinx.serialization.json.Json
                .decodeFromString(DiskRecord.serializer(), legacyBody)
        assertThat(decoded.provByCell).describedAs("a pre-provenance record decodes with an empty provByCell").isEmpty()
        assertThat(decoded.fingerprint).describedAs("the rest of the legacy record is intact").isEqualTo("legacy")
    }

    /**
     * B5 injectivity lock: the disk fingerprint changes with EVERY ledger-affecting request field (missing one =
     * two requests collide to one file = a wrong badge — the forbidden failure), and is unchanged by the four
     * fields the cache key normalizes away (so duration / worker-count / AP-pin tweaks still hit).
     */
    @Test
    fun `certificate fingerprint changes with every ledger-affecting field and ignores the normalized ones`() {
        val (base, pool, subs) = couplingPanel()

        fun fp(p: WakfuBestBuildParams) = MaxDamageCertificateCache.fingerprintForTest(p, pool, emptyList(), subs, applyDomination = false)
        val baseline = fp(base)

        // Normalized-away fields must NOT change the fingerprint.
        assertThat(fp(base.copy(searchDuration = 999.seconds))).describedAs("duration is normalized away").isEqualTo(baseline)
        assertThat(fp(base.copy(stopWhenBuildMatch = !base.stopWhenBuildMatch))).describedAs("stop-on-match is normalized away").isEqualTo(baseline)
        assertThat(fp(base.copy(maxDamageApTarget = 12))).describedAs("AP pin is normalized away").isEqualTo(baseline)
        assertThat(fp(base.copy(solverWorkers = 3))).describedAs("worker count is normalized away").isEqualTo(baseline)

        // Every ledger-affecting field must change it.
        assertThat(fp(base.copy(character = base.character.copy(level = base.character.level + 5)))).describedAs("level").isNotEqualTo(baseline)
        assertThat(fp(base.copy(character = base.character.copy(minLevel = base.character.minLevel + 1)))).describedAs("minLevel").isNotEqualTo(baseline)
        assertThat(fp(base.copy(character = base.character.copy(clazz = CharacterClass.IOP)))).describedAs("class").isNotEqualTo(baseline)
        val perturbedSkills =
            CharacterSkills(base.character.level).apply {
                intelligence.getCharacteristics().first { it.maxPointsAssignable >= 1 }.setPointAssigned(1)
            }
        assertThat(
            fp(base.copy(character = Character(base.character.clazz, base.character.level, base.character.minLevel, perturbedSkills)))
        ).describedAs("skill allocation").isNotEqualTo(baseline)
        assertThat(fp(base.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 11)))))).describedAs("targets").isNotEqualTo(baseline)
        assertThat(fp(base.copy(maxRarity = Rarity.RELIC))).describedAs("maxRarity").isNotEqualTo(baseline)
        assertThat(fp(base.copy(excludedRarities = setOf(Rarity.EPIC)))).describedAs("excludedRarities").isNotEqualTo(baseline)
        assertThat(fp(base.copy(forcedItems = listOf("X")))).describedAs("forcedItems").isNotEqualTo(baseline)
        assertThat(fp(base.copy(excludedItems = listOf("Y")))).describedAs("excludedItems").isNotEqualTo(baseline)
        assertThat(fp(base.copy(scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT))).describedAs("mode").isNotEqualTo(baseline)
        assertThat(fp(base.copy(useRunes = !base.useRunes))).describedAs("useRunes").isNotEqualTo(baseline)
        assertThat(fp(base.copy(forcedRunes = listOf("R")))).describedAs("forcedRunes").isNotEqualTo(baseline)
        assertThat(fp(base.copy(forcedRunesByItem = mapOf("Item" to listOf(1, 2))))).describedAs("forcedRunesByItem").isNotEqualTo(baseline)
        assertThat(fp(base.copy(useSublimations = !base.useSublimations))).describedAs("useSublimations").isNotEqualTo(baseline)
        assertThat(fp(base.copy(maxSublimationTier = 1))).describedAs("maxSublimationTier").isNotEqualTo(baseline)
        assertThat(fp(base.copy(forcedSublimations = listOf("S")))).describedAs("forcedSublimations").isNotEqualTo(baseline)
        assertThat(fp(base.copy(forcedPassives = listOf("P")))).describedAs("forcedPassives").isNotEqualTo(baseline)
        assertThat(fp(base.copy(damageScenario = base.damageScenario.copy(element = SpellElement.WATER)))).describedAs("scenario element").isNotEqualTo(baseline)
        assertThat(fp(base.copy(damageScenario = base.damageScenario.copy(berserk = true)))).describedAs("scenario berserk").isNotEqualTo(baseline)
        assertThat(fp(base.copy(damageScenario = base.damageScenario.copy(critCapPercent = 50)))).describedAs("scenario critCap").isNotEqualTo(baseline)
        assertThat(
            fp(base.copy(damageScenario = base.damageScenario.copy(elementResistances = mapOf(SpellElement.FIRE to 20))))
        ).describedAs("scenario resistances").isNotEqualTo(baseline)

        // Pool / rune / sublimation identity.
        assertThat(MaxDamageCertificateCache.fingerprintForTest(base, pool, emptyList(), emptyList(), applyDomination = false)).describedAs("sub pool").isNotEqualTo(baseline)
        assertThat(MaxDamageCertificateCache.fingerprintForTest(base, pool, emptyList(), subs, applyDomination = true)).describedAs("domination").isNotEqualTo(baseline)
    }

    /**
     * B5 field-completeness tripwire. If a field is added to any of these request types, this test fails — forcing
     * the author to encode it in [MaxDamageSearch] `fingerprintOf` (or normalize it away in `keyFor`) BEFORE the
     * disk cache can serve a wrong "proven optimal" badge for two requests that differ only in the new field.
     */
    @Test
    fun `certificate fingerprint covers every request field (tripwire)`() {
        fun instanceFieldNames(type: Class<*>): Set<String> =
            type.declaredFields
                .filterNot {
                    it.isSynthetic ||
                        java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.map { it.name }
                .toSet()

        assertThat(instanceFieldNames(WakfuBestBuildParams::class.java))
            .describedAs("a new WakfuBestBuildParams field must be added to fingerprintOf (or normalized in keyFor)")
            .containsExactlyInAnyOrder(
                "character",
                "targetStats",
                "searchDuration",
                "stopWhenBuildMatch",
                "maxRarity",
                "excludedRarities",
                "forcedItems",
                "excludedItems",
                "scoreComputationMode",
                "useRunes",
                "forcedRunes",
                "forcedRunesByItem",
                "useSublimations",
                "maxSublimationTier",
                "forcedSublimations",
                "excludedSublimations",
                "forcedPassives",
                "damageScenario",
                "maxDamageApTarget",
                "solverWorkers"
            )
        assertThat(instanceFieldNames(DamageScenario::class.java))
            .describedAs("a new DamageScenario field must be added to fingerprintOf")
            .containsExactlyInAnyOrder(
                "element",
                "rangeBand",
                "orientation",
                "berserk",
                "healing",
                "critCapPercent",
                "targetResistancePercent",
                "baseDamage",
                "elementResistances",
                "survivabilityFloor",
                "minEffectiveHp"
            )
        // Character carries body-level base-stat fields too; the fingerprint uses only its equals identity.
        assertThat(instanceFieldNames(Character::class.java))
            .describedAs("Character's identity fields must stay covered by the fingerprint")
            .contains("clazz", "level", "minLevel", "characterSkills")
    }

    /**
     * P3.2 Increment B determinism lock: the orchestrator's result must be independent of thread scheduling.
     * Every (cell × world) exact task is a pure function over the warm caches and the merge is a per-cell max,
     * so the parallel ledger must equal the serial one exactly — and be identical run-to-run. `forceTier2All`
     * maximizes the parallel work (every cell's exact pass fans out over all 6 worlds).
     */
    @Test
    fun `max-damage certifyLedger parallel result equals the serial result and is deterministic`() {
        val (params, pool, subs) = couplingPanel()

        fun ledger(threads: Int) =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = null,
                forceTier2All = true,
                threads = threads
            )
        val serial = ledger(1)
        assertThat(serial.tier2Cells).describedAs("the panel confirms cells (there is exact work to parallelize)").isNotEmpty
        val parallelA = ledger(4)
        val parallelB = ledger(4)
        assertThat(parallelA).describedAs("the parallel ledger must equal the serial ledger").isEqualTo(serial)
        assertThat(parallelB).describedAs("the parallel ledger must be run-to-run deterministic").isEqualTo(parallelA)
    }

    /**
     * B6 lock: a recompute can REUSE a prior run's incumbent-independent SCALED fast bounds instead of re-running
     * the ~seconds-to-minutes tier-1 fast DP. Feeding a shape's own fast bounds back in must yield a byte-identical
     * ledger; feeding PERTURBED bounds must change the ledger — proving the precomputed array is actually consumed
     * (the fast DP is skipped), not silently recomputed and the argument ignored.
     */
    @Test
    fun `max-damage certifyLedger reuses precomputed fast bounds instead of recomputing them`() {
        val (params, pool, subs) = couplingPanel()
        // Reference compute (the tier-1 fast DP runs) at a low incumbent ⇒ every cell survives, real confirmation work.
        val reference =
            WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = false, incumbentObjective = 1L)
        assertThat(reference.fastObjectives).describedAs("the panel yields real fast bounds to reuse").isNotEmpty

        // Reuse the reference's own fast bounds ⇒ the fast DP is skipped, the ledger is byte-identical.
        val reused =
            WakfuBuildSolver.maxDamageCertificate(
                params,
                pool,
                emptyList(),
                subs,
                applyDomination = false,
                incumbentObjective = 1L,
                threads = 1,
                precomputedFast = reference.fastObjectives,
                precomputedBailed = reference.bailedCells
            )!!
        assertThat(reused).describedAs("reusing the shape's own fast bounds yields a byte-identical ledger").isEqualTo(reference)

        // Non-vacuous: PERTURBED fast bounds must flow through (proving they are consumed, not ignored/recomputed).
        val perturbed = reference.fastObjectives.mapValues { it.value * 2 + 1 }
        val withPerturbed =
            WakfuBuildSolver.maxDamageCertificate(
                params,
                pool,
                emptyList(),
                subs,
                applyDomination = false,
                incumbentObjective = 1L,
                threads = 1,
                precomputedFast = perturbed,
                precomputedBailed = reference.bailedCells
            )!!
        assertThat(withPerturbed)
            .describedAs("the precomputed fast bounds are load-bearing — perturbing them changes the ledger")
            .isNotEqualTo(reference)
        assertThat(withPerturbed.fastObjectives)
            .describedAs("the ledger echoes the (perturbed) precomputed fast bounds ⇒ the fast DP was skipped")
            .isEqualTo(perturbed)
    }

    /**
     * P4.1 lock: the PRODUCTION certificate API (`maxDamageCertificate`) must return exactly what the tested
     * orchestrator seam produces for the same inputs — it is the same model build + `certifyLedger`, just
     * exposed as a production entry point instead of a test seam.
     */
    @Test
    fun `max-damage production certificate API matches the orchestrator`() {
        val (params, pool, subs) = couplingPanel()
        val viaSeam =
            WakfuBuildSolver.certifyLedgerForTest(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = null,
                forceTier2All = false,
                threads = 1
            )
        val viaApi =
            WakfuBuildSolver.maxDamageCertificate(
                params,
                pool,
                sublimations = subs,
                applyDomination = false,
                incumbentObjective = null,
                threads = 1
            )
        assertThat(viaApi).describedAs("single-element panel ⇒ the production API returns a ledger").isNotNull
        assertThat(viaApi).describedAs("the production API equals the orchestrator seam").isEqualTo(viaSeam)
    }

    /**
     * P4.2 badge lock: `MaxDamageSearch.proveOptimality` must (a) short-circuit to ProvenOptimal when CP-SAT
     * already proved, (b) prove the SAME optimum via the certificate when `isOptimal` is stripped (the whole
     * point — the badge fires when CP-SAT timed out but the certificate closes it), (c) bound the gap with
     * ProvenWithin for a strictly-lower incumbent, and (d) stay Unavailable under forced sublimations. The pool
     * is the crit-free DI-vs-mastery frontier, where the certifier is EXACT (cert == CP-SAT), so the incumbent's
     * objective equals `maxCellObjective` and `incumbent ≥ maxCell` holds with equality.
     */
    @Test
    fun `max-damage proveOptimality proves the optimum via the certificate`() {
        val params = fireMaxDamageParams(50)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000)),
                equipment(3, ItemType.AMULET, "AmuDI", mapOf(Characteristic.DAMAGE_INFLICTED to 100)),
                equipment(4, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000)),
                equipment(5, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 100))
            ).groupBy { it.itemType }
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.isOptimal).describedAs("tiny pool ⇒ CP-SAT proves").isTrue()
        assertThat(best.maxDamageObjective).describedAs("max-damage results carry the CP-SAT objective").isNotNull

        // (a) isOptimal short-circuit.
        assertThat(MaxDamageSearch.proveOptimality(params, pool, emptyList(), emptyList(), best, threads = 1))
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)

        // (b) certificate path: strip isOptimal — the certificate must still prove the optimum.
        val unproven = best.copy(isOptimal = false)
        assertThat(MaxDamageSearch.proveOptimality(params, pool, emptyList(), emptyList(), unproven, threads = 1))
            .describedAs("the certificate proves the optimum CP-SAT left un-closed")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)

        // (c) a strictly-lower incumbent ⇒ ProvenWithin a positive fraction. The proof compares against the
        // UNPENALIZED proxy ([SolverResult.maxDamageRawProxy]) — equal to maxDamageObjective on this no-target
        // request — so lower THAT to simulate a weaker incumbent.
        val proxy = best.maxDamageRawProxy!!
        val below = best.copy(isOptimal = false, maxDamageRawProxy = proxy - proxy / 20)
        val within = MaxDamageSearch.proveOptimality(params, pool, emptyList(), emptyList(), below, threads = 1)
        assertThat(within).isInstanceOf(MaxDamageSearch.MaxDamageProof.ProvenWithin::class.java)
        assertThat((within as MaxDamageSearch.MaxDamageProof.ProvenWithin).fraction).isGreaterThan(0.0)

        // (d) P5.3: forced sublimations are no longer blanket-skipped — the certificate decides. A forced sub
        // shape the certifier can't model (a perStatStep ramp — its build-stat-driven valuation is not folded
        // for forced subs) bails ⇒ Unavailable (honest/absent). (Forced flat mastery/critM/crit/AP/MP shapes
        // became creditable, so this case pins the intent — "unmodelable forced shape ⇒ no badge" — on a
        // still-unmodelable shape.)
        val rampSub =
            sublimation(
                9500,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "RampX",
                perStatStep = SublimationEffect.PerStatStep(Characteristic.MOVEMENT_POINT, 4, 6, 24, Characteristic.DAMAGE_INFLICTED)
            )
        assertThat(
            MaxDamageSearch.proveOptimality(
                params.copy(useSublimations = true, forcedSublimations = listOf("RampX")),
                pool,
                emptyList(),
                listOf(rampSub),
                unproven,
                threads = 1
            )
        ).describedAs("a forced sub shape the certifier can't model ⇒ Unavailable").isEqualTo(MaxDamageSearch.MaxDamageProof.Unavailable)
    }

    /**
     * Required-target lock: `proveOptimality` now RUNS for a required-stat-target max-damage request (it was
     * previously blanket-skipped as "penalty units not comparable"). The comparison uses the UNPENALIZED damage
     * proxy ([SolverResult.maxDamageRawProxy]); when the incumbent FULLY meets the target its shortfall multiplier
     * is the flat maximum, so the damage-only certificate certifies the penalized objective:
     *  - a NON-binding AP target the damage-optimal build meets for free ⇒ the certificate proves the SAME optimum
     *    (`proxy ≥ maxCell`), where the OLD gate returned Unavailable;
     *  - an unreachable target the incumbent MISSES ⇒ its multiplier is below max, so a target-meeting build could
     *    out-score it and the certificate cannot rank the penalized objective ⇒ Unavailable (honest absence).
     * Guards the soundness of the gate-lift (a wrong ProvenOptimal here would be a false "proven optimum" badge).
     */
    @Test
    fun `max-damage proveOptimality certifies a required-target request when the target is met`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "AmuMA", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 2)),
                equipment(2, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
            ).groupBy { it.itemType }
        // A modest AP target the damage-optimal build meets for free (base 6 + amulet 2 = 8 ≥ 7) — NON-binding, so
        // the incumbent's damage equals the unconstrained ceiling and the certificate proves it tight (no gap).
        val params = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.maxDamageRawProxy).describedAs("required-target max-damage results carry the unpenalized proxy").isNotNull

        // Strip isOptimal so the CERTIFICATE path (not CP-SAT's own OPTIMAL) decides — the whole point of the lift.
        val unproven = best.copy(isOptimal = false)
        assertThat(MaxDamageSearch.proveOptimality(params, pool, emptyList(), emptyList(), unproven, threads = 1))
            .describedAs("a met, non-binding required target ⇒ the certificate proves the optimum (was blanket-Unavailable before)")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)

        // A target the build CANNOT reach ⇒ the incumbent misses it ⇒ its multiplier is below max ⇒ Unavailable.
        val unreachable = params.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 99))))
        val bestUnreachable =
            runBlocking { MaxDamageSearch.run(unreachable, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(MaxDamageSearch.proveOptimality(unreachable, pool, emptyList(), emptyList(), bestUnreachable.copy(isOptimal = false), threads = 1))
            .describedAs("an unmet required target ⇒ certificate cannot rank the penalized objective ⇒ Unavailable")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.Unavailable)
    }

    /**
     * A5 lock: the AP-window probe planner drops any probe pinned below a required AP target. A probe pins AP == N
     * (an `addEquality` in `buildMaxDamageObjective`) while the hard leg additionally posts `actual ≥ T`, so a probe
     * with `N < T` is INFEASIBLE by construction and would pay a doomed hard-then-soft double solve for nothing.
     * With required AP 11 the below-target probes (8..10) must be skipped (11 == a0 is always excluded); with no
     * required AP target the full `[a0−3, a0+3]` window (minus a0) survives.
     */
    @Test
    fun `apProbeTargets drops probes below a required AP target`() {
        assertThat(MaxDamageSearch.apProbeTargets(a0 = 11, requiredApTarget = 11))
            .describedAs("probes below the required AP target are infeasible ⇒ skipped (a0 itself also excluded)")
            .containsExactly(12, 13, 14)
        assertThat(MaxDamageSearch.apProbeTargets(a0 = 11, requiredApTarget = MaxDamageSearch.MIN_AP_TARGET))
            .describedAs("no required AP target ⇒ the full window survives (only a0 excluded)")
            .containsExactly(8, 9, 10, 12, 13, 14)
    }

    /**
     * A7 lock (provenance): a hard-constraints-leg result carries [SolverResult.maxDamageHardConstraintsMet] = true
     * because the solver enforced every required target (`actual ≥ target` in its exact arithmetic), while a
     * soft-leg fallback (unreachable target) carries it as false. `proveOptimality` trusts this flag as the
     * authoritative "targets met" verdict instead of re-deriving it through the scorer — the two use different,
     * deliberately-irreducible percent-rounding (solver `tPercent` vs scorer `applyPercent`), so a hard-leg build
     * can be solver-feasible yet read one point short on the scorer grid for HP (the only percent-affected required
     * target). This locks the plumbing that soundness relies on.
     */
    @Test
    fun `hard-leg max-damage results carry the hard-constraints-met provenance flag`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "AmuMA", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 2)),
                equipment(2, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
            ).groupBy { it.itemType }
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)

        // Reachable AP target (base 6 + amulet 2 = 8 ≥ 7) ⇒ the hard leg produces the build ⇒ the flag is set.
        val reachable = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
        val bestReachable =
            runBlocking { MaxDamageSearch.run(reachable, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(bestReachable.maxDamageHardConstraintsMet)
            .describedAs("a hard-leg (solver-enforced-target) result is marked hard-constraints-met")
            .isTrue

        // Unreachable AP target ⇒ the hard leg is INFEASIBLE ⇒ the soft fallback produces the build ⇒ flag false.
        val unreachable = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 99))))
        val bestUnreachable =
            runBlocking { MaxDamageSearch.run(unreachable, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(bestUnreachable.maxDamageHardConstraintsMet)
            .describedAs("a soft-leg fallback (unreachable target) is NOT hard-constraints-met")
            .isFalse
    }

    /**
     * C2 lock: the hard-leg infeasibility pre-screen. A required target ABOVE its reachable ceiling makes the hard
     * model provably infeasible, so `optimize` skips the doomed CP-SAT solve entirely (falling straight to the soft
     * leg). Asserts the static flag directly (model build only, no solve): a reachable AP target is NOT flagged; an
     * unreachable one IS. `reach.last` is a sound over-estimate, so this can never falsely flag a feasible model.
     */
    @Test
    fun `C2 hard-leg pre-screen flags an unreachable required target, not a reachable one`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "AmuMA", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 2)),
                equipment(2, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
            ).groupBy { it.itemType }
        // Base AP 6 + amulet 2 = 8 reachable (plus a little from skills): target 7 fits, 99 cannot.
        val reachable = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
        val unreachable = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 99))))
        assertThat(WakfuBuildSolver.maxDamageStaticallyInfeasibleForTest(reachable, pool))
            .describedAs("a reachable AP target must NOT be pre-screened as infeasible (would drop a feasible hard leg)")
            .isFalse
        assertThat(WakfuBuildSolver.maxDamageStaticallyInfeasibleForTest(unreachable, pool))
            .describedAs("an unreachable AP target (99 vs a reachable max ~8) is pre-screened as statically infeasible")
            .isTrue
    }

    /**
     * C3 lock: the domination memo must be PER-ELEMENT, never per-search. The filter's `compared` set includes the
     * SCORED element's mastery (via `scenarioMasteryStats`), so the same pool filters differently per element — a
     * fire scenario dominates away a water-only item and vice-versa. Sharing one element's result across elements
     * would prune the true per-element optimum (a wrong badge). This asserts the memoized filter keyed on the
     * (element-carrying) [DominationShape] returns element-appropriate — and therefore DIFFERENT — pools, and that
     * each matches the direct un-memoized filter.
     */
    @Test
    fun `C3 domination memo is per-element (fire and water shapes filter the same pool differently)`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "FireAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500)),
                equipment(2, ItemType.AMULET, "WaterAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 500))
            ).groupBy { it.itemType }
        val fireParams = fireMaxDamageParams(50)
        val waterParams = fireParams.copy(damageScenario = fireParams.damageScenario.copy(element = SpellElement.WATER))
        val fireShape = dominationShape(fireParams, emptyList())!!
        val waterShape = dominationShape(waterParams, emptyList())!!

        val fireFiltered = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, fireShape)
        val waterFiltered = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, waterShape)

        // Fire scenario keeps the fire item (the water-only item is dominated on the compared fire mastery); water
        // keeps the water item — so the two filtered pools genuinely DIFFER (the memo must not collapse them).
        assertThat(fireFiltered.getValue(ItemType.AMULET).map { it.equipmentId })
            .describedAs("under a FIRE shape the water-only amulet is dominated away")
            .containsExactly(1)
        assertThat(waterFiltered.getValue(ItemType.AMULET).map { it.equipmentId })
            .describedAs("under a WATER shape the fire-only amulet is dominated away")
            .containsExactly(2)
        // And the memoized result is identical to the direct un-memoized filter (transparency).
        assertThat(fireFiltered)
            .isEqualTo(filterDominatedPool(pool, fireShape.pinned, fireShape.compared, fireShape.minimized))
        assertThat(waterFiltered)
            .isEqualTo(filterDominatedPool(pool, waterShape.pinned, waterShape.compared, waterShape.minimized))
    }

    /**
     * A1 lock (part 2, end-to-end): a HARD-constrained max-damage solve with domination ON — the production shape
     * — over a pool where the ONLY item that can satisfy a resistance target carries that resistance on the
     * GENERIC line. Before the fix, domination pruned that item (its generic-resistance feeder was not compared),
     * so the hard model went falsely INFEASIBLE (hasSolution=false) and the improvement `f2d6994d` shipped was
     * silently lost. With the fix the item survives, the hard model is FEASIBLE, the item is picked, and the built
     * build meets the target. Uses [WakfuBuildSolver.maxDamageSolveForTest] so domination is forced ON alongside a
     * pinned (deterministic) tuning — the production `optimize` disables domination whenever a tuning is set.
     */
    @Test
    fun `hard-constrained solve with domination keeps the only item meeting a generic-resistance target`() {
        val a = equipment(1, ItemType.AMULET, "MasteryOnly", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
        val b =
            equipment(
                2,
                ItemType.AMULET,
                "GenericResist",
                mapOf(Characteristic.RESISTANCE_ELEMENTARY to 50, Characteristic.MASTERY_ELEMENTARY_FIRE to 200)
            )
        val pool = mapOf(ItemType.AMULET to listOf(a, b))
        // Single-element resistance target (⇒ no item prefilter). LEVEL 1 so the character has a single Intelligence
        // point (max 10 generic resistance) and no Major-branch resistance (unlocks at 25): skills can supply at most
        // 10, far below the target of 40, so item B (generic 50) is the ONLY way to satisfy it. If domination prunes
        // B the hard model is INFEASIBLE — which is exactly the pre-fix behaviour this test guards against.
        val params =
            fireMaxDamageParams(1)
                .copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 40))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)

        val outcome =
            WakfuBuildSolver.maxDamageSolveForTest(
                params,
                pool,
                tuning,
                tightDomains = true,
                applyDomination = true,
                hardConstraints = true
            )
        assertThat(outcome.hasSolution)
            .describedAs("with the feeder fix, domination keeps the generic-resistance item ⇒ the hard model is FEASIBLE")
            .isTrue
        assertThat(outcome.selectedEquipmentIds)
            .describedAs("the only item that can satisfy the resistance target must be the one picked")
            .contains(2)

        // The built build actually meets the target (belt-and-braces over the in-model hard constraint).
        val chosen = (pool[ItemType.AMULET] ?: emptyList()).filter { it.equipmentId in outcome.selectedEquipmentIds }
        val stats =
            computeCharacteristicsValues(
                BuildCombination(chosen, CharacterSkills(1)),
                params.character.baseCharacteristicValues,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1),
                mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 1)
            )
        assertThat(stats.getOrDefault(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 0))
            .describedAs("the returned build meets the fire-resistance target")
            .isGreaterThanOrEqualTo(40)
    }

    /**
     * A1 lock (part 2): the item prefilter is a top-N-per-stat HEURISTIC that can prune the true optimum, so a
     * request that trips it (here an AGGREGATE `RESISTANCE_ELEMENTARY` target ⇒ 4 wanted resistance elements ⇒
     * `needsItemPrefilter`) must NEVER earn a "proven optimal" badge — NEITHER via CP-SAT's own `OPTIMAL` over the
     * reduced pool NOR via the certificate (which is built through the same prefilter and would under-count). This
     * asserts `proveOptimality` withholds the badge even for an `isOptimal = true` incumbent — the gate is the only
     * thing standing between a prefiltered solve and a WRONG badge (invariant: a proven badge must be truly global).
     */
    @Test
    fun `proveOptimality withholds the badge for a prefiltered (multi-element resistance) request`() {
        val pool =
            listOf(equipment(1, ItemType.AMULET, "Amu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000)))
                .groupBy { it.itemType }
        // Aggregate resistance target ⇒ resistanceElementsWanted has all four elements ⇒ needsItemPrefilter is true.
        val params =
            fireMaxDamageParams(50)
                .copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 40))))
        // An incumbent flagged OPTIMAL: without the prefilter gate the `isOptimal` short-circuit would (wrongly)
        // return ProvenOptimal. The gate must pre-empt it.
        val optimalIncumbent =
            SolverResult(
                individual = BuildCombination(emptyList(), CharacterSkills(50)),
                matchPercentage = BigDecimal.ONE,
                progressPercentage = 100,
                isOptimal = true,
                maxDamageRawProxy = 1L
            )
        assertThat(MaxDamageSearch.proveOptimality(params, pool, emptyList(), emptyList(), optimalIncumbent, threads = 1))
            .describedAs("a prefiltered request can never be soundly certified ⇒ badge withheld even when isOptimal")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.Unavailable)
    }

    /**
     * A2 lock #1: a BINDING AP target — one reachable only via an item that COSTS damage — must be ENFORCED by
     * `addRequiredTargetHardConstraints`, not merely nudged. Base AP is 6; the target of 8 needs +2 AP, carried
     * ONLY by `ApTax` (which gives up 1500 fire mastery relative to `FreeDmg`). So the constrained optimum MUST
     * drop the higher-mastery amulet for `ApTax`. With the hard constraints neutered the plain-damage solve keeps
     * `FreeDmg` and misses the target — exactly the mutation A2 proved the original three tests could not catch
     * (their AP target was non-binding by construction).
     */
    @Test
    fun `max-damage hard-constraints enforce a binding AP target`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "FreeDmg", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000)),
                equipment(
                    2,
                    ItemType.AMULET,
                    "ApTax",
                    mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.ACTION_POINT to 2)
                ),
                equipment(3, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
            ).groupBy { it.itemType }
        val params =
            fireMaxDamageParams(50)
                .copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 8))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!

        val names =
            best.individual.equipments
                .map { it.name.fr }
                .toSet()
        assertThat(names)
            .describedAs("the binding AP target forces the AP amulet and rejects the higher-mastery one")
            .contains("ApTax")
            .doesNotContain("FreeDmg")
        val stats =
            computeCharacteristicsValues(
                best.individual,
                params.character.baseCharacteristicValues,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1),
                emptyMap()
            )
        assertThat(stats.getOrDefault(Characteristic.ACTION_POINT, 0))
            .describedAs("the returned build meets AP ≥ 8")
            .isGreaterThanOrEqualTo(8)
        assertThat(best.isOptimal)
            .describedAs("a met AP target under a plain damage objective proves OPTIMAL directly")
            .isTrue
    }

    /**
     * A2 lock #2: an aggregate `RESISTANCE_ELEMENTARY` target is the MIN over the four elements
     * (`requiredActualStat`'s min-of-four), NOT a sum / any-element aggregate. `AllRes` (generic +50 ⇒ 50 on every
     * element) is the only single amulet whose per-element MINIMUM clears 40; `FireOnly` (specific fire 100, 0
     * elsewhere) carries more fire mastery and would win under any wrong aggregate that reads fire alone — but its
     * minimum is 0. Same slot (AMULET, capacity 1) forces the choice — a RING (capacity 2) would let the solver
     * equip BOTH and make the assertion vacuous. LEVEL 1 so skill points supply at most 10 generic resistance (one
     * Intelligence point, no Major branch) — far below 40 — so an ITEM is genuinely required (a level-50 character
     * would satisfy the target from skills and keep the max-mastery FireOnly). Asserting ALL FOUR per-element
     * actuals ≥ 40 kills both a drift to sum/any-element semantics AND the neutered-hard-constraint mutation.
     */
    @Test
    fun `max-damage hard-constraints enforce an aggregate resistance target as the min of four elements`() {
        val pool =
            listOf(
                equipment(
                    1,
                    ItemType.AMULET,
                    "FireOnly",
                    mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 100, Characteristic.MASTERY_ELEMENTARY_FIRE to 800)
                ),
                equipment(
                    2,
                    ItemType.AMULET,
                    "AllRes",
                    mapOf(Characteristic.RESISTANCE_ELEMENTARY to 50, Characteristic.MASTERY_ELEMENTARY_FIRE to 200)
                )
            ).groupBy { it.itemType }
        val params =
            fireMaxDamageParams(1)
                .copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 40))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!

        val names =
            best.individual.equipments
                .map { it.name.fr }
                .toSet()
        assertThat(names)
            .describedAs("only the all-elements amulet lifts the per-element MINIMUM to the target")
            .contains("AllRes")
            .doesNotContain("FireOnly")
        val stats =
            computeCharacteristicsValues(
                best.individual,
                params.character.baseCharacteristicValues,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1),
                params.targetStats.resistanceElementsWanted
            )
        assertThat(WakfuBuildSolver.ELEMENTARY_RESISTANCES.map { stats.getOrDefault(it, 0) })
            .describedAs("every one of the four elemental resistances meets the aggregate target (min-of-four)")
            .allMatch { it >= 40 }
    }

    /**
     * Hard-constraints-first lock #1 (the whole point): a required target the damage-optimal build meets FOR
     * FREE reduces the hard model to the unconstrained problem, so CP-SAT proves OPTIMAL directly (no soft
     * penalty product, no certificate needed) and returns the FULL-damage build. The old soft-penalty path
     * returned an un-proven (and sometimes strictly worse) build for the same request.
     */
    @Test
    fun `max-damage hard-constraints-first proves a vacuous AP target directly`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "AmuMA", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 2)),
                equipment(2, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000))
            ).groupBy { it.itemType }
        // Base 6 + amulet 2 = 8 ≥ 7, so the target never binds: the hard model == the unconstrained model.
        val params = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.isOptimal)
            .describedAs("a vacuous hard target reduces to the unconstrained solve ⇒ CP-SAT proves OPTIMAL outright")
            .isTrue
        assertThat(best.individual.equipments.map { it.name.fr })
            .describedAs("the proven build is the full-damage build (both mastery items)")
            .contains("AmuMA", "BeltM")
    }

    /**
     * Hard-constraints-first lock #2 (the safety net): an UNREACHABLE target makes the hard model INFEASIBLE.
     * The solve must not return "no build" — it falls back to the soft shortfall penalty and still yields the
     * closest achievable build (the pre-existing behaviour for impossible requests).
     */
    @Test
    fun `max-damage hard-constraints-first falls back to soft when the target is unreachable`() {
        val pool =
            listOf(
                equipment(1, ItemType.AMULET, "AmuMA", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 2))
            ).groupBy { it.itemType }
        // AP ≥ 99 is impossible (base 6 + amulet 2 = 8) ⇒ hard model INFEASIBLE. Without the fallback the flow
        // would emit NOTHING; with it, the soft penalty runs and the search still returns a build. (The soft
        // penalty's power-6 multiplier collapses toward zero on a wildly-unreachable target, so the exact
        // fallback build is unspecified — the guarantee under test is only that a result IS produced.)
        val params = fireMaxDamageParams(50).copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 99))))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 20.0)
        val results = runBlocking { MaxDamageSearch.run(params, pool, emptyList(), tuning).toList() }
        assertThat(results)
            .describedAs("an unreachable hard target must fall back to the soft penalty and still yield a build (not nothing)")
            .isNotEmpty
    }

    /**
     * P5.3 Inc 4 (partial): a build proved with a FORCED normal flat-DI sublimation gets the badge — the
     * blanket forced-sub skip in `proveOptimality` is gone and the certificate now credits the forced sub.
     */
    @Test
    fun `max-damage proveOptimality proves a build with a forced flat-DI sublimation`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.ACTION_POINT to 2), maxShardSlots = 4),
                equipment(3, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 60), maxShardSlots = 4)
            ).groupBy { it.itemType }
        val subs = listOf(sublimation(9001, "FlatDi", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 40)))
        val params = fireMaxDamageParams(50).copy(useSublimations = true, forcedSublimations = listOf("FlatDi"))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), subs, tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.maxDamageObjective).describedAs("the forced-sub solve carries the CP-SAT objective").isNotNull
        // Strip isOptimal so the certificate path runs; it credits the forced FlatDi and must prove the optimum.
        val proof = MaxDamageSearch.proveOptimality(params, pool, emptyList(), subs, best.copy(isOptimal = false), threads = 1)
        assertThat(proof)
            .describedAs("a forced normal flat-DI sub is certified ⇒ ProvenOptimal")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
    }

    /**
     * Phases 2+3 end-to-end badge lock: a build that STACKS a cumulable normal sub (2 copies of the same
     * sublimation, one per carrier) must still be certifiable. The solver stacks the +40% DI sub on two of the
     * three 3-socket carriers (Phase 2), and the certificate's keptSubs pool-duplication (Phase 3) must credit
     * BOTH copies so the incumbent still `≥` the ledger's max-cell bound ⇒ ProvenOptimal. A stacking-blind
     * certifier would under-count the incumbent's own value and withhold the badge — the regression this guards.
     */
    @Test
    fun `max-damage proveOptimality proves a build that stacks a cumulable sublimation`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 3),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.ACTION_POINT to 2), maxShardSlots = 3),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600), maxShardSlots = 3)
            ).groupBy { it.itemType }
        // Cumulable DI sub (maxStackLevel 6 / tier 3 ⇒ maxCopies 2): +40% DI per copy, so the optimum stacks it twice.
        val subs =
            listOf(
                sublimation(
                    9001,
                    "StackDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 40),
                    cumulable = true,
                    maxStackLevel = 6,
                    maxTier = 3
                )
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), subs, tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(
            best.individual.sublimations.values
                .flatten()
                .count { it.name.en == "StackDi" }
        ).describedAs("the max-damage optimum stacks the cumulable DI sub to its 2-copy cap")
            .isEqualTo(2)
        // Strip isOptimal so the certificate path runs; it must credit BOTH stacked copies and still prove the optimum.
        val proof = MaxDamageSearch.proveOptimality(params, pool, emptyList(), subs, best.copy(isOptimal = false), threads = 1)
        assertThat(proof)
            .describedAs("a build stacking a cumulable normal sub is certified ⇒ ProvenOptimal (Phases 2+3 keep the badge)")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
    }

    /**
     * The FORCED-stacking certificate lock. A forced cumulable sub splits across the certifier's two worlds: its
     * BASE copy is credited into the constants (and pre-charges a normal slot via `subCap`), while its
     * `maxCopies − 1` EXTRA copies join the OPTIONAL keptSubs pool. Credit too little and a stacked forced build
     * out-scores its own bound (badge silently lost); credit the base twice and the bound inflates. Both copies
     * must be priced exactly once ⇒ the incumbent meets the ceiling ⇒ ProvenOptimal.
     */
    @Test
    fun `max-damage proveOptimality proves a build that stacks a FORCED cumulable sublimation`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 3),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.ACTION_POINT to 2), maxShardSlots = 3),
                equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600), maxShardSlots = 3)
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(
                    9001,
                    "ForcedStackDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 40),
                    cumulable = true,
                    maxStackLevel = 6,
                    maxTier = 3
                )
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true, forcedSublimations = listOf("ForcedStackDi"))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), subs, tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(
            best.individual.sublimations.values
                .flatten()
                .count { it.name.en == "ForcedStackDi" }
        ).describedAs("the forced cumulable sub still reaches its 2-copy cap")
            .isEqualTo(2)
        val proof = MaxDamageSearch.proveOptimality(params, pool, emptyList(), subs, best.copy(isOptimal = false), threads = 1)
        assertThat(proof)
            .describedAs("base copy in the constants + extra copy in the optional pool ⇒ each priced once ⇒ ProvenOptimal")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
    }

    /**
     * P4.3 + B4 lock: the certificate cache memoizes the INCUMBENT-FREE raw parts. Identical inputs return an
     * equal ledger with no recompute; a DIFFERENT incumbent reuses the SAME entry (reconstructed from cache), so
     * the shape never grows a second entry. (Before B4 the incumbent was part of the key and a different incumbent
     * was a distinct entry; B4 makes it a from-cache reconstruction — see the incumbent-free reconstruction lock.)
     */
    @Test
    fun `max-damage certificate cache memoizes the incumbent-free raw parts`() {
        MaxDamageCertificateCache.clear()
        val (params, pool, subs) = couplingPanel()
        val a =
            MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = null, threads = 1)
        assertThat(a).describedAs("single-element panel ⇒ a ledger").isNotNull
        assertThat(MaxDamageCertificateCache.size).isEqualTo(1)
        val b =
            MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = null, threads = 1)
        assertThat(b).describedAs("identical inputs ⇒ an equal ledger, reconstructed from cache (no recompute)").isEqualTo(a)
        assertThat(MaxDamageCertificateCache.size).describedAs("a hit adds no entry").isEqualTo(1)
        MaxDamageCertificateCache.certificate(params, pool, emptyList(), subs, applyDomination = false, incumbentObjective = 999L, threads = 1)
        assertThat(MaxDamageCertificateCache.size).describedAs("a different incumbent reuses the incumbent-free entry — no new key").isEqualTo(1)
        MaxDamageCertificateCache.clear()
        assertThat(MaxDamageCertificateCache.size).isZero()
    }

    /**
     * Conversion-sub exactness lock (the Unraveling shape — the ONE choosable CONVERSION sub, whose presence
     * used to bail the certifier on EVERY cell of the production pool). Replicates it exactly: EPIC rarity,
     * `CRIT_AT_LEAST 40` condition, 100% critical-mastery → elemental-mastery conversion — on a pool where
     * taking it is the optimum (big critM anchor) but doing so must (a) occupy THE epic-sub slot (a competing
     * epic DI sub exists), (b) require the equipped epic item, and (c) satisfy its crit condition. The
     * certifier's two-pass max must EQUAL the proven AP-pinned CP-SAT cell-max: an over-count here means a
     * forgotten coupling (epic slot / epic item / condition), an under-count a broken conversion fold.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT with a taken Unraveling-like conversion sub`() {
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        val pool =
            listOf(
                // critM is sized (3000) so converting it strictly beats the competing +40 DI epic sub:
                // Δgraw = critM·(400 − 4c) at 100% conversion vs D ×1.4 — the conversion must WIN here.
                equipment(1, ItemType.HELMET, "CritMHelm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.MASTERY_CRITICAL to 3000), maxShardSlots = 3),
                equipment(2, ItemType.AMULET, "EpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200), rarity = Rarity.EPIC),
                equipment(3, ItemType.BELT, "CritBelt", mapOf(Characteristic.CRITICAL_HIT to 40), maxShardSlots = 3),
                equipment(4, ItemType.RING, "MRing", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            ).groupBy { it.itemType }
        val unravelLike =
            sublimation(
                9101,
                SublimationRarity.EPIC,
                SublimationKind.CONVERSION,
                "UnravelLike",
                condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
            )
        val epicDi =
            sublimation(
                9102,
                SublimationRarity.EPIC,
                SublimationKind.FLAT,
                "EpicDi",
                effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 40))
            )
        // Third epic contender — the Critical Secret shape (+30 crit iff the sheet has NO crit mastery).
        // Its world-C split must stay EXACT: taking it means forgoing the conversion/DI epics AND the
        // 3000-critM helm, which CP-SAT arbitrates too.
        val critSecretLike =
            sublimation(
                9103,
                SublimationRarity.EPIC,
                SublimationKind.STATIC_CONDITIONAL,
                "CritSecretLike",
                effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 30)),
                condition = SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, 0)
            )
        val subs = listOf(unravelLike, epicDi, critSecretLike)

        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(cert.values.any { it > 0 })
            .describedAs("the certifier must certify (not bail) with the supported conversion shape present")
            .isTrue()
        var compared = 0
        var conversionBound = false
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            check(profile.status == "OPTIMAL") { "AP=$ap: the == lock needs a PROVEN cell-max, got ${profile.status}" }
            assertThat(certObj)
                .describedAs("AP=%d: certifier (%d) must equal the proven CP-SAT cell-max (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
            // Strictness: the conversion must actually DECIDE at least one compared cell (else the == never
            // exercised the pass-B accounting) — CP-SAT without the conversion sub must be strictly lower.
            val without =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    listOf(epicDi),
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (without.hasSolution && profile.objective > without.objective) conversionBound = true
        }
        assertThat(compared).describedAs("at least one AP cell was compared against CP-SAT").isGreaterThan(0)
        assertThat(conversionBound)
            .describedAs("taking the conversion must beat the no-conversion optimum on some cell")
            .isTrue()
    }

    /**
     * Weapon-tradeoff exactness lock (the Light Weapons Expert shape): a NO_OFFHAND_OR_TWO_HANDED sub whose
     * mastery bonus beats the best two-handed weapon — the optimum takes the sub and plays one-handed-only.
     * The certifier must arbitrate the either/or via its weapon world-split and EQUAL the proven CP-SAT
     * cell-max: crediting the sub next to a two-hander would over-count, dropping the restricted world's
     * one-handed option would under-count.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT on the no-offhand weapon tradeoff`() {
        val params = fireMaxDamageParams(50).copy(useSublimations = true)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300), maxShardSlots = 3),
                equipment(2, ItemType.TWO_HANDED_WEAPONS, "BigStaff", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500)),
                equipment(3, ItemType.ONE_HANDED_WEAPONS, "Wand", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200)),
                equipment(4, ItemType.OFF_HAND_WEAPONS, "Shield", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 150))
            ).groupBy { it.itemType }
        val lweLike =
            sublimation(
                9104,
                SublimationRarity.NORMAL,
                SublimationKind.STATIC_CONDITIONAL,
                "LweLike",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 600)),
                condition = SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
            )
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = listOf(lweLike), applyDomination = false)
        var compared = 0
        var subDecided = false
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    listOf(lweLike),
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            check(profile.status == "OPTIMAL") { "AP=$ap: the == lock needs a PROVEN cell-max, got ${profile.status}" }
            assertThat(certObj)
                .describedAs("AP=%d: certifier (%d) must equal the proven CP-SAT cell-max (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
            val without =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    emptyList(),
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (without.hasSolution && profile.objective > without.objective) subDecided = true
        }
        assertThat(compared).describedAs("at least one AP cell was compared against CP-SAT").isGreaterThan(0)
        assertThat(subDecided)
            .describedAs("the no-offhand sub must beat the weapon world on some cell (else the split is untested)")
            .isTrue()
    }

    /**
     * MP↔mastery coupling exactness lock (the Featherweight shape): an MP-sourced DI ramp is only worth its
     * step value on builds that actually CARRY the MP — and the MP boots compete with the mastery boots for
     * the same slot. Valuing the ramp at pool-max MP while also banking the mastery boots (the old fold) is
     * the fantasy this pool detects: the certifier must value the ramp PER FRONTIER POINT (item MP rides the
     * (di, graw, mp) frontier) and EQUAL the proven CP-SAT cell-max. Level 20 keeps Major (MP/AP) skill
     * points out of the picture, so the arbitration is purely between the two boots.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT on the Featherweight MP tradeoff`() {
        val params = fireMaxDamageParams(20).copy(useSublimations = true)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Helm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300), maxShardSlots = 3),
                // The either/or: mastery boots vs the MP boots that feed the ramp (base MP 3, threshold 4).
                // Both socketed: TWO normal subs (ramp + Swiftness) each need a distinct ≥3-socket carrier.
                equipment(2, ItemType.BOOTS, "MasteryBoots", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400), maxShardSlots = 3),
                equipment(3, ItemType.BOOTS, "MpBoots", mapOf(Characteristic.MOVEMENT_POINT to 2), maxShardSlots = 3)
            ).groupBy { it.itemType }
        val featherLike =
            sublimation(
                9105,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "FeatherLike",
                perStatStep =
                    SublimationEffect.PerStatStep(
                        source = Characteristic.MOVEMENT_POINT,
                        threshold = 4,
                        perStep = 150,
                        cap = 300,
                        target = Characteristic.DAMAGE_INFLICTED
                    )
            )
        // The Swiftness shape: +1 MP that must arrive only WITH its −10 DI (and BEFORE the ramp is valued) —
        // at cap 300 the extra step (+150 via the ramp, −10 direct) is worth taking, so CP-SAT arbitrates it.
        val swiftLike =
            sublimation(
                9106,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "SwiftLike",
                effects =
                    listOf(
                        SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, -10),
                        SublimationEffect.Flat(Characteristic.MOVEMENT_POINT, 1)
                    )
            )
        val subs = listOf(featherLike, swiftLike)
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        var compared = 0
        var rampDecided = false
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 30.0
                )
            if (!profile.hasSolution) continue
            check(profile.status == "OPTIMAL") { "AP=$ap: the == lock needs a PROVEN cell-max, got ${profile.status}" }
            assertThat(certObj)
                .describedAs("AP=%d: certifier (%d) must equal the proven CP-SAT cell-max (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
            val without =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    emptyList(),
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (without.hasSolution && profile.objective > without.objective) rampDecided = true
        }
        assertThat(compared).describedAs("at least one AP cell was compared against CP-SAT").isGreaterThan(0)
        assertThat(rampDecided)
            .describedAs("the MP ramp must beat the mastery boots on some cell (else the coupling is untested)")
            .isTrue()
    }

    /**
     * The two cell-16 certificate fantasies, as a fixture. (1) NEGATIVE-AP items: a Souvenir-like ring
     * (−1 max-AP, big mastery) must be charged its exact cost — the old optimistic per-carrier max floored
     * the negative at 0, so the certifier hosted the ring for free and certified a real AP-(a−1) build
     * into the AP-a cell. (2) SAME-NAME ring pairs: the model forbids two same-fr-name rings (Mythic +
     * Legendary of one ring), but the ring stage paired them. Both inflations must be gone: every
     * certified cell equals the proven CP-SAT cell-max.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT on negative-AP items and same-name ring rarities`() {
        val params = fireMaxDamageParams(50)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.AMULET, "ApAmulet", mapOf(Characteristic.ACTION_POINT to 2, Characteristic.MASTERY_ELEMENTARY_FIRE to 100)),
                equipment(3, ItemType.RING, "Twin", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.MAX_ACTION_POINT to -1), rarity = Rarity.MYTHIC),
                equipment(4, ItemType.RING, "Twin", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.MAX_ACTION_POINT to -1), rarity = Rarity.LEGENDARY),
                equipment(5, ItemType.RING, "Solo", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300)),
                equipment(6, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
            ).groupBy { it.itemType }
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val obj = optimalMaxDamageObjective(params, pool, ap) ?: continue
            assertThat(certObj)
                .describedAs("AP=%d: certifier must equal proven CP-SAT (no free negative-AP ring, no same-name pair)", ap)
                .isEqualTo(obj)
            compared++
        }
        assertThat(compared).describedAs("several AP cells exercised the negative-AP band").isGreaterThan(1)
    }

    /**
     * P5.1 forced-item slot pinning: with a forced NON-ring item the certifier restricts that slot to the
     * forced item AND requires it (a MANDATORY stage), so the exact ledger equals the forced-pinned CP-SAT
     * optimum on every cell — even when the forced item is SUBOPTIMAL, where an unpinned certifier would
     * over-count by picking the better item or leaving the slot empty. (Forced rings/weapons bail — the
     * certifier can't pin their free/combined second slot yet.)
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced item`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.AMULET, "GoodAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2000, Characteristic.ACTION_POINT to 1)),
                equipment(3, ItemType.AMULET, "BadAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100)),
                equipment(4, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
            ).groupBy { it.itemType }
        val params = fireMaxDamageParams(50).copy(forcedItems = listOf("BadAmu"))
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        assertThat(cert.values.any { it > 0 }).describedAs("the certifier certifies the forced pool").isTrue()
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val pinned = optimalMaxDamageObjective(params, pool, ap) ?: continue
            assertThat(certObj)
                .describedAs("AP=%d: certifier must equal the FORCED-pinned CP-SAT optimum", ap)
                .isEqualTo(pinned)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared with the forced item").isGreaterThan(0)
        // The pin is LOAD-BEARING: forcing the worse amulet must strictly lower the certified optimum on some
        // cell vs the free pool (where GoodAmu wins), proving the certifier really restricts + requires it.
        val freeCert = WakfuBuildSolver.certifierCellObjectivesForTest(fireMaxDamageParams(50), pool, applyDomination = false)
        val loweredCell = cert.entries.firstOrNull { it.value > 0 && (freeCert[it.key] ?: 0L) > it.value }
        assertThat(loweredCell).describedAs("forcing the worse amulet lowers the certified optimum on some cell").isNotNull
    }

    /**
     * P5.1 safety fallback: a forced RING (or weapon) is NOT pinnable yet — its free/combined second slot can't
     * be modeled — so the certifier BAILS every cell (`-1`) rather than restrict the whole ring pool and
     * under-count a `forced + free-ring` build. Bail is sound; the badge is honestly absent.
     */
    @Test
    fun `max-damage AP-cell certifier bails on a forced ring`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.RING, "Twin", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800)),
                equipment(3, ItemType.RING, "Solo", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000))
            ).groupBy { it.itemType }
        val params = fireMaxDamageParams(50).copy(forcedItems = listOf("Twin"))
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        assertThat(cert).describedAs("the certifier still runs over the AP cells").isNotEmpty
        assertThat(cert.values).describedAs("a forced ring bails every cell (-1)").allMatch { it == -1L }
    }

    /**
     * P5.3 Inc 1 — forced FLAT sub: forcing a NORMAL FLAT Damage-Inflicted sub must be credited into the
     * certifier's constants + charge a sub slot, so the exact ledger equals the FORCED-pinned CP-SAT optimum on
     * every cell. Uses a crit-free pool where the certifier is EXACT (the coupling panel is only `≥`, so `==`
     * belongs on a tight fixture). (Epic/relic + mastery/crit/AP/MP forced flats bail for now — a later increment.)
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced FLAT sub`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.ACTION_POINT to 2), maxShardSlots = 4),
                equipment(3, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 60), maxShardSlots = 4)
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(9001, "FlatDi", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 40)),
                sublimation(9002, "FlatDi2", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 25))
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true, forcedSublimations = listOf("FlatDi"))
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(cert.values.any { it > 0 }).describedAs("the certifier certifies the forced-FlatDi pool (not all bailed)").isTrue()
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            assertThat(certObj)
                .describedAs("AP=%d: forced-FlatDi certifier (%d) must equal the pinned CP-SAT optimum (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared against forced-FlatDi CP-SAT").isGreaterThan(0)
    }

    /**
     * The per-cell FORCED-STACKING lock. A forced cumulable sub is split across the certifier's two accountings:
     * its base copy folds into the constants (and pre-charges a normal slot via `subCap`), its `maxCopies − 1`
     * extra copies join the optional keptSubs pool. On every reachable AP cell the exact certifier must land
     * EXACTLY on the pinned CP-SAT optimum — under-count is a wrong badge, over-count a ceiling no build can meet.
     *
     * The pool deliberately carries FOUR ≥3-socket items for three normal subs (base + copy + FlatDi2), one of
     * them AP-free: the certifier is carrier-BLIND by design (it only bounds `n ≤ subCap`), so a pool where the
     * carriers bind first would let it legitimately over-count and mask the accounting this test is pinning.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced cumulable sub`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "AmuM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800, Characteristic.ACTION_POINT to 2), maxShardSlots = 4),
                equipment(3, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 60), maxShardSlots = 4),
                equipment(4, ItemType.CAPE, "CapeM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500), maxShardSlots = 4)
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(
                    9001,
                    "StackDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 40),
                    cumulable = true,
                    maxStackLevel = 6,
                    maxTier = 3
                ),
                sublimation(9002, "FlatDi2", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 25))
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true, forcedSublimations = listOf("StackDi"))
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(cert.values.any { it > 0 }).describedAs("the certifier certifies the forced-cumulable pool (not all bailed)").isTrue()
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            assertThat(certObj)
                .describedAs("AP=%d: forced-cumulable certifier (%d) must equal the pinned CP-SAT optimum (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared against forced-cumulable CP-SAT").isGreaterThan(0)
    }

    /**
     * P5.3 Inc 1 load-bearing check (mirrors P5.1's): forcing a NEGATIVE flat-DI sub must LOWER the certified
     * optimum vs the free pool on some cell — proving the forced credit (−DI folded into the constant) AND the
     * slot charge really bind, not a no-op.
     */
    @Test
    fun `max-damage AP-cell certifier forced negative-DI sub lowers the certified optimum`() {
        val (base, pool, panelSubs) = couplingPanel()
        val negDi = sublimation(9010, "NegDi", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to -20))
        val subs = panelSubs + negDi
        val free = WakfuBuildSolver.certifierCellObjectivesForTest(base, pool, sublimations = subs, applyDomination = false)
        val forced =
            WakfuBuildSolver.certifierCellObjectivesForTest(
                base.copy(forcedSublimations = listOf("NegDi")),
                pool,
                sublimations = subs,
                applyDomination = false
            )
        val lowered = forced.entries.firstOrNull { (ap, v) -> v > 0 && (free[ap] ?: 0L) > v }
        assertThat(lowered).describedAs("forcing the −20-DI sub must lower the certified optimum on some cell").isNotNull
    }

    /**
     * P5.3 Inc 1: the `fast ≥ exact` soundness lock must still hold with a forced FLAT sub (THE ONE TRAP — the
     * fast pass must credit the forced sub too, or the eliminated cells under-count fatally).
     */
    @Test
    fun `max-damage fast certifier upper-bounds the exact certifier with a forced FLAT sub`() {
        val (base, pool, subs) = couplingPanel()
        val (exact, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(
                base.copy(forcedSublimations = listOf("FlatDi")),
                pool,
                sublimations = subs,
                applyDomination = false
            )
        assertThat(exact.values.any { it > 0 }).describedAs("the forced panel certifies").isTrue()
        for ((ap, exactObj) in exact) {
            if (exactObj < 0) continue
            assertThat(fast[ap] ?: -1L)
                .describedAs("AP=%d: fast (%d) must upper-bound exact (%d) with a forced FLAT sub", ap, fast[ap], exactObj)
                .isGreaterThanOrEqualTo(exactObj)
        }
    }

    /**
     * P5.3 Inc 2 lock: forcing a NORMAL conditional DI sub (`CondDi`, `CRIT_AT_MOST 30`) credits its DI per
     * state only where the condition holds, matching the pinned CP-SAT optimum per cell. Crit-free-enough pool
     * (crit below the residual band) so `==` is exact.
     *
     * This lock earned its keep before it ever passed: its first RED run exposed that the CP-SAT MODEL credited
     * a forced sub's conditional effect UNCONDITIONALLY (`appliesVar` exempted forced subs from their own
     * condition), letting the solver return invalid builds — e.g. CondDi's +30 DI "applying" at 40 pre-combat
     * crit, 1.65% above any real build. The certifier (which gates exactly) was right; the model was fixed to
     * gate a forced sub's effect on `subVar ∧ condHolds` (the sub stays equipped — and inert — in builds that
     * break the condition). If this ever diverges again, suspect the MODEL side first.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced conditional sub`() {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), maxShardSlots = 4),
                equipment(2, ItemType.AMULET, "CritAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 700, Characteristic.CRITICAL_HIT to 25), maxShardSlots = 4),
                equipment(3, ItemType.BELT, "BeltDI", mapOf(Characteristic.DAMAGE_INFLICTED to 40, Characteristic.ACTION_POINT to 2), maxShardSlots = 4)
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(
                    9001,
                    "CondDi",
                    SublimationRarity.NORMAL,
                    SublimationKind.STATIC_CONDITIONAL,
                    mapOf(Characteristic.DAMAGE_INFLICTED to 30),
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, 30)
                ),
                sublimation(9002, "FlatDi2", SublimationRarity.NORMAL, SublimationKind.FLAT, mapOf(Characteristic.DAMAGE_INFLICTED to 15))
            )
        val params = fireMaxDamageParams(50).copy(useSublimations = true, forcedSublimations = listOf("CondDi"))
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(cert.values.any { it > 0 }).describedAs("the certifier certifies the forced-CondDi pool").isTrue()
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            assertThat(certObj)
                .describedAs("AP=%d: forced-CondDi certifier (%d) must equal the pinned CP-SAT optimum (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared against forced-CondDi CP-SAT").isGreaterThan(0)
    }

    /**
     * Shared fixture for the P5.3 forced-special locks: the Unraveling-lock trio — a CONVERSION, a flat-DI and
     * a Critical-Secret sub, all EPIC, competing for THE ≤1 epic-sub slot on a critM-heavy pool. Same shape the
     * choosable exactness lock uses, so forcing each contender pins the exact same arbitration.
     */
    private fun forcedSpecialTrio(): Triple<Map<ItemType, List<Equipment>>, List<Sublimation>, WakfuBestBuildParams> {
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "CritMHelm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.MASTERY_CRITICAL to 3000), maxShardSlots = 3),
                equipment(2, ItemType.AMULET, "EpicAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200), rarity = Rarity.EPIC),
                equipment(3, ItemType.BELT, "CritBelt", mapOf(Characteristic.CRITICAL_HIT to 40), maxShardSlots = 3),
                equipment(4, ItemType.RING, "MRing", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300))
            ).groupBy { it.itemType }
        val subs =
            listOf(
                sublimation(
                    9201,
                    SublimationRarity.EPIC,
                    SublimationKind.CONVERSION,
                    "UnravelForce",
                    condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                    conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
                ),
                sublimation(
                    9202,
                    SublimationRarity.EPIC,
                    SublimationKind.FLAT,
                    "EpicDiForce",
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 40))
                ),
                sublimation(
                    9203,
                    SublimationRarity.EPIC,
                    SublimationKind.STATIC_CONDITIONAL,
                    "CritSecretForce",
                    effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 30)),
                    condition = SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, 0)
                )
            )
        return Triple(pool, subs, fireMaxDamageParams(50).copy(useSublimations = true))
    }

    /** Per-cell `certExact == pinned CP-SAT` and `certFast ≥ certExact` for a forced-sub shape; returns the exact map. */
    private fun assertForcedCertMatchesPinnedCpSat(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
    ): Map<Int, Long> {
        val (exact, fast) =
            WakfuBuildSolver.certifierExactAndFastCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        assertThat(exact.values.any { it > 0 }).describedAs("the certifier certifies the forced shape (no bail)").isTrue()
        var compared = 0
        for ((ap, certObj) in exact) {
            if (certObj < 0) continue
            assertThat(fast[ap] ?: -1L)
                .describedAs("AP=%d: fast ≥ exact under the forced shape", ap)
                .isGreaterThanOrEqualTo(certObj)
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params.copy(maxDamageApTarget = ap),
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 10.0,
                    applyDomination = false,
                    deterministicLimit = 6.0
                )
            if (!profile.hasSolution) continue
            check(profile.status == "OPTIMAL") { "AP=$ap: the == lock needs a PROVEN cell-max, got ${profile.status}" }
            assertThat(certObj)
                .describedAs("AP=%d: forced certifier (%d) must equal the pinned CP-SAT optimum (%d)", ap, certObj, profile.objective)
                .isEqualTo(profile.objective)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared against pinned CP-SAT").isGreaterThan(0)
        return exact
    }

    /**
     * P5.3 Inc 3: forcing the CONVERSION contender (the Unraveling shape). The certifier keeps the base worlds
     * with the sub EQUIPPED-BUT-INERT (slot charged, zero credit — covering condition-broken / moved-0 builds)
     * plus the conversion-ACTIVE world, drops the Critical-Secret ACTIVE world (both EPIC ⇒ they can never
     * coexist), and must equal the forced-pinned CP-SAT optimum on every cell.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced conversion sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("UnravelForce")), pool, subs)
    }

    /**
     * P5.3 Inc 3: forcing the CRITICAL-SECRET contender. World C (ACTIVE: pre-combat critM zeroed, +30 crit)
     * and the base worlds (INERT: the 3000-critM helm worn, the sub charged but silent) must jointly equal the
     * forced-pinned CP-SAT optimum — whose model now gates the effect on its condition, arbitrating the same
     * helm-vs-crit trade.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced Critical-Secret sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("CritSecretForce")), pool, subs)
    }

    /**
     * P5.3: forcing the EPIC flat-DI contender exercises the epic machinery end-to-end: the forced sub holds
     * THE epic sub slot (the conversion and Critical-Secret ACTIVE worlds are dropped — same rarity), every
     * harvested state must host the epic carrier item, and the choosable epic stage is emptied. Load-bearing:
     * the conversion is the free-pool winner by fixture design, so forcing the weaker EpicDi must strictly
     * LOWER the certified optimum on some cell — proving the pin really binds.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced epic flat-DI sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val free = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = subs, applyDomination = false)
        val forced = assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("EpicDiForce")), pool, subs)
        val lowered = forced.any { (ap, v) -> v >= 0 && (free[ap] ?: -1L) > v }
        assertThat(lowered)
            .describedAs("forcing the weaker epic sub lowers the certified optimum on some cell")
            .isTrue()
    }

    /**
     * P5.3 coverage bail: a forced NON-EPIC conversion coexisting with an EPIC Critical-Secret shape admits a
     * both-ACTIVE build no world covers (they do not share the ≤1-epic-sub slot), so the certifier BAILS every
     * cell rather than under-count it — the badge is honestly absent.
     */
    @Test
    fun `max-damage AP-cell certifier bails on a forced non-epic conversion with a Critical-Secret present`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val normalConv =
            sublimation(
                9204,
                SublimationRarity.NORMAL,
                SublimationKind.CONVERSION,
                "NormalConv",
                condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40),
                conversion = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100)
            )
        val mixed = subs.filter { it.kind != SublimationKind.CONVERSION } + normalConv
        val cert =
            WakfuBuildSolver.certifierCellObjectivesForTest(
                params.copy(forcedSublimations = listOf("NormalConv")),
                pool,
                sublimations = mixed,
                applyDomination = false
            )
        assertThat(cert.values).describedAs("every cell bails (-1)").allMatch { it == -1L }
    }

    /**
     * P5.3 badge, flagship forced-special scenario end-to-end: a search with a FORCED conversion sub proves via
     * the certificate (isOptimal stripped ⇒ the certificate path must re-prove the same optimum).
     */
    @Test
    fun `max-damage proveOptimality proves a build with a forced conversion sublimation`() {
        val (pool, subs, params0) = forcedSpecialTrio()
        val params = params0.copy(forcedSublimations = listOf("UnravelForce"))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), subs, tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.maxDamageObjective).describedAs("the forced-conversion solve carries the CP-SAT objective").isNotNull
        val proof = MaxDamageSearch.proveOptimality(params, pool, emptyList(), subs, best.copy(isOptimal = false), threads = 1)
        assertThat(proof)
            .describedAs("a forced conversion sub is certified ⇒ ProvenOptimal")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
    }

    /**
     * P5.3 forced-effects: a forced COMBAT_CONDITIONAL sub — by far the most commonly forced kind (136 of
     * 232, never solver-choosable) — is EQUIPPED-BUT-INERT in the model ([buildSublimationTerms] skips the
     * kind): its situational effects are never credited, but it charges a sub slot. The certifier must mirror
     * that exactly (zero credit + slot charge), not bail. The huge un-credited DI effect is the honeypot: any
     * accidental crediting would blow the == comparison.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced combat-conditional sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val combat =
            sublimation(
                9210,
                SublimationRarity.NORMAL,
                SublimationKind.COMBAT_CONDITIONAL,
                "CombatForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 500)),
                solverChoosable = false
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("CombatForce")), pool, subs + combat)
    }

    /**
     * P5.3 forced-effects: an EPIC forced combat sub additionally occupies THE epic sub slot — the conversion
     * and Critical-Secret ACTIVE worlds are dropped and every harvested state must host the epic carrier.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced epic combat-conditional sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val combat =
            sublimation(
                9211,
                SublimationRarity.EPIC,
                SublimationKind.COMBAT_CONDITIONAL,
                "EpicCombatForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 500)),
                solverChoosable = false
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("EpicCombatForce")), pool, subs + combat)
    }

    /**
     * P5.3 forced-effects: a forced PERMANENT crit sub (Influence II shape). Its crit joins `critConst` — the
     * sheet floor every pre-combat window reads — so the fixture conversion's CRIT_AT_LEAST-40 gate flips at a
     * different item-crit threshold, and the crit cells shift. Must stay == pinned CP-SAT (whose model feeds
     * the permanent crit to [preCombatStat] the same way).
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced permanent crit sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val influence =
            sublimation(
                9212,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "InfluenceForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 9, appliesBeforeCombat = true))
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("InfluenceForce")), pool, subs + influence)
    }

    /**
     * P5.3 forced-effects: a forced START-OF-COMBAT crit sub (Berserk Critical II shape). Its crit reaches the
     * in-combat total c (band + budget-gap + cEnumMax arithmetic) but must NEVER feed a pre-combat window —
     * the fixture conversion's CRIT_AT_LEAST-40 cannot be satisfied by it.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced start-of-combat crit sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val startCrit =
            sublimation(
                9213,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "StartCritForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 15))
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("StartCritForce")), pool, subs + startCrit)
    }

    /**
     * P5.3 forced-effects: a forced flat MASTERY sub (Carnage II shape) folds into the mastery constant of
     * every world — including the conversion world, where it must NOT be converted (a conversion reads the
     * PRE-sub critM only).
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced mastery sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val carnage =
            sublimation(
                9214,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "CarnageForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 80))
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("CarnageForce")), pool, subs + carnage)
    }

    /**
     * P5.3 forced-effects: a forced flat CRITICAL-MASTERY sub. Its critM stays on the critM axis in every
     * world — un-converted in the conversion world (pre-sub sources only) and legally alive in the
     * Critical-Secret world (a start-of-combat critM lands AFTER the CS condition is read).
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced critical-mastery sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val critM =
            sublimation(
                9215,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "CritMForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_CRITICAL, 200))
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("CritMForce")), pool, subs + critM)
    }

    /**
     * P5.3 forced-effects: a forced PERMANENT AP sub (Vivacity II shape, +1 AP). apConst shifts, so every AP
     * cell's item-AP band and the pre-combat AP windows move by one — the pinned CP-SAT sees the same +1
     * through the stat loop, so the per-cell == comparison verifies the shift end-to-end.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced permanent AP sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val vivacity =
            sublimation(
                9216,
                SublimationRarity.NORMAL,
                SublimationKind.FLAT,
                "VivacityForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.ACTION_POINT, 1, appliesBeforeCombat = true))
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("VivacityForce")), pool, subs + vivacity)
    }

    /**
     * P5.3 forced-effects: a forced SUPPORTED-conditional mastery sub is gated per harvested state (same
     * [subAllowedAt] window the choosable subs use) — credited only where its CRIT_AT_LEAST-40 can hold,
     * inert (slot charged) elsewhere. Mirrors the model's `subVar ∧ condHolds` gate.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced conditional mastery sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val condM =
            sublimation(
                9217,
                SublimationRarity.NORMAL,
                SublimationKind.STATIC_CONDITIONAL,
                "CondMForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY_FIRE, 120)),
                condition = SublimationCondition(SublimationConditionType.CRIT_AT_LEAST, 40)
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("CondMForce")), pool, subs + condM)
    }

    /**
     * P5.3 forced-effects: a forced sub with an UNSUPPORTED condition type keeps the optimistic unconditional
     * credit — on BOTH sides ([appliesVar] returns the pinned subVar; the certifier folds the constants), so
     * the == comparison still holds exactly.
     */
    @Test
    fun `max-damage AP-cell certifier matches pinned CP-SAT with a forced unsupported-condition sub`() {
        val (pool, subs, params) = forcedSpecialTrio()
        val other =
            sublimation(
                9218,
                SublimationRarity.NORMAL,
                SublimationKind.STATIC_CONDITIONAL,
                "OtherCondForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 25)),
                condition = SublimationCondition(SublimationConditionType.OTHER)
            )
        assertForcedCertMatchesPinnedCpSat(params.copy(forcedSublimations = listOf("OtherCondForce")), pool, subs + other)
    }

    /**
     * P5.3 badge end-to-end for the headline player scenario: a search with a FORCED combat-conditional
     * sublimation gets the proven-optimal badge via the certificate.
     */
    @Test
    fun `max-damage proveOptimality proves a build with a forced combat-conditional sublimation`() {
        val (pool, subs0, params0) = forcedSpecialTrio()
        val combat =
            sublimation(
                9219,
                SublimationRarity.NORMAL,
                SublimationKind.COMBAT_CONDITIONAL,
                "RetributionForce",
                effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 120)),
                solverChoosable = false
            )
        val subs = subs0 + combat
        val params = params0.copy(forcedSublimations = listOf("RetributionForce"))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
        val best =
            runBlocking { MaxDamageSearch.run(params, pool, emptyList(), subs, tuning).toList() }
                .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
        assertThat(best.maxDamageObjective).describedAs("the forced-combat-sub solve carries the CP-SAT objective").isNotNull
        val proof = MaxDamageSearch.proveOptimality(params, pool, emptyList(), subs, best.copy(isOptimal = false), threads = 1)
        assertThat(proof)
            .describedAs("a forced combat-conditional sub is certified ⇒ ProvenOptimal")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
    }

    /**
     * OVER-CAP crit cells: item crit rides bundled on gear, so the best-mastery selection can push the
     * arithmetic crit total past the 100 cap (here 60+60+3). Effective crit clamps at the cap, but the
     * old enumeration stopped AT the cap and pruned any state above its band — silently UNDER-counting
     * exactly those builds (a certificate that misses the true optimum is a void proof). The certifier
     * must now value them in over-cap cells at the capped rate and match CP-SAT exactly.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT when item crit exceeds the cap`() {
        val params = fireMaxDamageParams(50)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "CritHelm", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.CRITICAL_HIT to 60)),
                equipment(2, ItemType.AMULET, "CritAmu", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.CRITICAL_HIT to 60)),
                equipment(3, ItemType.BELT, "BeltM", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400))
            ).groupBy { it.itemType }
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val obj = optimalMaxDamageObjective(params, pool, ap) ?: continue
            assertThat(certObj)
                .describedAs("AP=%d: certifier must count the over-cap (120 item crit) build at the capped rate", ap)
                .isEqualTo(obj)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared").isGreaterThan(0)
    }

    /**
     * NEGATIVE total crit clamps to an effective 0 (the scorer's coerceIn floor): an Epaulectriques-like
     * item (−10 crit, best-in-slot mastery) makes the optimum's arithmetic crit 3−10 = −7. The old
     * per-carrier floor valued it at c = 3 (an over-count); the exact charge must instead land the state
     * in the c = 0 clamp band and match CP-SAT, not drop it to the next-best belt.
     */
    @Test
    fun `max-damage AP-cell certifier matches CP-SAT on a negative-crit item worn for its mastery`() {
        val params = fireMaxDamageParams(50)
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, "Anchor", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000)),
                equipment(2, ItemType.BELT, "TaxBelt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 900, Characteristic.CRITICAL_HIT to -10)),
                equipment(3, ItemType.BELT, "PlainBelt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800))
            ).groupBy { it.itemType }
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, applyDomination = false)
        var compared = 0
        for ((ap, certObj) in cert) {
            if (certObj < 0) continue
            val obj = optimalMaxDamageObjective(params, pool, ap) ?: continue
            assertThat(certObj)
                .describedAs("AP=%d: certifier must value the −10-crit belt in the c=0 clamp band", ap)
                .isEqualTo(obj)
            compared++
        }
        assertThat(compared).describedAs("at least one AP cell compared").isGreaterThan(0)
    }

    /**
     * Production-shape regression lock: on the REAL shipped rune + sublimation catalogs (which contain
     * Unraveling, the choosable CONVERSION sub) the certifier must still certify — a new unsupported
     * sub shape landing in the choosable set would silently bail EVERY cell and turn the certifier (and
     * anything wired on it) into dead code in production, which is exactly what happened once.
     */
    @Test
    @Tag("slow")
    fun `max-damage AP-cell certifier does not bail on the shipped sublimation catalog`() {
        val params = fireMaxDamageParams(110).copy(useRunes = true, useSublimations = true)
        val cert =
            WakfuBuildSolver.certifierCellObjectivesForTest(
                params,
                fullEpicPool(110),
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true
            )
        assertThat(cert.values.count { it > 0 })
            .describedAs("the certifier certifies real AP cells on the shipped catalogs: %s", cert.toSortedMap())
            .isGreaterThan(0)
    }

    /** Proven-OPTIMAL max-damage objective for [pool] pinned to [ap], or null when the cell is infeasible. */
    private fun optimalMaxDamageObjective(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        ap: Int,
    ): Long? {
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params.copy(maxDamageApTarget = ap),
                pool,
                emptyList(),
                emptyList(),
                workers = 1,
                seconds = 10.0,
                applyDomination = false,
                deterministicLimit = 6.0
            )
        if (!profile.hasSolution) return null
        check(profile.status == "OPTIMAL") { "AP=$ap: expected OPTIMAL on the tiny synthetic pool, got ${profile.status}" }
        return profile.objective
    }

    /** [optimalMaxDamageObjective] but with sublimations — the pinned CP-SAT cell-max the P6.1 fuzz lock compares against. */
    private fun optimalMaxDamageObjectiveWithSubs(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
        ap: Int,
    ): Long? {
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params.copy(maxDamageApTarget = ap),
                pool,
                emptyList(),
                subs,
                workers = 1,
                seconds = 10.0,
                applyDomination = false,
                deterministicLimit = 6.0
            )
        if (!profile.hasSolution) return null
        check(profile.status == "OPTIMAL") { "AP=$ap: expected OPTIMAL on the tiny synthetic pool, got ${profile.status}" }
        return profile.objective
    }

    /** Deterministic P6.1 fuzz scenario #[iteration]: (params, pool, subs). Shared by the fuzz lock and its diagnostic. */
    private fun fuzzScenario(iteration: Int): Triple<WakfuBestBuildParams, Map<ItemType, List<Equipment>>, List<Sublimation>> {
        val singleSlots =
            listOf(
                ItemType.AMULET,
                ItemType.BELT,
                ItemType.CAPE,
                ItemType.BOOTS,
                ItemType.HELMET,
                ItemType.CHEST_PLATE,
                ItemType.SHOULDER_PADS,
                ItemType.EMBLEM
            )
        val rng = Random(0x6BADC0DE + iteration.toLong())
        val params = fireMaxDamageParams(50).copy(useSublimations = true)

        var nextItemId = 1
        val items = mutableListOf<Equipment>()
        val slotCount = 3 + rng.nextInt(4) // 3–6 single-occupancy slots
        for (slot in singleSlots.shuffled(rng).take(slotCount)) {
            repeat(2 + rng.nextInt(3)) { items += randomFireItem(nextItemId++, slot, "It$slot", rng) } // 2–4 items
        }
        // Optional same-name ring twins (exercises the same-name-ring ban) OR two distinct rings.
        if (rng.nextBoolean()) {
            val twinName = if (rng.nextBoolean()) "TwinRing" else null // null → distinct names
            items += randomFireItem(nextItemId++, ItemType.RING, twinName ?: "RingA", rng)
            items += randomFireItem(nextItemId++, ItemType.RING, twinName ?: "RingB", rng)
        }
        // 0–3 solver-choosable FLAT subs (a NORMAL sub needs a ≥3-socket carrier, which randomFireItem
        // sometimes grants; if none exists the sub simply can't be hosted — CP-SAT and the certifier agree).
        val subs =
            (0 until rng.nextInt(4)).map { s ->
                val permanent = rng.nextBoolean()
                val effect =
                    if (rng.nextBoolean()) {
                        mapOf(Characteristic.DAMAGE_INFLICTED to (5 + rng.nextInt(36)))
                    } else {
                        mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to (50 + rng.nextInt(251)))
                    }
                // ~half the subs are cumulable (maxStackLevel 6 / maxTier 3 ⇒ maxCopies 2), so the fuzz locks the
                // certifier's STACKING bound (the keptSubs pool-duplication) against CP-SAT across all 25 seeds —
                // in whatever 1-or-2-copy realization each random pool's 3-socket carriers happen to allow.
                val cumulable = rng.nextBoolean()
                sublimation(
                    9_000 + iteration * 10 + s,
                    "Sub$iteration-$s",
                    SublimationRarity.NORMAL,
                    SublimationKind.FLAT,
                    effect,
                    permanent = permanent,
                    cumulable = cumulable,
                    maxStackLevel = if (cumulable) 6 else 1,
                    maxTier = if (cumulable) 3 else 1
                )
            }
        return Triple(params, items.groupBy { it.itemType }, subs)
    }

    /**
     * A random damage-relevant item for the P6.1 fuzz lock. Always carries some fire mastery (so DI has
     * something to multiply and the objective is positive), then randomly adds critM, a SMALL crit ± (kept far
     * below the c≈97-100 residual band so the exact certifier stays exact), AP ±, MP, DI, and 0/3/4 sockets.
     * A non-null [fixedName] forces the item's French name (used to mint same-name ring twins).
     */
    private fun randomFireItem(
        id: Int,
        type: ItemType,
        fixedName: String,
        rng: Random,
    ): Equipment {
        val stats = mutableMapOf<Characteristic, Int>(Characteristic.MASTERY_ELEMENTARY_FIRE to (100 + rng.nextInt(1901)))
        if (rng.nextInt(3) == 0) stats[Characteristic.MASTERY_CRITICAL] = 50 + rng.nextInt(351)
        if (rng.nextInt(3) == 0) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(12) - 3 // −3..8
        if (rng.nextInt(3) == 0) stats[Characteristic.ACTION_POINT] = rng.nextInt(5) - 2 // −2..2
        if (rng.nextInt(3) == 0) stats[Characteristic.MOVEMENT_POINT] = rng.nextInt(3) // 0..2
        if (rng.nextInt(3) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 5 + rng.nextInt(46) // 5..50
        val sockets = listOf(0, 0, 3, 4)[rng.nextInt(4)]
        return equipment(id, type, fixedName, stats, maxShardSlots = sockets)
    }

    /**
     * A synthetic solver-choosable sublimation for the certifier coupling tests. [permanent] marks every
     * effect [SublimationEffect.appliesBeforeCombat] — a character-sheet stat that feeds a pre-combat
     * start-of-combat condition (default false = applied at combat start, never fed to a condition).
     */
    private fun sublimation(
        stateId: Int,
        name: String,
        rarity: SublimationRarity,
        kind: SublimationKind,
        effects: Map<Characteristic, Int>,
        condition: SublimationCondition? = null,
        permanent: Boolean = false,
        cumulable: Boolean = false,
        maxStackLevel: Int = 1,
        maxTier: Int = 1,
    ): Sublimation =
        Sublimation(
            stateId = stateId,
            name = I18nText(name, name, name, name),
            rarity = rarity,
            maxStackLevel = maxStackLevel,
            maxTier = maxTier,
            cumulable = cumulable,
            kind = kind,
            solverChoosable = true,
            condition = condition,
            effects = effects.map { (characteristic, value) -> SublimationEffect.Flat(characteristic, value, appliesBeforeCombat = permanent) }
        )
}
