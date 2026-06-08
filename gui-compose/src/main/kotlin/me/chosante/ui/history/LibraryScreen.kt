package me.chosante.ui.history

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.components.ItemThumbnail
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.UiState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The "My Builds" library: a searchable grid of saved-build cards. Every card can be loaded back
 * into the workspace, pinned into the compare view, renamed or deleted. Reads entirely off the
 * in-memory [UiState.savedBuilds]; all writes go through the model's callbacks.
 */
@Composable
fun LibraryScreen(
    ui: UiState,
    onLoad: (String) -> Unit,
    onCompare: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onGoBuilder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(ui.savedBuilds, query) {
            val q = query.trim()
            if (q.isBlank()) {
                ui.savedBuilds
            } else {
                ui.savedBuilds.filter { it.name.contains(q, ignoreCase = true) || it.request.clazz.contains(q, ignoreCase = true) }
            }
        }
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
        Header(count = ui.savedBuilds.size)
        if (ui.savedBuilds.isNotEmpty()) {
            SearchField(query = query, onQueryChange = { query = it })
        }
        when {
            ui.savedBuilds.isEmpty() -> EmptyState(onGoBuilder = onGoBuilder)
            filtered.isEmpty() ->
                Text(
                    text = tr(Tr.LIBRARY_NO_MATCH),
                    style = WTypography.bodyMedium.copy(color = WColor.muted),
                    modifier = Modifier.padding(vertical = 24.dp)
                )

            else ->
                filtered.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WDimens.gap)) {
                        row.forEach { entry ->
                            Box(modifier = Modifier.weight(1f)) {
                                BuildCard(
                                    entry = entry,
                                    isActive = entry.id == ui.activeBuildId,
                                    onLoad = { onLoad(entry.id) },
                                    onCompare = { onCompare(entry.id) },
                                    onRename = { onRename(entry.id, entry.name) },
                                    onDelete = { onDelete(entry.id, entry.name) }
                                )
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
        }
    }
}

@Composable
private fun Header(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = tr(Tr.LIBRARY_TITLE), style = WTypography.headlineLarge.copy(fontWeight = FontWeight.Bold))
            Text(text = tr(Tr.LIBRARY_SUBTITLE), style = WTypography.labelMedium.copy(color = WColor.muted))
        }
        if (count > 0) {
            Text(
                text = "$count ${tr(Tr.LIBRARY_COUNT)}",
                style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted)
            )
        }
    }
}

@Composable
private fun BuildCard(
    entry: HistoryEntry,
    isActive: Boolean,
    onLoad: () -> Unit,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val date =
        remember(entry.createdAt) {
            Instant
                .ofEpochMilli(entry.createdAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(dateFormatter)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(
                    1.dp,
                    if (isActive) WColor.accent.copy(alpha = 0.6f) else WColor.hairline,
                    RoundedCornerShape(WDimens.radius)
                ).padding(WDimens.pad)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = WTypography.titleMedium.copy(color = WColor.text, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.classDisplayName()} · ${tr(Tr.LEVEL_SHORT)} ${entry.request.level} · $date",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
            HeadlineBadge(entry = entry)
        }
        Spacer(modifier = Modifier.height(11.dp))
        IconBand(entry = entry)
        if (entry.note != null) {
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = entry.note!!,
                style = WTypography.bodySmall.copy(color = WColor.muted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CardButton(text = tr(Tr.ACTION_LOAD), filled = true, color = WColor.accent, onClick = onLoad, modifier = Modifier.weight(1f))
            CardButton(text = tr(Tr.ACTION_COMPARE), filled = false, color = WColor.accent2, onClick = onCompare, modifier = Modifier.weight(1f))
            IconButton(glyph = "✎", onClick = onRename)
            IconButton(glyph = "🗑", onClick = onDelete)
        }
    }
}

@Composable
private fun HeadlineBadge(entry: HistoryEntry) {
    val masteryMode = entry.isMasteryMode()
    val value = if (masteryMode) entry.requestedMasteryTotal().formatCompact() else "${entry.result.match.toInt()}%"
    val label = if (masteryMode) tr(Tr.MASTERY_SHORT) else tr(Tr.MATCH)
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = value,
            style = WTypography.titleMedium.copy(fontFamily = WType.mono, color = if (entry.result.optimal) WColor.success else WColor.text)
        )
        Text(
            text = if (entry.result.optimal) tr(Tr.OPTIMAL_PROVEN) else label,
            style = WTypography.labelSmall.copy(color = if (entry.result.optimal) WColor.success else WColor.muted)
        )
    }
}

@Composable
private fun IconBand(entry: HistoryEntry) {
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

@Composable
private fun CardButton(
    text: String,
    filled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (filled) color else Color.Transparent)
                .border(1.dp, if (filled) color else WColor.border, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = WTypography.labelMedium.copy(color = if (filled) WColor.bg else WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconButton(
    glyph: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = glyph, style = WTypography.labelMedium.copy(color = WColor.muted, lineHeight = 14.sp))
    }
}

@Composable
private fun EmptyState(onGoBuilder: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = tr(Tr.LIBRARY_EMPTY), style = WTypography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = tr(Tr.LIBRARY_EMPTY_HINT),
            style = WTypography.bodyMedium.copy(color = WColor.muted, lineHeight = 20.sp),
            modifier = Modifier.widthIn(max = 420.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        CardButton(text = tr(Tr.NAV_BUILDER), filled = true, color = WColor.accent, onClick = onGoBuilder, modifier = Modifier.widthIn(min = 140.dp))
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            cursorBrush = SolidColor(WColor.accent),
            textStyle = WTypography.bodyMedium.copy(color = WColor.text),
            modifier = Modifier.fillMaxWidth()
        )
        if (query.isEmpty()) {
            Text(text = tr(Tr.LIBRARY_SEARCH), style = WTypography.bodyMedium.copy(color = WColor.faint))
        }
    }
}
