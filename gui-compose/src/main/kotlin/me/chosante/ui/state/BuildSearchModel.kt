package me.chosante.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
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
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.Rarity
import me.chosante.createZenithBuild

private typealias BuildFinder = suspend (WakfuBestBuildParams) -> Flow<GeneticAlgorithmResult<BuildCombination>>
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

    private var job: Job? = null

    fun setMode(mode: ScoreComputationMode) {
        ui = ui.copy(mode = mode)
    }

    fun setLang(lang: me.chosante.ui.i18n.Lang) {
        ui = ui.copy(lang = lang)
    }

    fun setClass(clazz: me.chosante.common.CharacterClass) {
        ui = ui.copy(clazz = clazz)
    }

    fun setLevel(level: String) {
        val parsed = level.onlyDigits().take(3).toIntOrNull()?.coerceIn(1, 245) ?: return
        ui = ui.copy(level = parsed, minLevel = ui.minLevel.coerceAtMost(parsed))
    }

    fun setMinLevel(minLevel: String) {
        val parsed = minLevel.onlyDigits().take(3).toIntOrNull()?.coerceIn(0, ui.level) ?: return
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
            ui = ui.copy(targets = ui.targets + def.toRow("0"), modal = null)
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
        val character = Character(snapshot.clazz, snapshot.level, snapshot.minLevel)
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
                scoreComputationMode = snapshot.mode
            )

        ui =
            snapshot.copy(
                phase = Phase.Searching,
                progress = 0,
                match = java.math.BigDecimal.ZERO,
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
                    buildFinder(params)
                        .conflate()
                        .collect { result ->
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
                        if (ui.phase == Phase.Searching) {
                            ui = ui.copy(phase = Phase.Done, lastLandedEquipmentId = null)
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    withContext(mainDispatcher) {
                        ui = ui.copy(phase = Phase.Idle, error = exception.message ?: "Search failed")
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
            ui = ui.copy(toast = me.chosante.ui.i18n.Tr.TOAST_ZENITH_COPIED.value(ui.lang))
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
                    ui = ui.copy(zenith = ZenithState.Ready, zenithUrl = link, toast = me.chosante.ui.i18n.Tr.TOAST_ZENITH_READY.value(ui.lang))
                    onReady(link)
                }
            } catch (exception: Exception) {
                withContext(mainDispatcher) {
                    ui = ui.copy(zenith = ZenithState.Error, error = exception.message ?: "Zenith build failed")
                }
            }
        }
    }

    private fun UiState.toTargetStats(): TargetStats =
        TargetStats(targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0) })

    private fun newlyLandedEquipmentId(
        previous: BuildCombination?,
        next: BuildCombination,
    ): Int? {
        val previousIds = previous?.equipments?.map { it.equipmentId }?.toSet() ?: emptySet()
        return next.equipments.firstOrNull { it.equipmentId !in previousIds }?.equipmentId
    }

    private fun clearLandedMarkerLater(equipmentId: Int) {
        scope.launch {
            delay(560)
            withContext(mainDispatcher) {
                if (ui.lastLandedEquipmentId == equipmentId) {
                    ui = ui.copy(lastLandedEquipmentId = null)
                }
            }
        }
    }
}
