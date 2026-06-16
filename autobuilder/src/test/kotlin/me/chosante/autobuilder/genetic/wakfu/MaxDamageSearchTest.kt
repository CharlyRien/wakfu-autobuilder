package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellElement
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

/**
 * Coverage for the max-damage external loop ([MaxDamageSearch]) — previously untested despite being the
 * centerpiece of the mode. Uses a deterministic [WakfuBuildSolver.SolverTuning] so the underlying CP-SAT
 * solves are reproducible (no wall-clock flakiness).
 */
class MaxDamageSearchTest {
    @Test
    fun `the external loop returns a damaging build and never claims proven optimality`(): Unit =
        runBlocking {
            val level = 50
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, CharacterSkills(level))
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "ApAmulet", mapOf(Characteristic.ACTION_POINT to 2, Characteristic.MASTERY_ELEMENTARY_FIRE to 50)),
                    equipment(2, ItemType.AMULET, "MasteryAmulet", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 200)),
                    equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 80))
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
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    damageScenario = DamageScenario(element = SpellElement.FIRE),
                    useRunes = false
                )

            val results =
                MaxDamageSearch
                    .run(params, equipments.groupBy { it.itemType }, emptyList(), WakfuBuildSolver.SolverTuning())
                    .toList()

            assertThat(results).describedAs("the loop streams at least one build").isNotEmpty
            val last = results.last()
            assertThat(last.isOptimal)
                .describedAs("a heuristic AP-window + sequencing loop must never claim a proven global optimum")
                .isFalse()
            assertThat(last.matchPercentage)
                .describedAs("found a damaging fire rotation")
                .isGreaterThan(BigDecimal.ZERO)
            assertThat(last.individual.equipments).describedAs("equips at least one item").isNotEmpty
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
