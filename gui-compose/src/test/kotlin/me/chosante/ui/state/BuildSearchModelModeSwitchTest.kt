package me.chosante.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Switching the search mode used to destroy work: Max Damage emptied the target rows and Most Masteries came back with
 * none, and the result of a long search vanished with a click on a tab. Each mode now keeps its own rows AND the result
 * found under it ([UiState.modeWorkspaces]); the live mode is the only one on screen, the others are parked.
 */
class BuildSearchModelModeSwitchTest {
    private val masteries = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
    private val damage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE

    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private fun boss(id: Int) =
        Monster(
            id = id,
            name = I18nText("Boss $id", "Boss $id", "Boss $id", "Boss $id"),
            level = 110,
            hp = 1_000,
            fireResistance = 10,
            waterResistance = 20,
            earthResistance = 30,
            airResistance = 40
        )

    private fun result(
        match: String,
        isOptimal: Boolean = true,
    ) = SolverResult(individual = foundBuild, matchPercentage = BigDecimal(match), progressPercentage = 100, isOptimal = isOptimal)

    private fun newModel(
        scope: CoroutineScope,
        finder: () -> Flow<SolverResult<BuildCombination>> = { flowOf(result("1500")) },
        optimalityProver: (Boolean) -> MaxDamageSearch.MaxDamageProof = { MaxDamageSearch.MaxDamageProof.Unavailable },
        proofGate: CountDownLatch? = null,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { finder() },
            optimalityProver = { _, _, isCancelled, _ ->
                proofGate?.await(25, TimeUnit.SECONDS)
                optimalityProver(isCancelled())
            },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
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

    private fun BuildSearchModel.searchAndWait() {
        setDuration("1")
        search()
    }

    // ---- target rows ----

    @Test
    fun `the target rows come back after a visit to max damage`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)
        try {
            val seeded = model.ui.targets
            assertTrue(seeded.isNotEmpty())

            model.setMode(damage)
            assertTrue(model.ui.targets.isEmpty(), "max damage still starts constraint-free")

            model.setMode(masteries)
            assertEquals(seeded, model.ui.targets, "the eight seeded rows are back, values and priorities included")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `rows edited in a mode are what comes back to it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)
        try {
            // Most masteries: raise the AP floor and give it a priority.
            val ap = model.ui.targets.single { it.characteristic == Characteristic.ACTION_POINT }
            model.updateTargetValue(ap.id, "12")
            model.updateTargetWeight(ap.id, 4)
            val edited = model.ui.targets

            // Max damage: add its own row.
            model.setMode(damage)
            model.addTarget(Characteristic.HP)
            model.updateTargetValue(Characteristic.HP.name, "3000")
            val damageRows = model.ui.targets
            assertEquals(listOf(Characteristic.HP), damageRows.map { it.characteristic })

            model.setMode(masteries)
            assertEquals(edited, model.ui.targets)
            model.setMode(damage)
            assertEquals(damageRows, model.ui.targets)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `precision keeps its own mastery values across a visit to most masteries`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)
        try {
            model.toggleMaximizedMastery(Characteristic.MASTERY_CRITICAL)

            // First visit: the rows carry over, as they always did.
            model.setMode(precision)
            val critical = model.ui.targets.single { it.characteristic == Characteristic.MASTERY_CRITICAL }
            assertEquals("1", critical.value)

            model.updateTargetValue(critical.id, "500")
            model.setMode(masteries)
            // A maximized mastery is a bare marker in this mode: its value is always 1.
            assertEquals(
                "1",
                model.ui.targets
                    .single { it.characteristic == Characteristic.MASTERY_CRITICAL }
                    .value
            )

            model.setMode(precision)
            assertEquals(
                "500",
                model.ui.targets
                    .single { it.characteristic == Characteristic.MASTERY_CRITICAL }
                    .value
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `choosing the active mode again changes nothing`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }
                val before = model.ui

                model.setMode(masteries)

                assertEquals(before, model.ui, "re-clicking the selected tab must not wipe the shown build")
            } finally {
                scope.cancel()
            }
        }

    // ---- results ----

    @Test
    fun `a result is parked with its mode and comes back with it`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }
                val shown = model.ui.shownResult()
                assertEquals(foundBuild, shown.build)
                assertTrue(shown.optimal)

                model.setMode(precision)
                // The other mode is not asked to read a build it did not find.
                assertEquals(Phase.Idle, model.ui.phase)
                assertNull(model.ui.build)
                assertEquals(ShownResult(), model.ui.shownResult())

                model.setMode(masteries)
                assertEquals(shown, model.ui.shownResult(), "phase, build, score, achieved stats and proof come back as they were")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `each mode keeps its own result`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var next = "1500"
            val model = newModel(scope, finder = { flowOf(result(next)) })
            try {
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }

                model.setMode(precision)
                next = "88"
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }
                assertEquals(0, BigDecimal("88").compareTo(model.ui.match))

                model.setMode(masteries)
                assertEquals(0, BigDecimal("1500").compareTo(model.ui.match))
                model.setMode(precision)
                assertEquals(0, BigDecimal("88").compareTo(model.ui.match))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a parked result comes back without the proof that was running for it`(): Unit =
        runBlocking {
            val gate = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, optimalityProver = { MaxDamageSearch.MaxDamageProof.ProvenOptimal }, proofGate = gate)
            try {
                model.setMode(damage)
                model.searchAndWait()
                awaitUntil { model.ui.proofState is ProofState.Proving }

                model.setMode(masteries)
                gate.countDown()
                delay(300.milliseconds)
                model.setMode(damage)

                assertEquals(Phase.Done, model.ui.phase)
                assertEquals(foundBuild, model.ui.build)
                assertEquals(ProofState.Idle, model.ui.proofState, "the cancelled proof must neither spin nor land after the visit")
            } finally {
                gate.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `switching mode mid-search stops the search and parks its best-so-far`(): Unit =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope, finder = {
                    flow {
                        emit(result("10", isOptimal = false))
                        release.await()
                        emit(result("99", isOptimal = false))
                    }
                })
            try {
                model.searchAndWait()
                awaitUntil { model.ui.build != null }

                model.setMode(precision)
                assertEquals(Phase.Idle, model.ui.phase, "the new mode starts clean")
                assertNull(model.ui.build)

                // The stopped search streams nothing more into the screen it left.
                release.complete(Unit)
                delay(300.milliseconds)
                assertNull(model.ui.build)

                model.setMode(masteries)
                assertEquals(Phase.Done, model.ui.phase)
                assertTrue(model.ui.searchStopped)
                assertFalse(model.ui.optimal)
                assertEquals(0, BigDecimal("10").compareTo(model.ui.match), "the best-so-far at the moment of the switch")
            } finally {
                release.complete(Unit)
                scope.cancel()
            }
        }

