package me.chosante.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The search button's "Stop" ([BuildSearchModel.cancel]): a search that already has a build ends as a finished, usable,
 * NOT-proven result instead of stranding the build in the idle phase (dimmed paperdoll, "Awaiting search", Save / Zenith /
 * Export disabled); a stop before any build returns to idle as before.
 */
class BuildSearchModelStopTest {
    private val firstBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
    private val laterBuild =
        BuildCombination(
            equipments =
                listOf(
                    Equipment(
                        equipmentId = 1,
                        guiId = 1,
                        level = 1,
                        name = I18nText("Casque", "Helm", "Helm", "Helm"),
                        rarity = Rarity.COMMON,
                        itemType = ItemType.HELMET,
                        characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 100)
                    )
                ),
            characterSkills = CharacterSkills(110)
        )

    private fun result(
        build: BuildCombination,
        match: String,
        isOptimal: Boolean = false,
    ) = SolverResult(individual = build, matchPercentage = BigDecimal(match), progressPercentage = 20, isOptimal = isOptimal)

    private fun newModel(
        scope: CoroutineScope,
        finder: () -> Flow<SolverResult<BuildCombination>>,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { finder() },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            // The model reaches for the engine's process-wide warm-ups when a proof is stopped: not in a unit test.
            backgroundProofCanceller = {},
            historyRepository =
                HistoryRepository(
                    baseDir = Files.createTempDirectory("wakfu-test-history"),
                    ioDispatcher = Dispatchers.Unconfined
                )
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(20.milliseconds)
            }
        }
    }

    @Test
    fun `stopping a search that already has a build keeps it as a finished, usable, not-proven result`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope) {
                    flow {
                        // Even a result the stream flagged optimal must not be claimed once the user cut the search short.
                        emit(result(firstBuild, "1234", isOptimal = true))
                        awaitCancellation()
                    }
                }
            try {
                model.search()
                awaitUntil { model.ui.build != null }
                assertEquals(Phase.Searching, model.ui.phase)

                model.cancel()

                assertEquals(Phase.Done, model.ui.phase, "the build stays on screen as a finished result")
                assertEquals(firstBuild, model.ui.build)
                assertEquals(0, BigDecimal("1234").compareTo(model.ui.match), "the best-so-far score is kept")
                assertFalse(model.ui.optimal, "a stopped search makes no proof claim")
                assertEquals(ProofState.Idle, model.ui.proofState)
                assertTrue(model.ui.searchStopped, "the panel can say it was stopped early")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a stop before any build exists returns to idle`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope) { flow { awaitCancellation() } }
            try {
                model.search()
                assertEquals(Phase.Searching, model.ui.phase)

                model.cancel()

                assertEquals(Phase.Idle, model.ui.phase)
                assertEquals(0, model.ui.progress)
                assertNull(model.ui.build)
                assertFalse(model.ui.searchStopped)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a result the stopped search emits late never lands`(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope) {
                    flow {
                        emit(result(firstBuild, "10"))
                        release.await()
                        emit(result(laterBuild, "99"))
                    }
                }
            try {
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()

                release.complete(Unit)
                delay(300.milliseconds)

                assertEquals(firstBuild, model.ui.build, "the best-so-far at the moment of the stop is what stays")
                assertEquals(0, BigDecimal("10").compareTo(model.ui.match))
                assertEquals(Phase.Done, model.ui.phase)
            } finally {
                release.complete(Unit)
                scope.cancel()
            }
        }

    @Test
    fun `a stopped build can be saved, and its saved entry is not marked optimal`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope) {
                    flow {
                        emit(result(firstBuild, "77", isOptimal = true))
                        awaitCancellation()
                    }
                }
            try {
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()

                model.saveBuild(name = "Stopped early", note = null, asNew = true)

                awaitUntil { model.ui.savedBuilds.isNotEmpty() }
                val saved = model.ui.savedBuilds.single()
                assertEquals("Stopped early", saved.name)
                assertFalse(saved.result.optimal, "the library must not show a proven badge for a stopped search")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the next search clears the stopped flag`(): Unit =
        runBlocking {
            val calls = AtomicInteger(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope) {
                    if (calls.incrementAndGet() == 1) {
                        flow {
                            emit(result(firstBuild, "5"))
                            awaitCancellation()
                        }
                    } else {
                        flowOf(result(laterBuild, "50", isOptimal = true))
                    }
                }
            try {
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()
                assertTrue(model.ui.searchStopped)

                model.search()
                awaitUntil { model.ui.phase == Phase.Done }

                assertFalse(model.ui.searchStopped)
                assertEquals(laterBuild, model.ui.build)
                assertTrue(model.ui.optimal, "a search that runs to its end claims what the solver proved")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cancel with nothing searching leaves a finished result on screen`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope) { flowOf(result(firstBuild, "42", isOptimal = true)) }
            try {
                model.search()
                awaitUntil { model.ui.phase == Phase.Done }

                model.cancel()

                assertEquals(Phase.Done, model.ui.phase, "nothing was searching, so nothing changes phase")
                assertEquals(firstBuild, model.ui.build)
                assertFalse(model.ui.searchStopped, "a search that finished by itself was not stopped")
            } finally {
                scope.cancel()
            }
        }
}
