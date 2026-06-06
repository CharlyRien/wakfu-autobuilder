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
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.Character
import me.chosante.common.Rarity
import me.chosante.createZenithBuild
import me.chosante.ui.components.IconPreloader
import me.chosante.ui.components.warmUpPaths
import me.chosante.ui.i18n.Tr
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private typealias BuildFinder = (WakfuBestBuildParams) -> Flow<GeneticAlgorithmResult<BuildCombination>>
private typealias ZenithBuilder = suspend (ZenithInputParameters) -> String

class BuildSearchModel(
    private val scope: CoroutineScope,
    private val buildFinder: BuildFinder = { WakfuBestBuildFinderAlgorithm.run(it) },
    private val zenithBuilder: ZenithBuilder = { it.createZenithBuild() },
    private val openBrowser: (String) -> Unit = { link -> Desktop.getDesktop().browse(URI(link)) },
    private val copyToClipboard: (String) -> Unit = { link -> Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null) },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Swing,
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

    /** Estimated warm-up progress (0..1) for the loading screen. See [WarmupTiming]. */
    var warmupProgress by androidx.compose.runtime.mutableStateOf(0f)
        private set

    /** Estimated seconds left on the warm-up, or `null` once the estimate is exhausted/done. */
    var warmupEtaSeconds by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    private var job: Job? = null

    /** Mirrors [me.chosante.ui.SCREENSHOT_PATH_PROPERTY] / `WAKFU_COMPOSE_SCREENSHOT` (see Main.kt). */
    private val isScreenshotMode =
        System.getProperty("wakfu.compose.screenshot") != null ||
            System.getenv("WAKFU_COMPOSE_SCREENSHOT") != null

    init {
        // Decode item icons into the cache off the UI thread so they're ready (and decoded once)
        // by the time a build is shown. Purely background work: it never gates startup — items
        // simply appear as they decode, so we don't make the user wait on ~thousands of PNGs.
        scope.launch(Dispatchers.Default) {
            val paths = warmUpPaths(WakfuBestBuildFinderAlgorithm.equipments)
            IconPreloader.warmUp(scope, paths) { _, _ -> }
        }

        if (isScreenshotMode) {
            // Screenshots want the real UI immediately, with no warm-up gating.
            isReady = true
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
                delay(200.milliseconds)
                try {
                    WakfuBuildSolver.warmUp()
                    WarmupTiming.record(System.currentTimeMillis() - start)
                } finally {
                    // Always reveal the UI: a warm-up failure should degrade to a cold first search,
                    // never leave the app stuck on the loading screen.
                    ticker.cancel()
                    withContext(mainDispatcher) {
                        warmupProgress = 1f
                        warmupEtaSeconds = null
                        isReady = true
                    }
                }
            }
        }
    }

    fun setMode(mode: ScoreComputationMode) {
        val normalizedTargets =
            if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                ui.targets.map { target ->
                    if (target.characteristic.isMaximizableMastery()) {
                        target.copy(value = "1")
                    } else {
                        target
                    }
                }
            } else {
                ui.targets
            }
        ui = ui.copy(mode = mode, targets = normalizedTargets)
    }

    fun setSolver(solver: WakfuSolver) {
        ui = ui.copy(solver = solver)
    }

    fun setLang(lang: me.chosante.ui.i18n.Lang) {
        ui = ui.copy(lang = lang)
    }

    fun setClass(clazz: me.chosante.common.CharacterClass) {
        ui = ui.copy(clazz = clazz)
    }

    fun setLevel(level: String) {
        val parsed =
            level
                .onlyDigits()
                .take(3)
                .toIntOrNull()
                ?.coerceIn(1, 245) ?: return
        ui = ui.copy(level = parsed)
    }

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

    fun removeTarget(id: String) {
        ui = ui.copy(targets = ui.targets.filterNot { it.id == id })
    }

    fun addTarget(characteristic: me.chosante.common.Characteristic) {
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

    fun toggleMaximizedMastery(characteristic: me.chosante.common.Characteristic) {
        if (!characteristic.isMaximizableMastery()) {
            return
        }
        val existing = ui.targets.firstOrNull { it.characteristic == characteristic }
        ui =
            if (existing == null) {
                val row = statDefFor(characteristic)?.toRow("1") ?: return
                ui.copy(targets = ui.targets + row)
            } else {
                ui.copy(targets = ui.targets.filterNot { it.characteristic == characteristic })
            }
    }

    fun setMaxRarity(rarity: Rarity) {
        ui = ui.copy(maxRarity = rarity)
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

    /**
     * Force this exact item into the next searched build. Driven by the center paperdoll's per-slot
     * action; mirrors [pickItem]'s dedup but is independent of the picker modal and, like the modal,
     * does **not** re-run the search.
     */
    fun forceItem(equipment: me.chosante.common.Equipment) {
        val chip = equipment.toChip()
        if (ui.forcedItems.none { it.matchName == chip.matchName }) {
            ui = ui.copy(forcedItems = ui.forcedItems + chip)
        }
    }

    /** Exclude this exact item from the next search. Paperdoll counterpart to [forceItem]. */
    fun excludeItem(equipment: me.chosante.common.Equipment) {
        val chip = equipment.toChip()
        if (ui.excludedItems.none { it.matchName == chip.matchName }) {
            ui = ui.copy(excludedItems = ui.excludedItems + chip)
        }
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
            PickerMode.Forced ->
                if (ui.forcedItems.none { it.matchName == chip.matchName }) {
                    ui = ui.copy(forcedItems = ui.forcedItems + chip, modal = null)
                } else {
                    ui = ui.copy(modal = null)
                }

            PickerMode.Excluded ->
                if (ui.excludedItems.none { it.matchName == chip.matchName }) {
                    ui = ui.copy(excludedItems = ui.excludedItems + chip, modal = null)
                } else {
                    ui = ui.copy(modal = null)
                }

            null -> ui = ui.copy(modal = null)
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
        val effectiveMinLevel = snapshot.minLevel.coerceAtMost(snapshot.level)
        val character = Character(snapshot.clazz, snapshot.level, effectiveMinLevel)
        val targetStats = snapshot.toTargetStats()
        val params =
            WakfuBestBuildParams(
                character = character,
                targetStats = targetStats,
                searchDuration = (snapshot.duration.toIntOrNull() ?: 20).coerceAtLeast(1).seconds,
                stopWhenBuildMatch = snapshot.stopAtMatch,
                maxRarity = snapshot.maxRarity,
                forcedItems = snapshot.forcedItems.map { it.matchName },
                excludedItems = snapshot.excludedItems.map { it.matchName },
                scoreComputationMode = snapshot.mode,
                solver = snapshot.solver
            )

        ui =
            snapshot.copy(
                phase = Phase.Searching,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
                optimal = false,
                build = null,
                achieved = emptyMap(),
                lastLandedEquipmentId = null,
                zenith = ZenithState.Idle,
                zenithUrl = null,
                toast = null,
                error = null
            )
        job =
            scope.launch(Dispatchers.Default) {
                try {
                    var hasResult = false
                    buildFinder(params)
                        .conflate()
                        .collect { result ->
                            hasResult = true
                            val achieved =
                                computeCharacteristicsValues(
                                    buildCombination = result.individual,
                                    characterBaseCharacteristics = character.baseCharacteristicValues,
                                    masteryElementsWanted = targetStats.masteryElementsWanted,
                                    resistanceElementsWanted = targetStats.resistanceElementsWanted
                                )
                            withContext(mainDispatcher) {
                                val landedEquipmentId = newlyLandedEquipmentId(ui.build, result.individual)
                                ui =
                                    ui.copy(
                                        progress = result.progressPercentage,
                                        match = result.matchPercentage,
                                        optimal = result.isOptimal,
                                        build = result.individual,
                                        achieved = achieved,
                                        lastLandedEquipmentId = landedEquipmentId ?: ui.lastLandedEquipmentId
                                    )
                                if (landedEquipmentId != null) {
                                    clearLandedMarkerLater(landedEquipmentId)
                                }
                            }
                        }
                    withContext(mainDispatcher) {
                        if (ui.phase == Phase.Searching && hasResult) {
                            ui = ui.copy(phase = Phase.Done, lastLandedEquipmentId = null)
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

    fun clearToast() {
        ui = ui.copy(toast = null)
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
                            equipments = build.equipments
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

    private fun UiState.toTargetStats(): TargetStats = TargetStats(targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0) })

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
}
