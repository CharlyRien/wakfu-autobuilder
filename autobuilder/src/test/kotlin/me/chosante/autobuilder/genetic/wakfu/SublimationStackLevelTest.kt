package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SublimationStackLevelTest {
    @Test
    fun `forced signed conditional AP credits bail rather than dropping the debit`() {
        val sub =
            me.chosante.common.Sublimation(
                stateId = 999991,
                name = me.chosante.common.I18nText("Signed gate", "Signed gate", "", ""),
                rarity = me.chosante.common.SublimationRarity.NORMAL,
                maxTier = 3,
                maxStackLevel = 4,
                cumulable = true,
                kind = me.chosante.common.SublimationKind.STATIC_CONDITIONAL,
                condition = me.chosante.common.SublimationCondition(me.chosante.common.SublimationConditionType.AP_AT_LEAST, 0),
                effects =
                    listOf(
                        me.chosante.common.SublimationEffect
                            .Flat(Characteristic.ACTION_POINT, -3, valuesByLevel = listOf(-1, -2, -3, -4))
                    )
            )
        val carrier =
            me.chosante.common.Equipment(
                900005,
                900005,
                65,
                me.chosante.common.I18nText("Carrier", "Carrier", "", ""),
                Rarity.LEGENDARY,
                me.chosante.common.ItemType.CAPE,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100),
                maxShardSlots = 4
            )
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 65, 1, CharacterSkills(65)),
                targetStats = TargetStats(emptyList()),
                searchDuration = 10.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = false,
                useSublimations = false,
                forcedSublimations = listOf(sub.name.fr),
                forcedSublimationLevels = mapOf(sub.name.fr to 2)
            )
        val cert = WakfuBuildSolver.certifierCellObjectivesForTest(params, listOf(carrier).groupBy { it.itemType }, sublimations = listOf(sub))
        assertThat(cert).isNotEmpty()
        assertThat(cert.values).allMatch { it < 0 }
    }

    @Test
    fun `forced Neutralite level 2 uses one tier II shard and has no optional extras`(): Unit =
        runBlocking {
            val sub = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 6931 }
            val carrier =
                me.chosante.common.Equipment(
                    equipmentId = 900001,
                    guiId = 900001,
                    level = 65,
                    name = me.chosante.common.I18nText("Carrier", "Carrier", "", ""),
                    rarity = Rarity.LEGENDARY,
                    itemType = me.chosante.common.ItemType.CAPE,
                    characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100),
                    maxShardSlots = 4
                )
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, 65, 1, CharacterSkills(65)),
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
                    searchDuration = 10.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    useSublimations = false,
                    forcedSublimations = listOf(sub.name.fr),
                    forcedSublimationLevels = mapOf(sub.name.fr to 2)
                )
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 10.0)
            for (tier in listOf(3, 1)) {
                val pool = if (tier == 3) listOf(carrier) else listOf(carrier, carrier.copy(equipmentId = 900002, itemType = me.chosante.common.ItemType.HELMET))
                val result =
                    WakfuBuildSolver
                        .optimize(
                            params.copy(maxSublimationTier = tier),
                            pool.groupBy {
                                it.itemType
                            },
                            emptyList(),
                            listOf(sub),
                            tuning,
                            hardConstraints = true
                        ).toList()
                        .last()
                val shards =
                    result.individual.sublimations.values
                        .flatten()
                assertThat(shards).hasSize(if (tier == 3) 1 else 2)
                assertThat(shards.map { it.stackLevel }).containsOnly(2)
                assertThat(shards.map { it.socketTier }).containsOnly(if (tier == 3) 2 else 1)
                assertThat(shards.sumOf { (it.effects.single() as me.chosante.common.SublimationEffect.Flat).value }).isEqualTo(16)
                assertThat(result.isOptimal).isTrue()
            }
            assertThat(params.copy(forcedSublimationLevels = emptyMap()).forcedLevel(sub)).isEqualTo(4)
        }

    @Test
    fun `tester Xelor 65 fire distance request`(): Unit =
        runBlocking {
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.XELOR, 65, 1, CharacterSkills(65)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 4),
                                TargetStat(Characteristic.WAKFU_POINT, 20),
                                TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                                TargetStat(Characteristic.MASTERY_DISTANCE, 1)
                            )
                        ),
                    searchDuration = 120.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    forcedPassives = listOf("Mémoire"),
                    excludedSublimations = listOf("Vélocité II", "Vivacité II", "Visibilité II")
                )
            val results =
                WakfuBuildSolver
                    .optimize(
                        params,
                        WakfuBestBuildFinderAlgorithm.poolFor(params),
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.activeSublimations(params),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 120.0, applyDominationOverride = true),
                        hardConstraints = true
                    ).toList()
            val best = results.last()
            println(
                "TESTER score=${best.matchPercentage} optimal=${best.isOptimal} gear=${best.individual.equipments.map {
                    it.name.fr
                }} subs=${best.individual.sublimations.values.flatten().map { it.name.fr }}"
            )
            assertThat(best.matchPercentage).isGreaterThanOrEqualTo(java.math.BigDecimal("1354"))
            val subs =
                best.individual.sublimations.values
                    .flatten()
            assertThat(subs.filter { it.stateId == 6931 }).hasSize(2)
            assertThat(subs.filter { it.stateId == 8518 }).hasSize(2)
            assertThat(subs.filter { it.stateId in setOf(6931, 8518) }.map { it.stackLevel }).containsOnly(4)
            assertThat(best.isOptimal).isTrue()
        }
}
