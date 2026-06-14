package me.chosante.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.ui.components.ModalHost
import me.chosante.ui.history.CompareScreen
import me.chosante.ui.history.LibraryScreen
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.paperdoll.PaperdollPanel
import me.chosante.ui.request.RequestPanel
import me.chosante.ui.state.BuildSearchModel
import me.chosante.ui.state.Modal
import me.chosante.ui.state.Phase
import me.chosante.ui.state.PickerMode
import me.chosante.ui.state.Screen
import me.chosante.ui.state.folderCounts
import me.chosante.ui.state.statCatalog
import me.chosante.ui.stats.StatsPanel
import me.chosante.ui.theme.WColor
import java.awt.Cursor

@Composable
fun AppShell(
    model: BuildSearchModel,
    modifier: Modifier = Modifier,
) {
    val ui = model.ui
    val addStatExcludedCharacteristics =
        ui.targets.map { it.characteristic }.toSet() +
            if (ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                statCatalog
                    .map { it.characteristic }
                    .filter { it.isMaximizableMastery() }
                    .toSet()
            } else {
                emptySet()
            }

    // User-resizable side columns (#128). Kept at AppShell scope so the chosen widths survive
    // navigating to the library/compare screens and back; the middle build column takes the rest.
    var requestWidth by remember {
        mutableStateOf(
            me.chosante.ui.testing
                .screenshotInitialDp("request_width", 320.dp)
        )
    }
    var statsWidth by remember {
        mutableStateOf(
            me.chosante.ui.testing
                .screenshotInitialDp("stats_width", 360.dp)
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLang provides ui.lang) {
            Column(
                modifier = Modifier.fillMaxSize().background(WColor.bg)
            ) {
                TopBar(
                    ui = ui,
                    onSearch = model::onSearchPressed,
                    onCancel = model::cancel,
                    onClassChange = model::setClass,
                    onLevelChange = model::setLevel,
                    onMinLevelChange = model::setMinLevel,
                    onLangChange = model::setLang,
                    onNavigate = model::goToScreen,
                    onNewBuild = model::newBuild,
                    onDetachActiveBuild = model::clearActiveBuild
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when (ui.screen) {
                        Screen.Builder ->
                            BuilderBody(
                                model = model,
                                requestWidth = requestWidth,
                                statsWidth = statsWidth,
                                onRequestWidthDelta = { requestWidth = (requestWidth + it).coerceIn(260.dp, 440.dp) },
                                onStatsWidthDelta = { statsWidth = (statsWidth - it).coerceIn(300.dp, 460.dp) }
                            )
                        Screen.Library ->
                            LibraryScreen(
                                ui = ui,
                                onImport = model::requestImport,
                                onLoad = model::loadBuild,
                                onCompare = model::startCompare,
                                onDuplicate = model::duplicateBuild,
                                onEdit = model::requestEdit,
                                onDelete = model::requestDelete,
                                onToggleTag = model::toggleLibraryTag,
                                onCreateTag = model::requestCreateTag,
                                onRenameTag = model::requestRenameTag,
                                onDeleteTag = model::requestDeleteTag,
                                onFolderFilterChange = model::setLibraryFolderFilter,
                                onRenameFolder = model::requestRenameFolder,
                                onDeleteFolder = model::requestDeleteFolder,
                                onGoBuilder = { model.goToScreen(Screen.Builder) },
                                onSearchChange = model::setLibrarySearch,
                                onSortChange = model::setLibrarySort,
                                onClassFilterChange = model::setLibraryClassFilter,
                                onToggleGroup = model::toggleLibraryGroupByClass,
                                onClearFilters = model::clearLibraryFilters
                            )

                        Screen.Compare ->
                            CompareScreen(
                                ui = ui,
                                onPick = model::setCompareSlot,
                                onClearSlot = model::clearCompareSlot,
                                onBack = { model.goToScreen(Screen.Library) }
                            )
                    }
                }
            }

            ModalHost(
                modal = ui.modal,
                excludedCharacteristics = addStatExcludedCharacteristics,
                equipmentCatalog = model.equipmentCatalog,
                onSelectStat = model::addTarget,
                onPickItem = model::pickItem,
                onDismiss = model::closeModal,
                suggestedSaveName = model.suggestedSaveName(),
                isEditingExisting = ui.activeBuildId != null,
                // Save dialog excludes the *active* build's name; the Edit dialog must exclude the
                // *edited* build's name (it may differ from the active build) so its inline
                // duplicate-name warning matches what editBuild() will actually accept.
                takenNames =
                    (ui.modal as? Modal.EditBuild)?.let { m ->
                        ui.savedBuilds
                            .filter { it.id != m.id }
                            .map { it.name.trim().lowercase() }
                            .toSet()
                    } ?: model.takenBuildNames(),
                editingEntry = (ui.modal as? Modal.EditBuild)?.let { m -> ui.savedBuilds.firstOrNull { it.id == m.id } },
                existingFolders = folderCounts(ui.savedBuilds).map { it.first },
                existingTags = ui.knownTags,
                onSaveBuild = model::saveBuild,
                onEditBuild = model::editBuild,
                onDeleteBuild = model::deleteBuild,
                onRenameFolder = model::renameFolder,
                onDeleteFolder = model::deleteFolder,
                onCreateTag = model::createTag,
                onRenameTag = model::renameTag,
                onDeleteTag = model::deleteTag,
                onConfirmReSearch = model::confirmReSearch,
                onImportBuild = model::importBuild,
                validateImport = model::canParseImport,
                onClipboardText = model::clipboardText
            )
        }
    }
}