    // ---- picking a boss ----

    @Test
    fun `picking a boss from another mode parks that mode's work`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)
        try {
            val seeded = model.ui.targets

            model.pickBoss(boss(1))
            assertEquals(damage, model.ui.mode)
            assertEquals(1, model.ui.selectedBoss?.id)
            assertTrue(model.ui.targets.isEmpty(), "max damage starts constraint-free")

            model.setMode(masteries)
            assertEquals(seeded, model.ui.targets, "the rows left behind by the boss pick are not lost")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `picking another boss while in max damage keeps the rows set there but clears the stale result`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, optimalityProver = { MaxDamageSearch.MaxDamageProof.Unavailable })
            try {
                model.pickBoss(boss(1))
                model.addTarget(Characteristic.ACTION_POINT)
                model.updateTargetValue(Characteristic.ACTION_POINT.name, "12")
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }

                model.pickBoss(boss(2))

                assertEquals(2, model.ui.selectedBoss?.id)
                assertEquals(listOf(Characteristic.ACTION_POINT), model.ui.targets.map { it.characteristic })
                assertEquals(
                    "12",
                    model.ui.targets
                        .single()
                        .value
                )
                assertEquals(Phase.Idle, model.ui.phase, "a result computed against the previous boss is stale")
                assertNull(model.ui.build)
            } finally {
                scope.cancel()
            }
        }

    // ---- other ways the workspace is replaced ----

    @Test
    fun `starting a new build forgets the parked work`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)
        try {
            model.setMode(damage)
            model.addTarget(Characteristic.HP)
            model.setMode(masteries)
            assertTrue(model.ui.modeWorkspaces.isNotEmpty())

            model.newBuild()

            assertTrue(model.ui.modeWorkspaces.isEmpty())
            model.setMode(damage)
            assertTrue(model.ui.targets.isEmpty(), "no HP row left over from before the new build")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `loading a saved build forgets the parked work`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }
                model.saveBuild("Saved", null, asNew = true)
                awaitUntil { model.ui.savedBuilds.isNotEmpty() }
                val id =
                    model.ui.savedBuilds
                        .single()
                        .id

                model.setMode(damage)
                model.addTarget(Characteristic.HP)
                model.setMode(masteries)
                assertTrue(model.ui.modeWorkspaces.isNotEmpty())

                model.loadBuild(id)

                assertTrue(model.ui.modeWorkspaces.isEmpty(), "the loaded request replaces the whole workspace")
                model.setMode(damage)
                assertTrue(model.ui.targets.isEmpty(), "the HP row parked before the load is gone")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `viewing a build as damage can be undone`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.searchAndWait()
                awaitUntil { model.ui.phase == Phase.Done }
                val before = model.ui.shownResult()
                val rows = model.ui.targets

                model.viewCurrentBuildAsMaxDamage()
                assertEquals(damage, model.ui.mode)
                assertEquals(foundBuild, model.ui.build, "the same build is now read as damage")
                assertEquals(rows, model.ui.targets, "it keeps the rows it was found under")

                model.setMode(masteries)

                assertEquals(before, model.ui.shownResult(), "the original result is back untouched")
                assertEquals(rows, model.ui.targets)
            } finally {
                scope.cancel()
            }
        }
}
