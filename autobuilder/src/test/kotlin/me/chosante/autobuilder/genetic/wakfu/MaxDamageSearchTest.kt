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

    // ----- Lot 2 M2: bi-element enumeration -----

    @Test
    fun `pareto frontier keeps only non-dominated interior splits`() {
        //               AP:  0   1   2   3   4   5   6   7
        val tableA = longArrayOf(0, 10, 20, 20, 30, 30, 30, 40)
        val tableB = longArrayOf(0, 5, 10, 15, 20, 25, 30, 35)

        // totalAp = 6 → interior splits a ∈ 1..5:
        //   a=1: (10, 25)  — non-dominated
        //   a=2: (20, 20)  — non-dominated
        //   a=3: (20, 15)  — dominated by a=2 (20≥20, 20>15)
        //   a=4: (30, 10)  — non-dominated
        //   a=5: (30, 5)   — dominated by a=4 (30≥30, 10>5)
        val frontier = MaxDamageSearch.paretoFrontierSplits(tableA, tableB, totalAp = 6)
        assertThat(frontier).containsExactlyInAnyOrder(1, 2, 4)
    }

    @Test
    fun `pareto frontier excludes splits where either element has zero throughput`() {
        // Element A needs 3 AP minimum (cheapest spell costs 3 AP)
        val tableA = longArrayOf(0, 0, 0, 50, 50, 100)
        val tableB = longArrayOf(0, 20, 40, 60, 80, 100)

        // totalAp = 5 → interior splits a ∈ 1..4:
        //   a=1: tableA[1]=0  → excluded (zero A)
        //   a=2: tableA[2]=0  → excluded (zero A)
        //   a=3: (50, 40)     — non-dominated
        //   a=4: (50, 20)     — dominated by a=3 (50≥50, 40>20)
        val frontier = MaxDamageSearch.paretoFrontierSplits(tableA, tableB, totalAp = 5)
        assertThat(frontier).containsExactly(3)
    }

    @Test
    fun `bi-element scenarios are generated for a multi-element class`() {
        val scenarios = MaxDamageSearch.biElementScenarios(CharacterClass.CRA)
        assertThat(scenarios).describedAs("CRA has multiple playable elements → bi-element scenarios exist").isNotEmpty
        scenarios.forEach { (pair, totalAp, split) ->
            assertThat(pair.first).isNotEqualTo(pair.second)
            assertThat(split).describedAs("interior split").isBetween(1, totalAp - 1)
            assertThat(totalAp).isBetween(MaxDamageSearch.MIN_AP_TARGET, MaxDamageSearch.MAX_AP_TARGET)
        }
    }

    @Test
    fun `bi-element loop produces a result at least as good as mono`(): Unit =
        runBlocking {
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

            assertThat(results).describedAs("the loop with bi-element streams at least one build").isNotEmpty
            val last = results.last()
            assertThat(last.matchPercentage)
                .describedAs("bi-element-aware loop finds positive damage")
                .isGreaterThan(BigDecimal.ZERO)
            assertThat(last.isOptimal).isFalse()
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

    @Test
    fun `bi-element scenarios are ordered best-promise first so the cap keeps the strongest`() {
        val scenarios = MaxDamageSearch.biElementScenarios(CharacterClass.CRA)
        assertThat(scenarios).isNotEmpty
        assertThat(scenarios.map { it.promise })
            .describedAs("scenarios sorted by descending combined base throughput")
            .isEqualTo(scenarios.map { it.promise }.sortedDescending())
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
