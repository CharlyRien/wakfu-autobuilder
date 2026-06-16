package me.chosante.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.Characteristic
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.components.BreedIcon
import me.chosante.ui.components.CharacteristicIcon
import me.chosante.ui.components.ItemThumbnail
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.CompareSlot
import me.chosante.ui.state.UiState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.state.isEngineInternalStat
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/**
 * Side-by-side comparison of two saved builds, A | B. Each side is picked from the library; the
 * table below shows every stat where they differ with a per-row "best" marker. Cheap to render:
 * each side reads its stored `achieved` map (the same data the live Stats panel shows) — no engine
 * recomputation.
 */
@Composable
fun CompareScreen(
    ui: UiState,
    onPick: (CompareSlot, String) -> Unit,
    onClearSlot: (CompareSlot) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryA = ui.savedBuilds.firstOrNull { it.id == ui.compareA }
    val entryB = ui.savedBuilds.firstOrNull { it.id == ui.compareB }
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(WColor.bg)
                .verticalScroll(scroll)
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(WDimens.gap)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack = onBack)
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = tr(Tr.COMPARE_TITLE), style = WTypography.headlineLarge.copy(fontWeight = FontWeight.Bold))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WDimens.gap)) {
            SideColumn(
                slot = CompareSlot.A,
                entry = entryA,
                builds = ui.savedBuilds,
                placeholder = tr(Tr.COMPARE_PICK_A),
                onPick = onPick,
                onClear = onClearSlot,
                modifier = Modifier.weight(1f)
            )
            SideColumn(
                slot = CompareSlot.B,
                entry = entryB,
                builds = ui.savedBuilds,
                placeholder = tr(Tr.COMPARE_PICK_B),
                onPick = onPick,
                onClear = onClearSlot,
                modifier = Modifier.weight(1f)
            )
        }
        if (entryA != null && entryB != null) {
            ComparisonTable(entryA = entryA, entryB = entryB)
        } else {
            Text(
                text = tr(Tr.COMPARE_EMPTY),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
    }
}

