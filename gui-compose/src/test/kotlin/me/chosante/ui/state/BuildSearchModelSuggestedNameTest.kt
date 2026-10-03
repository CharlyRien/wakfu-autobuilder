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
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The name the Save dialog proposes must be one it accepts. The generated "Cra 110 · Distance" is the same for every build of
 * a class / level / focus, so saving one build and starting another used to open the dialog with the proposal already taken:
 * Save disabled and the "another build already uses this name" warning showing before the player typed anything.
 */
class BuildSearchModelSuggestedNameTest {
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private fun newModel(
        scope: CoroutineScope,
        tempDir: Path,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { flowOf(SolverResult(individual = foundBuild, matchPercentage = BigDecimal("100"), progressPercentage = 100, isOptimal = true)) },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            historyRepository = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
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

    /** The Save button is enabled iff the dialog shows no "name taken" warning: what [takenBuildNames] says of the suggestion. */
    private fun BuildSearchModel.suggestionIsAccepted(): Boolean = suggestedSaveName().trim().lowercase() !in takenBuildNames()

    @Test
    fun `with an empty library the generated name is proposed as it is`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, tempDir)
            try {
                model.searchOnce()

                assertThat(model.suggestedSaveName()).isEqualTo("Cra 110 · Distance")
                assertThat(model.suggestionIsAccepted()).isTrue()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new build after saving one is not proposed the name the saved one took`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, tempDir)
            try {
                model.searchOnce()
                model.saveBuild(model.suggestedSaveName(), null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 1 }
                assertThat(
                    model.ui.savedBuilds
                        .single()
                        .name
                ).isEqualTo("Cra 110 · Distance")

                // "New build" forgets the saved one; the same request comes back, so the generated name is the same again.
                model.newBuild()
                model.searchOnce()

                assertThat(model.suggestedSaveName()).isEqualTo("Cra 110 · Distance (2)")
                assertThat(model.suggestionIsAccepted()).describedAs("the dialog must open with Save enabled").isTrue()

                // …and so on: every proposal is free, whatever is already in the library.
                model.saveBuild(model.suggestedSaveName(), null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 2 }
                model.newBuild()
                model.searchOnce()
                assertThat(model.suggestedSaveName()).isEqualTo("Cra 110 · Distance (3)")
                assertThat(model.suggestionIsAccepted()).isTrue()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `detaching from the saved build does not leave its name as the proposal either`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, tempDir)
            try {
                model.searchOnce()
                model.saveBuild(model.suggestedSaveName(), null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 1 }
                assertThat(model.ui.activeBuildName).isEqualTo("Cra 110 · Distance")

                model.clearActiveBuild()

                assertThat(model.suggestedSaveName()).isEqualTo("Cra 110 · Distance (2)")
                assertThat(model.suggestionIsAccepted()).isTrue()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a loaded build keeps its own name as the proposal, so saving updates it`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, tempDir)
            try {
                model.searchOnce()
                model.saveBuild("My daily build", null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 1 }
                val id =
                    model.ui.savedBuilds
                        .single()
                        .id
                model.newBuild()
                model.loadBuild(id)

                assertThat(model.suggestedSaveName()).isEqualTo("My daily build")
                assertThat(model.suggestionIsAccepted()).isTrue()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the case and spacing of a saved name do not hide a collision`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, tempDir)
            try {
                model.searchOnce()
                model.saveBuild("  cra 110 · DISTANCE ", null, asNew = true)
                awaitUntil { model.ui.savedBuilds.size == 1 }
                model.newBuild()
                model.searchOnce()

                assertThat(model.suggestedSaveName()).isEqualTo("Cra 110 · Distance (2)")
                assertThat(model.suggestionIsAccepted()).isTrue()
            } finally {
                scope.cancel()
            }
        }
}
