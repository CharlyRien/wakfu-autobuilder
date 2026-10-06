package me.chosante.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.ENGINE_RESULTS_VERSION
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.ScenarioDamage
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.against
import me.chosante.autobuilder.domain.againstAllElements
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.autobuilder.genetic.wakfu.elementRowObjectives
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.Character
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.workspace.WorkspaceSnapshot
import me.chosante.createZenithBuild
import me.chosante.ui.components.BreedAssets
import me.chosante.ui.components.IconPreloader
import me.chosante.ui.components.warmUpPaths
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.historyJson
import me.chosante.ui.history.normalizeTags
import me.chosante.ui.history.restoredBoss
import me.chosante.ui.history.restoredBossDifficulty
import me.chosante.ui.history.restoredBossElement
import me.chosante.ui.history.restoredClass
import me.chosante.ui.history.restoredMode
import me.chosante.ui.history.restoredScenario
import me.chosante.ui.history.suggestedBuildName
import me.chosante.ui.history.toBuildCombination
import me.chosante.ui.history.toExcludedChips
import me.chosante.ui.history.toForcedChips
import me.chosante.ui.history.toHistoryEntry
import me.chosante.ui.history.toRequestSnapshot
import me.chosante.ui.history.toTargetRows
import me.chosante.ui.i18n.Tr
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private typealias BuildFinder = (WakfuBestBuildParams) -> Flow<SolverResult<BuildCombination>>
private typealias ZenithBuilder = suspend (ZenithInputParameters) -> String
private typealias OptimalityProver = (WakfuBestBuildParams, SolverResult<BuildCombination>, () -> Boolean, (String) -> Unit) -> MaxDamageSearch.MaxDamageProof

