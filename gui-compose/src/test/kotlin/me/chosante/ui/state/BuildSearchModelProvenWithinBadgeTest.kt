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
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Tr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The order of a max-damage [MaxDamageSearch.MaxDamageProof.ProvenWithin] verdict in `launchOptimalityProof`: the badge
 * ("proven within X %" + the small "still proving" cue, [ProofState.ProvenWithin.refining]) is published AT ONCE, and the
 * E8 construct of the proven optimum — then, failing that, the silent per-carrier refinement — runs BEHIND it, instead of a
 * spinner hiding the badge for the whole (up to a minute) attempt. Fake provers / constructor / refiner block on latches, so
 * every test observes the screen state WHILE the engine call is in flight. The same fakes drive the Zenith contract of that
 * window: the user can export the incumbent while the badge is up, and a link made for it must never be shown for — nor
 * handed to the browser / clipboard as — the constructed build that then swaps in.
 */
class BuildSearchModelProvenWithinBadgeTest {
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

    private fun solverResult(
        build: BuildCombination,
        match: String,
        isOptimal: Boolean,
    ) = SolverResult(
        individual = build,
        matchPercentage = BigDecimal(match),
        progressPercentage = 100,
        isOptimal = isOptimal,
        maxDamageObjective = 5_000L
    )

    /** Latches + probes of the fake engine calls: each blocks until its `release…` opens, so the test can look at the screen meanwhile. */
    private class Engine {
        val proverStarted = CountDownLatch(1)
        val releaseProver = CountDownLatch(1)
        val constructStarted = CountDownLatch(1)
        val releaseConstruct = CountDownLatch(1)
        val refineStarted = CountDownLatch(1)
        val releaseRefine = CountDownLatch(1)
        val constructCalls = AtomicInteger(0)
        val refineCalls = AtomicInteger(0)
        val constructCancelPoll = AtomicReference<() -> Boolean>()
        val refineCancelPoll = AtomicReference<() -> Boolean>()

        fun releaseAll() {
            releaseProver.countDown()
            releaseConstruct.countDown()
            releaseRefine.countDown()
        }
    }

    /**
     * The fake Zenith export: the builder blocks on [releaseBuilder] (open by default) and then answers [link] — or throws when
     * [fail] — while [opened] / [copied] record what reached the browser / clipboard.
     */
    private class Zenith(
        val link: String = "https://zenithwakfu.com/builder/incumbent",
        val fail: Boolean = false,
        open: Boolean = true,
    ) {
        val builderStarted = CountDownLatch(1)
        val releaseBuilder = CountDownLatch(if (open) 0 else 1)
        val opened = CopyOnWriteArrayList<String>()
        val copied = CopyOnWriteArrayList<String>()

        fun build(): String {
            builderStarted.countDown()
            releaseBuilder.await(25, TimeUnit.SECONDS)
            check(!fail) { "zenith down" }
            return link
        }
    }

