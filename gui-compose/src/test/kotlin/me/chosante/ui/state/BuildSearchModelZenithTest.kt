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
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Zenith export: ONE Zenith build per build on screen (Open then Copy used to create two), and failures that read as a plain
 * sentence with a Retry instead of "api.zenithwakfu.com" / "Timed out waiting for 10000 ms". The raw detail goes to the log.
 */
class BuildSearchModelZenithTest {
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    /** Everything the model hands to the outside world, and what the fake Zenith does. */
    private class Outside {
        val created = AtomicInteger(0)
        val opened = CopyOnWriteArrayList<String>()
        val copied = CopyOnWriteArrayList<String>()
        var failBrowser = false

        // Each Zenith build the fake creates gets its own link, so "which build is this link for" is visible in the asserts.
        @Volatile
        var zenith: suspend (ZenithInputParameters) -> String = { "https://zenithwakfu.com/builder/link-${created.get()}" }
    }

    private fun result() = SolverResult(individual = foundBuild, matchPercentage = BigDecimal("1000"), progressPercentage = 100, isOptimal = true)

    private fun newModel(
        scope: CoroutineScope,
        outside: Outside,
        finder: () -> Flow<SolverResult<BuildCombination>> = { flowOf(result()) },
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { finder() },
            zenithBuilder = { params ->
                outside.created.incrementAndGet()
                outside.zenith(params)
            },
            openBrowser = {
                if (outside.failBrowser) error("Desktop API is not supported on the current platform")
                outside.opened += it
            },
            copyToClipboard = { outside.copied += it },
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

    private suspend fun BuildSearchModel.searchDone() {
        setDuration("1")
        search()
        awaitUntil { ui.phase == Phase.Done }
    }

    // ---- one Zenith build per build ----

    @Test
    fun `Open then Copy creates one Zenith build and hands the same link to both`(): Unit =
        runBlocking {
            val outside = Outside()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.openZenithBuild()
                awaitUntil { outside.opened.isNotEmpty() }
                model.copyZenithLink()
                awaitUntil { outside.copied.isNotEmpty() }

                assertEquals(1, outside.created.get(), "the second action must reuse the link made for this build")
                assertEquals(outside.opened.toList(), outside.copied.toList())
                assertEquals(outside.opened.single(), model.ui.zenithUrl)
                assertEquals(Tr.TOAST_ZENITH_COPIED.en, model.ui.toast)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `Copy then Open creates one Zenith build too, and repeating an action still creates nothing`(): Unit =
        runBlocking {
            val outside = Outside()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.copyZenithLink()
                awaitUntil { outside.copied.isNotEmpty() }
                model.openZenithBuild()
                model.openZenithBuild()
                model.copyZenithLink()

                assertEquals(1, outside.created.get())
                assertEquals(2, outside.opened.size)
                assertEquals(2, outside.copied.size)
                assertEquals(setOf(outside.copied.first()), (outside.opened + outside.copied).toSet())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new search exports its build afresh`(): Unit =
        runBlocking {
            val outside = Outside()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()
                model.openZenithBuild()
                awaitUntil { outside.opened.isNotEmpty() }

                model.searchDone()
                assertNull(model.ui.zenithUrl, "a link made for the previous build is not shown for the new one")
                model.openZenithBuild()
                awaitUntil { outside.opened.size == 2 }

                assertEquals(2, outside.created.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a second request while the link is being made is ignored instead of creating a duplicate`(): Unit =
        runBlocking {
            val outside = Outside()
            val release = CountDownLatch(1)
            outside.zenith = {
                release.await(25, TimeUnit.SECONDS)
                "https://zenithwakfu.com/builder/slow"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.openZenithBuild()
                awaitUntil { model.ui.zenith == ZenithState.Loading && outside.created.get() == 1 }
                model.copyZenithLink()
                release.countDown()
                awaitUntil { outside.opened.isNotEmpty() }
                delay(200.milliseconds)

                assertEquals(1, outside.created.get())
                assertTrue(outside.copied.isEmpty(), "the ignored request does not act later either")
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    // ---- friendly failures ----

    @Test
    fun `an unreachable Zenith reads as a plain sentence with a Retry, the raw cause stays out of the banner`(): Unit =
        runBlocking {
            val outside = Outside()
            outside.zenith = { throw UnknownHostException("api.zenithwakfu.com") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.openZenithBuild()
                awaitUntil { model.ui.zenith == ZenithState.Error }

                val error = checkNotNull(model.ui.error)
                assertEquals(Tr.ZENITH_UNREACHABLE.en, error.message)
                assertEquals(ErrorRetry.OPEN_ZENITH, error.retry)
                assertFalse(error.message.contains("zenithwakfu"))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a Zenith timeout reads the same way, in French when the app is in French`(): Unit =
        runBlocking {
            val outside = Outside()
            outside.zenith = { withTimeout(30.milliseconds) { delay(5.seconds).let { "never reached" } } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.setLang(Lang.FR)
                model.searchDone()

                model.copyZenithLink()
                awaitUntil { model.ui.zenith == ZenithState.Error }

                val error = checkNotNull(model.ui.error)
                assertEquals(Tr.ZENITH_UNREACHABLE.fr, error.message)
                assertEquals(ErrorRetry.COPY_ZENITH, error.retry)
                assertFalse(error.message.contains("Timed out"))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `Retry repeats the action that failed and clears the banner`(): Unit =
        runBlocking {
            val outside = Outside()
            val attempts = AtomicInteger(0)
            outside.zenith = {
                if (attempts.incrementAndGet() == 1) throw UnknownHostException("api.zenithwakfu.com") else "https://zenithwakfu.com/builder/second"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()
                model.copyZenithLink()
                awaitUntil { model.ui.zenith == ZenithState.Error }
                assertTrue(outside.copied.isEmpty())

                model.retryAfterError()
                awaitUntil { outside.copied.isNotEmpty() }

                assertEquals(listOf("https://zenithwakfu.com/builder/second"), outside.copied.toList())
                assertEquals(ZenithState.Ready, model.ui.zenith)
                assertNull(model.ui.error)
                assertTrue(outside.opened.isEmpty(), "Retry repeats the Copy that failed, not the other action")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `Retry on a failed Open opens the browser`(): Unit =
        runBlocking {
            val outside = Outside()
            val attempts = AtomicInteger(0)
            outside.zenith = {
                if (attempts.incrementAndGet() == 1) throw UnknownHostException("api.zenithwakfu.com") else "https://zenithwakfu.com/builder/second"
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()
                model.openZenithBuild()
                awaitUntil { model.ui.zenith == ZenithState.Error }

                model.retryAfterError()
                awaitUntil { outside.opened.isNotEmpty() }

                assertEquals(listOf("https://zenithwakfu.com/builder/second"), outside.opened.toList())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `Retry with no error on screen does nothing`(): Unit =
        runBlocking {
            val outside = Outside()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.retryAfterError()

                assertEquals(0, outside.created.get())
                assertEquals(Phase.Done, model.ui.phase)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a browser that cannot open keeps the link and points at Copy build link`(): Unit =
        runBlocking {
            val outside = Outside()
            outside.failBrowser = true
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope, outside)
            try {
                model.searchDone()

                model.openZenithBuild()
                awaitUntil { model.ui.error != null }

                val error = checkNotNull(model.ui.error)
                assertEquals(Tr.ZENITH_BROWSER_FAILED.en.format(Tr.COPY_BUILD_LINK.en), error.message)
                assertNull(error.retry, "retrying would fail the same way: the message points at the alternative")
                assertFalse(error.message.contains("Desktop API"))
                assertEquals(ZenithState.Ready, model.ui.zenith, "the Zenith build itself was created fine")

                model.copyZenithLink()
                assertEquals(1, outside.created.get(), "Copy build link reuses the link the browser could not open")
                assertEquals(listOf(model.ui.zenithUrl), outside.copied.toList())
            } finally {
                scope.cancel()
            }
        }

    // ---- a failing search ----

    @Test
    fun `a search that crashes shows a plain sentence and a Retry that runs it again`(): Unit =
        runBlocking {
            val outside = Outside()
            val calls = AtomicInteger(0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope, outside) {
                    if (calls.incrementAndGet() == 1) error("libjniortools.dylib: dlopen failed") else flowOf(result())
                }
            try {
                model.setDuration("1")
                model.search()
                awaitUntil { model.ui.error != null }

                val error = checkNotNull(model.ui.error)
                assertEquals(Tr.SEARCH_FAILED.en, error.message)
                assertEquals(ErrorRetry.SEARCH, error.retry)
                assertEquals(Phase.Idle, model.ui.phase)

                model.retryAfterError()
                awaitUntil { model.ui.phase == Phase.Done }

                assertNull(model.ui.error)
                assertEquals(2, calls.get())
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a search that crashes after streaming a build keeps that build as a finished, not-proven result`(): Unit =
        runBlocking {
            val outside = Outside()
            val streamed = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(scope, outside) {
                    flow {
                        emit(result())
                        // Fail only once the screen has the build, so the test does not race the flow's buffering.
                        streamed.await()
                        error("native failure")
                    }
                }
            try {
                model.setDuration("1")
                model.search()
                awaitUntil { model.ui.build != null }
                streamed.complete(Unit)
                awaitUntil { model.ui.error != null }

                assertEquals(Phase.Done, model.ui.phase, "the build must not be stranded in the idle phase")
                assertNotNull(model.ui.build)
                assertTrue(model.ui.searchStopped)
                assertFalse(model.ui.optimal)
            } finally {
                scope.cancel()
            }
        }
}
