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
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm.MostMasteriesProof
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The user's control over the background optimality check: the "Stop" link ([BuildSearchModel.stopProof]) and the
 * persisted "Check optimality after the search" switch ([BuildSearchModel.setVerifyOptimality]). Fake provers / constructor /
 * refiner block on latches, so every test observes the screen WHILE the engine call is in flight — and can let the engine
 * call return AFTER the stop, to prove that a late result never lands. [Engine.engineCancels] counts the model's reaches
 * for the engine's own search-time warm-ups (the real call touches process-wide caches, so it is replaced by a counter).
 */
class BuildSearchModelBackgroundProofTest {
    private val incumbentBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
    private val constructedBuild =
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

    /** A result carrying both modes' certificate-comparable objectives, so either mode's proof pipeline is eligible. */
    private fun solverResult(
        build: BuildCombination,
        match: String,
        isOptimal: Boolean,
    ) = SolverResult(
        individual = build,
        matchPercentage = BigDecimal(match),
        progressPercentage = 100,
        isOptimal = isOptimal,
        maxDamageObjective = 5_000L,
        mostMasteriesObjective = 1_000L
    )

    /** Latches, probes and verdicts of the fake engine calls. */
    private class Engine {
        // Max-damage chain: certificate -> E8 construct -> silent refinement.
        val proverStarted = CountDownLatch(1)
        val releaseProver = CountDownLatch(1)
        val constructStarted = CountDownLatch(1)
        val releaseConstruct = CountDownLatch(1)
        val refineStarted = CountDownLatch(1)
        val releaseRefine = CountDownLatch(1)
        val proverCalls = AtomicInteger(0)
        val constructCalls = AtomicInteger(0)
        val refineCalls = AtomicInteger(0)
        val proverCancelPoll = AtomicReference<() -> Boolean>()
        val constructCancelPoll = AtomicReference<() -> Boolean>()
        val refineCancelPoll = AtomicReference<() -> Boolean>()

        // Most-masteries quality proof: called either as a real wait/compute (its continue-predicate is true: it blocks until
        // released) or as a PEEK (the predicate is already false: it answers [mmPeekVerdict] at once, like a memo read).
        val mmEntered = CountDownLatch(1)
        val releaseMm = CountDownLatch(1)
        val mmPolls = CopyOnWriteArrayList<() -> Boolean>()
        val mmComputeVerdict = AtomicReference<MostMasteriesProof>(MostMasteriesProof.Unavailable)
        val mmPeekVerdict = AtomicReference<MostMasteriesProof>(MostMasteriesProof.Unavailable)

        // How many times the model reached for the engine's own warm-ups (BuildSearchModel.backgroundProofCanceller).
        val engineCancels = AtomicInteger(0)

        fun releaseAll() {
            releaseProver.countDown()
            releaseConstruct.countDown()
            releaseRefine.countDown()
            releaseMm.countDown()
        }
    }

    private fun newModel(
        scope: CoroutineScope,
        engine: Engine,
        proof: MaxDamageSearch.MaxDamageProof = MaxDamageSearch.MaxDamageProof.ProvenOptimal,
        construct: SolverResult<BuildCombination>? = null,
        refine: MaxDamageSearch.MaxDamageProof? = null,
        isOptimal: Boolean = false,
        preferences: LibraryPreferences = LibraryPreferences(null),
        buildFinder: () -> Flow<SolverResult<BuildCombination>> = { flowOf(solverResult(incumbentBuild, "1000", isOptimal)) },
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { buildFinder() },
            optimalityProver = { _, _, isCancelled, _ ->
                engine.proverCalls.incrementAndGet()
                engine.proverCancelPoll.set(isCancelled)
                engine.proverStarted.countDown()
                engine.releaseProver.await(25, TimeUnit.SECONDS)
                proof
            },
            mmQualityProver = { _, _, shouldContinue ->
                engine.mmPolls += shouldContinue
                if (!shouldContinue()) {
                    engine.mmPeekVerdict.get()
                } else {
                    engine.mmEntered.countDown()
                    // A real wait/compute that ignores the cancellation it is given and answers once released — the worst case
                    // for a Stop (the engine call returns a real verdict AFTER it): the model must drop that verdict itself.
                    engine.releaseMm.await(25, TimeUnit.SECONDS)
                    engine.mmComputeVerdict.get()
                }
            },
            provenOptimumConstructor = { _, _, isCancelled ->
                engine.constructCalls.incrementAndGet()
                engine.constructCancelPoll.set(isCancelled)
                engine.constructStarted.countDown()
                engine.releaseConstruct.await(25, TimeUnit.SECONDS)
                construct
            },
            proofRefiner = { _, _, isCancelled ->
                engine.refineCalls.incrementAndGet()
                engine.refineCancelPoll.set(isCancelled)
                engine.refineStarted.countDown()
                engine.releaseRefine.await(25, TimeUnit.SECONDS)
                refine
            },
            backgroundProofCanceller = { engine.engineCancels.incrementAndGet() },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = preferences,
            historyRepository =
                HistoryRepository(
                    baseDir = Files.createTempDirectory("wakfu-test-history"),
                    ioDispatcher = Dispatchers.Unconfined
                )
        )

