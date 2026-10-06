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
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelDamageViewTest {
    @Test
    fun `the damage view headline equals the max-damage result for the same build including its target penalty`(): Unit =
        runBlocking {
            for (apTarget in listOf("6", "10")) {
                val build = BuildCombination(emptyList(), CharacterSkills(110))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val damage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
                val model =
                    BuildSearchModel(
                        scope = scope,
                        buildFinder = { params ->
                            val score = if (params.scoreComputationMode == damage) WakfuBestBuildFinderAlgorithm.rescore(params, build) else BigDecimal("1500")
                            flowOf(SolverResult(build, score, 100, isOptimal = true))
                        },
                        mainDispatcher = Dispatchers.Unconfined,
                        ioDispatcher = Dispatchers.Unconfined,
                        libraryPreferences = LibraryPreferences(null),
                        backgroundProofCanceller = {},
                        historyRepository = HistoryRepository(Files.createTempDirectory("damage-view"), ioDispatcher = Dispatchers.Unconfined)
                    )
                try {
                    model.setVerifyOptimality(false)
                    model.ui.targets
                        .toList()
                        .forEach { model.removeTarget(it.id) }
                    model.addTarget(Characteristic.ACTION_POINT)
                    model.updateTargetValue(
                        model.ui.targets
                            .single()
                            .id,
                        apTarget
                    )
                    model.setScenario(DamageScenario(element = SpellElement.FIRE, orientation = Orientation.BACK, targetResistancePercent = 40))
                    model.search()
                    awaitUntil { model.ui.phase == Phase.Done }
                    model.viewCurrentBuildAsMaxDamage()
                    awaitUntil { model.ui.spellRotation != null }
                    val viewed = model.ui.match
                    assertNotEquals(BigDecimal("1500"), viewed)
                    model.search()
                    awaitUntil { model.ui.phase == Phase.Done }
                    assertEquals(0, viewed.compareTo(model.ui.match), "AP target $apTarget: the view uses the search's score")
                } finally {
                    scope.cancel()
                }
            }
        }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) delay(10)
        }
    }
}
