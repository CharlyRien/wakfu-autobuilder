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
    fun `a pinned mono no-debuff request is a single solve that reports proven optimality`(): Unit =
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
                    // Pinned single element (mono), and CRA has no confirmed resistance debuff → no AP-window
                    // probing and no bi-element phase, so the one CP-SAT solve is the provable optimum.
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
                .describedAs("mono + no debuff ⇒ a single proven CP-SAT solve, not a heuristic probe loop")
                .isTrue()
            assertThat(last.matchPercentage)
                .describedAs("found a damaging fire rotation")
                .isGreaterThan(BigDecimal.ZERO)
            assertThat(last.individual.equipments).describedAs("equips at least one item").isNotEmpty
        }

    // ----- boss / multi-candidate: provable per-element enumeration -----

    @Test
    fun `boss multi-element loop proves the optimum via per-element enumeration`(): Unit =
        runBlocking {
            // A boss puts several elements in play (auto-element). The loop solves each candidate element as its
            // OWN single-element problem (each provable) and takes the max — so the result is PROVEN, unlike the
            // old capped bi-element enumeration which stayed "best found". CRA has no resistance debuff, so no
            // heuristic phase runs and the boss optimum is provable.
            val level = 50
            val character = Character(clazz = CharacterClass.CRA, level = level, minLevel = level, CharacterSkills(level))
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, "ApAmulet", mapOf(Characteristic.ACTION_POINT to 2, Characteristic.MASTERY_ELEMENTARY to 100)),
                    equipment(2, ItemType.AMULET, "MasteryAmulet", mapOf(Characteristic.MASTERY_ELEMENTARY to 200)),
                    equipment(3, ItemType.BELT, "Belt", mapOf(Characteristic.MASTERY_ELEMENTARY to 80))
                )
            val scenario =
                DamageScenario(
                    element = SpellElement.FIRE,
                    elementResistances = SpellElement.entries.associateWith { 0 }
                )
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 1))),
                    searchDuration = 10.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    damageScenario = scenario,
                    useRunes = false
                )

            val results =
                MaxDamageSearch
                    .run(params, equipments.groupBy { it.itemType }, emptyList(), WakfuBuildSolver.SolverTuning())
                    .toList()

            assertThat(results).describedAs("the per-element loop streams at least one build").isNotEmpty
            val last = results.last()
            assertThat(last.matchPercentage)
                .describedAs("the boss-aware loop finds positive damage")
                .isGreaterThan(BigDecimal.ZERO)
            assertThat(last.isOptimal)
                .describedAs("a no-debuff boss case is PROVEN (every per-element solve proved)")
                .isTrue()
        }

    // ----- probe batching: the GUI-freeze / non-termination fix (production path, no SolverTuning) -----

    @Test
    fun `probe plan never oversubscribes cores and fits the phase budget`() {
        // The freeze came from spawning more CPU-pinned CP-SAT solves than the host has cores, and from
        // giving each probe the FULL phase budget so a batch ran for waves × budget. The plan must do
        // neither, for any host size and probe count.
        val phaseBudget = 6.seconds
        for (host in listOf(1, 2, 3, 7, 8, 15, 32)) {
            for (count in listOf(1, 2, 5, 6, 7, 13, 24, 100, 286)) {
                val plan = MaxDamageSearch.probePlan(count, host, phaseBudget)
                assertThat(plan.concurrency)
                    .describedAs("host=%d count=%d: concurrency stays within 1..host", host, count)
                    .isBetween(1, host)
                assertThat(plan.workersPerProbe).isGreaterThanOrEqualTo(1)
                assertThat(plan.concurrency * plan.workersPerProbe)
                    .describedAs("host=%d count=%d: concurrent native solver threads never exceed cores", host, count)
                    .isLessThanOrEqualTo(host)
                val waves = (count + plan.concurrency - 1) / plan.concurrency
                assertThat((plan.perProbeBudget * waves).inWholeNanoseconds)
                    .describedAs("host=%d count=%d: the whole batch fits the phase budget (no balloon)", host, count)
                    .isLessThanOrEqualTo(phaseBudget.inWholeNanoseconds)
            }
        }
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
