package me.chosante.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.ComputeBudget
import me.chosante.common.WakfuData
import me.chosante.ui.components.Hairline
import me.chosante.ui.components.Toggle
import me.chosante.ui.components.VerticalScrollHints
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.shell.LangToggle
import me.chosante.ui.state.ComputeSettings
import me.chosante.ui.state.ProcessorUse
import me.chosante.ui.state.UiState
import me.chosante.ui.state.WhatsNew
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WTypography
import kotlin.math.roundToInt

internal const val VERIFY_OPTIMALITY_TOGGLE_TAG = "verify-optimality-toggle"

/** A shell screen leaves room for explanations and translations while the engine keeps running. */
@Composable
fun SettingsScreen(
    ui: UiState,
    onBack: () -> Unit,
    onProcessorUse: (ProcessorUse) -> Unit,
    onCustomCores: (Int) -> Unit,
    onVerifyOptimality: (Boolean) -> Unit,
    onLang: (Lang) -> Unit,
    onHideChosen: (Boolean) -> Unit,
    onReportBug: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    availableCores: Int = ComputeBudget.availableCores,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().background(WColor.bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(WDimens.pad),
            verticalArrangement = Arrangement.spacedBy(WDimens.gap)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr(Tr.SETTINGS), style = WTypography.headlineLarge)
                SettingsAction(tr(Tr.BACK), "settings-back", onBack)
            }
            SettingsCard(Tr.SETTINGS_COMPUTING, "settings-computing") {
                Text(tr(Tr.SETTINGS_PROCESSOR_USE), style = WTypography.bodyLarge)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(WColor.bg)
                        .border(1.dp, WColor.border, RoundedCornerShape(10.dp))
                        .padding(4.dp)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProcessorUse.entries.forEach { preset ->
                        val selected = preset == ui.computeSettings.preset
                        val title = tr(preset.titleKey())
                        Box(
                            Modifier
                                .weight(1f)
                                .testTag("processor-${preset.name}")
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) WColor.raised else Color.Transparent)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onProcessorUse(preset) })
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, style = WTypography.labelMedium.copy(color = if (selected) WColor.text else WColor.muted))
                        }
                    }
                }
                Text(tr(Tr.SETTINGS_MACHINE_CORES).format(availableCores), style = WTypography.bodyMedium)
                Text(
                    tr(Tr.SETTINGS_PRESET_CORES).format(
                        availableCores,
                        ComputeSettings(ProcessorUse.BALANCED).cores(availableCores),
                        ComputeSettings(ProcessorUse.LOW).cores(availableCores)
                    ),
                    style = WTypography.bodySmall
                )
                if (ui.computeSettings.preset == ProcessorUse.CUSTOM) {
                    val count = ui.computeSettings.cores(availableCores)
                    val label = tr(Tr.SETTINGS_USED_CORES).format(count)
                    Text(label, style = WTypography.bodyMedium, modifier = Modifier.testTag("settings-core-count"))
                    Slider(
                        value = count.toFloat(),
                        onValueChange = { onCustomCores(it.roundToInt().coerceIn(1, availableCores)) },
                        valueRange = 1f..availableCores.toFloat(),
                        steps = (availableCores - 2).coerceAtLeast(0),
                        enabled = availableCores > 1,
                        colors = SliderDefaults.colors(thumbColor = WColor.accent2, activeTrackColor = WColor.accent2, inactiveTrackColor = WColor.raised),
                        modifier = Modifier.fillMaxWidth().testTag("settings-core-slider").semantics { contentDescription = label }
                    )
                }
                Text(tr(Tr.SETTINGS_CPU_TRADEOFF), style = WTypography.bodySmall)
                Text(tr(Tr.SETTINGS_CPU_EFFECT), style = WTypography.bodySmall)
                Hairline()
                SettingRow(tr(Tr.VERIFY_OPTIMALITY), tr(Tr.VERIFY_OPTIMALITY_SUB)) {
                    Toggle(ui.verifyOptimality, onVerifyOptimality, Modifier.testTag(VERIFY_OPTIMALITY_TOGGLE_TAG))
                }
            }
            SettingsCard(Tr.SETTINGS_INTERFACE, "settings-interface") {
                SettingRow(tr(Tr.SETTINGS_LANGUAGE)) {
                    LangToggle(ui.lang, onLang, tagPrefix = "settings-language")
                }
                Hairline()
                SettingRow(tr(Tr.SETTINGS_HIDE_CHOSEN)) {
                    Toggle(ui.pickerHideChosen, onHideChosen, Modifier.testTag("settings-hide-chosen"))
                }
            }
            SettingsCard(Tr.SETTINGS_ABOUT, "settings-about") {
                SettingRow(tr(Tr.SETTINGS_APP_VERSION)) { Text(WhatsNew.appVersion ?: "—", style = WTypography.bodyMedium) }
                SettingRow(tr(Tr.SETTINGS_DATA_VERSION)) { Text(WakfuData.VERSION, style = WTypography.bodyMedium) }
                Hairline()
                if (ui.error?.message == tr(Tr.SETTINGS_BROWSER_FAILED)) {
                    Text(ui.error.message, style = WTypography.bodySmall.copy(color = WColor.danger))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(WDimens.gap), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction(tr(Tr.SETTINGS_REPORT_BUG), "settings-report-bug", onReportBug)
                    SettingsAction(tr(Tr.SETTINGS_RESET), "settings-reset", onReset)
                }
            }
        }
        VerticalScrollHints(scroll)
    }
}

internal fun ProcessorUse.titleKey(): Tr =
    when (this) {
        ProcessorUse.MAXIMUM -> Tr.SETTINGS_MAXIMUM
        ProcessorUse.BALANCED -> Tr.SETTINGS_BALANCED
        ProcessorUse.LOW -> Tr.SETTINGS_LOW
        ProcessorUse.CUSTOM -> Tr.SETTINGS_CUSTOM
    }

@Composable
private fun SettingsCard(
    title: Tr,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clip(RoundedCornerShape(WDimens.radius))
            .background(WColor.surface)
            .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
            .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(tr(title), style = WTypography.headlineMedium)
        content()
    }
}

@Composable
private fun SettingRow(
    label: String,
    explanation: String? = null,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WDimens.gap)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = WTypography.bodyLarge)
            if (explanation != null) Text(explanation, style = WTypography.bodySmall)
        }
        content()
    }
}

@Composable
private fun SettingsAction(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = WTypography.labelMedium.copy(color = WColor.accent2),
        modifier =
            Modifier
                .testTag(tag)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp)
    )
}
