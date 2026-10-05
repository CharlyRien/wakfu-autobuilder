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
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.Characteristic
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A row of target 0 on a required stat is a floor, "never below 0" — so the request must say 0 only when the player does: a
 * typed 0 (the default template's "air resistance 0" and "dodge 0" are typed 0s) sends a floor, a blank or cleared field sends
 * no row at all.
 */
class BuildSearchModelBlankTargetTest {
    @Test
    fun `a blank target field sends no row, a typed 0 sends a floor`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sent = mutableListOf<WakfuBestBuildParams>()
                val model =
                    BuildSearchModel(
                        scope = scope,
                        buildFinder = { params ->
                            sent += params
                            flowOf(SolverResult(BuildCombination(emptyList(), CharacterSkills(params.character.level)), BigDecimal.ONE, progressPercentage = 100))
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
                model.setDuration("1")

                suspend fun searched(): TargetStats {
                    val before = sent.size
                    model.search()
                    withTimeout(25.seconds) {
                        while (sent.size == before || model.ui.phase != Phase.Done) delay(20.milliseconds)
                    }
                    return sent.last().targetStats
                }

                // The default template: its rows of target 0 are typed 0s — floors.
                val defaults = searched()
                assertThat(defaults.floorCharacteristics).containsExactly(Characteristic.DODGE)
                assertThat(defaults.resistanceFloorElements).containsExactly(Characteristic.RESISTANCE_ELEMENTARY_WIND)

                // Cleared: no row at all — nothing is asked of those stats.
                model.updateTargetValue(Characteristic.DODGE.name, "")
                model.updateTargetValue(Characteristic.RESISTANCE_ELEMENTARY_WIND.name, "")
                val cleared = searched()
                assertThat(cleared.map { it.characteristic }).doesNotContain(Characteristic.DODGE, Characteristic.RESISTANCE_ELEMENTARY_WIND)
                assertThat(cleared.hasFloors).isFalse()
                // ...while the rows the player filled in are all sent.
                assertThat(cleared.map { it.characteristic }).contains(Characteristic.ACTION_POINT, Characteristic.MASTERY_DISTANCE)

                // A typed 0 is a floor again.
                model.updateTargetValue(Characteristic.DODGE.name, "0")
                assertThat(searched().floorCharacteristics).containsExactly(Characteristic.DODGE)
            } finally {
                scope.cancel()
            }
        }
}
