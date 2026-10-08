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
    fun `current catalog automatic levels and marginals remain unchanged with exact plans`() {
        var checked = 0
        for (original in WakfuBestBuildFinderAlgorithm.sublimations) {
            for (limit in 1..3) {
                val sub = original.atTierLimit(limit) ?: continue
                if (!sub.stacksByLevel) continue
                val previousLevels = (1..((sub.maxStackLevel + sub.maxTier - 1) / sub.maxTier)).map { minOf(it * sub.maxTier, sub.maxStackLevel) }
                assertThat(sub.automaticStackLevels).describedAs("%s tier cap %d", sub.name.fr, limit).isEqualTo(previousLevels)
                for (k in 1..sub.maxCopies) {
                    val shards = sub.socketedShards(k, 245)
                    assertThat(shards.sumOf { it.socketTier ?: sub.maxTier }).isEqualTo(previousLevels[k - 1])
                    assertThat(shards.map { it.zenithId }).doesNotContain(0)
                    for (effect in sub.effects.filterIsInstance<me.chosante.common.SublimationEffect.StatEffect>()) {
                        for (characterLevel in 1..245) {
                            val previous =
                                sub.magnitudeAtStackLevel(effect, previousLevels[k - 1], characterLevel) -
                                    sub.magnitudeAtStackLevel(effect, if (k == 1) 0 else previousLevels[k - 2], characterLevel)
                            assertThat(sub.marginalMagnitude(effect, k, characterLevel)).isEqualTo(previous)
                        }
                    }
                }
                checked++
            }
        }
        assertThat(checked).isGreaterThan(0)
    }

    @Test
    fun `solver and certificate respect sparse tiers and can replace the first shard`(): Unit =
        runBlocking {
            val original = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 6931 }
            val carriers =
                listOf(me.chosante.common.ItemType.HELMET, me.chosante.common.ItemType.CAPE)
                    .mapIndexed { i, type ->
                        me.chosante.common.Equipment(
                            900020 + i,
                            900020 + i,
                            65,
                            me.chosante.common.I18nText("Carrier $i", "Carrier $i", "", ""),
                            Rarity.LEGENDARY,
                            type,
                            mapOf(
                                Characteristic.MASTERY_ELEMENTARY_FIRE to 100
                            ),
                            maxShardSlots = 4
                        )
                    }.groupBy { it.itemType }
            for (tiers in listOf(setOf(3), setOf(2, 3))) {
                val sub = original.copy(kind = me.chosante.common.SublimationKind.FLAT, condition = null, shardsByTier = original.shardsByTier.filterKeys { it in tiers })
                val params = reviewParams().copy(useSublimations = true)
                val result =
                    WakfuBuildSolver
                        .optimize(
                            params,
                            carriers,
                            emptyList(),
                            listOf(sub),
                            WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 10.0)
                        ).toList()
                        .last()
                assertThat(result.isOptimal).isTrue()
                val shards =
                    result.individual.sublimations.values
                        .flatten()
                assertThat(shards.map { it.socketTier }).containsExactlyElementsOf(if (tiers.size == 1) listOf(3) else listOf(2, 2))
                assertThat(shards.map { it.stackLevel }).containsOnly(if (tiers.size == 1) 3 else 4)
                val certificate = WakfuBuildSolver.certifierCellObjectivesForTest(params, carriers, sublimations = listOf(sub))
                for ((ap, bound) in certificate) {
                    if (bound < 0L) continue
                    val pinned =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            params.copy(maxDamageApTarget = ap),
                            carriers,
                            emptyList(),
                            listOf(sub),
                            workers = 1,
                            seconds = 10.0,
                            applyDomination = false,
                            deterministicLimit = 6.0
                        )
                    if (!pinned.hasSolution) continue
                    assertThat(pinned.status).isEqualTo("OPTIMAL")
                    assertThat(bound).isGreaterThanOrEqualTo(pinned.objective)
                }
            }
        }

    @Test
    fun `sublimation domain low is counted once and a derived gate can avoid its debit`() {
        WakfuBuildSolver.warmUp()
        val sub = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 5158 }
        val model =
            com.google.ortools.sat
                .CpModel()
        val selected = model.newBoolVar("selected")
        val params = reviewParams()
        val stats = StatBuilder(model, params, emptyList(), emptyMap(), emptyMap(), RuneModel.EMPTY, SublimationModel(mapOf(sub to selected), setOf(sub), 65))
        assertThat(stats.reachableSumDomain(listOf(Term(selected, -2L)), 3L)).isEqualTo(1L..3L)
        assertThat(stats.perSubValue(listOf(Term(selected, -2L))).getValue(sub)).isEqualTo(-2L)
        val gate = model.newBoolVar("selected_and_condition")
        stats.tracker.seed(gate, 0L..1L, "gate")
        stats.subDerivedVars[gate] = sub
        assertThat(stats.perSubValue(listOf(Term(gate, -2L))).getValue(sub)).isZero()
    }

    @Test
    fun `forced Maniement two hands cannot under-count the AP above 12 optimum`() {
        val sub = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 5158 }
        val carrier =
            me.chosante.common.Equipment(
                900010,
                900010,
                65,
                me.chosante.common.I18nText("AP carrier", "AP carrier", "", ""),
                Rarity.EPIC,
                me.chosante.common.ItemType.HELMET,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100, Characteristic.ACTION_POINT to 7, Characteristic.MOVEMENT_POINT to 1),
                maxShardSlots = 4
            )
        val pool = listOf(carrier).groupBy { it.itemType }
        val params = reviewParams().copy(forcedSublimations = listOf(sub.name.fr), targetStats = TargetStats(listOf(TargetStat(Characteristic.MOVEMENT_POINT, 4))))
        val optimum =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params,
                pool,
                emptyList(),
                listOf(sub),
                workers = 1,
                seconds = 10.0,
                applyDomination = false,
                deterministicLimit = 6.0
            )
        assertThat(optimum.status).isEqualTo("OPTIMAL")
        val ap = optimum.actualStats.getValue(Characteristic.ACTION_POINT).toInt()
        assertThat(ap).isGreaterThan(12)
        assertThat(optimum.actualStats.getValue(Characteristic.MOVEMENT_POINT)).isEqualTo(4L)
        val pinned =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params.copy(maxDamageApTarget = ap),
                pool,
                emptyList(),
                listOf(sub),
                workers = 1,
                seconds = 10.0,
                applyDomination = false,
                deterministicLimit = 6.0
            )
        assertThat(pinned.status).isEqualTo("OPTIMAL")
        assertThat(pinned.objective).isEqualTo(optimum.objective)
        val certificate = WakfuBuildSolver.certifierCellObjectivesForTest(params, pool, sublimations = listOf(sub)).getValue(ap)
        // -1 is the test seam's explicit bail; production treats it as an unbounded cell and cannot stamp a proof.
        val bound = if (certificate == -1L) Long.MAX_VALUE else certificate
        assertThat(bound).isGreaterThanOrEqualTo(pinned.objective)
        assertThat(certificate).isEqualTo(-1L)
    }

    private fun reviewParams() =
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
            useSublimations = false
        )

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
