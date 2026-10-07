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
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.history.HistoryEntry
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.historyJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved build remembers the game-data version it was computed with ([HistoryEntry.dataVersion]) — and that stamp used to be
 * dead weight: nothing ever compared it with the data the app ships. Loading a build saved before a game update now flags it
 * ([UiState.staleDataVersion]) until a new search replaces it, and the stamp stays truthful: saving or exporting the build as it
 * is must not relabel old numbers as current. The stored builds themselves are never rewritten by any of this.
 */
class BuildSearchModelStaleDataTest {
    private val oldData = "1.92.1.58"
    private val currentData = "1.93.1.62"
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private var clipboard = ""

    private fun newModel(
        scope: CoroutineScope,
        dir: Path,
        shipping: String,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { flowOf(SolverResult(individual = foundBuild, matchPercentage = BigDecimal("100"), progressPercentage = 100, isOptimal = true)) },
            zenithBuilder = { "" },
            copyToClipboard = { clipboard = it },
            readClipboard = { clipboard },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            dataVersion = shipping,
            historyRepository = HistoryRepository(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(20.milliseconds)
            }
        }
    }

    private suspend fun BuildSearchModel.searchOnce() {
        setDuration("1")
        search()
        awaitUntil { ui.phase == Phase.Done }
    }

    /** One build saved by an app that shipped [savedWith], then the library opened by an app that ships [currentData]. */
    private suspend fun libraryFromOlderApp(
        scope: CoroutineScope,
        dir: Path,
        savedWith: String = oldData,
    ): Pair<BuildSearchModel, String> {
        val older = newModel(scope, dir, shipping = savedWith)
        older.searchOnce()
        older.saveBuild("Daily build", null, asNew = true)
        awaitUntil { older.ui.savedBuilds.size == 1 }
        val current = newModel(scope, dir, shipping = currentData)
        awaitUntil { current.ui.savedBuilds.size == 1 }
        return current to
            current.ui.savedBuilds
                .single()
                .id
    }

    @Test
    fun `loading a build saved with other game data flags it, and leaves the stored build alone`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir)

                model.loadBuild(id)

                assertThat(model.ui.staleDataVersion).isEqualTo(oldData)
                assertThat(model.ui.build).describedAs("the build is shown and usable, not blocked").isNotNull()
                assertThat(model.ui.phase).isEqualTo(Phase.Done)
                assertThat(
                    model.ui.savedBuilds
                        .single()
                        .dataVersion
                ).describedAs("the stored build is not rewritten").isEqualTo(oldData)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a build saved with this app's own data is not flagged`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir, savedWith = currentData)

                model.loadBuild(id)

                assertThat(model.ui.staleDataVersion).isNull()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `searching again clears the flag, and updating the build then stamps it with the current data`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir)
                model.loadBuild(id)
                assertThat(model.ui.staleDataVersion).isEqualTo(oldData)

                model.searchOnce()
                assertThat(model.ui.staleDataVersion).describedAs("a result found by this app's data").isNull()

                model.saveBuild("Daily build", null, asNew = false)
                awaitUntil {
                    model.ui.savedBuilds
                        .single()
                        .dataVersion == currentData
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `saving a stale build as it is keeps its original stamp, so its card keeps saying so`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir)
                model.loadBuild(id)

                // "Update build" without a new search: the numbers are still those of the old data.
                model.saveBuild("Daily build (renamed)", "kept", asNew = false)
                awaitUntil {
                    model.ui.savedBuilds
                        .single()
                        .name == "Daily build (renamed)"
                }
                assertThat(
                    model.ui.savedBuilds
                        .single()
                        .dataVersion
                ).isEqualTo(oldData)
                assertThat(model.ui.staleDataVersion).describedAs("still the old data's result on screen").isEqualTo(oldData)

                // …and "Save as new" too.
                model.saveBuild("Copy of the old build", null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 2 }
                assertThat(model.ui.savedBuilds.map { it.dataVersion }).containsOnly(oldData)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `exporting a stale build keeps its stamp, and importing it flags it again`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir)
                model.loadBuild(id)
                model.exportBuild()
                assertThat(historyJson.decodeFromString(HistoryEntry.serializer(), clipboard).dataVersion).isEqualTo(oldData)

                model.newBuild()
                assertThat(model.ui.staleDataVersion).describedAs("a blank workspace has no stale result").isNull()
                model.importBuild(clipboard)
                awaitUntil { model.ui.savedBuilds.size == 2 && model.ui.build != null }

                assertThat(model.ui.staleDataVersion).isEqualTo(oldData)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the flag belongs to the result, so a mode switch parks it and brings it back`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val (model, id) = libraryFromOlderApp(scope, dir)
                model.loadBuild(id)
                assertThat(model.ui.staleDataVersion).isEqualTo(oldData)

                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
                assertThat(model.ui.build).isNull()
                assertThat(model.ui.staleDataVersion).describedAs("nothing is shown in the other mode, so nothing is flagged").isNull()

                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
                assertThat(model.ui.build).isNotNull()
                assertThat(model.ui.staleDataVersion).isEqualTo(oldData)
            } finally {
                scope.cancel()
            }
        }
}
