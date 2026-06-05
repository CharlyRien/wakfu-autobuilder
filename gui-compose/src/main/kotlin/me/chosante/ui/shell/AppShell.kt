package me.chosante.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.ui.components.ModalHost
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.paperdoll.PaperdollPanel
import me.chosante.ui.request.RequestPanel
import me.chosante.ui.state.BuildSearchModel
import me.chosante.ui.state.Modal
import me.chosante.ui.state.Phase
import me.chosante.ui.state.PickerMode
import me.chosante.ui.state.statCatalog
import me.chosante.ui.stats.StatsPanel
import me.chosante.ui.theme.WColor

@Composable
fun AppShell(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val model = remember { BuildSearchModel(scope) }
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

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLang provides ui.lang) {
            Column(
                modifier = Modifier.fillMaxSize().background(WColor.bg)
            ) {
                TopBar(
                    ui = ui,
                    onSearch = model::search,
                    onCancel = model::cancel,
                    onClassChange = model::setClass,
                    onLevelChange = model::setLevel,
                    onMinLevelChange = model::setMinLevel,
                    onLangChange = model::setLang
                )
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(WColor.hairline)
                ) {
                    ShellColumn(
                        title = tr(Tr.ZONE_REQUEST),
                        hint = tr(Tr.ZONE_REQUEST_HINT),
                        modifier = Modifier.width(320.dp)
                    ) {
                        RequestPanel(
                            ui = ui,
                            onModeChange = model::setMode,
                            onTargetValueChange = model::updateTargetValue,
                            onRemoveTarget = model::removeTarget,
                            onAddTarget = { model.openModal(Modal.AddStat) },
                            onToggleMastery = model::toggleMaximizedMastery,
                            onMaxRarityChange = model::setMaxRarity,
                            onDurationChange = model::setDuration,
                            onStopAtMatchChange = model::setStopAtMatch,
                            onSolverChange = model::setSolver,
                            onAddForcedItem = { model.openModal(Modal.ItemPicker(PickerMode.Forced)) },
                            onRemoveForcedItem = model::removeForcedItem,
                            onAddExcludedItem = { model.openModal(Modal.ItemPicker(PickerMode.Excluded)) },
                            onRemoveExcludedItem = model::removeExcludedItem
                        )
                    }
                    VerticalSeparator()
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
                        PaperdollPanel(ui = ui)
                    }
                    VerticalSeparator()
                    ShellColumn(
                        title = tr(Tr.ZONE_STATS),
                        hint = tr(Tr.ZONE_STATS_HINT),
                        modifier = Modifier.width(360.dp)
                    ) {
                        StatsPanel(
                            ui = ui,
                            onOpenZenith = model::openZenithBuild,
                            onCopyZenith = model::copyZenithLink
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
                onDismiss = model::closeModal
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

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier.width(1.dp).fillMaxHeight().background(WColor.hairline)
    )
}
