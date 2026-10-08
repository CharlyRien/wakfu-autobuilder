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