    private fun BuildSearchModel.searchMaxDamage() {
        setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
        setDuration("1")
        search()
    }

    /** The default mode is most-masteries. */
    private fun BuildSearchModel.searchMostMasteries() {
        setDuration("1")
        search()
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(50.milliseconds)
            }
        }
    }

    // ---- Stop: keeps what is known, drops what lands late ----

    @Test
    fun `Stop during the E8 construct keeps the badge without its cue and a build constructed late never swaps in`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    refine = MaxDamageSearch.MaxDamageProof.ProvenOptimal
                )
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState)

                model.stopProof()

                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState, "the badge stays, its cue goes")
                assertEquals(incumbentBuild, model.ui.build, "stopping never changes the shown build")
                assertTrue(engine.constructCancelPoll.get()(), "the construct must see the proof's cancel flag (its CP-SAT work stops)")
                assertEquals(1, engine.engineCancels.get(), "the engine's own warm-ups are stopped with it")

                // The engine call returns AFTER the stop with a perfectly good proven-optimal build — none of it may land.
                engine.releaseConstruct.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState)
                assertEquals(incumbentBuild, model.ui.build, "a late constructed build must not swap in")
                assertFalse(model.ui.optimal)
                assertEquals(0, engine.refineCalls.get(), "a stopped proof must not start the minutes-long refinement either")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop during the silent refinement keeps the badge and a refinement that finishes late is ignored`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = null,
                    refine = MaxDamageSearch.MaxDamageProof.ProvenOptimal
                )
            try {
                engine.releaseProver.countDown()
                engine.releaseConstruct.countDown()
                model.searchMaxDamage()
                assertTrue(engine.refineStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState)

                model.stopProof()

                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState)
                assertTrue(engine.refineCancelPoll.get()(), "the refinement must see the proof's cancel flag (its CP-SAT work stops)")

                // The refinement closes the proof AFTER the stop: the badge must not flip to "proven optimal".
                engine.releaseRefine.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState, "a late refinement must not land")
                assertFalse(model.ui.optimal)
                assertEquals(incumbentBuild, model.ui.build)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop before the certificate knows anything falls back to the not-proven state and its late verdict is ignored`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // The certificate would answer ProvenOptimal — and a ProvenWithin would start the E8 construct: neither may land.
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                model.searchMaxDamage()
                assertTrue(engine.proverStarted.await(25, TimeUnit.SECONDS))
                awaitUntil { model.ui.proofState is ProofState.Proving }

                model.stopProof()

                assertEquals(ProofState.Idle, model.ui.proofState, "nothing known yet: the usual not-proven hint")
                assertEquals(incumbentBuild, model.ui.build)
                assertTrue(engine.proverCancelPoll.get()(), "the certificate must see the proof's cancel flag")
                assertEquals(1, engine.engineCancels.get())

                engine.releaseProver.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "a late verdict must not resurrect a badge")
                assertFalse(model.ui.optimal)
                assertEquals(0, engine.constructCalls.get())
                assertEquals(0, engine.refineCalls.get())
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop before the certificate answers ProvenWithin starts neither the construct nor the refinement`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02), construct = solverResult(constructedBuild, "1100", isOptimal = true))
            try {
                model.searchMaxDamage()
                assertTrue(engine.proverStarted.await(25, TimeUnit.SECONDS))
                model.stopProof()

                engine.releaseProver.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "a late ProvenWithin verdict must not publish its badge")
                assertEquals(0, engine.constructCalls.get(), "work whose result nothing can display must not run")
                assertEquals(0, engine.refineCalls.get())
                assertEquals(incumbentBuild, model.ui.build)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop ends a most-masteries quality wait and its verdict arriving late is ignored`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine)
            engine.mmComputeVerdict.set(MostMasteriesProof.ProvenWithin(0.30))
            try {
                model.searchMostMasteries()
                assertTrue(engine.mmEntered.await(25, TimeUnit.SECONDS))
                awaitUntil { model.ui.proofState is ProofState.Proving }

                model.stopProof()

                assertEquals(ProofState.Idle, model.ui.proofState)
                assertFalse(engine.mmPolls[0](), "the waiting proof must see its cancel flag (its wait stops)")
                assertEquals(1, engine.engineCancels.get(), "the bound the search's tail left computing is stopped too")

                engine.releaseMm.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "a late quality verdict must not land")
                assertEquals(incumbentBuild, model.ui.build)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop changes nothing when no check is running, and is safe to repeat`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                // Nothing yet: no build, no proof.
                model.stopProof()
                assertEquals(ProofState.Idle, model.ui.proofState)

                // A final verdict is left alone.
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                model.stopProof()
                model.stopProof()
                assertEquals(ProofState.ProvenOptimal, model.ui.proofState)
                assertEquals(incumbentBuild, model.ui.build)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `a badge whose cue was already stopped is not touched by a second Stop`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // The construct fails and the refinement is inapplicable: the badge ends without its cue on its own.
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02), construct = null, refine = null)
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.ProvenWithin(0.02, refining = false) }
                model.stopProof()
                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `Stop never reaches for the warm-ups of a search that is still running`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val searchEnds = CompletableDeferred<Unit>()
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenOptimal,
                    buildFinder = {
                        flow {
                            emit(solverResult(incumbentBuild, "1000", isOptimal = false))
                            searchEnds.await()
                        }
                    }
                )
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.build != null }
                assertEquals(Phase.Searching, model.ui.phase)

                model.stopProof()
                assertEquals(0, engine.engineCancels.get(), "the running search's own warm-ups are what lets it stop early")

                // The search then ends and, the check being ON, verifies as usual.
                searchEnds.complete(Unit)
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(0, engine.engineCancels.get())
            } finally {
                searchEnds.complete(Unit)
                engine.releaseAll()
                scope.cancel()
            }
        }

    // ---- The "Check optimality after the search" switch ----

    @Test
    fun `the check is ON by default and verifies as usual`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                assertTrue(model.ui.verifyOptimality)
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(1, engine.proverCalls.get())
                assertEquals(0, engine.engineCancels.get(), "with the check ON the engine's warm-ups are left for the proof to join")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF a finished max-damage search launches no proof work at all`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Were any of these launched they would answer — so a stray launch cannot go unnoticed.
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    refine = MaxDamageSearch.MaxDamageProof.ProvenOptimal
                )
            try {
                model.setVerifyOptimality(false)
                assertFalse(model.ui.verifyOptimality)
                // Switching OFF reaches for the engine's leftovers once (a previous search's, were there any)…
                val afterSwitch = engine.engineCancels.get()
                model.searchMaxDamage()
                awaitUntil { model.ui.phase == Phase.Done }
                awaitUntil { engine.engineCancels.get() == afterSwitch + 1 }
                delay(400.milliseconds)

                assertEquals(0, engine.proverCalls.get(), "no certificate wait or compute after the search")
                assertEquals(0, engine.constructCalls.get(), "no E8 construct")
                assertEquals(0, engine.refineCalls.get(), "no silent refinement")
                assertEquals(0, engine.mmPolls.size, "no quality bound either")
                assertEquals(ProofState.Idle, model.ui.proofState, "the normal not-proven hint")
                assertEquals(incumbentBuild, model.ui.build, "the searched build stays")
                assertFalse(model.ui.optimal)
                assertEquals(afterSwitch + 1, engine.engineCancels.get(), "…and once more when the search ends: the warm-ups it left for a proof that will not come")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF a result CP-SAT proved still shows its proof`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, isOptimal = true)
            try {
                model.setVerifyOptimality(false)
                model.searchMaxDamage()
                awaitUntil { model.ui.phase == Phase.Done }
                delay(300.milliseconds)
                // The stats panel reads the headline straight from the result: nothing needs to be computed.
                assertTrue(model.ui.optimal)
                assertEquals(0, engine.proverCalls.get())
                assertEquals(ProofState.Idle, model.ui.proofState)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF most-masteries shows a quality bound the search already paid for, without computing`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.mmPeekVerdict.set(MostMasteriesProof.ProvenWithin(0.05))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine)
            try {
                model.setVerifyOptimality(false)
                val afterSwitch = engine.engineCancels.get()
                model.searchMostMasteries()
                awaitUntil { model.ui.proofState == ProofState.ProvenWithin(0.05, refining = false) }

                assertEquals(1, engine.mmPolls.size)
                assertFalse(engine.mmPolls[0](), "the prover is only PEEKED: its continue-predicate is already false, so it can only read the memo")
                assertEquals(incumbentBuild, model.ui.build)
                assertEquals(afterSwitch + 1, engine.engineCancels.get(), "a bound the search left computing is cancelled as it ends")
                assertEquals(1L, engine.mmEntered.count, "the prover never entered its compute path")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF a most-masteries quality bound that is not ready is neither waited for nor computed`(): Unit =
        runBlocking {
            val engine = Engine()
            // Nothing memoized: the peek answers "unavailable". A (wrong) real wait/compute would block on releaseMm.
            engine.mmPeekVerdict.set(MostMasteriesProof.Unavailable)
            engine.mmComputeVerdict.set(MostMasteriesProof.ProvenWithin(0.30))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine)
            try {
                model.setVerifyOptimality(false)
                model.searchMostMasteries()
                awaitUntil { model.ui.phase == Phase.Done }
                awaitUntil { engine.mmPolls.size == 1 }
                delay(300.milliseconds)

                assertFalse(engine.mmPolls[0]())
                assertEquals(ProofState.Idle, model.ui.proofState, "the normal not-proven hint")
                assertEquals(1L, engine.mmEntered.count, "nothing was waited for or computed")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF a most-masteries result CP-SAT proved runs no quality proof`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, isOptimal = true)
            try {
                model.setVerifyOptimality(false)
                model.searchMostMasteries()
                awaitUntil { model.ui.phase == Phase.Done }
                delay(300.milliseconds)
                assertTrue(model.ui.optimal)
                assertEquals(0, engine.mmPolls.size)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `switching the check OFF stops a running proof exactly like Stop`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                model.searchMaxDamage()
                assertTrue(engine.proverStarted.await(25, TimeUnit.SECONDS))
                awaitUntil { model.ui.proofState is ProofState.Proving }

                model.setVerifyOptimality(false)

                assertFalse(model.ui.verifyOptimality)
                assertEquals(ProofState.Idle, model.ui.proofState)
                assertTrue(engine.proverCancelPoll.get()(), "the running certificate must see the cancel flag")
                assertEquals(1, engine.engineCancels.get())

                engine.releaseProver.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "a late verdict must not land")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `switching the check OFF during the refinement keeps the badge and stops the work behind it`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02), construct = null, refine = MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                engine.releaseProver.countDown()
                engine.releaseConstruct.countDown()
                model.searchMaxDamage()
                assertTrue(engine.refineStarted.await(25, TimeUnit.SECONDS))

                model.setVerifyOptimality(false)

                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState)
                assertTrue(engine.refineCancelPoll.get()())
                engine.releaseRefine.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.ProvenWithin(0.02, refining = false), model.ui.proofState)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `switching the check ON again leaves the shown build alone and makes the next search verify`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                model.setVerifyOptimality(false)
                val afterSwitch = engine.engineCancels.get()
                model.searchMaxDamage()
                awaitUntil { model.ui.phase == Phase.Done }
                awaitUntil { engine.engineCancels.get() == afterSwitch + 1 }

                model.setVerifyOptimality(true)
                delay(300.milliseconds)
                assertEquals(0, engine.proverCalls.get(), "switching ON does not retroactively verify the build on screen")
                assertEquals(ProofState.Idle, model.ui.proofState)

                model.search()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(1, engine.proverCalls.get())
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `the switch in force when the search ends decides, so flipping it mid-search counts`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val searchEnds = CompletableDeferred<Unit>()
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenOptimal,
                    buildFinder = {
                        flow {
                            emit(solverResult(incumbentBuild, "1000", isOptimal = false))
                            searchEnds.await()
                        }
                    }
                )
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.build != null }
                model.setVerifyOptimality(false) // while Searching
                searchEnds.complete(Unit)
                awaitUntil { model.ui.phase == Phase.Done }
                awaitUntil { engine.engineCancels.get() == 1 }
                delay(300.milliseconds)
                assertEquals(0, engine.proverCalls.get(), "switched OFF mid-search: no proof after it")
                assertEquals(ProofState.Idle, model.ui.proofState)
                assertEquals(1, engine.engineCancels.get(), "cancelled once, at the end of the search — not while it ran")
            } finally {
                searchEnds.complete(Unit)
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `with the check OFF the real engine answers the memo peek at once and its warm-up cancel is harmless`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            // Only the search is stubbed: the quality prover and the warm-up canceller are the real engine's.
            val model =
                BuildSearchModel(
                    scope = scope,
                    buildFinder = { flowOf(solverResult(incumbentBuild, "1000", isOptimal = false)) },
                    zenithBuilder = { "" },
                    mainDispatcher = Dispatchers.Unconfined,
                    ioDispatcher = Dispatchers.Unconfined,
                    libraryPreferences = LibraryPreferences(null),
                    historyRepository =
                        HistoryRepository(
                            baseDir = Files.createTempDirectory("wakfu-test-history"),
                            ioDispatcher = Dispatchers.Unconfined
                        )
                )
            try {
                model.setVerifyOptimality(false)

                // Most-masteries: nothing is memoized for this request, so the real peek finds no bound — and starts none.
                model.searchMostMasteries()
                awaitUntil { model.ui.phase == Phase.Done }
                delay(500.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "no bound to show, none computed")
                assertNull(model.ui.error)

                // Max-damage: no proof launched; only the engine's leftovers are cancelled (a no-op here).
                model.searchMaxDamage()
                awaitUntil { model.ui.phase == Phase.Done }
                delay(500.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState)
                assertNull(model.ui.error)
                assertEquals(incumbentBuild, model.ui.build)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the switch is restored from the preferences and survives New build`(): Unit =
        runBlocking {
            val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/${javaClass.simpleName}")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                node.clear()
                val engine = Engine()
                assertTrue(newModel(scope, engine, preferences = LibraryPreferences(node)).ui.verifyOptimality, "first launch: ON")

                val first = newModel(scope, engine, preferences = LibraryPreferences(node))
                first.setVerifyOptimality(false)
                assertFalse(
                    newModel(scope, engine, preferences = LibraryPreferences(node)).ui.verifyOptimality,
                    "a relaunch (a fresh model on the same preferences) restores OFF"
                )

                first.newBuild()
                assertFalse(first.ui.verifyOptimality, "New build resets the request, not the app option")

                first.setVerifyOptimality(true)
                assertTrue(newModel(scope, engine, preferences = LibraryPreferences(node)).ui.verifyOptimality, "and ON is restored too")
            } finally {
                scope.cancel()
                runCatching { node.removeNode() }
            }
        }
}