    /** A max-damage model whose search instantly yields [incumbentBuild] and whose proof / construct / refinement are the fakes of [engine]. */
    private fun newModel(
        scope: CoroutineScope,
        engine: Engine,
        proof: MaxDamageSearch.MaxDamageProof,
        construct: SolverResult<BuildCombination>? = null,
        refine: MaxDamageSearch.MaxDamageProof? = null,
        zenith: Zenith = Zenith(),
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { flowOf(solverResult(incumbentBuild, "1000", isOptimal = false)) },
            optimalityProver = { _, _, _, _ ->
                engine.proverStarted.countDown()
                engine.releaseProver.await(25, TimeUnit.SECONDS)
                proof
            },
            provenOptimumConstructor = { _, _, isCancelled ->
                engine.constructCancelPoll.set(isCancelled)
                engine.constructCalls.incrementAndGet()
                engine.constructStarted.countDown()
                engine.releaseConstruct.await(25, TimeUnit.SECONDS)
                construct
            },
            proofRefiner = { _, _, isCancelled ->
                engine.refineCancelPoll.set(isCancelled)
                engine.refineCalls.incrementAndGet()
                engine.refineStarted.countDown()
                engine.releaseRefine.await(25, TimeUnit.SECONDS)
                refine
            },
            zenithBuilder = { zenith.build() },
            openBrowser = { zenith.opened += it },
            copyToClipboard = { zenith.copied += it },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
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

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(50.milliseconds)
            }
        }
    }

    /** Runs a ProvenWithin(0.02) proof whose construct FAILS and whose refinement answers [refine]; returns the state the badge ends in. */
    private suspend fun badgeAfterFailedConstruct(refine: MaxDamageSearch.MaxDamageProof?): ProofState {
        val engine = Engine()
        engine.releaseAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02), construct = null, refine = refine)
        try {
            model.searchMaxDamage()
            assertTrue(engine.refineStarted.await(25, TimeUnit.SECONDS), "the refinement runs behind the badge once the construct failed")
            awaitUntil { model.ui.proofState != ProofState.ProvenWithin(0.02, refining = true) }
            assertEquals(incumbentBuild, model.ui.build, "a failed construct never swaps the build")
            assertFalse(model.ui.optimal)
            return model.ui.proofState
        } finally {
            engine.releaseAll()
            scope.cancel()
        }
    }

    @Test
    fun `a ProvenWithin verdict shows its badge at once and the constructed optimum lands behind it`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true)
                )
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()

                // The construct is in flight (blocked): the badge must ALREADY be up with the "still proving" cue — not a spinner.
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState)
                assertEquals(incumbentBuild, model.ui.build, "the incumbent stays on screen while the construct runs")
                assertFalse(model.ui.optimal)
                assertFalse(engine.constructCancelPoll.get()(), "nothing cancelled the construct")

                engine.releaseConstruct.countDown()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(constructedBuild, model.ui.build, "the constructed proven optimum replaces the shown build")
                assertEquals(0, BigDecimal("1100").compareTo(model.ui.match))
                assertTrue(model.ui.optimal)
                assertEquals(0, engine.refineCalls.get(), "a constructed optimum needs no refinement")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `a failed construct keeps the badge and its cue through the refinement and ends it with the tighter bound`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02), construct = null, refine = MaxDamageSearch.MaxDamageProof.ProvenWithin(0.01))
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()

                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState, "badge first, while the construct runs")

                engine.releaseConstruct.countDown()
                assertTrue(engine.refineStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState, "the same badge keeps its cue while the refinement runs")
                assertEquals(incumbentBuild, model.ui.build)

                engine.releaseRefine.countDown()
                awaitUntil { model.ui.proofState == ProofState.ProvenWithin(0.01, refining = false) }
                assertEquals(incumbentBuild, model.ui.build, "a refinement tightens the badge, it never swaps the build")
                assertFalse(model.ui.optimal)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `a failed construct and an inapplicable refinement end the badge without its cue`(): Unit =
        runBlocking {
            assertEquals(ProofState.ProvenWithin(0.02, refining = false), badgeAfterFailedConstruct(refine = null))
            // A refinement that does not improve keeps the (tighter of the two) bound.
            assertEquals(ProofState.ProvenWithin(0.02, refining = false), badgeAfterFailedConstruct(refine = MaxDamageSearch.MaxDamageProof.Unavailable))
        }

    @Test
    fun `a refinement that closes the proof turns the badge into proven optimal`(): Unit =
        runBlocking {
            assertEquals(ProofState.ProvenOptimal, badgeAfterFailedConstruct(refine = MaxDamageSearch.MaxDamageProof.ProvenOptimal))
        }

    @Test
    fun `a proof superseded while its construct runs sees its cancel flag and cannot swap the build in`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    refine = MaxDamageSearch.MaxDamageProof.ProvenWithin(0.01)
                )
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ProofState.ProvenWithin(0.02, refining = true), model.ui.proofState)

                model.cancel()
                assertTrue(engine.constructCancelPoll.get()(), "the construct must see the proof's cancel flag (its CP-SAT work stops)")

                // The engine call returns after the cancel with a perfectly good build — none of it may land.
                engine.releaseConstruct.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState)
                assertEquals(incumbentBuild, model.ui.build, "a superseded proof must not swap the build in")
                assertFalse(model.ui.optimal)
                assertEquals(0, engine.refineCalls.get(), "a dead proof must not start the minutes-long refinement either")
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `a badge that could not land never starts the construct or the refinement`(): Unit =
        runBlocking {
            val engine = Engine()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    refine = MaxDamageSearch.MaxDamageProof.ProvenWithin(0.01)
                )
            try {
                model.searchMaxDamage()
                assertTrue(engine.proverStarted.await(25, TimeUnit.SECONDS))

                // The proof is superseded while the certificate is still computing: its verdict arrives for a dead proof.
                model.cancel()
                engine.releaseProver.countDown()
                delay(400.milliseconds)
                assertEquals(ProofState.Idle, model.ui.proofState, "a late verdict must not resurrect a badge")
                assertEquals(0, engine.constructCalls.get(), "work whose result nothing can display must not run")
                assertEquals(0, engine.refineCalls.get())
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `a ProvenOptimal verdict is applied as soon as it is known`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(incumbentBuild, model.ui.build)
                assertEquals(0, engine.constructCalls.get(), "a proven optimum has nothing to construct")
                assertEquals(0, engine.refineCalls.get())
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    @Test
    fun `an Unavailable verdict is applied as soon as it is known`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.Unavailable)
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.Unavailable }
                assertEquals(0, engine.constructCalls.get())
                assertEquals(0, engine.refineCalls.get())
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }

    // ---- A Zenith link never outlives the build it was made for: the construct swaps the shown build while the badge is up ----

    @Test
    fun `a Zenith link made for the incumbent is reset when the constructed build swaps in`(): Unit =
        runBlocking {
            val engine = Engine()
            val zenith = Zenith()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    zenith = zenith
                )
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))

                // The refining badge is up and the incumbent is on screen: the user exports it to Zenith meanwhile.
                model.copyZenithLink()
                awaitUntil { model.ui.zenith == ZenithState.Ready && zenith.copied.isNotEmpty() }
                assertEquals(zenith.link, model.ui.zenithUrl)
                assertEquals(listOf(zenith.link), zenith.copied.toList())

                engine.releaseConstruct.countDown()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(constructedBuild, model.ui.build)
                assertEquals(ZenithState.Idle, model.ui.zenith, "a link made for the incumbent is never shown for the constructed build")
                assertNull(model.ui.zenithUrl)
            } finally {
                engine.releaseAll()
                zenith.releaseBuilder.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `a Zenith link still being made when the constructed build swaps in is dropped silently`(): Unit =
        runBlocking {
            val engine = Engine()
            val zenith = Zenith(open = false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    zenith = zenith
                )
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))

                // Both exports are in flight for the incumbent (their builder is blocked) when the swap happens.
                model.openZenithBuild()
                model.copyZenithLink()
                assertTrue(zenith.builderStarted.await(25, TimeUnit.SECONDS))
                assertEquals(ZenithState.Loading, model.ui.zenith)

                engine.releaseConstruct.countDown()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                assertEquals(constructedBuild, model.ui.build)
                assertEquals(ZenithState.Idle, model.ui.zenith, "the swap resets the pending export")

                // The links for the incumbent arrive after the swap: they must neither land nor reach the browser / clipboard.
                zenith.releaseBuilder.countDown()
                delay(400.milliseconds)
                assertEquals(ZenithState.Idle, model.ui.zenith, "no Ready for a stale build")
                assertNull(model.ui.zenithUrl)
                assertTrue(zenith.opened.isEmpty(), "no browser for a stale build")
                assertTrue(zenith.copied.isEmpty(), "no clipboard for a stale build")
                assertNotEquals(Tr.TOAST_ZENITH_READY.value(model.ui.lang), model.ui.toast)
            } finally {
                engine.releaseAll()
                zenith.releaseBuilder.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `a Zenith failure for a build that has been swapped out is dropped, one for the shown build still surfaces`(): Unit =
        runBlocking {
            // Stale: the export fails only after the swap.
            val engine = Engine()
            val stale = Zenith(fail = true, open = false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope,
                    engine,
                    MaxDamageSearch.MaxDamageProof.ProvenWithin(0.02),
                    construct = solverResult(constructedBuild, "1100", isOptimal = true),
                    zenith = stale
                )
            // Current: the same failure on a build that stays on screen is reported as usual.
            val currentEngine = Engine()
            currentEngine.releaseAll()
            val current = Zenith(fail = true)
            val currentModel = newModel(scope, currentEngine, MaxDamageSearch.MaxDamageProof.ProvenOptimal, zenith = current)
            try {
                engine.releaseProver.countDown()
                model.searchMaxDamage()
                assertTrue(engine.constructStarted.await(25, TimeUnit.SECONDS))
                model.copyZenithLink()
                assertTrue(stale.builderStarted.await(25, TimeUnit.SECONDS))
                engine.releaseConstruct.countDown()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }
                stale.releaseBuilder.countDown()
                delay(400.milliseconds)
                assertEquals(ZenithState.Idle, model.ui.zenith, "no Error for a stale build")
                assertNull(model.ui.error)

                currentModel.searchMaxDamage()
                awaitUntil { currentModel.ui.proofState == ProofState.ProvenOptimal }
                currentModel.copyZenithLink()
                awaitUntil { currentModel.ui.zenith == ZenithState.Error }
                assertEquals("zenith down", currentModel.ui.error)
            } finally {
                engine.releaseAll()
                stale.releaseBuilder.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `a Zenith link for the build that stays on screen still lands and reaches the browser and the clipboard`(): Unit =
        runBlocking {
            val engine = Engine()
            engine.releaseAll()
            val zenith = Zenith()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, engine, MaxDamageSearch.MaxDamageProof.ProvenOptimal, zenith = zenith)
            try {
                model.searchMaxDamage()
                awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }

                model.copyZenithLink()
                // The "copied" toast is the last thing the completion does, after the clipboard call.
                awaitUntil { model.ui.toast == Tr.TOAST_ZENITH_COPIED.value(model.ui.lang) }
                assertEquals(ZenithState.Ready, model.ui.zenith)
                assertEquals(zenith.link, model.ui.zenithUrl)
                assertEquals(listOf(zenith.link), zenith.copied.toList())

                model.openZenithBuild()
                awaitUntil { zenith.opened.isNotEmpty() }
                assertEquals(listOf(zenith.link), zenith.opened.toList())
                assertEquals(ZenithState.Ready, model.ui.zenith)
                assertEquals(zenith.link, model.ui.zenithUrl)
            } finally {
                engine.releaseAll()
                scope.cancel()
            }
        }
}
