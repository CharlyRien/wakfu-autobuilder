package me.chosante.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.against
import me.chosante.autobuilder.domain.againstAllElements
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.Character
import me.chosante.common.Characteristic
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.createZenithBuild
import me.chosante.ui.components.BreedAssets
import me.chosante.ui.components.IconPreloader
import me.chosante.ui.components.warmUpPaths
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.historyJson
import me.chosante.ui.history.normalizeTags
import me.chosante.ui.history.restoredClass
import me.chosante.ui.history.restoredMode
import me.chosante.ui.history.restoredScenario
import me.chosante.ui.history.suggestedBuildName
import me.chosante.ui.history.toBuildCombination
import me.chosante.ui.history.toExcludedChips
import me.chosante.ui.history.toForcedChips
import me.chosante.ui.history.toHistoryEntry
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

private typealias BuildFinder = (WakfuBestBuildParams) -> Flow<GeneticAlgorithmResult<BuildCombination>>
private typealias ZenithBuilder = suspend (ZenithInputParameters) -> String

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
    // A meaningful (non-zero) per-element resistance keeps its own value; a zero one is an inert
    // placeholder (e.g. the default wind=0) the global must override, so all four really get the value.
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

class BuildSearchModel(
    private val scope: CoroutineScope,
    private val buildFinder: BuildFinder = { WakfuBestBuildFinderAlgorithm.run(it) },
    private val zenithBuilder: ZenithBuilder = { it.createZenithBuild() },
    private val openBrowser: (String) -> Unit = { link -> Desktop.getDesktop().browse(URI(link)) },
    private val copyToClipboard: (String) -> Unit = { link -> Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null) },
    private val readClipboard: () -> String = {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull().orEmpty()
    },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Swing,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
) {
    var ui by androidx.compose.runtime.mutableStateOf(UiState())
        private set

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
        // Seed the persisted UI options (language + library view) + tag registry before any UI reads them.
        tagRegistry = libraryPreferences.loadTags()
        ui =
            ui.copy(
                lang = libraryPreferences.loadLang(),
                librarySort = libraryPreferences.loadSort(),
                libraryGroupByClass = libraryPreferences.loadGroupByClass()
            )

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
            scope.launch(Dispatchers.Default) {
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
                    withContext(mainDispatcher) {
                        warmupProgress = 1f
                        warmupEtaSeconds = null
                        isReady = true
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
     * Decodes item icons into the cache off the UI thread so they're ready (and decoded once) by the
     * time a build is shown. Purely background work: it never gates startup — items simply appear as
     * they decode, so we don't make the user wait on ~thousands of PNGs. First touch of
     * [WakfuBestBuildFinderAlgorithm.equipments] also pays its (lazy) JSON parse, here on a
     * background thread — never on the UI thread.
     */
    private fun startIconPreload() {
        scope.launch(Dispatchers.Default) {
            val paths = warmUpPaths(WakfuBestBuildFinderAlgorithm.equipments) + BreedAssets.warmUpPaths()
            IconPreloader.warmUp(scope, paths) { _, _ -> }
        }
    }

    fun setMode(mode: ScoreComputationMode) {
        val normalizedTargets =
            when (mode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                    ui.targets.map { target ->
                        if (target.characteristic.isMaximizableMastery()) {
                            target.copy(value = "1")
                        } else {
                            target
                        }
                    }
                // Max-damage maximizes the rotation's real damage directly, so the seeded AP/MP/range/HP/crit
                // rows would only act as hard power-6 constraints that can exclude higher-damage builds (e.g.
                // pinning AP=11 stops the solver finding the best AP breakpoint). Start CONSTRAINT-FREE; the user
                // can still add an explicit target row (an AP floor, a min HP…) if they want a more playable build.
                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> emptyList()
                else -> ui.targets
            }
        // Switching mode invalidates any completed result: a build/match/rotation found under the old mode
        // would be reinterpreted under the new mode's display rules. Clear it so the UI returns to Idle.
        ui =
            ui.copy(
                mode = mode,
                targets = normalizedTargets,
                phase = Phase.Idle,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                build = null,
                achieved = emptyMap(),
                spellRotation = null
            )
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
     * which also forces max-damage. Clears any stale result computed under the previous scenario.
     */
    fun pickBoss(monster: Monster) {
        ui =
            ui.copy(
                selectedBoss = monster,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                // Same as setMode: max-damage is constraint-free by default (seeded AP/MP/HP targets would only
                // hold the solver back from the highest-damage build vs this boss).
                targets = emptyList(),
                modal = null,
                phase = Phase.Idle,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                build = null,
                achieved = emptyMap(),
                spellRotation = null
            )
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
            ui = ui.copy(modal = null)
            return
        }
        statDefFor(characteristic)?.let { def ->
            ui =
                ui.copy(
                    targets = ui.targets + def.toRow(if (characteristic.isMaximizableMastery()) "1" else "0"),
                    modal = null
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
        ui =
            if (rarity in excluded) {
                ui.copy(excludedRarities = excluded - rarity)
            } else if (excluded.size < Rarity.entries.size - 1) {
                ui.copy(excludedRarities = excluded + rarity)
            } else {
                return
            }
    }

    fun setDuration(duration: String) {
        ui = ui.copy(duration = duration.onlyDigits().take(3))
    }

    fun setStopAtMatch(stopAtMatch: Boolean) {
        ui = ui.copy(stopAtMatch = stopAtMatch)
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

    fun addForcedSublimation(name: String) {
        if (name.isNotBlank() && name !in ui.forcedSublimations) ui = ui.copy(forcedSublimations = ui.forcedSublimations + name)
    }

    fun removeForcedSublimation(name: String) {
        ui = ui.copy(forcedSublimations = ui.forcedSublimations - name)
    }

    /**
     * Force the sublimation chosen from the [Modal.SublimationPicker] modal, then close it. The engine
     * matches sublimations by their **French** name, so that's the key we store (regardless of UI lang).
     */
    fun pickSublimation(sub: me.chosante.common.Sublimation) {
        addForcedSublimation(sub.name.fr)
        ui = ui.copy(modal = null)
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
        val name = passive.name ?: return
        val slots =
            me.chosante.autobuilder.domain.PassiveCatalog
                .slotsForLevel(ui.level)
        if (name !in ui.forcedPassives && ui.forcedPassives.size < slots) {
            ui = ui.copy(forcedPassives = ui.forcedPassives + name)
        }
        ui = ui.copy(modal = null)
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
        ui = ui.copy(modal = null)
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
            scope.launch(Dispatchers.Default) {
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
        job?.cancel()
        val snapshot = ui
        // Contradictory level bounds (min above the character level) match no normal item — the
        // engine keeps items with minLevel <= itemLevel <= level — so the solver would fall back to
        // the only level-agnostic slots (pets/mounts) and surface a near-empty nonsense build. Fail
        // fast with a clear, visible error instead of silently coercing the bounds and searching.
        if (snapshot.minLevel > snapshot.level) {
            ui = snapshot.copy(error = Tr.LEVEL_RANGE_INVALID.value(snapshot.lang))
            return
        }
        val character = Character(snapshot.clazz, snapshot.level, snapshot.minLevel)
        val targetStats = snapshot.toTargetStats()
        // A targeted boss overlays its per-element resistances onto the manual scenario (mirrors the CLI):
        // a forced element pins that one element, else all four are filled so the objective auto-picks.
        val damageScenario =
            when {
                snapshot.selectedBoss != null && snapshot.bossElement != null ->
                    snapshot.scenario.against(snapshot.selectedBoss, snapshot.bossElement)
                snapshot.selectedBoss != null -> snapshot.scenario.againstAllElements(snapshot.selectedBoss)
                else -> snapshot.scenario
            }
        val params =
            WakfuBestBuildParams(
                character = character,
                targetStats = targetStats,
                searchDuration = (snapshot.duration.toIntOrNull() ?: 20).coerceAtLeast(1).seconds,
                // "Stop at 100% match" only applies to precision mode (the only mode with an exact target);
                // ignore a stale toggle when searching in most-masteries / max-damage.
                stopWhenBuildMatch = snapshot.stopAtMatch && snapshot.mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                maxRarity = snapshot.maxRarity,
                excludedRarities = snapshot.excludedRarities,
                forcedItems = snapshot.forcedItems.map { it.matchName },
                excludedItems = snapshot.excludedItems.map { it.matchName },
                scoreComputationMode = snapshot.mode,
                useSublimations = snapshot.useSublimations,
                forcedSublimations = snapshot.forcedSublimations,
                forcedPassives = snapshot.forcedPassives,
                forcedRunesByItem = snapshot.forcedRunesByItem,
                damageScenario = damageScenario
            )

        ui =
            snapshot.copy(
                phase = Phase.Searching,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                build = null,
                achieved = emptyMap(),
                spellRotation = null,
                lastLandedEquipmentId = null,
                zenith = ZenithState.Idle,
                zenithUrl = null,
                toast = null,
                error = null
            )
        job =
            scope.launch(Dispatchers.Default) {
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
                    buildFinder(params)
                        .conflate()
                        .collect { result ->
                            hasResult = true
                            // Resolve the achieved per-stat grid with the SAME random-element assignment the scorer
                            // used, so the displayed values match the score: most-masteries → exact max-min,
                            // precision → exact max-capped, max-damage → greedy. Mirrors FindMostMasteriesFromInputScoring;
                            // omitting the mode would fall to the greedy `else` branch and diverge from the score.
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
                            val achieved =
                                computeCharacteristicsValues(
                                    buildCombination = result.individual,
                                    characterBaseCharacteristics = character.baseCharacteristicValues,
                                    masteryElementsWanted = targetStats.masteryElementsWanted,
                                    resistanceElementsWanted = targetStats.resistanceElementsWanted,
                                    scoreComputationMode = params.scoreComputationMode,
                                    masteryElementsToMinimize = masteryElementsToMinimize,
                                    resistanceElementsToMinimize = resistanceElementsToMinimize
                                )
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
                    withContext(mainDispatcher) {
                        if (ui.phase == Phase.Searching && hasResult) {
                            // Snap the time-based bar to a clean 100% on completion (the solver may
                            // have proven the optimum well before the wall-clock budget ran out).
                            ui = ui.copy(phase = Phase.Done, progress = 100, lastLandedEquipmentId = null)
                        } else if (ui.phase == Phase.Searching) {
                            ui =
                                ui.copy(
                                    phase = Phase.Idle,
                                    progress = 0,
                                    error = Tr.SEARCH_NO_RESULT.value(ui.lang),
                                    lastLandedEquipmentId = null
                                )
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (throwable: Throwable) {
                    // Catch Throwable (not just Exception): the solver can raise fatal Errors
                    // (e.g. native-access / linkage issues) that would otherwise crash the event
                    // thread with a masked coroutines error instead of surfacing here.
                    throwable.printStackTrace()
                    val message = throwable.message ?: throwable::class.qualifiedName ?: "Search failed"
                    withContext(mainDispatcher) {
                        ui = ui.copy(phase = Phase.Idle, error = message)
                    }
                } finally {
                    progressTicker.cancel()
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
        ui = ui.copy(phase = Phase.Idle, progress = 0)
    }

    fun openZenithBuild() {
        createZenithLink { link ->
            runCatching {
                openBrowser(link)
            }.onFailure { exception ->
                ui = ui.copy(zenith = ZenithState.Error, error = exception.message ?: "Unable to open Zenith")
            }
        }
    }

    fun copyZenithLink() {
        createZenithLink { link ->
            copyToClipboard(link)
            ui =
                ui.copy(
                    toast =
                        Tr.TOAST_ZENITH_COPIED.value(ui.lang)
                )
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
                dataVersion = dataVersion,
                tags = active?.tags ?: emptyList(),
                folder = active?.folder
            ) ?: return
        copyToClipboard(historyJson.encodeToString(HistoryEntry.serializer(), entry))
        ui = ui.copy(toast = Tr.TOAST_BUILD_EXPORTED.value(ui.lang))
    }

    private fun createZenithLink(onReady: (String) -> Unit) {
        val build = ui.build ?: return
        ui = ui.copy(zenith = ZenithState.Loading, error = null, toast = null)
        val character = Character(ui.clazz, ui.level, ui.minLevel).copy(characterSkills = build.characterSkills)
        scope.launch(Dispatchers.Default) {
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
                withContext(mainDispatcher) {
                    ui = ui.copy(zenith = ZenithState.Error, error = exception.message ?: "Zenith build failed")
                }
            }
        }
    }

    private fun UiState.toTargetStats(): TargetStats {
        val raw = targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0, it.weight) }
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

    fun goToScreen(screen: Screen) {
        ui = ui.copy(screen = screen)
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

    /** Default text for the save dialog's name field. */
    fun suggestedSaveName(): String = ui.activeBuildName ?: ui.suggestedBuildName()

    /**
     * Names already used by *other* saved builds (the active build's own name is excluded so updating
     * it isn't blocked). The save dialog rejects these so two builds never share a name — which would
     * make the library and the compare view ambiguous.
     */
    fun takenBuildNames(): Set<String> =
        ui.savedBuilds
            .filter { it.id != ui.activeBuildId }
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
                dataVersion = dataVersion,
                tags = existing?.tags ?: emptyList(),
                folder = existing?.folder
            ) ?: return
        // Note: saving does NOT lock search. The lock guards *revisiting* a build loaded from the
        // library (a deliberate act); right after saving you should stay free to keep iterating.
        ui = ui.copy(modal = null, activeBuildId = id, activeBuildName = trimmedName)
        scope.launch(ioDispatcher) {
            runCatching { historyRepository.save(entry) }
                .onSuccess {
                    val all = historyRepository.loadAll()
                    withContext(mainDispatcher) { ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all), toast = Tr.TOAST_BUILD_SAVED.value(ui.lang)) }
                }.onFailure { throwable ->
                    withContext(mainDispatcher) { ui = ui.copy(error = throwable.message ?: "Could not save build") }
                }
        }
    }

    /**
     * Loads a saved build into the workspace: restores its request (so it can be tweaked & re-run)
     * and its result (shown without re-running), marks it as the active build, and locks the search
     * button. Returns to the Builder screen.
     */
    fun loadBuild(id: String) {
        val entry = ui.savedBuilds.firstOrNull { it.id == id } ?: return
        job?.cancel()
        val loadedBuild = entry.toBuildCombination()
        // Recompute the spell rotation for a loaded max-damage build (else the Rotation card would show a
        // rotation left over from a prior search, or nothing). Cheap — no solver, just the rotation DP.
        val rotation =
            if (entry.restoredMode() == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                val character =
                    me.chosante.common.Character(entry.restoredClass(), entry.request.level, entry.request.minLevel, loadedBuild.characterSkills)
                SpellRotationOptimizer.bestSequencedRotation(loadedBuild, character, character.clazz, entry.restoredScenario())
            } else {
                null
            }
        ui =
            ui.copy(
                screen = Screen.Builder,
                modal = null,
                clazz = entry.restoredClass(),
                level = entry.request.level,
                minLevel = entry.request.minLevel,
                mode = entry.restoredMode(),
                scenario = entry.restoredScenario(),
                maxRarity = entry.request.maxRarity,
                duration = entry.request.duration,
                stopAtMatch = entry.request.stopAtMatch,
                targets = entry.toTargetRows(),
                forcedItems = entry.toForcedChips(),
                excludedItems = entry.toExcludedChips(),
                phase = Phase.Done,
                progress = 100,
                match = entry.result.match.toBigDecimal(),
                optimal = entry.result.optimal,
                build = loadedBuild,
                spellRotation = rotation,
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
            runCatching { historyRepository.save(entry) }
                .onSuccess {
                    val all = historyRepository.loadAll()
                    withContext(mainDispatcher) {
                        ui = ui.copy(savedBuilds = all, knownTags = computeKnownTags(all))
                        loadBuild(entry.id)
                        ui = ui.copy(toast = Tr.TOAST_BUILD_IMPORTED.value(ui.lang))
                    }
                }.onFailure { throwable ->
                    withContext(mainDispatcher) { ui = ui.copy(error = throwable.message ?: "Could not import build") }
                }
        }
    }

    /** A library name unique against existing builds: keeps [base] if free, else appends " (2)", " (3)"… */
    private fun uniqueLibraryName(base: String): String {
        val trimmed = base.trim().ifBlank { Tr.IMPORTED_BUILD_NAME.value(ui.lang) }
        val taken = ui.savedBuilds.map { it.name.trim().lowercase() }.toSet()
        if (trimmed.lowercase() !in taken) return trimmed
        var n = 2
        while ("$trimmed ($n)".lowercase() in taken) n++
        return "$trimmed ($n)"
    }

    /** Clears the active-build identity (the workspace becomes an "unsaved build" again, unlocked). */
    fun clearActiveBuild() {
        ui = ui.copy(activeBuildId = null, activeBuildName = null, searchLocked = false)
    }

    /**
     * Starts a fresh, blank build: resets the whole workspace to defaults (request + result), drops
     * any active-build link, and unlocks search. Keeps the language and the saved-build library.
     * This is the explicit "New build" escape from editing a loaded build.
     */
    fun newBuild() {
        job?.cancel()
        ui = UiState(lang = ui.lang, savedBuilds = ui.savedBuilds, screen = Screen.Builder)
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
            runCatching { historyRepository.save(copy) }
                .onSuccess {
                    val all = historyRepository.loadAll()
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
                    withContext(mainDispatcher) { ui = ui.copy(error = throwable.message ?: "Could not duplicate build") }
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

    /** Opens the compare view with [id] pre-selected as side A. */
    fun startCompare(id: String) {
        ui = ui.copy(screen = Screen.Compare, compareA = id, compareB = null, modal = null)
    }

    fun setCompareSlot(
        slot: CompareSlot,
        id: String,
    ) {
        ui =
            when (slot) {
                CompareSlot.A -> ui.copy(compareA = id)
                CompareSlot.B -> ui.copy(compareB = id)
            }
    }

    fun clearCompareSlot(slot: CompareSlot) {
        ui =
            when (slot) {
                CompareSlot.A -> ui.copy(compareA = null)
                CompareSlot.B -> ui.copy(compareB = null)
            }
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
                        compareA = if (ui.compareA == id) null else ui.compareA,
                        compareB = if (ui.compareB == id) null else ui.compareB,
                        knownTags = computeKnownTags(all),
                        libraryFolder = ui.libraryFolder.coercedTo(all),
                        librarySelectedTags = ui.librarySelectedTags.coercedToTags(all)
                    )
            }
        }
    }
}