@Composable
private fun SideColumn(
    slot: CompareSlot,
    entry: HistoryEntry?,
    builds: List<HistoryEntry>,
    placeholder: String,
    onPick: (CompareSlot, String) -> Unit,
    onClear: (CompareSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BuildPicker(slot = slot, current = entry, builds = builds, placeholder = placeholder, onPick = onPick, onClear = onClear)
        if (entry != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BreedIcon(clazz = entry.restoredClass(), size = 20.dp)
                Text(
                    text = "${entry.classDisplayName()} · ${tr(Tr.LEVEL_SHORT)} ${entry.request.level}",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
            val headline =
                if (entry.isMasteryMode()) {
                    "${entry.requestedMasteryTotal().formatCompact()} ${tr(Tr.MASTERY_SHORT)}"
                } else {
                    "${entry.result.match.toInt()}% ${tr(Tr.MATCH)}"
                }
            Text(
                text = headline + if (entry.result.optimal) " · ${tr(Tr.OPTIMAL_PROVEN)}" else "",
                style = WTypography.labelMedium.copy(color = if (entry.result.optimal) WColor.success else WColor.text)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entry.result.equipments.take(14).forEach { equipment ->
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        ItemThumbnail(equipment = equipment, size = 26.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildPicker(
    slot: CompareSlot,
    current: HistoryEntry?,
    builds: List<HistoryEntry>,
    placeholder: String,
    onPick: (CompareSlot, String) -> Unit,
    onClear: (CompareSlot) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current?.name ?: placeholder,
                style = WTypography.bodyMedium.copy(color = if (current == null) WColor.faint else WColor.text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (current != null) {
                Box(
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable { onClear(slot) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", style = WTypography.labelSmall.copy(color = WColor.muted))
                }
            } else {
                Text(text = "▾", style = WTypography.labelSmall.copy(color = WColor.muted))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            builds.forEach { build ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = build.name,
                            style = WTypography.bodyMedium.copy(color = if (build.id == current?.id) WColor.accent else WColor.text)
                        )
                    },
                    onClick = {
                        onPick(slot, build.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ComparisonTable(
    entryA: HistoryEntry,
    entryB: HistoryEntry,
) {
    val lang = LocalLang.current
    val rows =
        remember(entryA, entryB) {
            val keys = (entryA.result.achieved.keys + entryB.result.achieved.keys).filterNot { it.isEngineInternalStat() }
            keys
                .map { key -> Triple(key, entryA.result.achieved[key] ?: 0, entryB.result.achieved[key] ?: 0) }
                .filter { (_, a, b) -> a != 0 || b != 0 }
                .sortedBy { it.first.ordinal }
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(text = tr(Tr.COMPARE_STAT), style = WTypography.labelMedium.copy(color = WColor.muted), modifier = Modifier.weight(1f))
            Text(text = "A", style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted), modifier = Modifier.width(72.dp))
            Text(text = "B", style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted), modifier = Modifier.width(72.dp))
            Text(text = tr(Tr.COMPARE_BETTER), style = WTypography.labelMedium.copy(color = WColor.muted), modifier = Modifier.width(60.dp))
        }
        // Headline: the value the engine actually maximized (specialized summed + min of elements).
        // This is the row that says which build the solver judges better — unlike the per-stat rows
        // below, where a build can win several elemental lines yet lose overall.
        EngineScoreRow(aValue = entryA.requestedMasteryTotal(), bValue = entryB.requestedMasteryTotal())
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
        rows.forEachIndexed { index, (characteristic, aValue, bValue) ->
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
            }
            ComparisonRow(characteristic = characteristic, label = characteristic.label(lang), aValue = aValue, bValue = bValue)
        }
    }
}

@Composable
private fun EngineScoreRow(
    aValue: Int,
    bValue: Int,
) {
    val winner =
        when {
            aValue > bValue -> CompareSlot.A
            bValue > aValue -> CompareSlot.B
            else -> null
        }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = tr(Tr.COMPARE_ENGINE_SCORE),
            style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        EngineValueCell(value = aValue, highlighted = winner == CompareSlot.A)
        EngineValueCell(value = bValue, highlighted = winner == CompareSlot.B)
        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            Text(
                text =
                    when (winner) {
                        CompareSlot.A -> "◀ A"
                        CompareSlot.B -> "B ▶"
                        null -> tr(Tr.COMPARE_EQUAL)
                    },
                style = WTypography.labelMedium.copy(color = if (winner == null) WColor.muted else WColor.success, fontFamily = WType.mono, lineHeight = 16.sp)
            )
        }
    }
}

@Composable
private fun EngineValueCell(
    value: Int,
    highlighted: Boolean,
) {
    Text(
        text = value.formatCompact(),
        style =
            WTypography.bodyMedium.copy(
                fontFamily = WType.mono,
                fontWeight = FontWeight.Bold,
                color = if (highlighted) WColor.success else WColor.text
            ),
        modifier = Modifier.width(72.dp)
    )
}

@Composable
private fun ComparisonRow(
    characteristic: Characteristic,
    label: String,
    aValue: Int,
    bValue: Int,
) {
    val winner =
        when {
            aValue > bValue -> CompareSlot.A
            bValue > aValue -> CompareSlot.B
            else -> null
        }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CharacteristicIcon(characteristic = characteristic, size = 16.dp)
        Spacer(modifier = Modifier.width(9.dp))
        Text(
            text = label,
            style = WTypography.bodyMedium.copy(color = WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ValueCell(value = aValue, highlighted = winner == CompareSlot.A)
        ValueCell(value = bValue, highlighted = winner == CompareSlot.B)
        Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
            Text(
                text =
                    when (winner) {
                        CompareSlot.A -> "◀ A"
                        CompareSlot.B -> "B ▶"
                        null -> tr(Tr.COMPARE_EQUAL)
                    },
                style =
                    WTypography.labelMedium.copy(
                        color = if (winner == null) WColor.muted else WColor.success,
                        fontFamily = WType.mono,
                        lineHeight = 16.sp
                    )
            )
        }
    }
}

@Composable
private fun ValueCell(
    value: Int,
    highlighted: Boolean,
) {
    Text(
        text = value.formatCompact(),
        style =
            WTypography.bodyMedium.copy(
                fontFamily = WType.mono,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) WColor.success else WColor.text
            ),
        modifier = Modifier.width(72.dp)
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        modifier =
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "← ${tr(Tr.BACK)}", style = WTypography.labelMedium.copy(color = WColor.text))
    }
}