@Composable
private fun BuilderBody(
    model: BuildSearchModel,
    requestWidth: Dp,
    statsWidth: Dp,
    onRequestWidthDelta: (Dp) -> Unit,
    onStatsWidthDelta: (Dp) -> Unit,
) {
    val ui = model.ui
    Row(
        modifier = Modifier.fillMaxSize().background(WColor.hairline)
    ) {
        ShellColumn(
            title = tr(Tr.ZONE_REQUEST),
            hint = tr(Tr.ZONE_REQUEST_HINT),
            modifier = Modifier.width(requestWidth)
        ) {
            RequestPanel(
                ui = ui,
                onModeChange = model::setMode,
                onTargetValueChange = model::updateTargetValue,
                onTargetWeightChange = model::updateTargetWeight,
                onRemoveTarget = model::removeTarget,
                onAddTarget = { model.openModal(Modal.AddStat) },
                onToggleMastery = model::toggleMaximizedMastery,
                onToggleRarity = model::toggleRarity,
                onDurationChange = model::setDuration,
                onStopAtMatchChange = model::setStopAtMatch,
                onAddForcedItem = { model.openModal(Modal.ItemPicker(PickerMode.Forced)) },
                onRemoveForcedItem = model::removeForcedItem,
                onAddExcludedItem = { model.openModal(Modal.ItemPicker(PickerMode.Excluded)) },
                onRemoveExcludedItem = model::removeExcludedItem
            )
        }
        ResizableSeparator(onDelta = onRequestWidthDelta)
        ShellColumn(
            title = tr(Tr.ZONE_BUILD),
            hint =
                when (ui.phase) {
                    Phase.Idle -> tr(Tr.ZONE_BUILD_IDLE)
                    Phase.Searching -> tr(Tr.ZONE_BUILD_SEARCHING)
                    Phase.Done -> tr(Tr.ZONE_BUILD_DONE)
                },
            modifier = Modifier.weight(1f)
        ) {
            PaperdollPanel(
                ui = ui,
                onForceItem = model::forceItem,
                onExcludeItem = model::excludeItem
            )
        }
        ResizableSeparator(onDelta = onStatsWidthDelta)
        ShellColumn(
            title = tr(Tr.ZONE_STATS),
            hint = tr(Tr.ZONE_STATS_HINT),
            modifier = Modifier.width(statsWidth)
        ) {
            StatsPanel(
                ui = ui,
                onOpenZenith = model::openZenithBuild,
                onCopyZenith = model::copyZenithLink,
                onSaveBuild = model::requestSaveBuild,
                onExport = model::exportBuild
            )
        }
    }
}

@Composable
private fun ShellColumn(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxHeight().background(WColor.bg)
    ) {
        ZoneHeader(title = title, hint = hint)
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
}

/**
 * Draggable column divider (#128). Renders as the same hairline rule as before but inside a wider,
 * invisible drag hit area with a horizontal-resize cursor; dragging reports the delta (as [Dp]) to
 * [onDelta], which the caller applies to the adjacent column width.
 */
@Composable
private fun ResizableSeparator(onDelta: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier =
            Modifier
                .width(7.dp)
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        onDelta(with(density) { dragAmount.toDp() })
                    }
                },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(WColor.hairline))
    }
}