/** The four specific elemental masteries, mutually exclusive with the aggregate "all elements". */
private val ELEMENTAL_MASTERY_ELEMENTS =
    setOf(
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val ELEMENTAL_RESISTANCES =
    listOf(
        Characteristic.RESISTANCE_ELEMENTARY_WATER,
        Characteristic.RESISTANCE_ELEMENTARY_FIRE,
        Characteristic.RESISTANCE_ELEMENTARY_EARTH,
        Characteristic.RESISTANCE_ELEMENTARY_WIND
    )

/**
 * Splits a single "all resistances" target ([Characteristic.RESISTANCE_ELEMENTARY]) into the four
 * per-element resistance targets, keeping any element the user already set explicitly. The solver
 * models the aggregate as one min-over-four constraint, which the score's power-6 penalty makes
 * brittle — one short element craters the whole build; four independent per-element constraints
 * degrade gracefully (the "400 in each" form that works). Applied only when handing off to the
 * engine, so the UI keeps a single editable row.
 */
internal fun expandGlobalResistance(targets: List<TargetStat>): List<TargetStat> {
    val global = targets.firstOrNull { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY } ?: return targets
    // A meaningful (non-zero) per-element resistance keeps its own value; a zero one only keeps its element at 0 or more
    // (e.g. the default wind=0), which the global's own row on that element asks for anyway (`≥ value ≥ 0`), so the global
    // overrides it and all four really get the value.
    val explicit = targets.filter { it.characteristic in ELEMENTAL_RESISTANCES && it.target != 0 }.map { it.characteristic }.toSet()
    val perElement =
        ELEMENTAL_RESISTANCES
            .filter { it !in explicit }
            .map { TargetStat(it, global.target, global.userDefinedWeight) }
    return targets.filterNot {
        it.characteristic == Characteristic.RESISTANCE_ELEMENTARY ||
            (it.characteristic in ELEMENTAL_RESISTANCES && it.target == 0)
    } + perElement
}

/**
 * Whether [rescored] is the score a saved build stored, [stored] being that score read back. A save keeps its score as a [Double]
 * ([me.chosante.common.history.ResultSnapshot.match]), so the two are compared there: a score of 16-17 significant digits does not
 * survive BigDecimal → Double → BigDecimal, and comparing the BigDecimals would call an unchanged build changed.
 */
private fun isStoredScore(
    rescored: java.math.BigDecimal,
    stored: java.math.BigDecimal,
): Boolean = rescored.toDouble() == stored.toDouble()

/** The library re-score publishes every [RESCORE_BATCH_SIZE] builds or [RESCORE_BATCH_NANOS], whichever comes first. */
private const val RESCORE_BATCH_SIZE = 20
private const val RESCORE_BATCH_NANOS = 100_000_000L

class BuildSearchModel(
    private val scope: CoroutineScope,
    private val buildFinder: BuildFinder = { WakfuBestBuildFinderAlgorithm.run(it) },
    // Post-search certificate optimality proof (P4.4). Injectable so tests drive proofState deterministically
    // without a real (minutes-long) exact solve.
    private val optimalityProver: OptimalityProver = { params, result, isCancelled, onPhase ->
        WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(params, result, isCancelled, onPhase)
    },
    // Most-masteries backup certificate (plan §8.9bis): the "proven within X%" quality verdict for
    // searches CP-SAT left un-proven. Its bound is computed in the search's tail (E10-for-MM, §8.19),
    // so this is normally instant at search end; it waits for the in-flight bound (or computes it,
    // after a short budget) otherwise. Injectable for the same reason as [optimalityProver] (the real
    // DP takes seconds on the full pool).
    private val mmQualityProver: (WakfuBestBuildParams, SolverResult<BuildCombination>, () -> Boolean) -> WakfuBestBuildFinderAlgorithm.MostMasteriesProof =
        { params, result, shouldContinue -> WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(params, result, shouldContinue) },
    private val zenithBuilder: ZenithBuilder = { it.createZenithBuild() },
    private val openBrowser: (String) -> Unit = { link -> Desktop.getDesktop().browse(URI(link)) },
    private val copyToClipboard: (String) -> Unit = { link -> Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null) },
    private val readClipboard: () -> String = {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull().orEmpty()
    },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Swing,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** CPU work shares an injectable dispatcher so model tests can use one scheduler for every state update. */
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val historyRepository: HistoryRepository = HistoryRepository(),
    /** Persisted library view options (sort + group-by-class). Injectable for tests. */
    private val libraryPreferences: LibraryPreferences = LibraryPreferences(),
    /** Wakfu game-data version stamped onto saved builds (injectable for tests). */
    private val dataVersion: String = WakfuBestBuildFinderAlgorithm.dataVersion,
    private val idGenerator: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    // E8 rescue after a ProvenWithin verdict: CONSTRUCT the proven optimum from the certificate (a failing attempt can
    // take up to a minute). Injectable like [optimalityProver], so tests drive the badge-first / upgrade-later order
    // deterministically instead of running the real engine.
    private val provenOptimumConstructor: (WakfuBestBuildParams, SolverResult<BuildCombination>, () -> Boolean) -> SolverResult<BuildCombination>? =
        { params, result, isCancelled -> WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(params, result, isCancelled) },
    // Silent per-carrier refinement behind a soft-leg ProvenWithin badge (minutes) — injectable for the same reason.
    private val proofRefiner: (WakfuBestBuildParams, SolverResult<BuildCombination>, () -> Boolean) -> MaxDamageSearch.MaxDamageProof? =
        { params, result, isCancelled -> WakfuBestBuildFinderAlgorithm.refineMaxDamageOptimality(params, result, isCancelled) },
    // Stops the optimality work the ENGINE started beside the last search and leaves running after it (the certificate and
    // quality-bound warm-ups a proof would join). Reached only when no proof will use them — the user stopped the check or
    // switched it off — and only while no search runs. Injectable so tests see WHEN the model reaches for it without
    // touching the engine's process-wide caches.
    private val backgroundProofCanceller: () -> Unit = { WakfuBestBuildFinderAlgorithm.cancelBackgroundProofs() },
    // A build's score under the CURRENT rules ([WakfuBestBuildFinderAlgorithm.rescore]): what a loaded saved build ([loadBuild]) and
    // the constructed optimum that swaps in after a proof ([launchOptimalityProof]) are shown and saved with, so both read like a
    // search's own result. Injectable so tests can hand back a score of any shape, or time the load without it.
    private val buildRescorer: (WakfuBestBuildParams, BuildCombination) -> java.math.BigDecimal =
        { params, build -> WakfuBestBuildFinderAlgorithm.rescore(params, build) },
    /**
     * Where the request being edited is remembered between launches ([WorkspaceStore]), or null to remember nothing. Null by
     * default so a model built by a test never reads or writes the user's real workspace: the app passes its store (Main.kt).
     */
    private val workspaceStore: WorkspaceStore? = null,
    /** How long the request must stay unchanged before it is written ([WorkspaceStore]); typing a value writes once. */
    private val workspaceSaveDebounce: kotlin.time.Duration = 800.milliseconds,
    /** The game data a remembered request is checked against when it comes back. Injectable for tests. */
    private val workspaceCatalog: () -> WorkspaceCatalog = { WorkspaceCatalog.fromGameData() },
    /** The engine results version stamped onto saved builds ([ENGINE_RESULTS_VERSION]); injectable for tests. */
    private val engineResultsVersion: Int = ENGINE_RESULTS_VERSION,
    /**
     * How long the reveal of the main UI waits for the remembered request still being read. Past it, the UI shows the defaults
     * and the request is applied when the read lands (if the user has not edited meanwhile). Injectable for tests.
     */
    private val workspaceReadWait: kotlin.time.Duration = 2.seconds,
) {
    private val uiState = androidx.compose.runtime.mutableStateOf(UiState())

    var ui: UiState
        get() = uiState.value
        private set(value) {
            val previous = uiState.value
            uiState.value = value
            rememberWorkspaceLater(value)
            // The library and the compare view show each saved build with the numbers of the current rules: re-score the builds
            // when one of them opens, whenever the library changes, or when a search ends while it is on screen.
            val showsLibrary = value.screen == Screen.Library || value.screen == Screen.Compare
            val searchEnded = previous.phase == Phase.Searching && value.phase != Phase.Searching
            if (showsLibrary &&
                value.phase != Phase.Searching &&
                (previous.screen != value.screen || previous.savedBuilds !== value.savedBuilds || searchEnded)
            ) {
                rescoreLibrary(value.savedBuilds)
            } else if (!showsLibrary && previous.screen != value.screen) {
                // Nobody looks at the cards any more: stop (the cache keeps every build already re-scored).
                rescoreJob?.cancel()
            }
        }

    // --- Remembered workspace (see [WorkspaceStore]) ---

    /**
     * False until the remembered request has been put back ([restoreWorkspace]): writing before that would replace the file
     * with the defaults the app starts from.
     */
    private var workspaceRestored = false

    /** The request last handed to the store (or restored from it): an unchanged request is never written again. */
    private var rememberedRequest: me.chosante.common.history.RequestSnapshot? = null

    /**
     * The request exactly as [restoreWorkspace] put it back, until the game-data check of [restoreWorkspace] has run: that check
     * only cleans the request while it is still this one (a build loaded meanwhile is not "your last session").
     */
    private var restoredRequest: me.chosante.common.history.RequestSnapshot? = null

    /**
     * The latest request not yet known to be on disk — what [flushWorkspace] writes. Volatile and a plain value so the JVM
     * shutdown hook (Main.kt: Cmd+Q, Dock → Quit and logout skip the window's close request) can write it from its own thread
     * without reading Compose state.
     */
    @Volatile
    private var pendingWorkspace: WorkspaceSnapshot? = null

    /**
     * The request shown when the main UI was revealed while the remembered one was still being read ([restoreWorkspaceWhenRead]);
     * null otherwise. Until the read lands nothing is written by the debounce, but an edit away from this request is kept in
     * [pendingWorkspace], so a quit in the meantime still writes it (the user's edit wins over the late read anyway).
     */
    private var requestAtReveal: me.chosante.common.history.RequestSnapshot? = null

    private var workspaceSaveJob: Job? = null

    // --- Library re-scoring (see [rescoreLibrary]) ---

    /** What [rescoreLibrary] recomputes a saved build's numbers from; any change of it (or of the app's rules) is a miss. */
    private data class RescoreKey(
        val id: String,
        val request: me.chosante.common.history.RequestSnapshot,
        val result: me.chosante.common.history.ResultSnapshot,
        val dataVersion: String,
        val engineResultsVersion: Int,
    )

    /**
     * Saved builds already re-scored under the current rules, so reopening the library recomputes nothing. One slot per entry id,
     * holding the full key it was computed for: a changed entry replaces its slot, and a deleted one costs one stale slot at most.
     */
    private val rescoreCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<RescoreKey, me.chosante.common.history.ResultSnapshot>>()

    private var rescoreJob: Job? = null

    /**
     * `true` once the app is ready to show its main UI: OR-Tools' one-time cold start has been paid
     * (or we're in screenshot mode). Until then a [me.chosante.ui.shell.LoadingScreen] is shown
     * instead of the heavy main UI — mounting that UI *during* native-library loading is what made
     * the window appear to hang.
     */
    var isReady by androidx.compose.runtime.mutableStateOf(false)
        private set

    /**
     * Completed by the UI once the window is actually on screen. Gates the native warm-up: on its
     * very first launch macOS spends seconds validating the freshly extracted OR-Tools dylibs and
     * stalls the UI thread for the whole load (see `OrToolsNativeLoader`), so the load must never
     * start before the loading screen has had its first frame. Awaited with a timeout so headless
     * usage (tests) can never hang on it.
     */
    val windowShown: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()

    /** Estimated warm-up progress (0..1) for the loading screen. See [WarmupTiming]. */
    var warmupProgress by androidx.compose.runtime.mutableStateOf(0f)
        private set

    /** Estimated seconds left on the warm-up, or `null` once the estimate is exhausted/done. */
    var warmupEtaSeconds by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    private var job: Job? = null

    // The post-search optimality proof runs independently of [job] (it can take minutes after the search
    // already finished), so it has its own handle — cancelled when a new search starts, or when the user stops it
    // ([stopProof]) or switches the check off ([setVerifyOptimality]).
    private var proofJob: Job? = null

    // B8: cancelling [proofJob] only stops the coroutine, not the blocking certifier DP running inside it (which
    // can hold a core for minutes). This flag — set by [cancelProof], polled once per certifier DP stage — makes
    // the DP bail promptly. AtomicBoolean because the parallel exact tier polls it off pool threads. ONE FLAG PER
    // PROOF LAUNCH: each launch installs a fresh one and its provers capture that instance, so a quick follow-up
    // search can never un-cancel a superseded proof that hasn't polled its flag yet.
    private var proofCancelled =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Persisted tag registry (display casing). Tags here survive having no build, until deleted. */
    private var tagRegistry: List<String> = emptyList()

    /** Union of the registry and tags currently on [builds], de-duped case-insensitively, A–Z. */
    private fun computeKnownTags(builds: List<me.chosante.common.history.HistoryEntry>): List<String> =
        (tagRegistry + builds.flatMap { it.tags })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** Mirrors [me.chosante.ui.SCREENSHOT_PATH_PROPERTY] / `WAKFU_COMPOSE_SCREENSHOT` (see Main.kt). */
    private val isScreenshotMode =
        System.getProperty("wakfu.compose.screenshot") != null ||
            System.getenv("WAKFU_COMPOSE_SCREENSHOT") != null

    /** Screenshot-only: pin the 1st build item as required and exclude the 2nd, to capture the #125 badges. */
    private val screenshotForceFirst =
        System.getProperty("wakfu.compose.screenshot.forceFirst") != null ||
            System.getenv("WAKFU_COMPOSE_SCREENSHOT_FORCE_FIRST") != null

    /** Screenshot-only: start in precision mode so a capture can show that screen's target editor (#123). */
    private val screenshotPrecisionMode =
        System.getProperty("wakfu.compose.screenshot.precision") != null ||
            System.getenv("WAKFU_COMPOSE_SCREENSHOT_PRECISION") != null

    /** Screenshot-only: spread ascending 1..5 priorities across targets so a capture shows the priority bars at different levels (#123). */
    private val screenshotVaryPriority =
        System.getProperty("wakfu.compose.screenshot.varyPriority") != null ||
            System.getenv("WAKFU_COMPOSE_SCREENSHOT_VARY_PRIORITY") != null

    init {
        // Seed the persisted UI options (language + library view + the post-search optimality check) + tag registry
        // before any UI reads them.
        tagRegistry = libraryPreferences.loadTags()
        ui =
            ui.copy(
                lang = libraryPreferences.loadLang(),
                librarySort = libraryPreferences.loadSort(),
                libraryGroupByClass = libraryPreferences.loadGroupByClass(),
                verifyOptimality = libraryPreferences.loadVerifyOptimality()
            )

        // Read the remembered request while the engine warms up (a small local file, long read by the time warm-up ends); it
        // is put back when the loading screen gives way to the main UI, never earlier — and never in screenshot mode, whose
        // captures must show the default request.
        val rememberedWorkspace =
            workspaceStore
                ?.takeUnless { isScreenshotMode }
                ?.let { store -> scope.async(ioDispatcher) { store.load() } }

        // Load the saved-build library off the UI thread. A read failure must never block startup —
        // it just yields an empty library that fills in as the user saves builds.
        scope.launch(ioDispatcher) {
            val all = runCatching { historyRepository.loadAll() }.getOrDefault(emptyList())
            withContext(mainDispatcher) { ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all)) }
        }

        if (isScreenshotMode) {
            // Screenshots want the real UI immediately, with no warm-up gating. Kick off a search
            // with the default request so the captured frame shows a populated build (paperdoll,
            // stats, skill tree) instead of an empty shell. The first solve pays OR-Tools' cold
            // start inline; ScreenshotCapture waits for the build before grabbing pixels.
            startIconPreload()
            isReady = true
            if (screenshotPrecisionMode) setMode(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)
            if (screenshotVaryPriority) {
                // Vary the constraint priority bars across levels so a capture shows the gradient.
                ui = ui.copy(targets = ui.targets.mapIndexed { index, target -> target.copy(weight = (index % 5) + 1) })
            }
            screenshotExcludedRarities()?.let { ui = ui.copy(excludedRarities = it) }
            search()
        } else {
            // Pay OR-Tools' one-time cold start behind the loading screen, so the first real search
            // starts warm and the heavy main UI only mounts once the native library is loaded (no
            // CPU/IO contention with Compose's first render). The short delay lets the loader paint.
            scope.launch(backgroundDispatcher) {
                val estimateMs = WarmupTiming.estimatedDurationMs()
                val start = System.currentTimeMillis()
                // The native load reports no real progress, so animate an estimated %/ETA from the
                // elapsed time vs. the last measured duration. The bar caps below 100% until warm-up
                // actually finishes, then snaps full — never claims "done" early.
                val ticker =
                    launch {
                        while (isActive) {
                            val elapsed = System.currentTimeMillis() - start
                            val remainingMs = estimateMs - elapsed
                            withContext(mainDispatcher) {
                                warmupProgress = (elapsed.toFloat() / estimateMs).coerceIn(0f, 0.92f)
                                warmupEtaSeconds = if (remainingMs > 0) ceil(remainingMs / 1000.0).toInt() else null
                            }
                            delay(80.milliseconds)
                        }
                    }
                // Wait for the loading screen's first frame before touching the native engine: the
                // load can stall the UI thread (macOS first-launch code-sign validation), and a
                // stall behind a painted window is invisible while one before it looks like the app
                // failed to start. Generous bound: on a cold first packaged launch the window itself
                // can take seconds to appear (Skiko's freshly extracted dylib pays the same macOS
                // validation), and guessing low here would start the native load before the first
                // frame — the exact failure this gate prevents. Nothing user-visible ever waits on
                // the timeout (tests cancel their scope; the GUI completes the gate within ~200ms),
                // it only exists so a headless run can never hang.
                kotlinx.coroutines.withTimeoutOrNull(15.seconds) { windowShown.await() }
                delay(100.milliseconds)
                try {
                    WakfuBuildSolver.warmUp()
                    WarmupTiming.record(System.currentTimeMillis() - start)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // Swallow (Throwable: native loading raises Errors, not Exceptions): a warm-up
                    // failure should degrade to a cold first search, never crash the app.
                    throwable.printStackTrace()
                } finally {
                    // Always reveal the UI: a warm-up failure must never leave the app stuck on the
                    // loading screen.
                    ticker.cancel()
                    // Bounded: the main UI must never wait on a slow disk. A read still running by then is applied when it lands.
                    val landed = rememberedWorkspace?.let { read -> runCatching { withTimeoutOrNull(workspaceReadWait) { read.await() } } }
                    withContext(mainDispatcher) {
                        warmupProgress = 1f
                        warmupEtaSeconds = null
                        try {
                            // In the same frame as the reveal, so the main UI never shows the defaults first.
                            if (rememberedWorkspace != null) {
                                // The wait may have given up just before the read landed: take what landed rather than drop it.
                                val read = landed?.getOrNull() ?: rememberedWorkspace.completedOrNull()
                                if (rememberedWorkspace.isCompleted) restoreWorkspaceSafely(read) else restoreWorkspaceWhenRead(rememberedWorkspace)
                            }
                        } finally {
                            // Nothing about the remembered workspace may keep the app on the loading screen.
                            isReady = true
                        }
                    }
                }
                // Only start decoding item icons once the engine is warm. During warm-up every core
                // counts: running the preloader (thousands of PNG decodes + the equipments JSON parse
                // it triggers) concurrently with the native cold start starved the AWT event thread,
                // which on macOS froze any window operation until warm-up finished. Icons are not
                // needed before the first build is shown, so starting late costs nothing visible.
                startIconPreload()
            }
        }
    }

    /**
     * Puts the remembered request ([snapshot], null when there is none to use) back into the workspace, then starts remembering
     * every later change. The game data may have changed since it was written: once the catalogs are loaded (off the UI thread),
     * the items, sublimations, passives and runes it no longer knows are dropped, with a toast saying how many.
     */
    private fun restoreWorkspace(snapshot: WorkspaceSnapshot?) {
        if (snapshot != null) ui = ui.withRememberedRequest(snapshot.request)
        rememberedRequest = ui.toRequestSnapshot(keepBossInAnyMode = true)
        workspaceRestored = true
        if (snapshot == null) return
        restoredRequest = rememberedRequest
        scope.launch(backgroundDispatcher) {
            val catalog = runCatching { workspaceCatalog() }.getOrNull() ?: return@launch
            withContext(mainDispatcher) {
                // Only while the request is still the restored one: once the user edited it or loaded a build, what is on screen
                // is no longer "your last session" (and nothing the user picked since can be unknown anyway).
                val untouched = restoredRequest != null && ui.toRequestSnapshot(keepBossInAnyMode = true) == restoredRequest
                restoredRequest = null
                if (!untouched) return@withContext
                val (cleaned, dropped) = ui.withoutUnknownEntries(catalog)
                if (cleaned == ui) return@withContext
                ui = if (dropped > 0) cleaned.copy(toast = Tr.TOAST_WORKSPACE_ENTRIES_REMOVED.value(ui.lang).format(dropped)) else cleaned
            }
        }
    }

    /** The value of this read if it has landed (null if it has not, or failed). Never suspends. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.Deferred<WorkspaceSnapshot?>.completedOrNull(): WorkspaceSnapshot? = if (isCompleted) runCatching { getCompleted() }.getOrNull() else null

    /** [restoreWorkspace], falling back to the defaults (and remembering from them on) if putting [snapshot] back throws. */
    private fun restoreWorkspaceSafely(snapshot: WorkspaceSnapshot?) {
        runCatching { restoreWorkspace(snapshot) }.onFailure { if (!workspaceRestored) runCatching { restoreWorkspace(null) } }
    }

    /**
     * The remembered request was still being read when the main UI was revealed (a slow disk): the defaults show meanwhile, and
     * NOTHING is written until the read lands — otherwise the user's first edit would overwrite the request still being read. When
     * it lands, it is put back if the request is still the untouched defaults; if the user already edited, their edit wins and
     * is remembered from then on.
     */
    private fun restoreWorkspaceWhenRead(read: kotlinx.coroutines.Deferred<WorkspaceSnapshot?>) {
        val atReveal = ui.toRequestSnapshot(keepBossInAnyMode = true)
        requestAtReveal = atReveal
        scope.launch(mainDispatcher) {
            val snapshot = runCatching { read.await() }.getOrNull()
            requestAtReveal = null
            val untouched = ui.toRequestSnapshot(keepBossInAnyMode = true) == atReveal
            restoreWorkspaceSafely(snapshot?.takeIf { untouched })
            // The user's edits made while the read ran were not written (saving was not armed yet): write them now.
            if (!untouched) rememberWorkspaceLater(ui, force = true)
        }
    }

    /**
     * Writes [state]'s request to the [workspaceStore] once it has stayed unchanged for [workspaceSaveDebounce]. Called on every
     * state change: anything that is not the request (a search's progress, a result, a modal) changes nothing here.
     */
    private fun rememberWorkspaceLater(
        state: UiState,
        force: Boolean = false,
    ) {
        val store = workspaceStore ?: return
        if (!workspaceRestored) {
            // A slow read still running: no write yet, but keep an edit for a flush (a quit before the read lands).
            val atReveal = requestAtReveal ?: return
            val request = state.toRequestSnapshot(keepBossInAnyMode = true)
            pendingWorkspace = if (request == atReveal) null else WorkspaceSnapshot(dataVersion = dataVersion, request = request)
            return
        }
        val request = state.toRequestSnapshot(keepBossInAnyMode = true)
        if (request == rememberedRequest && !force) return
        rememberedRequest = request
        workspaceSaveJob?.cancel()
        val snapshot = WorkspaceSnapshot(dataVersion = dataVersion, request = request)
        pendingWorkspace = snapshot
        workspaceSaveJob =
            scope.launch(ioDispatcher) {
                delay(workspaceSaveDebounce)
                writeIfPending(store, snapshot)
            }
    }

    private val workspaceWriteLock = Any()

    /**
     * Writes [snapshot] if it is still the latest unsaved request. Under one lock with every other write, so a debounced write
     * that lost a race with a flush can never land after it and put an older request back on disk.
     */
    private fun writeIfPending(
        store: WorkspaceStore,
        snapshot: WorkspaceSnapshot,
    ) {
        synchronized(workspaceWriteLock) {
            if (pendingWorkspace !== snapshot) return
            store.saveBlocking(snapshot)
            // Only if nothing newer arrived during the write: an edit made meanwhile (set without this lock, from the UI thread)
            // must stay pending for its own debounced write or a flush.
            if (pendingWorkspace === snapshot) pendingWorkspace = null
        }
    }

    /**
     * Writes the latest unsaved request right away, skipping the debounce — for the window's close request and the JVM shutdown
     * hook (Main.kt), after which a pending write would never run. Reads only [pendingWorkspace], never Compose state, so it is
     * safe from any thread. A small atomic file write on the calling thread; nothing to do when everything is already written.
     */
    fun flushWorkspace() {
        val store = workspaceStore ?: return
        val snapshot = pendingWorkspace ?: return
        writeIfPending(store, snapshot)
    }

    /**
     * Decodes item icons into the cache off the UI thread so they're ready (and decoded once) by the
     * time a build is shown. Purely background work: it never gates startup — items simply appear as
     * they decode, so we don't make the user wait on ~thousands of PNGs. First touch of
     * [WakfuBestBuildFinderAlgorithm.equipments] also pays its (lazy) JSON parse, here on a
     * background thread — never on the UI thread.
     */
    private fun startIconPreload() {
        scope.launch(backgroundDispatcher) {
            val paths = warmUpPaths(WakfuBestBuildFinderAlgorithm.equipments) + BreedAssets.warmUpPaths()
            IconPreloader.warmUp(scope, paths) { _, _ -> }
        }
    }

    /**
     * Switches the search mode. Each mode keeps its own work ([UiState.modeWorkspaces]): its target rows AND the result found
     * under it. Leaving a mode parks both; coming back restores them, so a visit to another mode destroys nothing. A mode
     * entered for the first time starts from [firstVisitRows]. Choosing the mode that is already active changes nothing.
     *
     * The shown build cannot simply stay on screen under the new mode: what it displays is read by the mode that found it —
     * the headline (mastery score / % match / expected damage), the achieved-stat grid (resolved with that mode's
     * random-element assignment) and the max-damage rotation — and Save / Export snapshot the live mode and rows together
     * with it, which would pair it with another mode's request. Parking it with its mode keeps it recoverable instead.
     */
    fun setMode(mode: ScoreComputationMode) {
        if (mode != ui.mode) enterMode(mode)
    }

    private fun enterMode(mode: ScoreComputationMode) {
        // A running search belongs to the mode being left: stop it first, so its best-so-far is what gets parked (otherwise it
        // would keep streaming builds into a screen that now reads under another mode's rules).
        if (ui.phase == Phase.Searching) cancel()
        // The parked build's proof/refinement has nothing left to display, and would keep CP-SAT busy for nobody.
        cancelProof()
        val workspaces = ui.modeWorkspaces + (ui.mode to ModeWorkspace(ui.targets, ui.shownResult().atRest()))
        val arriving = workspaces[mode]
        ui =
            ui
                .copy(
                    mode = mode,
                    targets = arriving?.let { rowsForMode(mode, it.targets) } ?: firstVisitRows(mode, ui.targets),
                    modeWorkspaces = workspaces - mode
                ).withResult(arriving?.result ?: ShownResult())
    }

    /**
     * The rows of a mode visited for the first time, derived from the [current] rows of the mode being left — the carry-over
     * the mode switch always had between the two target-driven modes, so the first switch loses nothing the user typed.
     * Max-damage is the exception: it maximizes the rotation's real damage directly, so the seeded AP/MP/range/HP/crit rows
     * would only act as hard power-6 constraints that can exclude higher-damage builds (e.g. pinning AP=11 stops the solver
     * finding the best AP breakpoint). It starts CONSTRAINT-FREE; the user can still add an explicit target row (an AP floor,
     * a min HP…) if they want a more playable build.
     */
    private fun firstVisitRows(
        mode: ScoreComputationMode,
        current: List<TargetRow>,
    ): List<TargetRow> =
        when (mode) {
            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> emptyList()
            else -> rowsForMode(mode, current)
        }

    /**
     * [rows] as [mode] reads them. A maximized-mastery row of most-masteries is a bare "maximize this" marker whose value is
     * always 1 (the panel offers no field for it), so a number typed under another mode is normalized away; the other modes
     * keep every value.
     */
    private fun rowsForMode(
        mode: ScoreComputationMode,
        rows: List<TargetRow>,
    ): List<TargetRow> =
        when (mode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                rows.map { row -> if (row.characteristic.isMaximizableMastery()) row.copy(value = "1") else row }
            else -> rows
        }

    fun setScenario(scenario: DamageScenario) {
        // Turning the survivability floor on (via the toggle or the Tank preset) without a value would be a
        // silent no-op — default the floor from the level so it actually nudges the build (and the min-EHP
        // field shows a tunable number rather than 0).
        val withFloor =
            if (scenario.survivabilityFloor && scenario.minEffectiveHp <= 0) {
                scenario.copy(minEffectiveHp = DamageScenario.defaultMinEffectiveHp(ui.level))
            } else {
                scenario
            }
        ui = ui.copy(scenario = withFloor)
    }

    /**
     * Target [monster] in max-damage mode: switch to max-damage (the boss fills the per-element
     * resistances the objective optimizes over) and close the picker — mirroring the CLI's `--boss`,
     * which also forces max-damage. Coming from another mode parks that mode's work like the mode tab does (max-damage then
     * starts from its own rows, constraint-free the first time); picking a boss while already in max-damage keeps the rows the
     * user set there. The shown result was computed against the previous target, so it is cleared — and a search still running
     * for it is stopped.
     */
    fun pickBoss(monster: Monster) {
        if (ui.mode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) enterMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
        job?.cancel()
        job = null
        cancelProof() // the cleared build's proof/refinement has nothing left to display
        ui = ui.copy(selectedBoss = monster, modal = null).withResult(ShownResult())
    }

    /** Drop the boss target; the next search falls back to the manual damage [UiState.scenario]. */
    fun clearBoss() {
        ui = ui.copy(selectedBoss = null, bossElement = null)
    }

    /** Force the damage element vs the boss, or null to let the objective auto-pick the best one. */
    fun setBossElement(element: SpellElement?) {
        ui = ui.copy(bossElement = element)
    }

    /** Dungeon HP multiplier (integer) for the turns-to-kill estimate; display only, never the build. */
    fun setBossDifficulty(value: String) {
        ui = ui.copy(bossDifficulty = value.onlyDigits().take(3))
    }

    fun setLang(lang: me.chosante.ui.i18n.Lang) {
        ui = ui.copy(lang = lang)
        libraryPreferences.saveLang(lang)
    }

    fun setClass(clazz: me.chosante.common.CharacterClass) {
        // Passives are class-specific and slot-capped — drop any that don't exist for the new class so a
        // stale chip can't linger, eat a slot, and be silently dropped at solve time.
        ui = ui.copy(clazz = clazz, forcedPassives = reconcilePassives(ui.forcedPassives, clazz, ui.level))
    }

    fun setLevel(level: String) {
        val parsed =
            level
                .onlyDigits()
                .take(3)
                .toIntOrNull()
                ?.coerceIn(1, 245) ?: return
        // Lowering the level shrinks the passive-slot budget — trim the loadout so the shown chips match
        // exactly what the engine folds (resolvedPassives caps with the same slot count).
        ui = ui.copy(level = parsed, forcedPassives = reconcilePassives(ui.forcedPassives, ui.clazz, parsed))
        reconcileForcedItemsForCurrentRequest()
    }

    /** Keep only passives that belong to [clazz], capped to [level]'s passive slots (preserving order). */
    private fun reconcilePassives(
        forced: List<String>,
        clazz: me.chosante.common.CharacterClass,
        level: Int,
    ): List<String> =
        forced
            .filter { PassiveCatalog.findByName(clazz, it) != null }
            .take(PassiveCatalog.slotsForLevel(level))

    fun setMinLevel(minLevel: String) {
        val parsed =
            minLevel
                .onlyDigits()
                .take(3)
                .toIntOrNull()
                ?.coerceIn(0, 245) ?: return
        ui = ui.copy(minLevel = parsed)
        reconcileForcedItemsForCurrentRequest()
    }

    fun updateTargetValue(
        id: String,
        value: String,
    ) {
        ui = ui.copy(targets = ui.targets.map { if (it.id == id) it.copy(value = value.onlyDigits()) else it })
    }

    /** Sets a constraint's priority (#123), clamped to 1..5 — the segmented bar. See [TargetRow.weight]. */
    fun updateTargetWeight(
        id: String,
        weight: Int,
    ) {
        ui = ui.copy(targets = ui.targets.map { if (it.id == id) it.copy(weight = weight.coerceIn(1, 5)) else it })
    }

    fun removeTarget(id: String) {
        ui = ui.copy(targets = ui.targets.filterNot { it.id == id })
    }

    fun addTarget(characteristic: Characteristic) {
        if (ui.targets.any { it.characteristic == characteristic }) {
            return
        }
        statDefFor(characteristic)?.let { def ->
            ui =
                ui.copy(
                    targets = ui.targets + def.toRow(if (characteristic.isMaximizableMastery()) "1" else "0")
                )
        }
    }

    fun toggleMaximizedMastery(characteristic: Characteristic) {
        if (!characteristic.isMaximizableMastery()) {
            return
        }
        val alreadySelected = ui.targets.any { it.characteristic == characteristic }
        if (alreadySelected) {
            ui = ui.copy(targets = ui.targets.filterNot { it.characteristic == characteristic })
            return
        }
        val row = statDefFor(characteristic)?.toRow("1") ?: return
        // "All elements" and the specific elements are mutually exclusive: they express two distinct
        // intents (a build balanced over the four vs. one focused on those elements), and combining
        // them is what made the engine optimise the wrong thing. Selecting one clears the other;
        // non-elemental masteries (distance/crit/…) are never cleared.
        val conflicting =
            when (characteristic) {
                Characteristic.MASTERY_ELEMENTARY -> ELEMENTAL_MASTERY_ELEMENTS
                in ELEMENTAL_MASTERY_ELEMENTS -> setOf(Characteristic.MASTERY_ELEMENTARY)
                else -> emptySet()
            }
        ui = ui.copy(targets = ui.targets.filterNot { it.characteristic in conflicting } + row)
    }

    /** Screenshot-only: seed excluded rarities from WAKFU_COMPOSE_SCREENSHOT_EXCLUDE_RARITIES (comma list). */
    private fun screenshotExcludedRarities(): Set<Rarity>? {
        val raw =
            System.getProperty("wakfu.compose.screenshot.excludeRarities")
                ?: System.getenv("WAKFU_COMPOSE_SCREENSHOT_EXCLUDE_RARITIES") ?: return null
        return raw
            .split(",")
            .mapNotNull { token -> runCatching { Rarity.valueOf(token.trim().uppercase()) }.getOrNull() }
            .toSet()
            .ifEmpty { null }
    }

    /**
     * Toggle whether [rarity] is allowed in the search (#124). Excluding the last still-allowed rarity
     * is refused — an all-excluded set would leave the solver no items at all.
     */
    fun toggleRarity(rarity: Rarity) {
        val excluded = ui.excludedRarities
        val next =
            if (rarity in excluded) {
                ui.copy(excludedRarities = excluded - rarity)
            } else if (excluded.size < Rarity.entries.size - 1) {
                ui.copy(excludedRarities = excluded + rarity)
            } else {
                return
            }
        ui = next
        reconcileForcedItemsForCurrentRequest()
    }

    private fun reconcileForcedItemsForCurrentRequest() {
        val snapshot = ui
        if (snapshot.forcedItems.isEmpty()) return
        scope.launch(backgroundDispatcher) {
            val byFrenchName = WakfuBestBuildFinderAlgorithm.equipments.groupBy { it.name.fr }
            val kept =
                snapshot.forcedItems.filter { chip ->
                    val matches = byFrenchName[chip.matchName].orEmpty()
                    matches.isEmpty() || matches.any { it.isEquippableIn(snapshot) }
                }
            val removed = snapshot.forcedItems.size - kept.size
            if (removed <= 0) return@launch
            withContext(mainDispatcher) {
                if (ui.level == snapshot.level &&
                    ui.minLevel == snapshot.minLevel &&
                    ui.maxRarity == snapshot.maxRarity &&
                    ui.excludedRarities == snapshot.excludedRarities &&
                    ui.forcedItems == snapshot.forcedItems
                ) {
                    ui =
                        ui.copy(
                            forcedItems = kept,
                            toast = Tr.TOAST_FORCED_ITEMS_REMOVED.value(ui.lang).format(removed)
                        )
                }
            }
        }
    }

    private fun Equipment.isEquippableIn(state: UiState): Boolean {
        val levelOk = itemType == ItemType.PETS || itemType == ItemType.MOUNTS || level in state.minLevel..state.level
        val rarityOk = rarity <= state.maxRarity && rarity !in state.excludedRarities
        return levelOk && rarityOk
    }

    fun setDuration(duration: String) {
        ui = ui.copy(duration = duration.onlyDigits().take(3))
    }

    fun setStopAtMatch(stopAtMatch: Boolean) {
        ui = ui.copy(stopAtMatch = stopAtMatch)
    }

    /**
     * The "Check optimality after the search" switch (persisted; default ON). It decides, when a max-damage or
     * most-masteries search ENDS, whether the engine keeps working to prove how close the build is to the best possible
     * one — the "Verifying optimality…" wait, the E8 construct that can swap in the proven-optimal build, the silent
     * refinement of the badge. The value read is the one in force when the search ends, so flipping it mid-search counts.
     *
     * **OFF means no proof work after the search ends** — nothing is launched ([launchOptimalityProof] /
     * [launchMostMasteriesQualityProof] are not called), and the engine's own leftovers are cancelled
     * ([skipBackgroundProof]). What costs nothing still shows: a result the search itself proved ([UiState.optimal] —
     * CP-SAT's proof, or the certificate that landed during the search and stopped it early) keeps its "proven optimal"
     * headline, and a most-masteries quality bound the search's tail already finished is read from the engine's memo
     * without computing anything (a "proven within X %" badge). A max-damage certificate is NOT peeked the same way: one
     * request shape (required targets the build misses) cannot be answered from its memo without computing, and telling
     * the shapes apart here would duplicate the engine's gating — so max-damage shows the normal "not proven" hint.
     *
     * Switching OFF while a proof runs stops it, exactly like [stopProof]. Switching ON launches nothing for a build
     * already on screen: it takes effect with the next search.
     */
    fun setVerifyOptimality(enabled: Boolean) {
        ui = ui.copy(verifyOptimality = enabled)
        libraryPreferences.saveVerifyOptimality(enabled)
        if (!enabled) stopProof()
    }

    fun removeForcedItem(item: ItemChip) {
        ui = ui.copy(forcedItems = ui.forcedItems - item)
    }

    fun removeExcludedItem(item: ItemChip) {
        ui = ui.copy(excludedItems = ui.excludedItems - item)
    }

    /** The modeled runes ([me.chosante.common.RuneType]) the user can pin onto an item, sorted for the picker. */
    val runeOptions: List<me.chosante.common.RuneType> by lazy {
        WakfuBestBuildFinderAlgorithm.runes.sortedBy { it.name.fr.lowercase() }
    }

    fun setUseSublimations(enabled: Boolean) {
        ui = ui.copy(useSublimations = enabled)
    }

    fun setMaxSublimationTier(tier: Int?) {
        ui = ui.copy(maxSublimationTier = tier)
    }

    fun addForcedSublimation(name: String) {
        if (name.isNotBlank() && name !in ui.forcedSublimations) ui = ui.copy(forcedSublimations = ui.forcedSublimations + name)
    }

    fun removeForcedSublimation(name: String) {
        ui = ui.copy(forcedSublimations = ui.forcedSublimations - name)
    }

    fun addExcludedSublimation(name: String) {
        if (name.isNotBlank() && name !in ui.excludedSublimations) ui = ui.copy(excludedSublimations = ui.excludedSublimations + name)
    }

    fun removeExcludedSublimation(name: String) {
        ui = ui.copy(excludedSublimations = ui.excludedSublimations - name)
    }

    /**
     * Apply the sublimation chosen from the [Modal.SublimationPicker] modal — forced by default, EXCLUDED
     * when the picker was opened in exclude mode — then close it. The engine matches sublimations by their
     * **French** name, so that's the key we store (regardless of UI lang).
     */
    fun pickSublimation(sub: me.chosante.common.Sublimation) {
        val exclude = (ui.modal as? Modal.SublimationPicker)?.exclude == true
        if (exclude) addExcludedSublimation(sub.name.fr) else addForcedSublimation(sub.name.fr)
    }

    fun removeForcedPassive(name: String) {
        ui = ui.copy(forcedPassives = ui.forcedPassives - name)
    }

    /**
     * Add the passive chosen from the [Modal.PassivePicker] to the loadout (matched by **French** name by
     * the engine), capped to the level's passive slots, then close the modal. A duplicate or over-cap pick
     * is ignored.
     */
    fun pickPassive(passive: me.chosante.common.Passive) {
        // forcedPassives stores the canonical FRENCH name (the engine matches passives by French).
        val name = passive.name?.fr ?: return
        val slots =
            me.chosante.autobuilder.domain.PassiveCatalog
                .slotsForLevel(ui.level)
        if (name !in ui.forcedPassives && ui.forcedPassives.size < slots) {
            ui = ui.copy(forcedPassives = ui.forcedPassives + name)
        }
    }

    /** Open the per-item rune picker for [equipment] (only meaningful when the item has sockets). */
    fun openItemRunePicker(equipment: me.chosante.common.Equipment) {
        openModal(Modal.ItemRunePicker(equipment.name.fr))
    }

    /** Rune ids currently pinned onto the item with this French name. */
    fun pinnedRunes(itemName: String): List<Int> = ui.forcedRunesByItem[itemName].orEmpty()

    /** Replace the runes pinned onto [itemName] (an empty list clears the entry), then close the picker. */
    fun setForcedRunesForItem(
        itemName: String,
        runeIds: List<Int>,
    ) {
        val updated =
            if (runeIds.isEmpty()) {
                ui.forcedRunesByItem - itemName
            } else {
                ui.forcedRunesByItem + (itemName to runeIds)
            }
        ui = ui.copy(forcedRunesByItem = updated, modal = null)
    }

    /** Copy the runes currently displayed on [equipment] into the per-item forced-runes request. */
    fun lockCurrentRunes(equipment: me.chosante.common.Equipment) {
        val runeIds =
            ui.build
                ?.runes
                ?.get(equipment)
                .orEmpty()
                .map { it.id }
        if (runeIds.isEmpty()) return
        ui =
            ui.copy(
                forcedRunesByItem = ui.forcedRunesByItem + (equipment.name.fr to runeIds),
                toast = Tr.TOAST_RUNES_LOCKED.value(ui.lang)
            )
    }

    /**
     * Force this exact item into the next searched build. Driven by the center paperdoll's per-slot
     * action; mirrors [pickItem]'s dedup but is independent of the picker modal and, like the modal,
     * does **not** re-run the search.
     */
    fun forceItem(equipment: me.chosante.common.Equipment) {
        pinForced(equipment.toChip())
    }

    /** Exclude this exact item from the next search. Paperdoll counterpart to [forceItem]. */
    fun excludeItem(equipment: me.chosante.common.Equipment) {
        pinExcluded(equipment.toChip())
    }

    /**
     * Pin [chip] as required. Forcing and excluding are contradictory constraints, so this also drops
     * the item from the excluded list ([pinExcluded] is the mirror): the same item can never sit in
     * both lists — otherwise the engine's exclude filter wins and silently ignores the force, leaving
     * the item invisible yet still listed as forced. Re-pinning an already-forced item is a no-op.
     */
    private fun pinForced(chip: ItemChip) {
        ui =
            ui.copy(
                forcedItems = if (ui.forcedItems.any { it.matchName == chip.matchName }) ui.forcedItems else ui.forcedItems + chip,
                excludedItems = ui.excludedItems.filterNot { it.matchName == chip.matchName }
            )
    }

    /** Pin [chip] as excluded, dropping it from the forced list. Mirror of [pinForced]. */
    private fun pinExcluded(chip: ItemChip) {
        ui =
            ui.copy(
                excludedItems = if (ui.excludedItems.any { it.matchName == chip.matchName }) ui.excludedItems else ui.excludedItems + chip,
                forcedItems = ui.forcedItems.filterNot { it.matchName == chip.matchName }
            )
    }

    fun openModal(modal: Modal) {
        ui = ui.copy(modal = modal)
        if (modal is Modal.ItemPicker) {
            ensureCatalogLoaded()
        }
    }

    fun closeModal() {
        ui = ui.copy(modal = null)
    }

    fun pickItem(equipment: me.chosante.common.Equipment) {
        val chip = equipment.toChip()
        when ((ui.modal as? Modal.ItemPicker)?.mode) {
            PickerMode.Forced -> pinForced(chip)
            PickerMode.Excluded -> pinExcluded(chip)
            null -> {}
        }
    }

    /**
     * Full equipment list from the embedded Wakfu data. `null` while the (heavy) JSON resource is
     * still being parsed off the UI thread, so the picker can show a loading state instead of
     * freezing on first open.
     */
    var equipmentCatalog by androidx.compose.runtime.mutableStateOf<List<me.chosante.common.Equipment>?>(null)
        private set

    private var catalogJob: Job? = null

    private fun ensureCatalogLoaded() {
        if (equipmentCatalog != null || catalogJob != null) {
            return
        }
        catalogJob =
            scope.launch(backgroundDispatcher) {
                val loaded =
                    WakfuBestBuildFinderAlgorithm.equipments
                        .distinctBy { it.equipmentId }
                        .sortedWith(compareByDescending<me.chosante.common.Equipment> { it.level }.thenBy { it.name.fr })
                withContext(mainDispatcher) {
                    equipmentCatalog = loaded
                }
            }
    }

    fun search() {
        // A search wants every core: the library re-score waits until it ends, if either library view is still open.
        rescoreJob?.cancel()
        val snapshot = ui
        val params = snapshot.toSearchParams()
        val character = params.character
        val damageScenario = params.damageScenario

        // Validate the whole request up front and surface ALL problems together in a pop-up
        // (UiState.requestErrors) instead of throwing on the first and burying it in the results-panel banner.
        val requestProblems = WakfuBestBuildFinderAlgorithm.validateRequest(params)
        if (requestProblems.isNotEmpty()) {
            // A rejected request leaves the shown build — and its running proof/refinement — untouched:
            // cancelling before this check stranded the badge spinner forever on the old build.
            ui = snapshot.copy(requestErrors = requestProblems)
            return
        }
        job?.cancel()
        cancelProof()

        ui =
            snapshot.copy(
                phase = Phase.Searching,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                // Snapshotted HERE, from the request the engine is about to receive (not from the rows as they will be once
                // the search ends): this result says "no proof is possible" about THAT request, whatever is edited meanwhile.
                prefilteredRequest = params.targetStats.needsItemPrefilter,
                proofState = ProofState.Idle,
                searchStopped = false,
                // The new build is found with this app's own game data and engine, whatever the one it replaces was computed with.
                staleDataVersion = null,
                staleEngine = null,
                build = null,
                achieved = emptyMap(),
                spellRotation = null,
                scenarioDamages = emptyList(),
                lastLandedEquipmentId = null,
                zenith = ZenithState.Idle,
                zenithUrl = null,
                toast = null,
                error = null,
                requestErrors = emptyList()
            )
        job =
            scope.launch(backgroundDispatcher) {
                // The CP-SAT solver only reports progress when it finds a *better* solution, which can
                // be many seconds apart — or stop entirely once the first good build is found — so the
                // bar would sit frozen and the app looks dead mid-search. The budget is wall-clock, so
                // we drive the bar smoothly from elapsed time here; the solver callbacks below keep
                // refreshing the actual build/mastery. Child of `job`, so it dies with the search.
                val searchStartMs = clock()
                val searchDurationMs = params.searchDuration.inWholeMilliseconds.coerceAtLeast(1)
                val progressTicker =
                    launch(mainDispatcher) {
                        while (isActive) {
                            if (ui.phase == Phase.Searching) {
                                val pct = ((clock() - searchStartMs).toDouble() / searchDurationMs * 100).toInt().coerceIn(0, 99)
                                if (pct > ui.progress) ui = ui.copy(progress = pct)
                            }
                            delay(120)
                        }
                    }
                try {
                    var hasResult = false
                    // The per-position damage breakdown is a result-level detail that runs 3-4 extra rotations,
                    // so it's computed ONCE for the final build after the stream settles (below) — not on every
                    // streamed improvement. These capture the last build/achieved/headline-rotation for that.
                    var finalBuild: BuildCombination? = null
                    var finalAchieved: Map<Characteristic, Int> = emptyMap()
                    var finalRotation: SpellRotation? = null
                    // The final SolverResult (build + isOptimal + CP-SAT objective) drives the post-search
                    // optimality certificate below.
                    var finalResult: SolverResult<BuildCombination>? = null
                    buildFinder(params)
                        .conflate()
                        .collect { result ->
                            hasResult = true
                            val achieved = achievedStats(result.individual, params)
                            // Best spells to cast for this build's AP — only in max-damage mode, computed
                            // here off the UI thread (like `achieved`) so the panel just reads it. Uses the
                            // boss-overlaid `damageScenario` (not the raw `snapshot.scenario`) and picks the
                            // build's best playable element, so the shown rotation is exactly the turn that was scored.
                            val spellRotation =
                                if (snapshot.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                                    SpellRotationOptimizer.bestSequencedRotation(
                                        result.individual,
                                        character,
                                        character.clazz,
                                        damageScenario
                                    )
                                } else {
                                    null
                                }
                            finalBuild = result.individual
                            finalAchieved = achieved
                            finalRotation = spellRotation
                            finalResult = result
                            withContext(mainDispatcher) {
                                val landedEquipmentId = newlyLandedEquipmentId(ui.build, result.individual)
                                ui =
                                    ui.copy(
                                        // `progress` is driven smoothly by progressTicker (time-based);
                                        // the solver only emits on improvements, so don't set it here.
                                        match = result.matchPercentage,
                                        optimal = result.isOptimal,
                                        maxDamageStructural = result.maxDamageHeuristicPhases,
                                        build = result.individual,
                                        achieved = achieved,
                                        spellRotation = spellRotation,
                                        lastLandedEquipmentId = landedEquipmentId ?: ui.lastLandedEquipmentId
                                    )
                                if (landedEquipmentId != null) {
                                    clearLandedMarkerLater(landedEquipmentId)
                                }
                                if (screenshotForceFirst && ui.forcedItems.isEmpty() && ui.excludedItems.isEmpty()) {
                                    result.individual.equipments
                                        .getOrNull(0)
                                        ?.let { forceItem(it) }
                                    result.individual.equipments
                                        .getOrNull(1)
                                        ?.let { excludeItem(it) }
                                }
                            }
                        }
                    // Compute the per-position breakdown ONCE for the final build, still off the UI thread
                    // (we're on backgroundDispatcher here), reusing the final headline rotation for the
                    // configured combo so only the OTHER positions pay a rotation.
                    val finalBuildSnapshot = finalBuild
                    val scenarioDamages =
                        if (snapshot.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE && finalBuildSnapshot != null) {
                            SpellRotationOptimizer.scenarioBreakdown(
                                finalBuildSnapshot,
                                character,
                                character.clazz,
                                damageScenario,
                                includeBerserk = (finalAchieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0,
                                configuredRotationTotal = finalRotation?.totalExpectedDamage
                            )
                        } else {
                            emptyList()
                        }
                    val completedResult = finalResult
                    withContext(mainDispatcher) {
                        if (ui.phase == Phase.Searching && hasResult) {
                            // Snap the time-based bar to a clean 100% on completion (the solver may
                            // have proven the optimum well before the wall-clock budget ran out).
                            ui = ui.copy(phase = Phase.Done, progress = 100, scenarioDamages = scenarioDamages, lastLandedEquipmentId = null)
                            // Whether the engine keeps working after the search is the user's call ("Check optimality
                            // after the search"): read NOW, so flipping it mid-search counts. OFF starts no proof work
                            // at all — see [setVerifyOptimality] / [skipBackgroundProof].
                            val verifyOptimality = ui.verifyOptimality
                            // A request no search can prove ([TargetStats.needsItemPrefilter]) has nothing to verify: the engine
                            // answers "unavailable" at once, so launching the check would only flash a "Verifying optimality…"
                            // spinner over the explanation the stats panel gives for it ([UiState.prefilteredRequest]). The
                            // engine starts no warm-up for such a request either, so there is nothing to cancel.
                            val provable = !params.targetStats.needsItemPrefilter
                            // Certificate optimality proof (P4.4): only for max-damage, and off the search's
                            // critical path — a full exact solve can take minutes, so it runs in its own job and
                            // streams its verdict into [UiState.proofState] when ready. It can prove an optimum
                            // CP-SAT left un-closed (badge flips to proven even when `optimal` was false).
                            if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE && completedResult != null && provable) {
                                if (verifyOptimality) {
                                    launchOptimalityProof(params, completedResult, character, damageScenario)
                                } else {
                                    skipBackgroundProof(params, completedResult)
                                }
                            } else if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                                completedResult != null &&
                                provable &&
                                !completedResult.isOptimal &&
                                completedResult.mostMasteriesObjective != null
                            ) {
                                // Backup quality certificate (§8.9bis): CP-SAT ended without a proof (short
                                // budget / low-core machine) — bound the gap instead. Automatic; the bound
                                // was computed in the search's tail (§8.19), so the verdict is usually
                                // instant — else the same ProofState pipeline renders the phase ("Verifying
                                // optimality…") until the bound lands, then the badge.
                                if (verifyOptimality) {
                                    launchMostMasteriesQualityProof(params, completedResult)
                                } else {
                                    skipBackgroundProof(params, completedResult)
                                }
                            }
                        } else if (ui.phase == Phase.Searching) {
                            ui =
                                ui.copy(
                                    phase = Phase.Idle,
                                    progress = 0,
                                    error = UiError(Tr.SEARCH_NO_RESULT.value(ui.lang)),
                                    lastLandedEquipmentId = null
                                )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (throwable: Throwable) {
                    // Catch Throwable (not just Exception): the solver can raise fatal Errors
                    // (e.g. native-access / linkage issues) that would otherwise crash the event
                    // thread with a masked coroutines error instead of surfacing here. Request-validation
                    // problems are caught BEFORE the search starts (see validateRequest above), so they
                    // don't reach here.
                    // The raw detail (native-library paths, class names…) goes to the log; the player gets a plain
                    // sentence and a Retry. A search that fails after streaming a build ends like a stopped one, so the
                    // build is not stranded in the idle phase.
                    throwable.printStackTrace()
                    withContext(mainDispatcher) {
                        val failure = UiError(Tr.SEARCH_FAILED.value(ui.lang), ErrorRetry.SEARCH)
                        ui = if (ui.phase == Phase.Searching) ui.searchEndedEarly().copy(error = failure) else ui.copy(error = failure)
                    }
                } finally {
                    progressTicker.cancel()
                }
            }
    }

    /**
     * Most-masteries backup quality certificate (plan §8.9bis): bounds how far the shown un-proven
     * build can be from the optimum ("proven within X%"). Runs automatically after a most-masteries
     * search whose CP-SAT leg ended non-OPTIMAL — the case of short budgets and low-core machines,
     * where the 1-worker proof would take 15-20 min. ONE full-tier pass (§8.19): its incumbent-free
     * bound was computed in the search's tail, so the verdict usually lands at once; otherwise the
     * spinner phase shows until the bound does. Streams through the same
     * [UiState.proofState] pipeline as the max-damage proof; failures and unsupported shapes degrade
     * to [ProofState.Unavailable] — never a wrong badge.
     */
    private fun launchMostMasteriesQualityProof(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
    ) {
        val provenBuild = result.individual
        val proofStartMs = clock()
        // B8 wiring: the proof polls this while it waits for the bound (and per DP stage when it
        // computes it) — a new search / mode switch flips it and the superseded proof stops at once.
        val cancelled = newProofCancelFlag()
        proofJob =
            scope.launch(backgroundDispatcher) {
                withContext(mainDispatcher) {
                    if (!cancelled.get() && ui.phase == Phase.Done && ui.build == provenBuild && ui.proofState == ProofState.Idle) {
                        ui = ui.copy(proofState = ProofState.Proving(ProofProgress(phase = ProofPhase.CERTIFYING, startedAtMs = proofStartMs)))
                    }
                }
                val proof =
                    try {
                        mmQualityProver(params, result) { !cancelled.get() }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        throwable.printStackTrace()
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable
                    }
                val state =
                    when (proof) {
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal -> ProofState.ProvenOptimal
                        is WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin -> ProofState.ProvenWithin(proof.percent)
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable -> ProofState.Unavailable
                    }
                withContext(mainDispatcher) {
                    // Only onto the build it proved, and never over a badge a cancel/load already reset
                    // (builds compare by VALUE: a reloaded copy of this build must not inherit a dead proof).
                    if (!cancelled.get() &&
                        ui.phase == Phase.Done &&
                        ui.build == provenBuild &&
                        (ui.proofState is ProofState.Proving || ui.proofState == ProofState.Idle)
                    ) {
                        ui = ui.copy(proofState = state)
                    }
                }
            }
    }

    /**
     * "Check optimality after the search" is OFF ([setVerifyOptimality]) and a max-damage / un-proven most-masteries search
     * just ended: NO proof work follows — no certificate wait or compute, no E8 construct, no silent refinement, no
     * quality-bound compute. What costs nothing still shows:
     *  - a result the search itself proved ([UiState.optimal]) keeps its "proven optimal" headline — the stats panel
     *    reads it straight from the result, nothing to do here;
     *  - most-masteries only: a quality bound the search's tail already finished is read from the engine's memo by PEEKING.
     *    The prover runs with a continue-predicate that is already false, so a memoized bound answers at once and anything
     *    else returns "unavailable" without starting or joining a compute (see
     *    [WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality]); the usual "not proven" hint then stays.
     *
     * Finally the engine's own warm-ups — started beside the search and kept alive for a proof to join, which will not
     * come — are cancelled, so nothing keeps the processor busy. That runs on the UI thread, right as the search ends:
     * no newer search can be running yet (it would lose its own warm-ups to the cancel).
     */
    private fun skipBackgroundProof(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
    ) {
        // A fresh flag: a new search, a mode switch or a Stop supersedes the peek like any other proof launch.
        val cancelled = newProofCancelFlag()
        backgroundProofCanceller()
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) return
        val shownBuild = result.individual
        proofJob =
            scope.launch(backgroundDispatcher) {
                val verdict =
                    try {
                        mmQualityProver(params, result) { false }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        throwable.printStackTrace()
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable
                    }
                val state =
                    when (verdict) {
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal -> ProofState.ProvenOptimal
                        is WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin -> ProofState.ProvenWithin(verdict.percent)
                        WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable -> return@launch
                    }
                withContext(mainDispatcher) {
                    // Only onto the build it read, and never over a state a cancel/load/new search already reset.
                    if (!cancelled.get() && ui.phase == Phase.Done && ui.build == shownBuild && ui.proofState == ProofState.Idle) {
                        ui = ui.copy(proofState = state)
                    }
                }
            }
    }

    /**
     * Computes the AP-cell certificate optimality proof (P4.4) off the UI thread and streams the verdict into
     * [UiState.proofState]. The certificate solve is a blocking call that can take minutes, so it runs in its
     * own [proofJob]; the result is only applied while the shown build is still the one it was proving (a new
     * search / build swap invalidates it). Failures degrade to [ProofState.Unavailable] — never a wrong badge.
     *
     * A [MaxDamageSearch.MaxDamageProof.ProvenWithin] verdict is shown AT ONCE as [ProofState.ProvenWithin] with
     * `refining = true` (the badge plus a small "still proving" cue) and the work that may improve on it runs BEHIND
     * it, instead of a spinner hiding the badge for the whole attempt (a failing E8 construct can take a minute):
     * first the E8 construct of the proven optimum, then — failing that — the silent per-carrier refinement. A
     * constructed build swaps in with [ProofState.ProvenOptimal]; a refinement tightens the badge (or closes it);
     * anything else leaves it with `refining = false`. Every late application is guarded on that refining badge
     * still being the one on screen for the proven build — and on this launch's cancel flag, which is how [stopProof]
     * (the user's Stop link, or switching the check off) keeps the badge while nothing the stopped proof computes
     * afterwards lands.
     */
    private fun launchOptimalityProof(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
        character: Character,
        damageScenario: DamageScenario,
    ) {
        val provenBuild = result.individual
        val cancelled = newProofCancelFlag()
        val proofStartMs = clock()

        // The proof-progress callback (v1): phase transitions observed HERE feed it; a later per-cell hook
        // from the certifier DP can call the same function with cellsDone/cellsTotal filled in.
        suspend fun reportProofProgress(progress: ProofProgress) {
            withContext(mainDispatcher) {
                // Only while the shown build is still the one being proven, and never resurrect a badge a
                // cancel/load already reset (proofState must still be Proving — or Idle for the first report).
                // The cancel-flag check matters because builds compare by VALUE: a saved copy of this very
                // build, reloaded after a cancel, must not be picked up by a late report of the dead proof.
                if (!cancelled.get() &&
                    ui.phase == Phase.Done &&
                    ui.build == provenBuild &&
                    (ui.proofState is ProofState.Proving || ui.proofState == ProofState.Idle)
                ) {
                    ui = ui.copy(proofState = ProofState.Proving(progress))
                }
            }
        }

        // Publishes the "proven within X %" badge with the small "still proving" cue the moment the certificate verdict is
        // known, so the E8 construct and the silent refinement run BEHIND it. Same guards as [reportProofProgress]: never
        // resurrect a badge a cancel/load already reset. Returns whether the badge landed.
        suspend fun publishRefiningBadge(fraction: Double): Boolean =
            withContext(mainDispatcher) {
                if (cancelled.get() ||
                    ui.phase != Phase.Done ||
                    ui.build != provenBuild ||
                    !(ui.proofState is ProofState.Proving || ui.proofState == ProofState.Idle)
                ) {
                    return@withContext false
                }
                ui = ui.copy(proofState = ProofState.ProvenWithin(fraction, refining = true))
                true
            }

        // True while the refining badge THIS proof published is still the one on screen for the proven build — what every
        // late application (the constructed build, the refinement) is guarded on. Read it on the main dispatcher.
        fun refiningBadgeShown(): Boolean {
            val shown = ui.proofState
            return !cancelled.get() && ui.phase == Phase.Done && ui.build == provenBuild && shown is ProofState.ProvenWithin && shown.refining
        }
        proofJob =
            scope.launch(backgroundDispatcher) {
                val proofScope = this
                reportProofProgress(ProofProgress(phase = ProofPhase.CERTIFYING, startedAtMs = proofStartMs))
                val proof =
                    try {
                        optimalityProver(params, result, { cancelled.get() }) { stageKey ->
                            // The engine reports from a worker thread; hop to the UI state safely — as a CHILD of
                            // this proof job, so cancelling the proof also drops its in-flight stage reports.
                            proofScope.launch {
                                reportProofProgress(
                                    ProofProgress(phase = ProofPhase.CERTIFYING, startedAtMs = proofStartMs, detailKey = stageKey)
                                )
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        throwable.printStackTrace()
                        MaxDamageSearch.MaxDamageProof.Unavailable
                    }
                // A ProvenWithin verdict means the certificate has proven a strictly better build EXISTS than the search
                // reached. Show that badge AT ONCE (with the "still proving" cue) and work behind it — only behind a badge
                // that actually landed: work whose result nothing can display would just pin the CPU.
                val badgeLanded = proof is MaxDamageSearch.MaxDamageProof.ProvenWithin && publishRefiningBadge(proof.fraction)
                // E8 fast-path: try to CONSTRUCT that proven optimum from the same certificate DP (off the UI thread,
                // here). On success we swap the shown build to it and flip the badge to ProvenOptimal — recomputing its
                // stats / rotation / scenario breakdown EXACTLY as the search did (same character + boss-overlaid
                // scenario), so the whole sheet stays consistent with the paperdoll. Its score too: the solver's own
                // (`up.matchPercentage`) is the across-elements damage, not the debuff-aware one every search path stores, so
                // keeping it would put a headline beside a rotation card that disagrees with it, and a save of the swapped
                // build would read as changed (its proof flag dropped) when reloaded.
                val upgrade =
                    if (badgeLanded) {
                        try {
                            provenOptimumConstructor(params, result, { cancelled.get() })?.let { up ->
                                val upBuild = up.individual
                                // The stats column's own read of a build, as for every streamed one ([achievedStats]).
                                val upAchieved = achievedStats(upBuild, params)
                                val upRotation = SpellRotationOptimizer.bestSequencedRotation(upBuild, character, character.clazz, damageScenario)
                                val upScenario =
                                    SpellRotationOptimizer.scenarioBreakdown(
                                        upBuild,
                                        character,
                                        character.clazz,
                                        damageScenario,
                                        includeBerserk = (upAchieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0,
                                        configuredRotationTotal = upRotation?.totalExpectedDamage
                                    )
                                UpgradedBuild(upBuild, upAchieved, upRotation, upScenario, buildRescorer(params, upBuild))
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            throwable.printStackTrace()
                            null
                        }
                    } else {
                        null
                    }
                if (proof !is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
                    // A final verdict (or none): nothing is left to work on, so it is applied as soon as it is known — unless
                    // the proof was stopped or superseded meanwhile (its launch flag), whose late verdict must not land.
                    withContext(mainDispatcher) {
                        if (!cancelled.get() && ui.phase == Phase.Done && ui.build == provenBuild) {
                            ui =
                                ui.copy(
                                    proofState =
                                        if (proof == MaxDamageSearch.MaxDamageProof.ProvenOptimal) ProofState.ProvenOptimal else ProofState.Unavailable
                                )
                        }
                    }
                    return@launch
                }
                if (!badgeLanded) return@launch
                if (upgrade != null) {
                    // The constructed proven optimum replaces the shown build — but only while the refining badge this proof
                    // published is still the one on screen for it (a cancel / new search / load invalidates the late swap).
                    withContext(mainDispatcher) {
                        if (refiningBadgeShown()) {
                            ui =
                                ui.copy(
                                    build = upgrade.build,
                                    achieved = upgrade.achieved,
                                    spellRotation = upgrade.rotation,
                                    scenarioDamages = upgrade.scenario,
                                    match = upgrade.match,
                                    optimal = true,
                                    proofState = ProofState.ProvenOptimal,
                                    // A Zenith link made for the incumbent (or one still loading) must never be shown for the
                                    // constructed build — [createZenithLink] drops the late completion of the latter.
                                    zenith = ZenithState.Idle,
                                    zenithUrl = null
                                )
                        }
                    }
                    return@launch
                }
                // Silent refinement (2026-07-21): no constructed build, so the per-carrier exact closure keeps running behind
                // the same badge — its `refining=true` cue makes the stats panel render a small "still proving" indicator
                // (user request) — and ends it with refining=false, a tighter bound or ProvenOptimal. Refine only behind the
                // badge still on screen: a minutes-long CP-SAT pass whose result nothing can display would just pin the CPU.
                if (withContext(mainDispatcher) { refiningBadgeShown() }) {
                    val refined =
                        try {
                            proofRefiner(params, result, { cancelled.get() })
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            throwable.printStackTrace()
                            null
                        }
                    withContext(mainDispatcher) {
                        val shown = ui.proofState
                        // Apply only while the refining badge this pass produced is still the one on screen (a Stop or a
                        // new search flags the proof cancelled and clears that cue).
                        if (!cancelled.get() && ui.phase == Phase.Done && ui.build == provenBuild && shown is ProofState.ProvenWithin && shown.refining) {
                            ui =
                                ui.copy(
                                    proofState =
                                        when (refined) {
                                            MaxDamageSearch.MaxDamageProof.ProvenOptimal -> ProofState.ProvenOptimal
                                            is MaxDamageSearch.MaxDamageProof.ProvenWithin ->
                                                ProofState.ProvenWithin(minOf(shown.fraction, refined.fraction))
                                            else -> shown.copy(refining = false)
                                        }
                                )
                        }
                    }
                }
            }
    }

    // The E8-constructed proven optimum + its derived sheet fields, computed off the UI thread in
    // [launchOptimalityProof] and applied together so the swapped build's stats stay consistent.
    private data class UpgradedBuild(
        val build: BuildCombination,
        val achieved: Map<Characteristic, Int>,
        val rotation: SpellRotation?,
        val scenario: List<ScenarioDamage>,
        val match: java.math.BigDecimal,
    )

    /**
     * The search button's "Stop". A search that already streamed a build ends as a finished-but-not-proven result: the
     * best-so-far build stays on screen, fully usable (not dimmed; save, export and Zenith enabled), flagged
     * [UiState.searchStopped]. It makes no proof claim — the stream is cut short (a max-damage stream may hold results of a
     * sub-problem only) and no proof ever ran for it — so [UiState.optimal] is dropped and the proof state is cleared. A stop
     * before any build exists simply returns to idle. Calling it when nothing is searching only drops a proof that is still
     * running (the "Stop" link of the background optimality check, [stopProof], is the user-facing way to do that).
     */
    fun cancel() {
        val searching = ui.phase == Phase.Searching
        job?.cancel()
        job = null
        cancelProof()
        ui = if (searching) ui.searchEndedEarly() else ui.copy(proofState = ProofState.Idle)
    }

    /**
     * This state after its search ended before finishing (stopped, or failed): a build already found stays on screen as a
     * finished, usable, NOT-proven result ([UiState.searchStopped]); with no build yet it is back to idle.
     */
    private fun UiState.searchEndedEarly(): UiState =
        if (build != null) {
            copy(
                phase = Phase.Done,
                optimal = false,
                proofState = ProofState.Idle,
                searchStopped = true,
                lastLandedEquipmentId = null
            )
        } else {
            copy(phase = Phase.Idle, progress = 0, proofState = ProofState.Idle)
        }

    /**
     * The "Stop" link beside the background optimality check's cue. It stops the check like [cancelProof] — the proof's
     * coroutine AND the blocking solves inside it, which poll this launch's cancel flag — but KEEPS what is already known
     * and never touches the shown build:
     *  - a "proven within X %" badge that was still being worked on behind ([ProofState.ProvenWithin.refining]) stays, without
     *    its cue;
     *  - a check that knew nothing yet ([ProofState.Proving]) falls back to the usual "not proven" hint
     *    ([ProofState.Idle]);
     *  - any other state — a final verdict, or nothing running — is left as it is.
     * The engine's own warm-ups a proof would have joined are cancelled too (they would otherwise keep the processor busy
     * for nobody), unless a search is running.
     *
     * A result the stopped proof computes AFTER this call is dropped: its launch flag is set, which every late application
     * checks, its coroutine is cancelled, and the refining badge it would be applied onto is gone.
     */
    fun stopProof() {
        // The proof first, the engine's warm-ups second: a proof still waiting on a warm-up that is cancelled under it
        // would start its own compute when that wait ends — flagged cancelled, it returns instead.
        cancelProof()
        // A search still running keeps its own warm-ups (they are what lets it stop early); only a finished search's
        // leftovers are cancelled.
        if (ui.phase != Phase.Searching) backgroundProofCanceller()
        val shown = ui.proofState
        ui =
            when {
                shown is ProofState.Proving -> ui.copy(proofState = ProofState.Idle)
                shown is ProofState.ProvenWithin && shown.refining -> ui.copy(proofState = shown.copy(refining = false))
                else -> ui
            }
    }

    /**
     * Stops the running optimality proof / refinement: the coroutine AND (B8) the blocking certifier DP and
     * CP-SAT solves inside it, which poll that launch's own cancel flag. Call it wherever the proven build
     * stops being the one on screen.
     */
    private fun cancelProof() {
        proofCancelled.set(true)
        proofJob?.cancel()
        proofJob = null
    }

    /**
     * Installs (and returns) a fresh cancel flag for a new proof launch — see [proofCancelled]. A proof still
     * running at this point is superseded, so it is cancelled first: once its flag is replaced nothing could
     * reach it any more.
     */
    private fun newProofCancelFlag(): java.util.concurrent.atomic.AtomicBoolean {
        cancelProof()
        return java.util.concurrent.atomic
            .AtomicBoolean(false)
            .also { proofCancelled = it }
    }

    /** View the currently displayed build through max-damage damage/rotation cards without re-running the solver. */
    fun viewCurrentBuildAsMaxDamage() {
        val snapshot = ui
        val build = snapshot.build ?: return
        job?.cancel()
        cancelProof()
        // The build moves to the max-damage view WITH its rows, but the mode it came from keeps its work: going back there
        // restores the original result and rows, so this view can always be undone.
        val parked = snapshot.modeWorkspaces + (snapshot.mode to ModeWorkspace(snapshot.targets, snapshot.shownResult().atRest()))
        ui =
            snapshot.copy(
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                modeWorkspaces = parked - ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                phase = Phase.Done,
                progress = 100,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                proofState = ProofState.Idle,
                spellRotation = null,
                scenarioDamages = emptyList(),
                error = null,
                toast = null
            )
        val params = ui.toSearchParams()
        val character = params.character
        val damageScenario = params.damageScenario
        job =
            scope.launch(backgroundDispatcher) {
                // The same score the max-damage result path streams, including required-target shortfalls.
                val match = buildRescorer(params, build)
                val achieved = achievedStats(build, params)
                val rotation = SpellRotationOptimizer.bestSequencedRotation(build, character, character.clazz, damageScenario)
                val breakdown =
                    SpellRotationOptimizer.scenarioBreakdown(
                        build,
                        character,
                        character.clazz,
                        damageScenario,
                        includeBerserk = (achieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0,
                        configuredRotationTotal = rotation.totalExpectedDamage
                    )
                withContext(mainDispatcher) {
                    if (ui.build == build && ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                        ui = ui.copy(match = match, achieved = achieved, spellRotation = rotation, scenarioDamages = breakdown)
                    }
                }
            }
    }

    private fun UiState.currentDamageScenario(): DamageScenario = scenario.aimedAt(selectedBoss, bossElement)

    /**
     * This manual scenario aimed at [boss] (null = as is), mirroring the CLI: a forced [element] pins that one element, else
     * all four are filled from the bestiary so the objective auto-picks the best playable one.
     */
    private fun DamageScenario.aimedAt(
        boss: Monster?,
        element: SpellElement?,
    ): DamageScenario =
        when {
            boss != null && element != null -> against(boss, element)
            boss != null -> againstAllElements(boss)
            else -> this
        }

    /** Dismisses the pre-search request-errors pop-up ([UiState.requestErrors]). */
    fun dismissRequestErrors() {
        ui = ui.copy(requestErrors = emptyList())
    }

    fun openZenithBuild() {
        createZenithLink(ErrorRetry.OPEN_ZENITH) { link ->
            runCatching {
                openBrowser(link)
            }.onFailure { exception ->
                // The link itself is fine (the state stays Ready, so "Copy build link" reuses it): only the browser failed.
                // Raw detail to the log, a plain sentence on screen.
                exception.printStackTrace()
                ui = ui.copy(error = UiError(Tr.ZENITH_BROWSER_FAILED.value(ui.lang).format(Tr.COPY_BUILD_LINK.value(ui.lang))))
            }
        }
    }

    fun copyZenithLink() {
        createZenithLink(ErrorRetry.COPY_ZENITH) { link ->
            copyToClipboard(link)
            ui =
                ui.copy(
                    toast =
                        Tr.TOAST_ZENITH_COPIED.value(ui.lang)
                )
        }
    }

    /** The error banner's "Retry": repeats the action that failed, after dropping the banner. */
    fun retryAfterError() {
        val retry = ui.error?.retry ?: return
        ui = ui.copy(error = null)
        when (retry) {
            ErrorRetry.OPEN_ZENITH -> openZenithBuild()
            ErrorRetry.COPY_ZENITH -> copyZenithLink()
            // The player already confirmed this request (a loaded build's re-search guard included): run it as it stands.
            ErrorRetry.SEARCH -> search()
        }
    }

    /**
     * Copies the current build — its full request (input) *and* discovered result (output) — to the
     * clipboard as a [HistoryEntry] JSON, so a tester can hand you a build without a screenshot. The
     * same payload re-imports losslessly via [importBuild]. Reflects the live workspace; when a saved
     * build is loaded its metadata (note/tags/folder/created date) is preserved.
     */
    fun exportBuild() {
        if (ui.build == null) return
        val active = ui.activeBuildId?.let { id -> ui.savedBuilds.firstOrNull { it.id == id } }
        val entry =
            ui.toHistoryEntry(
                id = active?.id ?: idGenerator(),
                name = ui.activeBuildName ?: ui.suggestedBuildName(),
                note = active?.note,
                createdAt = active?.createdAt ?: clock(),
                dataVersion = ui.resultDataVersion(),
                engineResultsVersion = ui.resultEngineVersion(),
                tags = active?.tags ?: emptyList(),
                folder = active?.folder
            ) ?: return
        copyToClipboard(historyJson.encodeToString(HistoryEntry.serializer(), entry))
        ui = ui.copy(toast = Tr.TOAST_BUILD_EXPORTED.value(ui.lang))
    }

    /**
     * Hands [onReady] the Zenith link of the build on screen. A build is exported ONCE: when its link already exists — made
     * by an earlier "Open in Zenith" / "Copy build link", or saved with the build — it is reused, because creating another
     * Zenith build for the same build each time (Open then Copy made two) only litters the player's Zenith account. A request
     * made while the link is being created is ignored (the buttons are disabled meanwhile, and a second creation would
     * duplicate the build). [retry] is what the error banner's Retry repeats if the creation fails.
     */
    private fun createZenithLink(
        retry: ErrorRetry,
        onReady: (String) -> Unit,
    ) {
        val build = ui.build ?: return
        ui.zenithUrl?.takeIf { ui.zenith == ZenithState.Ready }?.let { existing ->
            onReady(existing)
            return
        }
        if (ui.zenith == ZenithState.Loading) return
        ui = ui.copy(zenith = ZenithState.Loading, error = null, toast = null)
        val character = Character(ui.clazz, ui.level, ui.minLevel).copy(characterSkills = build.characterSkills)
        scope.launch(backgroundDispatcher) {
            try {
                val link =
                    zenithBuilder(
                        ZenithInputParameters(
                            character = character,
                            equipments = build.equipments,
                            runes = build.runes,
                            sublimations = build.sublimations
                        )
                    )
                withContext(mainDispatcher) {
                    // The shown build may have been swapped (the E8 construct), replaced or cleared while the link was being
                    // created: a link made for another build must never be shown nor handed to the browser / clipboard. Drop
                    // it silently and leave the state as that change left it.
                    if (ui.build != build) return@withContext
                    ui =
                        ui.copy(
                            zenith = ZenithState.Ready,
                            zenithUrl = link,
                            toast =
                                Tr.TOAST_ZENITH_READY.value(ui.lang)
                        )
                    onReady(link)
                }
            } catch (exception: Exception) {
                // Whatever went wrong (no network, a timeout, an API error), the player gets one plain sentence and a Retry;
                // the raw detail ("api.zenithwakfu.com", "Timed out waiting for 10000 ms"…) goes to the log.
                exception.printStackTrace()
                withContext(mainDispatcher) {
                    // Same guard: a failure for a build that is no longer the shown one is no news.
                    if (ui.build != build) return@withContext
                    ui = ui.copy(zenith = ZenithState.Error, error = UiError(Tr.ZENITH_UNREACHABLE.value(ui.lang), retry))
                }
            }
        }
    }

    internal fun UiState.toTargetStats(): TargetStats {
        // A typed 0 is a row ("never below 0"); a blank — or cleared — field asks for nothing, so it sends no row at all. Except a
        // mastery most-masteries maximizes: its row is a checkbox there (no field, its value never read), so it always counts.
        val raw =
            targets.mapNotNull { row ->
                val value = row.value.toIntOrNull() ?: if (row.isMaximized(mode)) 0 else return@mapNotNull null
                TargetStat(row.characteristic, value, row.weight)
            }
        // Most-masteries only: split a single "all resistances" target into the four per-element ones
        // so the solver gets four graceful constraints instead of one brittle min-over-four. The UI
        // keeps a single editable row; the split happens here, on the way to the engine.
        val forEngine =
            if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                expandGlobalResistance(raw)
            } else {
                raw
            }
        return TargetStats(forEngine)
    }

    /**
     * This request's rows as a version before blank fields and rows of target 0 changed meaning read them (1.13): a blank or cleared
     * field was a row of target 0 — a WANTED element then, for a resistance as for a mastery — and most-masteries split "all
     * resistances" into the four elements ([expandGlobalResistance]). Only [rescored] reads it, to tell whether such a version
     * searched this request on the pre-filtered pool ([TargetStats.legacyNeedsItemPrefilter]). Its rows include [toTargetStats]'s
     * (a blank field adds a row, never removes one), so it also covers what the current reading would flag.
     */
    private fun UiState.legacyTargetStats(): TargetStats {
        val raw = targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0, it.weight) }
        return TargetStats(if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) expandGlobalResistance(raw) else raw)
    }

    /**
     * This request as the engine receives it: what a search runs on, and what a loaded saved build is re-scored against
     * ([rescored]) — one mapping, so the two can never read the same request differently.
     */
    private fun UiState.toSearchParams(): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(clazz, level, minLevel),
            targetStats = toTargetStats(),
            // Blank duration = the longest sensible run (10 min). Kept finite on purpose: an unbounded
            // budget would make the time-driven progress bar meaningless and risk a search that never
            // returns on a hard input. (QOL-2)
            searchDuration = (duration.toIntOrNull() ?: 600).coerceAtLeast(1).seconds,
            // "Stop at 100% match" only applies to precision mode (the only mode with an exact target);
            // ignore a stale toggle when searching in most-masteries / max-damage.
            stopWhenBuildMatch = stopAtMatch && mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
            maxRarity = maxRarity,
            excludedRarities = excludedRarities,
            forcedItems = forcedItems.map { it.matchName },
            excludedItems = excludedItems.map { it.matchName },
            scoreComputationMode = mode,
            useSublimations = useSublimations,
            maxSublimationTier = maxSublimationTier,
            forcedSublimations = forcedSublimations,
            excludedSublimations = excludedSublimations,
            forcedPassives = forcedPassives,
            forcedRunesByItem = forcedRunesByItem,
            // A targeted boss overlays its per-element resistances onto the manual scenario (mirrors the CLI):
            // a forced element pins that one element, else all four are filled so the objective auto-picks.
            damageScenario = currentDamageScenario()
        )

    /**
     * The per-stat grid the stats column shows for [build] under [params]'s request, resolved with the SAME random-element
     * assignment the scorer used so the displayed values match the score: most-masteries → exact max-min, precision → exact
     * max-capped, max-damage → greedy, and per-element rows over several elements (the four resistance rows "all
     * resistances" expands to, fire + water mastery in precision…) or a resistance floor ("air resistance 0") → the exact
     * optimum of the solver's joint fold (`elementRowObjectives`). Mirrors FindMostMasteriesFromInputScoring; omitting the mode
     * would fall to the greedy `else` branch and diverge from the score. A search's streamed builds and a reloaded saved build
     * both read their stats here, so the two can never disagree about the same build.
     */
    private fun achievedStats(
        build: BuildCombination,
        params: WakfuBestBuildParams,
    ): Map<Characteristic, Int> {
        val targetStats = params.targetStats
        val masteryElementsToMinimize =
            if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                targetStats.masteryElementsToMinimize
            } else {
                null
            }
        val resistanceElementsToMinimize =
            if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                targetStats.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }
            ) {
                targetStats.resistanceElementsWanted.keys.toList()
            } else {
                null
            }
        return computeCharacteristicsValues(
            buildCombination = build,
            characterBaseCharacteristics = params.character.baseCharacteristicValues,
            masteryElementsWanted = targetStats.masteryElementsWanted,
            resistanceElementsWanted = targetStats.resistanceElementsWanted,
            scoreComputationMode = params.scoreComputationMode,
            masteryElementsToMinimize = masteryElementsToMinimize,
            resistanceElementsToMinimize = resistanceElementsToMinimize,
            // Max-damage: the scenario gates its sublimation effects (berserk, orientation, range…) as it does for the scorer and
            // the solver, so a gated dodge or lock shows where the search counted it. (No other mode reads it.)
            damageScenario = params.damageScenario,
            // Per-element rows over several elements, and the floors ("air resistance 0"): the scorers' exact placement of the
            // solver's joint fold — a floor's value with the random rolls the player puts there, so the row's status reads what the
            // search enforced.
            elementRows = targetStats.elementRowObjectives(params.scoreComputationMode)
        )
    }

    private fun newlyLandedEquipmentId(
        previous: BuildCombination?,
        next: BuildCombination,
    ): Int? {
        val previousIds = previous?.equipments?.map { it.equipmentId }?.toSet() ?: emptySet()
        return next.equipments.firstOrNull { it.equipmentId !in previousIds }?.equipmentId
    }

    private fun clearLandedMarkerLater(equipmentId: Int) {
        scope.launch {
            delay(560.milliseconds)
            withContext(mainDispatcher) {
                if (ui.lastLandedEquipmentId == equipmentId) {
                    ui = ui.copy(lastLandedEquipmentId = null)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Build history / comparison
    // ---------------------------------------------------------------------------------------------

    /**
     * The search button's click handler. When a saved build is loaded the button is *locked*: the
     * first click asks for confirmation (so re-optimizing a saved build is a deliberate act) rather
     * than silently recomputing it. Otherwise it runs the search straight away.
     */
    fun onSearchPressed() {
        if (ui.searchLocked) {
            ui = ui.copy(modal = Modal.ConfirmReSearch)
        } else {
            search()
        }
    }

    /** Confirms the guarded re-search: unlock and run. The active build identity is kept so the user
     * can save the recomputed result back over the same entry. */
    fun confirmReSearch() {
        ui = ui.copy(modal = null, searchLocked = false)
        search()
    }

    /**
     * "Re-run the search" on an obsolete saved build (My Builds, compare view): restores its request — exactly as [loadBuild]
     * does — and starts the search at once. The build stays the active one, so saving the new result updates it.
     */
    fun rerunSearch(id: String) {
        if (ui.savedBuilds.none { it.id == id }) return
        loadBuild(id)
        confirmReSearch()
    }

    fun goToScreen(screen: Screen) {
        ui = ui.copy(screen = screen)
    }

    /** Switch the result region between the discovered build and the class's spells & passives. */
    fun setBuilderTab(tab: BuilderTab) {
        ui = ui.copy(builderTab = tab)
    }

    // --- Library organize (search / sort / filter / group) ---

    fun setLibrarySearch(query: String) {
        ui = ui.copy(librarySearch = query)
    }

    fun setLibrarySort(sort: LibrarySort) {
        ui = ui.copy(librarySort = sort)
        libraryPreferences.saveSort(sort)
    }

    /** Single-select class filter; passing the already-selected class (or null) clears it. */
    fun setLibraryClassFilter(clazz: me.chosante.common.CharacterClass?) {
        ui = ui.copy(libraryClassFilter = clazz)
    }

    /** Toggles a tag in the active filter (OR semantics: builds matching any selected tag are shown). */
    fun toggleLibraryTag(tag: String) {
        val key = tag.lowercase()
        ui =
            ui.copy(
                librarySelectedTags = if (key in ui.librarySelectedTags) ui.librarySelectedTags - key else ui.librarySelectedTags + key
            )
    }

    fun toggleLibraryGroupByClass() {
        val next = !ui.libraryGroupByClass
        ui = ui.copy(libraryGroupByClass = next)
        libraryPreferences.saveGroupByClass(next)
    }

    /** Resets the in-memory library filters (search + class + tags + folder). Sort/group are durable. */
    fun clearLibraryFilters() {
        ui =
            ui.copy(
                librarySearch = "",
                libraryClassFilter = null,
                librarySelectedTags = emptySet(),
                libraryFolder = LibraryFolderFilter.All
            )
    }

    /** Opens the save dialog, pre-filling the name (existing name when editing a loaded build). */
    fun requestSaveBuild() {
        if (ui.build == null) return
        ui = ui.copy(modal = Modal.SaveBuild)
    }

    /**
     * Default text for the save dialog's name field: the loaded build's own name (saving updates it), else the generated
     * "Cra 110 · Distance" — made unique against the library ("… (2)") so that suggestion never collides with a build you
     * already saved, which would open the dialog with Save disabled and the "name already used" warning showing.
     */
    fun suggestedSaveName(asNew: Boolean = false): String {
        val base = ui.activeBuildName ?: ui.suggestedBuildName()
        return if (asNew || ui.activeBuildId == null) uniqueLibraryName(base) else base
    }

    /**
     * The game-data version a snapshot of the build on screen is stamped with: the one it was COMPUTED with. That is this app's
     * data for a build found here, but the original stamp for a stale saved/imported build ([UiState.staleDataVersion]) — saving
     * or exporting it without a new search must not relabel numbers from old data as current (which would also silence the
     * "saved with other game data" note on its card).
     */
    private fun UiState.resultDataVersion(): String = staleDataVersion ?: dataVersion

    /**
     * The engine results version a snapshot of the build on screen is stamped with — the one it was computed with, like
     * [resultDataVersion]: this app's for a build found here, the stored one (null when the save never recorded it) for a loaded
     * build of an older engine ([UiState.staleEngine]), so saving it as it is keeps it flagged as improvable.
     */
    private fun UiState.resultEngineVersion(): Int? = staleEngine.let { if (it == null) engineResultsVersion else it.savedVersion }

    /**
     * Names already used by saved builds (all of them for a copy; the active build is excluded so updating
     * it isn't blocked). The save dialog rejects these so two builds never share a name — which would
     * make the library and the compare view ambiguous.
     */
    fun takenBuildNames(asNew: Boolean = false): Set<String> =
        ui.savedBuilds
            .filter { asNew || it.id != ui.activeBuildId }
            .map { it.name.trim().lowercase() }
            .toSet()

    /**
     * Persists the current workspace build. When [asNew] is false and a build is already loaded, it
     * overwrites that entry (same id); otherwise it creates a new entry. Local write is the source of
     * truth and is done off the UI thread; the in-memory library is refreshed afterwards.
     */
    fun saveBuild(
        name: String,
        note: String?,
        asNew: Boolean,
    ) {
        val trimmedName = name.trim().ifBlank { ui.suggestedBuildName() }
        if (trimmedName.lowercase() in takenBuildNames(asNew)) {
            ui = ui.copy(error = UiError(Tr.SAVE_NAME_TAKEN.value(ui.lang)))
            return
        }
        val overwrite = !asNew && ui.activeBuildId != null
        val id = if (overwrite) ui.activeBuildId!! else idGenerator()
        // Overwriting rebuilds the entry from the workspace, which doesn't carry user metadata (tags,
        // folder) — re-read them from the existing entry so an "Update build" never silently wipes them.
        val existing = if (overwrite) ui.savedBuilds.firstOrNull { it.id == id } else null
        val entry =
            ui.toHistoryEntry(
                id = id,
                name = trimmedName,
                note = note,
                createdAt = clock(),
                dataVersion = ui.resultDataVersion(),
                engineResultsVersion = ui.resultEngineVersion(),
                tags = existing?.tags ?: emptyList(),
                folder = existing?.folder
            ) ?: return
        // Note: saving does NOT lock search. The lock guards *revisiting* a build loaded from the
        // library (a deliberate act); right after saving you should stay free to keep iterating.
        ui = ui.copy(modal = null, activeBuildId = id, activeBuildName = trimmedName)
        scope.launch(ioDispatcher) {
            runCatching {
                historyRepository.save(entry)
                historyRepository.loadAll()
            }.onSuccess { all ->
                withContext(mainDispatcher) { ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all), toast = Tr.TOAST_BUILD_SAVED.value(ui.lang)) }
            }.onFailure { throwable ->
                throwable.printStackTrace()
                withContext(mainDispatcher) { ui = ui.copy(error = UiError(Tr.SAVE_BUILD_FAILED.value(ui.lang))) }
            }
        }
    }

    /**
     * Loads a saved build into the workspace: restores its request (so it can be tweaked & re-run)
     * and its result (shown without re-running, but re-scored under the CURRENT rules — see [rescored]),
     * marks it as the active build, and locks the search button. Returns to the Builder screen.
     */
    fun loadBuild(id: String) {
        val entry = ui.savedBuilds.firstOrNull { it.id == id } ?: return
        job?.cancel()
        // Cancel any in-flight optimality proof: it is proving the PREVIOUS build, and the loaded build has no
        // certificate (its stored CP-SAT `optimal` flag is restored below, unless the re-score moved the build's score).
        // Without this, a running proof could leave "Proving optimality…" stuck, or a prior ProvenOptimal could paint a
        // green badge on this build that the certificate never saw (proofState is reset to Idle in the copy below).
        cancelProof()
        val loadedBuild = entry.toBuildCombination()
        // Recompute the spell rotation for a loaded max-damage build (else the Rotation card would show a
        // rotation left over from a prior search, or nothing). Cheap — no solver, just one rotation DP.
        val isMaxDamage = entry.restoredMode() == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        // A boss build is scored against the boss it was searched with (otherwise the rotation would be computed against
        // the manual scenario's 0 % resistance and disagree with the damage saved next to it).
        val loadedBoss = entry.restoredBoss()
        val loadedBossElement = entry.restoredBossElement()
        val loadedScenario = entry.restoredScenario().aimedAt(loadedBoss, loadedBossElement)
        val restoredCharacter =
            me.chosante.common.Character(entry.restoredClass(), entry.request.level, entry.request.minLevel, loadedBuild.characterSkills)
        val rotation =
            if (isMaxDamage) {
                SpellRotationOptimizer.bestSequencedRotation(loadedBuild, restoredCharacter, restoredCharacter.clazz, loadedScenario)
            } else {
                null
            }
        val restored =
            ui.withSavedRequest(entry).copy(
                screen = Screen.Builder,
                modal = null,
                phase = Phase.Done,
                progress = 100,
                // The STORED score, proof flag and stats: [rescored] swaps them for the current rules' just below.
                match = entry.result.match.toBigDecimal(),
                optimal = entry.result.optimal,
                // A loaded build is not re-proven by the certificate (only its stored CP-SAT `optimal` flag is
                // restored above) — reset the proof state so a prior search's verdict can't leak onto it.
                proofState = ProofState.Idle,
                // A save does not record whether its search was structurally heuristic (resistance-debuff sequencing…): the
                // build shows no such hint, not the one of whatever search ran before it.
                maxDamageStructural = false,
                searchStopped = false,
                // Computed with other game data than this app's (a build saved before a game update, or imported from another
                // version): the stats column says so until a new search replaces it. The stored build itself is left untouched.
                staleDataVersion = entry.dataVersion.takeIf { it != dataVersion },
                // Computed by an older engine than this app's (or before saves recorded it): a re-run may improve it.
                staleEngine = entry.engineResultsVersion.let { saved -> if (saved == null || saved < engineResultsVersion) StaleEngine(saved) else null },
                build = loadedBuild,
                spellRotation = rotation,
                scenarioDamages = emptyList(),
                achieved = entry.result.achieved,
                lastLandedEquipmentId = null,
                zenith = if (entry.zenithUrl != null) ZenithState.Ready else ZenithState.Idle,
                zenithUrl = entry.zenithUrl,
                error = null,
                toast = null,
                activeBuildId = entry.id,
                activeBuildName = entry.name,
                searchLocked = true
            )
        // The request this save was computed for — the rows and mode restored just above, never the rows the workspace held before
        // the load — decides whether any search of it could ever have been proven: the stats column explains the missing badge
        // of such a build ([UiState.prefilteredRequest]) instead of suggesting a longer search.
        val loaded = restored.copy(prefilteredRequest = runCatching { restored.toTargetStats().needsItemPrefilter }.getOrDefault(false))
        ui = loaded.rescored()
        // The per-position breakdown runs 3-4 more rotations, so compute it OFF the UI thread (the rotation
        // card already renders from `rotation` above) and patch it in when ready — only if this build is still
        // the active one, so a quick load-another-build doesn't get a stale breakdown.
        if (isMaxDamage && rotation != null) {
            val achieved = ui.achieved
            scope.launch(backgroundDispatcher) {
                val breakdown =
                    SpellRotationOptimizer.scenarioBreakdown(
                        loadedBuild,
                        restoredCharacter,
                        restoredCharacter.clazz,
                        loadedScenario,
                        includeBerserk = (achieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0,
                        configuredRotationTotal = rotation.totalExpectedDamage
                    )
                withContext(mainDispatcher) {
                    if (ui.activeBuildId == entry.id) ui = ui.copy(scenarioDamages = breakdown)
                }
            }
        }
    }

    /**
     * This state with [entry]'s request in place of its own — exactly what [loadBuild] restores, and what [rescoreSaved] scores a
     * library card with, so a card and a reload of the same build can never read its request differently. Unlike the remembered
     * workspace ([withRememberedRequest]) it clamps nothing: a save is replayed as it was searched.
     */
    private fun UiState.withSavedRequest(entry: HistoryEntry): UiState =
        copy(
            clazz = entry.restoredClass(),
            level = entry.request.level,
            minLevel = entry.request.minLevel,
            mode = entry.restoredMode(),
            // The loaded request replaces the whole workspace: no other mode's parked work survives it.
            modeWorkspaces = emptyMap(),
            scenario = entry.restoredScenario(),
            // The boss the build was searched against comes back with it (none for a build saved without one).
            selectedBoss = entry.restoredBoss(),
            bossElement = entry.restoredBossElement(),
            bossDifficulty = entry.restoredBossDifficulty(),
            maxRarity = entry.request.maxRarity,
            duration = entry.request.duration,
            stopAtMatch = entry.request.stopAtMatch,
            targets = entry.toTargetRows(),
            forcedItems = entry.toForcedChips(),
            excludedItems = entry.toExcludedChips(),
            useSublimations = entry.request.useSublimations,
            maxSublimationTier = entry.request.maxSublimationTier,
            // Builds saved before the July 2026 sublimation rename ("Carnage II" → "Carnage III")
            // keep their forced/excluded chips working under the current names.
            forcedSublimations =
                entry.request.forcedSublimations
                    .map(WakfuBestBuildFinderAlgorithm::canonicalSublimationName)
                    .distinct(),
            excludedSublimations =
                entry.request.excludedSublimations
                    .map(WakfuBestBuildFinderAlgorithm::canonicalSublimationName)
                    .distinct(),
            excludedRarities = entry.request.excludedRarities,
            forcedPassives = entry.request.forcedPassives,
            forcedRunesByItem = entry.request.forcedRunesByItem
        )

    /**
     * This freshly loaded saved build with the score and stats of the CURRENT rules in place of the stored ones. A save keeps
     * the numbers of the rules it was found under, and the rules move: a build saved while the Neutralité family read the SUM of the
     * secondary masteries came back showing a bonus the game never grants. The re-score is the search's own — the same request
     * ([toSearchParams]), stats grid ([achievedStats]) and scorer ([buildRescorer], by default [WakfuBestBuildFinderAlgorithm.rescore]) —
     * and costs milliseconds, no solver. The items and sublimations come from the save itself, so a build whose items left the catalog
     * re-scores too. A proof belongs to the rules it was made under: a build whose score moved ([isStoredScore]) loses its stored
     * "proven optimal" flag, and so does any build of a request no search can prove ([UiState.prefilteredRequest], read from
     * [TargetStats.needsItemPrefilter]: several elements of one family): an older version may have stored a proof made by a model
     * that counted every random-element roll on every element. So does a build of a request an older version searched on the
     * pre-filtered pool because it counted a resistance row of target 0 — or a blank field, which it read as 0 — as a wanted element
     * ([TargetStats.legacyNeedsItemPrefilter] on the rows read that way, [legacyTargetStats]: "fire resistance 100" beside the default
     * "air resistance 0", or beside a blank air field; "fire mastery 50" beside a blank water mastery) — its stored proof covers that
     * reduced pool only, though the request now searches the whole catalog. So does a build the game refuses
     * ([WakfuBestBuildFinderAlgorithm.equipConditionViolation]: a save made before the item EQUIP conditions were enforced may wear a
     * nation sword without its ring, one made before the exclusivity groups were read may wear 18691 — a COMMON item the game
     * counts as epic — beside an epic item, and one made before the stat gates were enforced may wear Cartes And at 4 range — the
     * stats column and the library card then say which item would be inactive, see [me.chosante.ui.components.StatGateCue]). Only a build the scorer cannot
     * read at all keeps its stored numbers.
     */
    private fun UiState.rescored(): UiState {
        val shown = build ?: return this
        return runCatching {
            val params = toSearchParams()
            val score = buildRescorer(params, shown)
            val provable = !prefilteredRequest && !legacyTargetStats().legacyNeedsItemPrefilter
            // A build the game refuses (an item EQUIP condition: a save made before they were enforced may wear a nation sword
            // without its ring; an exclusivity group: one made before they were read may wear 18691 beside an epic item; a stat
            // gate: one made before they were enforced may wear Cartes And at 4 range) is no proven optimum, whatever was stored with it.
            val wearable = WakfuBestBuildFinderAlgorithm.equipConditionViolation(shown, params.character.clazz) == null
            copy(match = score, achieved = achievedStats(shown, params), optimal = optimal && provable && wearable && isStoredScore(score, match))
        }.getOrDefault(this)
    }

    /**
     * Re-scores the saved [builds] under the CURRENT rules, off the UI thread, and publishes each result as it lands
     * ([UiState.libraryRescores]) — the library cards and the compare view then show the numbers a loaded build would show
     * ([rescored]) instead of the stored ones. Usually milliseconds per build, but a rare multi-element build can take more than
     * half a second: the work runs one build at a time on [backgroundDispatcher], a newer call cancels it between two builds, and
     * a build whose request, result and rules did not change since its last re-score comes straight from [rescoreCache].
     */
    private fun rescoreLibrary(builds: List<HistoryEntry>) {
        rescoreJob?.cancel()
        if (ui.phase == Phase.Searching || builds.isEmpty()) return
        rescoreJob =
            scope.launch(backgroundDispatcher) {
                val batch = LinkedHashMap<String, RescoredResult>()
                var lastPublish = System.nanoTime()

                suspend fun publish() {
                    if (batch.isEmpty()) return
                    val landed = batch.toMap()
                    batch.clear()
                    lastPublish = System.nanoTime()
                    withContext(mainDispatcher) {
                        val changed = landed.filter { (id, rescore) -> ui.libraryRescores[id] != rescore }
                        if (changed.isNotEmpty()) ui = ui.copy(libraryRescores = ui.libraryRescores + changed)
                    }
                }
                for (entry in builds) {
                    ensureActive()
                    val key = RescoreKey(entry.id, entry.request, entry.result, dataVersion, engineResultsVersion)
                    val current =
                        rescoreCache[entry.id]?.takeIf { it.first == key }?.second
                            // One unreadable save must not stop the others (nor reach the app's scope): it keeps its stored numbers.
                            ?: runCatching { rescoreSaved(entry) }.getOrElse { entry.result }.also { rescoreCache[entry.id] = key to it }
                    batch[entry.id] = RescoredResult(stored = entry.result, current = current)
                    // In batches, so a large library costs a few state writes, not one recomposition per build.
                    if (batch.size >= RESCORE_BATCH_SIZE || System.nanoTime() - lastPublish >= RESCORE_BATCH_NANOS) publish()
                }
                publish()
            }
    }

    /**
     * [entry]'s result with the score, stats and proof flag a [loadBuild] of it would show: its request restored the same way,
     * then [rescored]. The stored numbers come back unchanged when the scorer cannot read the build.
     */
    private fun rescoreSaved(entry: HistoryEntry): me.chosante.common.history.ResultSnapshot {
        val restored =
            UiState()
                .withSavedRequest(entry)
                .copy(
                    build = entry.toBuildCombination(),
                    match = entry.result.match.toBigDecimal(),
                    optimal = entry.result.optimal,
                    achieved = entry.result.achieved
                )
        val scored = restored.copy(prefilteredRequest = runCatching { restored.toTargetStats().needsItemPrefilter }.getOrDefault(false)).rescored()
        return entry.result.copy(match = scored.match.toDouble(), achieved = scored.achieved, optimal = scored.optimal)
    }

    /** Opens the import dialog, where a build exported via [exportBuild] is pasted. See [importBuild]. */
    fun requestImport() {
        ui = ui.copy(modal = Modal.ImportBuild)
    }

    /** Best-effort read of the system clipboard for the import dialog's "Paste" button. */
    fun clipboardText(): String = readClipboard()

    /** True when [rawJson] decodes to a valid exported build — gates the import dialog's confirm button. */
    fun canParseImport(rawJson: String): Boolean = rawJson.isNotBlank() && runCatching { historyJson.decodeFromString(HistoryEntry.serializer(), rawJson.trim()) }.isSuccess

    /**
     * Imports a build exported via [exportBuild]: parses the pasted [HistoryEntry] JSON, saves it as a
     * fresh library entry (new id + a name made unique, so it never overwrites an existing build), then
     * loads it into the workspace so it's visible at once. The denormalized result lets it display even
     * if its items left the catalog or the data version differs. Invalid JSON is a no-op (toast only).
     */
    fun importBuild(rawJson: String) {
        val parsed = runCatching { historyJson.decodeFromString(HistoryEntry.serializer(), rawJson.trim()) }.getOrNull()
        if (parsed == null) {
            ui = ui.copy(modal = null, toast = Tr.IMPORT_INVALID.value(ui.lang))
            return
        }
        val entry = parsed.copy(id = idGenerator(), name = uniqueLibraryName(parsed.name), createdAt = clock())
        ui = ui.copy(modal = null)
        scope.launch(ioDispatcher) {
            runCatching {
                historyRepository.save(entry)
                historyRepository.loadAll()
            }.onSuccess { all ->
                withContext(mainDispatcher) {
                    ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all))
                    loadBuild(entry.id)
                    ui = ui.copy(toast = Tr.TOAST_BUILD_IMPORTED.value(ui.lang))
                }
            }.onFailure { throwable ->
                throwable.printStackTrace()
                withContext(mainDispatcher) { ui = ui.copy(error = UiError(Tr.IMPORT_BUILD_FAILED.value(ui.lang))) }
            }
        }
    }

    /** A library name unique against existing builds: keeps [base] if free, else appends " (2)", " (3)"… */
    private fun uniqueLibraryName(base: String): String {
        val trimmed = base.trim().ifBlank { Tr.IMPORTED_BUILD_NAME.value(ui.lang) }
        val taken = ui.savedBuilds.map { it.name.trim().lowercase() }.toSet()
        return freeBuildName(trimmed, taken)
    }

    /** Clears the active-build identity (the workspace becomes an "unsaved build" again, unlocked). */
    fun clearActiveBuild() {
        ui = ui.copy(activeBuildId = null, activeBuildName = null, searchLocked = false)
    }

    /**
     * Starts a fresh, blank build: resets the whole workspace to defaults (request + result), drops
     * any active-build link, and unlocks search. Keeps the language, the "check optimality" switch and the
     * saved-build library. This is the explicit "New build" escape from editing a loaded build.
     */
    fun newBuild() {
        job?.cancel()
        cancelProof()
        ui = UiState(lang = ui.lang, verifyOptimality = ui.verifyOptimality, savedBuilds = ui.savedBuilds, screen = Screen.Builder)
    }

    /** Opens the Edit-build dialog (name + note + tags + folder). The dialog resolves the entry by id. */
    fun requestEdit(id: String) {
        ui = ui.copy(modal = Modal.EditBuild(id))
    }

    /**
     * Saves edited metadata for a saved build (name, note, tags, folder). This is also how a build
     * moves between folders and how a new folder is created (a folder exists iff a build references
     * it). Keeps names unique and updates the active-build name.
     */
    fun editBuild(
        id: String,
        newName: String,
        note: String?,
        tags: List<String>,
        folder: String?,
    ) {
        val trimmed = newName.trim()
        val entry = ui.savedBuilds.firstOrNull { it.id == id }
        if (trimmed.isBlank() || entry == null) {
            ui = ui.copy(modal = null)
            return
        }
        // Reject an edit that would collide with a *different* build's name, keeping names unique.
        val collides = ui.savedBuilds.any { it.id != id && it.name.trim().equals(trimmed, ignoreCase = true) }
        if (collides) {
            ui = ui.copy(modal = null, toast = Tr.SAVE_NAME_TAKEN.value(ui.lang))
            return
        }
        val normalizedTags = normalizeTags(tags)
        val edited =
            entry.copy(
                name = trimmed,
                note = note?.takeIf { it.isNotBlank() },
                tags = normalizedTags,
                folder = canonicalFolder(folder)
            )
        // Assigning a tag also registers it (so it persists even once removed from every build).
        registerTags(normalizedTags)
        ui = ui.copy(modal = null, activeBuildName = if (ui.activeBuildId == id) trimmed else ui.activeBuildName)
        scope.launch(ioDispatcher) {
            runCatching { historyRepository.save(edited) }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) {
                ui =
                    ui.copy(
                        savedBuilds = all,
                        knownTags = computeKnownTags(all),
                        libraryFolder = ui.libraryFolder.coercedTo(all),
                        librarySelectedTags = ui.librarySelectedTags.coercedToTags(all)
                    )
            }
        }
    }

    /**
     * Duplicates a saved build (#141): persists a brand-new library entry carrying the same request +
     * result but a fresh id and a unique "(copy)" name, leaving the original untouched. This lets the
     * user tweak the copy and compare it against the source without overwriting it. Stays on the
     * current screen; the copy lands at the top of the library (newest first). Written off the UI
     * thread, like every other history write.
     */
    fun duplicateBuild(id: String) {
        val source = ui.savedBuilds.firstOrNull { it.id == id } ?: return
        val copy = source.copy(id = idGenerator(), name = uniqueCopyName(source.name), createdAt = clock())
        scope.launch(ioDispatcher) {
            runCatching {
                historyRepository.save(copy)
                historyRepository.loadAll()
            }.onSuccess { all ->
                withContext(mainDispatcher) {
                    ui =
                        ui.copy(
                            savedBuilds = all,
                            lastDuplicatedBuildId = copy.id,
                            toast = Tr.TOAST_BUILD_DUPLICATED.value(ui.lang)
                        )
                    clearDuplicatedMarkerLater(copy.id)
                }
            }.onFailure { throwable ->
                throwable.printStackTrace()
                withContext(mainDispatcher) { ui = ui.copy(error = UiError(Tr.DUPLICATE_BUILD_FAILED.value(ui.lang))) }
            }
        }
    }

    /** Drops the just-duplicated highlight after a beat, so the cue fades on its own. */
    private fun clearDuplicatedMarkerLater(id: String) {
        scope.launch {
            delay(2200.milliseconds)
            withContext(mainDispatcher) {
                if (ui.lastDuplicatedBuildId == id) {
                    ui = ui.copy(lastDuplicatedBuildId = null)
                }
            }
        }
    }

    /**
     * A unique "<name> (copy)" — falling back to "(copy 2)", "(copy 3)", … when needed — so a
     * duplicate never collides with an existing build name. Names are kept unique so the library and
     * compare view stay unambiguous, mirroring the [editBuild]/[saveBuild] guards.
     */
    private fun uniqueCopyName(baseName: String): String {
        val suffix = Tr.DUPLICATE_SUFFIX.value(ui.lang)
        val base = baseName.trim()
        val taken = ui.savedBuilds.map { it.name.trim().lowercase() }.toSet()
        val first = "$base ($suffix)"
        if (first.lowercase() !in taken) return first
        var n = 2
        while ("$base ($suffix $n)".lowercase() in taken) n++
        return "$base ($suffix $n)"
    }

    /** Adds [tags] to the persisted registry (case-insensitively de-duped) and saves it. */
    private fun registerTags(tags: List<String>) {
        val merged = (tagRegistry + tags).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (merged.size != tagRegistry.size) {
            tagRegistry = merged
            libraryPreferences.saveTags(merged)
        }
    }

    // --- Folders (implicit: a folder exists iff ≥1 build references it) ---

    fun setLibraryFolderFilter(filter: LibraryFolderFilter) {
        ui = ui.copy(libraryFolder = filter)
    }

    fun requestRenameFolder(name: String) {
        ui = ui.copy(modal = Modal.RenameFolder(name))
    }

    fun requestDeleteFolder(name: String) {
        ui = ui.copy(modal = Modal.ConfirmDeleteFolder(name))
    }

    /**
     * Renames [oldName] to [newNameRaw] across every member. If another folder already matches
     * case-insensitively, this **merges** into that folder's canonical casing. No-op when blank or
     * unchanged. Runs as a single IO pass with one reload at the end.
     */
    fun renameFolder(
        oldName: String,
        newNameRaw: String,
    ) {
        val newName = newNameRaw.trim()
        if (newName.isBlank() || newName == oldName) {
            ui = ui.copy(modal = null)
            return
        }
        // Merge when the target name already exists (case-insensitively): adopt its canonical casing.
        val existingMatch = ui.savedBuilds.mapNotNull { it.folder }.firstOrNull { it.equals(newName, ignoreCase = true) && it != oldName }
        val canonical = existingMatch ?: newName
        val merged = existingMatch != null
        val members = ui.savedBuilds.filter { it.folder == oldName }
        ui =
            ui.copy(
                modal = null,
                toast = (if (merged) Tr.TOAST_FOLDERS_MERGED else Tr.TOAST_FOLDER_RENAMED).value(ui.lang),
                libraryFolder = if (ui.libraryFolder == LibraryFolderFilter.Named(oldName)) LibraryFolderFilter.Named(canonical) else ui.libraryFolder,
                activeBuildName = ui.activeBuildName
            )
        scope.launch(ioDispatcher) {
            members.forEach { runCatching { historyRepository.save(it.copy(folder = canonical)) } }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) { ui = ui.copy(savedBuilds = all, libraryFolder = ui.libraryFolder.coercedTo(all)) }
        }
    }

    /** Deletes [name] by unfiling its members (the builds themselves are kept). */
    fun deleteFolder(name: String) {
        val members = ui.savedBuilds.filter { it.folder == name }
        ui =
            ui.copy(
                modal = null,
                toast = Tr.TOAST_FOLDER_DELETED.value(ui.lang),
                libraryFolder = if (ui.libraryFolder == LibraryFolderFilter.Named(name)) LibraryFolderFilter.All else ui.libraryFolder
            )
        scope.launch(ioDispatcher) {
            members.forEach { runCatching { historyRepository.save(it.copy(folder = null)) } }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) { ui = ui.copy(savedBuilds = all, libraryFolder = ui.libraryFolder.coercedTo(all)) }
        }
    }

    /** If a Named filter points at a folder no build references anymore, fall back to All. */
    private fun LibraryFolderFilter.coercedTo(builds: List<me.chosante.common.history.HistoryEntry>): LibraryFolderFilter =
        if (this is LibraryFolderFilter.Named && builds.none { it.folder == name }) LibraryFolderFilter.All else this

    /**
     * Normalizes a folder name on assignment: blank → null, and a case-variant of an existing folder
     * adopts that folder's canonical casing (so picking "pvp" when "PvP" exists doesn't split them).
     */
    private fun canonicalFolder(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ui.savedBuilds.mapNotNull { it.folder }.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    // --- Tags (first-class: a registry of named tags, assignable to builds, persisted) ---

    fun requestCreateTag() {
        ui = ui.copy(modal = Modal.CreateTag)
    }

    fun requestRenameTag(name: String) {
        ui = ui.copy(modal = Modal.RenameTag(name))
    }

    fun requestDeleteTag(name: String) {
        ui = ui.copy(modal = Modal.ConfirmDeleteTag(name))
    }

    /** Creates a standalone tag in the registry (no build assignment). No-op on blank/duplicate. */
    fun createTag(nameRaw: String) {
        val name = nameRaw.trim()
        if (name.isBlank() || tagRegistry.any { it.equals(name, ignoreCase = true) }) {
            ui = ui.copy(modal = null)
            return
        }
        tagRegistry = tagRegistry + name
        libraryPreferences.saveTags(tagRegistry)
        ui = ui.copy(modal = null, knownTags = computeKnownTags(ui.savedBuilds))
    }

    /**
     * Renames tag [oldName] to [newNameRaw] across every build that carries it. If another tag already
     * matches case-insensitively, this **merges** into that tag's canonical casing (de-duped per build).
     * No-op when blank or unchanged. One IO pass, one reload.
     */
    fun renameTag(
        oldName: String,
        newNameRaw: String,
    ) {
        val newName = newNameRaw.trim()
        if (newName.isBlank() || newName.equals(oldName, ignoreCase = true)) {
            ui = ui.copy(modal = null)
            return
        }
        val existingMatch = tagRegistry.firstOrNull { it.equals(newName, ignoreCase = true) && !it.equals(oldName, ignoreCase = true) }
        val canonical = existingMatch ?: newName
        val merged = existingMatch != null
        // Update the registry: drop the old name, ensure the canonical one is present.
        tagRegistry = (tagRegistry.filterNot { it.equals(oldName, ignoreCase = true) } + canonical).distinctBy { it.lowercase() }
        libraryPreferences.saveTags(tagRegistry)
        val members = ui.savedBuilds.filter { entry -> entry.tags.any { it.equals(oldName, ignoreCase = true) } }
        ui =
            ui.copy(
                modal = null,
                toast = (if (merged) Tr.TOAST_TAGS_MERGED else Tr.TOAST_TAG_RENAMED).value(ui.lang),
                knownTags = computeKnownTags(ui.savedBuilds)
            )
        scope.launch(ioDispatcher) {
            members.forEach { entry ->
                val renamed = entry.tags.map { if (it.equals(oldName, ignoreCase = true)) canonical else it }
                runCatching { historyRepository.save(entry.copy(tags = normalizeTags(renamed))) }
            }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) {
                ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all), librarySelectedTags = ui.librarySelectedTags.coercedToTags(all))
            }
        }
    }

    /** Deletes tag [name] entirely: from the registry and from every build (the builds are kept). */
    fun deleteTag(name: String) {
        tagRegistry = tagRegistry.filterNot { it.equals(name, ignoreCase = true) }
        libraryPreferences.saveTags(tagRegistry)
        val members = ui.savedBuilds.filter { entry -> entry.tags.any { it.equals(name, ignoreCase = true) } }
        ui = ui.copy(modal = null, toast = Tr.TOAST_TAG_DELETED.value(ui.lang), knownTags = computeKnownTags(ui.savedBuilds))
        scope.launch(ioDispatcher) {
            members.forEach { entry ->
                runCatching { historyRepository.save(entry.copy(tags = entry.tags.filterNot { it.equals(name, ignoreCase = true) })) }
            }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) {
                ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all), librarySelectedTags = ui.librarySelectedTags.coercedToTags(all))
            }
        }
    }

    /**
     * Drops any active tag-filter key that no longer exists — neither carried by a build nor in the
     * registry. A renamed/deleted tag is cleared, but a still-valid standalone (0-build) tag the user
     * is filtering by is kept.
     */
    private fun Set<String>.coercedToTags(builds: List<me.chosante.common.history.HistoryEntry>): Set<String> {
        val present = (builds.flatMap { it.tags } + tagRegistry).map { it.lowercase() }.toSet()
        return this intersect present
    }

    fun requestDelete(
        id: String,
        name: String,
    ) {
        ui = ui.copy(modal = Modal.ConfirmDelete(id, name))
    }

    /** Opens the compare view with [id] pre-selected in the first column. */
    fun startCompare(id: String) {
        ui = ui.copy(screen = Screen.Compare, compareSlots = listOf(id, null), modal = null)
    }

    /** Pin build [id] into compare column [index] (no-op if the index is out of range). */
    fun setCompareSlot(
        index: Int,
        id: String,
    ) {
        if (index !in ui.compareSlots.indices) return
        ui = ui.copy(compareSlots = ui.compareSlots.mapIndexed { i, slot -> if (i == index) id else slot })
    }

    /**
     * The ✕ on compare column [index]: removes the column when more than the two base columns exist,
     * otherwise just empties it — so there are always at least [MIN_COMPARE_SLOTS] columns to compare.
     */
    fun clearCompareSlot(index: Int) {
        if (index !in ui.compareSlots.indices) return
        ui =
            if (ui.compareSlots.size > MIN_COMPARE_SLOTS) {
                ui.copy(compareSlots = ui.compareSlots.filterIndexed { i, _ -> i != index })
            } else {
                ui.copy(compareSlots = ui.compareSlots.mapIndexed { i, slot -> if (i == index) null else slot })
            }
    }

    /** Append an empty compare column, up to [MAX_COMPARE_SLOTS]. */
    fun addCompareSlot() {
        if (ui.compareSlots.size >= MAX_COMPARE_SLOTS) return
        ui = ui.copy(compareSlots = ui.compareSlots + null)
    }

    fun deleteBuild(id: String) {
        ui = ui.copy(modal = null)
        scope.launch(ioDispatcher) {
            runCatching { historyRepository.delete(id) }
            val all = historyRepository.loadAll()
            withContext(mainDispatcher) {
                val wasActive = ui.activeBuildId == id
                ui =
                    ui.copy(
                        savedBuilds = all,
                        activeBuildId = if (wasActive) null else ui.activeBuildId,
                        activeBuildName = if (wasActive) null else ui.activeBuildName,
                        searchLocked = if (wasActive) false else ui.searchLocked,
                        compareSlots = ui.compareSlots.map { if (it == id) null else it },
                        knownTags = computeKnownTags(all),
                        libraryFolder = ui.libraryFolder.coercedTo(all),
                        librarySelectedTags = ui.librarySelectedTags.coercedToTags(all)
                    )
            }
        }
    }
}
