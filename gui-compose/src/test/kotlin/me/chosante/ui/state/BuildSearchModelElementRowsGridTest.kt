package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.autobuilder.genetic.wakfu.elementRowObjectives
import me.chosante.common.Character
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The stats column reads a request's per-element rows the way the solver and the scorers do: with several rows of one family
 * (fire + water resistance here, beside the default "air resistance 0"), each random-element roll lands on the elements the
 * solver's joint fold places it on — not where the per-mode assignment of a lone family would.
 */
class BuildSearchModelElementRowsGridTest {
    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 110,
        name = I18nText("er$id", "er$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = 0
    )

    // Fire resistance 60 on the amulet, "+50 resistance on 1 random element" on the boots.
    private val amulet = item(960_001, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 60))
    private val boots = item(960_002, ItemType.BOOTS, mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 50))

    @Test
    fun `the stats column places a random roll where the solver's joint fold does`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                var searched: BuildCombination? = null
                val model =
                    BuildSearchModel(
                        scope = scope,
                        buildFinder = { params ->
                            val build = BuildCombination(listOf(amulet, boots), CharacterSkills(params.character.level))
                            searched = build
                            flowOf(SolverResult(build, WakfuBestBuildFinderAlgorithm.rescore(params, build), progressPercentage = 100, isOptimal = false))
                        },
                        optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
                        zenithBuilder = { "" },
                        mainDispatcher = Dispatchers.Unconfined,
                        ioDispatcher = Dispatchers.Unconfined,
                        libraryPreferences = LibraryPreferences(null),
                        backgroundProofCanceller = {},
                        buildRescorer = { _, _ -> BigDecimal.ONE },
                        historyRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
                    )
                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
                // Fire resistance 100 at priority 5, water resistance 100 at priority 1 (the default air resistance 0 stays).
                model.addTarget(Characteristic.RESISTANCE_ELEMENTARY_FIRE)
                model.updateTargetValue(Characteristic.RESISTANCE_ELEMENTARY_FIRE.name, "100")
                model.updateTargetWeight(Characteristic.RESISTANCE_ELEMENTARY_FIRE.name, 5)
                model.addTarget(Characteristic.RESISTANCE_ELEMENTARY_WATER)
                model.updateTargetValue(Characteristic.RESISTANCE_ELEMENTARY_WATER.name, "100")
                model.updateTargetWeight(Characteristic.RESISTANCE_ELEMENTARY_WATER.name, 1)
                model.setDuration("1")
                model.search()
                withTimeout(25.seconds) {
                    while (model.ui.phase != Phase.Done) delay(20.milliseconds)
                }

                // The roll goes to fire, the heavier row it fills (60 + 50 ≥ 100); water and air stay at 0.
                val achieved = model.ui.achieved
                assertEquals(110, achieved[Characteristic.RESISTANCE_ELEMENTARY_FIRE])
                assertEquals(0, achieved[Characteristic.RESISTANCE_ELEMENTARY_WATER] ?: 0)
                assertEquals(0, achieved[Characteristic.RESISTANCE_ELEMENTARY_WIND] ?: 0)

                // Control: the per-mode assignment of a lone family (the deficit greedy) would have put it on water instead.
                val targetStats =
                    TargetStats(model.ui.targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0, it.weight) })
                val build = requireNotNull(searched)
                val character = Character(model.ui.clazz, model.ui.level, model.ui.minLevel, build.characterSkills)

                fun grid(withRows: Boolean) =
                    computeCharacteristicsValues(
                        buildCombination = build,
                        characterBaseCharacteristics = character.baseCharacteristicValues,
                        masteryElementsWanted = targetStats.masteryElementsWanted,
                        resistanceElementsWanted = targetStats.resistanceElementsWanted,
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        masteryElementsToMinimize = targetStats.masteryElementsToMinimize,
                        elementRows = if (withRows) targetStats.elementRowObjectives(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) else null
                    )
                assertEquals(110, grid(withRows = true)[Characteristic.RESISTANCE_ELEMENTARY_FIRE])
                assertEquals(60, grid(withRows = false)[Characteristic.RESISTANCE_ELEMENTARY_FIRE])
                assertEquals(50, grid(withRows = false)[Characteristic.RESISTANCE_ELEMENTARY_WATER])
            } finally {
                scope.cancel()
            }
        }
}
