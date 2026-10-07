package me.chosante.ui.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.SublimationRarity
import me.chosante.ui.history.HistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class SublimationBulkExclusionTest {
    @Test
    fun `bulk toggle completes a partial exclusion and restores only that rarity`(
        @TempDir directory: Path,
    ): Unit =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val model =
                BuildSearchModel(
                    scope = backgroundScope,
                    mainDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    backgroundDispatcher = dispatcher,
                    historyRepository = HistoryRepository(directory, dispatcher),
                    libraryPreferences = LibraryPreferences(null)
                )
            for (rarity in listOf(SublimationRarity.EPIC, SublimationRarity.RELIC)) {
                val names =
                    WakfuBestBuildFinderAlgorithm.sublimations
                        .filter { it.rarity == rarity }
                        .map { it.name.fr }
                        .distinct()
                assertThat(names).hasSizeGreaterThan(1)
                model.addExcludedSublimation("unrelated")
                model.addExcludedSublimation(names.first())
                model.addForcedSublimation(names.last())
                model.toggleExcludeAllSublimationsOfRarity(rarity)
                assertThat(model.ui.excludedSublimations).containsAll(names).doesNotHaveDuplicates().contains("unrelated")
                assertThat(model.ui.forcedSublimations).contains(names.last())
                model.toggleExcludeAllSublimationsOfRarity(rarity)
                assertThat(model.ui.excludedSublimations).containsExactly("unrelated")
            }
        }
}
