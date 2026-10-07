package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
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

class StopAtMatchTest {
    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, greedyWarmStart = false)
    private val pool =
        listOf(ItemType.AMULET, ItemType.BELT).associateWith { slot ->
            (1..2).map { ap ->
                Equipment(
                    equipmentId = slot.id * 10 + ap,
                    guiId = 1,
                    level = 1,
                    name = I18nText("${slot.name}$ap", "", "", ""),
                    rarity = Rarity.COMMON,
                    itemType = slot,
                    characteristics = mapOf(Characteristic.ACTION_POINT to ap),
                    maxShardSlots = 0
                )
            }
        }

    private fun params(
        stop: Boolean,
        ap: Int = 6,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
        targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, ap))),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = stop,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    @Test
    fun `stop returns the first matching solution without a proof even if presolve proves optimal`(): Unit =
        runBlocking {
            // Base AP already meets the target: the first solution matches, whatever build CP-SAT tries first.
            val first = WakfuBuildSolver.optimize(params(false), pool, tuning = tuning.copy(stopAtFirstSolution = true)).toList().last()
            val results = WakfuBuildSolver.optimize(params(true), pool, tuning = tuning).toList()
            assertThat(results).hasSize(1)
            assertThat(results.last().individual).isEqualTo(first.individual)
            assertThat(results.last().matchPercentage).isGreaterThanOrEqualTo(BigDecimal(100))
            assertThat(results.last().progressPercentage).isEqualTo(100)
            assertThat(results.last().isOptimal).isFalse()
        }

    @Test
    fun `off keeps optimizing overflow and reports the proven optimum`(): Unit =
        runBlocking {
            val result = WakfuBuildSolver.optimize(params(false), pool, tuning = tuning).toList().last()
            assertThat(result.individual.equipments.sumOf { it.characteristics[Characteristic.ACTION_POINT] ?: 0 }).isEqualTo(4)
            assertThat(result.matchPercentage).isEqualByComparingTo("166.6")
            assertThat(result.isOptimal).isTrue()
        }

    @Test
    fun `an unreachable target is still optimized to completion`(): Unit =
        runBlocking {
            val on = WakfuBuildSolver.optimize(params(true, 100), pool, tuning = tuning).toList().last()
            val off = WakfuBuildSolver.optimize(params(false, 100), pool, tuning = tuning).toList().last()
            assertThat(on).isEqualTo(off)
            assertThat(on.isOptimal).isTrue()
            assertThat(on.matchPercentage).isLessThan(BigDecimal(100))
        }

    @Test
    fun `other modes ignore the flag`(): Unit =
        runBlocking {
            for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
                val off = params(false).copy(scoreComputationMode = mode)
                val a = WakfuBuildSolver.optimize(off, pool, tuning = tuning).toList().last()
                val b = WakfuBuildSolver.optimize(off.copy(stopWhenBuildMatch = true), pool, tuning = tuning).toList().last()
                assertThat(b).isEqualTo(a)
            }
        }
}
