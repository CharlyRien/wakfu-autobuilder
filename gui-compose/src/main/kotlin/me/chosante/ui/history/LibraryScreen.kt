package me.chosante.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.CharacterClass
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.components.BreedBackground
import me.chosante.ui.components.BreedIllustration
import me.chosante.ui.components.ItemThumbnail
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.paperdoll.bottomSlots
import me.chosante.ui.paperdoll.leftSlots
import me.chosante.ui.paperdoll.rightSlots
import me.chosante.ui.paperdoll.slotAssignments
import me.chosante.ui.state.LibraryFolderFilter
import me.chosante.ui.state.LibraryGroup
import me.chosante.ui.state.LibrarySort
import me.chosante.ui.state.UiState
import me.chosante.ui.state.classCounts
import me.chosante.ui.state.color
import me.chosante.ui.state.folderCounts
import me.chosante.ui.state.formatCompact
import me.chosante.ui.state.libraryLabel
import me.chosante.ui.state.organizeLibrary
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The "My Builds" library: a sidebar (all-builds + per-class filters) beside a toolbar (search,
 * sort, group-by-class) and a grid of saved-build cards. Every card can be loaded back into the
 * workspace, pinned into the compare view, renamed or deleted. Reads entirely off the in-memory
 * [UiState]; all writes/filters go through the model's callbacks.
 */
@Composable
fun LibraryScreen(
    ui: UiState,
    onImport: () -> Unit,
    onLoad: (String) -> Unit,
    onCompare: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String, String) -> Unit,
    onToggleTag: (String) -> Unit,
    onCreateTag: () -> Unit,
    onRenameTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onFolderFilterChange: (LibraryFolderFilter) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onGoBuilder: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onClassFilterChange: (CharacterClass?) -> Unit,
    onToggleGroup: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ui.savedBuilds.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(WColor.bg)
                    .verticalScroll(rememberScrollState())
                    .padding(WDimens.pad),
            verticalArrangement = Arrangement.spacedBy(WDimens.gap)
        ) {
            Header(count = 0, onImport = onImport)
            EmptyState(onGoBuilder = onGoBuilder)
        }
        return
    }

    val groups =
        remember(
            ui.savedBuilds,
            ui.librarySearch,
            ui.librarySort,
            ui.libraryClassFilter,
            ui.libraryGroupByClass,
            ui.librarySelectedTags,
            ui.libraryFolder
        ) {
            organizeLibrary(
                builds = ui.savedBuilds,
                search = ui.librarySearch,
                sort = ui.librarySort,
                classFilter = ui.libraryClassFilter,
                groupByClass = ui.libraryGroupByClass,
                selectedTags = ui.librarySelectedTags,
                folder = ui.libraryFolder
            )
        }

    // A fresh duplicate lands at the top (newest first); bring it into view so the highlight is seen.
    val gridScroll = rememberScrollState()
    LaunchedEffect(ui.lastDuplicatedBuildId) {
        if (ui.lastDuplicatedBuildId != null) gridScroll.animateScrollTo(0)
    }

    Row(modifier = modifier.fillMaxSize().background(WColor.bg)) {
        LibrarySidebar(
            ui = ui,
            onClassFilterChange = onClassFilterChange,
            onToggleTag = onToggleTag,
            onCreateTag = onCreateTag,
            onRenameTag = onRenameTag,
            onDeleteTag = onDeleteTag,
            onFolderFilterChange = onFolderFilterChange,
            onRenameFolder = onRenameFolder,
            onDeleteFolder = onDeleteFolder
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(gridScroll)
                    .padding(WDimens.pad),
            verticalArrangement = Arrangement.spacedBy(WDimens.gap)
        ) {
            Header(count = ui.savedBuilds.size, onImport = onImport)
            LibraryToolbar(
                ui = ui,
                onSearchChange = onSearchChange,
                onSortChange = onSortChange,
                onToggleGroup = onToggleGroup
            )
            if (groups.all { it.builds.isEmpty() }) {
                NoResults(onClearFilters = onClearFilters)
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // Responsive card grid: compact cards (the U-shaped slot block fills each edge-to-
                    // edge), up to 6 per row — wide enough that the Load button + 4 action icons fit.
                    val columns = (maxWidth / 230.dp).toInt().coerceIn(2, 6)
                    Column(verticalArrangement = Arrangement.spacedBy(WDimens.gap)) {
                        groups.forEach { group ->
                            LibraryGroupSection(
                                group = group,
                                ui = ui,
                                columns = columns,
                                onLoad = onLoad,
                                onCompare = onCompare,
                                onDuplicate = onDuplicate,
                                onEdit = onEdit,
                                onDelete = onDelete
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryGroupSection(
    group: LibraryGroup,
    ui: UiState,
    columns: Int,
    onLoad: (String) -> Unit,
    onCompare: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String, String) -> Unit,
) {
    if (group.builds.isEmpty()) return
    if (group.clazz != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = group.clazz.libraryLabel(),
                style = WTypography.labelMedium.copy(color = WColor.muted, fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "${group.builds.size}",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.faint)
            )
        }
    }
    group.builds.chunked(columns).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WDimens.gap)) {
            row.forEach { entry ->
                Box(modifier = Modifier.weight(1f)) {
                    BuildCard(
                        entry = entry,
                        isActive = entry.id == ui.activeBuildId,
                        isJustDuplicated = entry.id == ui.lastDuplicatedBuildId,
                        onLoad = { onLoad(entry.id) },
                        onCompare = { onCompare(entry.id) },
                        onDuplicate = { onDuplicate(entry.id) },
                        onEdit = { onEdit(entry.id) },
                        onDelete = { onDelete(entry.id, entry.name) }
                    )
                }
            }
            // Pad the final, partially-filled row so its cards keep the same width as full rows.
            repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NoResults(onClearFilters: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WDimens.gap)) {
        Text(
            text = tr(Tr.LIBRARY_NO_MATCH),
            style = WTypography.bodyMedium.copy(color = WColor.muted),
            modifier = Modifier.padding(vertical = 24.dp)
        )
        CardButton(
            text = tr(Tr.LIBRARY_CLEAR_FILTERS),
            filled = false,
            color = WColor.accent2,
            onClick = onClearFilters,
            modifier = Modifier.widthIn(min = 160.dp)
        )
    }
}

// ── Sidebar ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibrarySidebar(
    ui: UiState,
    onClassFilterChange: (CharacterClass?) -> Unit,
    onToggleTag: (String) -> Unit,
    onCreateTag: () -> Unit,
    onRenameTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onFolderFilterChange: (LibraryFolderFilter) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    val counts = remember(ui.savedBuilds) { classCounts(ui.savedBuilds) }
    val tagBuildCounts =
        remember(ui.savedBuilds) {
            ui.savedBuilds
                .flatMap { it.tags }
                .groupingBy { it.lowercase() }
                .eachCount()
        }
    val folders = remember(ui.savedBuilds) { folderCounts(ui.savedBuilds) }
    val unfiledCount = remember(ui.savedBuilds) { ui.savedBuilds.count { it.folder == null } }
    Column(
        modifier =
            Modifier
                .width(210.dp)
                .fillMaxHeight()
                .background(WColor.surface)
                .border(1.dp, WColor.hairline)
                .verticalScroll(rememberScrollState())
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SidebarRow(
            label = tr(Tr.LIBRARY_ALL_BUILDS),
            count = ui.savedBuilds.size,
            selected = ui.libraryClassFilter == null && ui.libraryFolder == LibraryFolderFilter.All,
            onClick = {
                onFolderFilterChange(LibraryFolderFilter.All)
                onClassFilterChange(null)
            }
        )
        if (folders.isNotEmpty()) {
            SidebarHeader(text = tr(Tr.LIBRARY_FOLDERS))
            folders.forEach { (name, count) ->
                ManagedSidebarRow(
                    label = name,
                    count = count,
                    selected = ui.libraryFolder == LibraryFolderFilter.Named(name),
                    onClick = { onFolderFilterChange(LibraryFolderFilter.Named(name)) },
                    onRename = { onRenameFolder(name) },
                    onDelete = { onDeleteFolder(name) }
                )
            }
            if (unfiledCount > 0) {
                SidebarRow(
                    label = tr(Tr.LIBRARY_UNFILED),
                    count = unfiledCount,
                    selected = ui.libraryFolder == LibraryFolderFilter.Unfiled,
                    onClick = { onFolderFilterChange(LibraryFolderFilter.Unfiled) }
                )
            }
        }
        var tagsCollapsed by remember { mutableStateOf(false) }
        SidebarSectionHeader(
            text = tr(Tr.LIBRARY_TAGS),
            collapsed = tagsCollapsed,
            onToggleCollapsed = { tagsCollapsed = !tagsCollapsed },
            onAdd = onCreateTag
        )
        if (!tagsCollapsed) {
            ui.knownTags.forEach { tag ->
                ManagedSidebarRow(
                    label = tag,
                    count = tagBuildCounts[tag.lowercase()] ?: 0,
                    selected = tag.lowercase() in ui.librarySelectedTags,
                    onClick = { onToggleTag(tag) },
                    onRename = { onRenameTag(tag) },
                    onDelete = { onDeleteTag(tag) }
                )
            }
        }
        if (counts.isNotEmpty()) {
            SidebarHeader(text = tr(Tr.LIBRARY_CLASSES))
            counts.forEach { (clazz, count) ->
                val selected = ui.libraryClassFilter == clazz
                SidebarRow(
                    label = clazz.libraryLabel(),
                    count = count,
                    selected = selected,
                    onClick = { onClassFilterChange(if (selected) null else clazz) }
                )
            }
        }
    }
}

/** A sidebar filter row that can also be renamed/deleted — shared by Folders and Tags. */
@Composable
private fun ManagedSidebarRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) WColor.accent.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    1.dp,
                    if (selected) WColor.accent.copy(alpha = 0.55f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                ).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The whole label+count area is the toggle target (not just the text), so the click zone
        // spans most of the row width; the hover highlight then reads as a clean full-width row.
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .padding(start = 9.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = WTypography.bodyMedium.copy(color = if (selected) WColor.text else WColor.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.faint)
            )
        }
        MiniIconButton(glyph = "✎", onClick = onRename)
        MiniIconButton(glyph = "✕", onClick = onDelete, hoverColor = WColor.danger)
    }
}

@Composable
private fun MiniIconButton(
    glyph: String,
    onClick: () -> Unit,
    hoverColor: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = if (hovered && hoverColor != null) hoverColor else WColor.muted
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .hoverable(interaction)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = glyph, style = WTypography.labelSmall.copy(color = tint, lineHeight = 12.sp))
    }
}

@Composable
private fun SidebarHeader(text: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = text, style = WTypography.labelMedium.copy(color = WColor.muted))
    Spacer(modifier = Modifier.height(2.dp))
}

/** A collapsible section header with a trailing ＋ button (used by Tags). Click the label/chevron to
 * fold the section away when the list grows long. */
@Composable
private fun SidebarSectionHeader(
    text: String,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onAdd: () -> Unit,
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleCollapsed)
                    .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = if (collapsed) "▸" else "▾",
                style = WTypography.labelSmall.copy(color = WColor.faint, lineHeight = 10.sp)
            )
            Text(text = text, style = WTypography.labelMedium.copy(color = WColor.muted))
        }
        MiniIconButton(glyph = "＋", onClick = onAdd)
    }
    Spacer(modifier = Modifier.height(2.dp))
}

@Composable
private fun SidebarRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) WColor.accent.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    1.dp,
                    if (selected) WColor.accent.copy(alpha = 0.55f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                ).clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = WTypography.bodyMedium.copy(color = if (selected) WColor.text else WColor.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.faint)
        )
    }
}

// ── Toolbar (search + sort + group) ──────────────────────────────────────────────────────────

@Composable
private fun LibraryToolbar(
    ui: UiState,
    onSearchChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onToggleGroup: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WDimens.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SearchField(query = ui.librarySearch, onQueryChange = onSearchChange)
        }
        SortDropdown(selected = ui.librarySort, onSelect = onSortChange)
        GroupToggle(checked = ui.libraryGroupByClass, onToggle = onToggleGroup)
    }
}

private fun LibrarySort.labelKey(): Tr =
    when (this) {
        LibrarySort.NEWEST -> Tr.SORT_NEWEST
        LibrarySort.OLDEST -> Tr.SORT_OLDEST
        LibrarySort.NAME -> Tr.SORT_NAME
        LibrarySort.LEVEL -> Tr.SORT_LEVEL
    }

@Composable
private fun SortDropdown(
    selected: LibrarySort,
    onSelect: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier =
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.surface)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = tr(Tr.LIBRARY_SORT), style = WTypography.labelSmall.copy(color = WColor.muted))
            Spacer(modifier = Modifier.width(7.dp))
            Text(text = tr(selected.labelKey()), style = WTypography.labelMedium.copy(color = WColor.text))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "▾", style = WTypography.labelSmall.copy(lineHeight = 10.sp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = tr(sort.labelKey()),
                            style = WTypography.bodyMedium.copy(color = if (sort == selected) WColor.accent else WColor.text)
                        )
                    },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GroupToggle(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.surface)
                .border(1.dp, if (checked) WColor.accent.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) WColor.accent else Color.Transparent)
                    .border(1.dp, if (checked) WColor.accent else WColor.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(text = "✓", style = WTypography.labelSmall.copy(color = WColor.bg, lineHeight = 12.sp))
            }
        }
        Text(
            text = tr(Tr.LIBRARY_GROUP_BY_CLASS),
            style = WTypography.labelMedium.copy(color = if (checked) WColor.text else WColor.muted)
        )
    }
}

@Composable
private fun Header(
    count: Int,
    onImport: () -> Unit,
) {
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
            Spacer(modifier = Modifier.width(14.dp))
        }
        // Paste a build a tester exported (input + result) — added to the library and opened.
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .clickable(onClick = onImport)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = tr(Tr.IMPORT_BUILD), style = WTypography.labelMedium.copy(color = WColor.text))
        }
    }
}

@Composable
private fun BuildCard(
    entry: HistoryEntry,
    isActive: Boolean,
    isJustDuplicated: Boolean,
    onLoad: () -> Unit,
    onCompare: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
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
    // A freshly duplicated card pulses an accent border + tint, then fades back as the marker clears.
    val borderColor by animateColorAsState(
        targetValue =
            when {
                isJustDuplicated -> WColor.accent
                isActive -> WColor.accent.copy(alpha = 0.6f)
                else -> WColor.hairline
            },
        label = "buildCardBorder"
    )
    val highlightTint by animateColorAsState(
        targetValue = if (isJustDuplicated) WColor.accent.copy(alpha = 0.10f) else Color.Transparent,
        label = "buildCardTint"
    )
    val clazz = entry.restoredClass()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, borderColor, RoundedCornerShape(WDimens.radius))
    ) {
        // Class art behind the content: a very faint themed background, and the T-pose illustration
        // centered so it "stands" in the open middle of the U-shaped slot grid (where no item tile
        // covers it). Dimmed enough that the name/pills/buttons stay legible on top.
        BreedBackground(clazz, modifier = Modifier.matchParentSize(), alpha = 0.07f)
        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            BreedIllustration(
                clazz = clazz,
                modifier = Modifier.fillMaxHeight().widthIn(max = 150.dp),
                contentScale = ContentScale.Fit,
                alpha = 0.45f
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(highlightTint)
                    .padding(WDimens.pad)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    // Full name in a tooltip too, so a name truncated at two lines stays fully readable.
                    WTooltip(text = entry.name) {
                        Text(
                            text = entry.name,
                            style = WTypography.titleMedium.copy(color = WColor.text, fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = date,
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                HeadlineBadge(entry = entry)
            }
            Spacer(modifier = Modifier.height(9.dp))
            PillsRow(entry = entry)
            Spacer(modifier = Modifier.height(11.dp))
            SlotMiniGrid(entry = entry)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Primary action keeps a text label (clearest); secondary actions are compact glyphs.
                CardButton(
                    text = tr(Tr.ACTION_LOAD),
                    filled = true,
                    color = WColor.accent,
                    onClick = onLoad,
                    modifier = Modifier.weight(1f)
                )
                ActionIconButton(glyph = "⇄", tooltip = tr(Tr.ACTION_COMPARE), onClick = onCompare)
                ActionIconButton(glyph = "⧉", tooltip = tr(Tr.ACTION_DUPLICATE), onClick = onDuplicate)
                ActionIconButton(glyph = "✎", tooltip = tr(Tr.ACTION_RENAME), onClick = onEdit)
                ActionIconButton(glyph = "✕", tooltip = tr(Tr.ACTION_DELETE), hoverColor = WColor.danger, onClick = onDelete)
            }
        }
    }
}

/** A small Compose-Desktop tooltip wrapper in the app's dark style. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        delayMillis = 350,
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, RoundedCornerShape(7.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
            ) {
                Text(text = text, style = WTypography.labelSmall.copy(color = WColor.text))
            }
        },
        content = content
    )
}

/**
 * A square line-glyph action button (theme-consistent monochrome, not emoji) with a tooltip. Stays
 * neutral by default; if [hoverColor] is set, the glyph and border take that colour on hover (used to
 * flag the destructive delete action in red).
 */
@Composable
private fun ActionIconButton(
    glyph: String,
    tooltip: String,
    onClick: () -> Unit,
    hoverColor: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = hovered && hoverColor != null
    WTooltip(text = tooltip) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WColor.raised)
                    .border(1.dp, if (active) hoverColor!!.copy(alpha = 0.6f) else WColor.border, RoundedCornerShape(8.dp))
                    .hoverable(interaction)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = glyph,
                style = WTypography.titleMedium.copy(color = if (active) hoverColor!! else WColor.muted, lineHeight = 16.sp)
            )
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
private fun PillsRow(entry: HistoryEntry) {
    // Meta pills first, then up to 4 user-tag pills (a "+n" pill standing in for the rest). Pills
    // wrap 3-per-row so a long set never overflows the card.
    val metaPills =
        listOf(
            entry.classDisplayName(),
            "${tr(Tr.LEVEL_SHORT)} ${entry.request.level}",
            tr(if (entry.isMasteryMode()) Tr.MODE_MASTERIES else Tr.MODE_PRECISION)
        )
    val tagAccent = WColor.accent2.copy(alpha = 0.35f)
    val shownTags = entry.tags.take(4)
    val overflow = entry.tags.size - shownTags.size
    val pills =
        metaPills.map { it to WColor.border } +
            shownTags.map { it to tagAccent } +
            if (overflow > 0) listOf("+$overflow" to tagAccent) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        pills.chunked(3).forEach { rowPills ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                rowPills.forEach { (text, color) -> MetaPill(text = text, borderColor = color) }
            }
        }
    }
}

@Composable
private fun MetaPill(
    text: String,
    borderColor: Color = WColor.border,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(WColor.raised)
                .border(1.dp, borderColor, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = WTypography.labelSmall.copy(color = WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// A compact echo of the paperdoll: two flanking columns (left + right gear) with the central area
// left empty (no character render, by design), and the weapons / pet / mount along the bottom.
// A two-handed weapon shows in both weapon tiles (mirrors the paperdoll); legitimately-empty slots
// stay visible as dimmed glyph tiles (the solver leaves e.g. the mount empty by design).
@Composable
private fun SlotMiniGrid(entry: HistoryEntry) {
    val assignments = remember(entry.result.equipments) { slotAssignments(entry.result.equipments) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = 4.dp
        // Tiles sized so the 4-wide bottom row spans the full card width → the U is flush to the edges.
        val tile = ((maxWidth - gap * 3) / 4).coerceAtLeast(18.dp)
        // Spread the two arms so they line up with the outer tiles of the bottom row → a U.
        val armGap = tile * 2 + gap * 3

        @Composable
        fun tileFor(slot: me.chosante.ui.paperdoll.DollSlot) = SlotTile(equipment = assignments[slot.id], glyph = slot.glyph, size = tile)

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            // Five paired rows: left-column gear | empty centre | right-column gear.
            leftSlots.indices.forEach { i ->
                Row(horizontalArrangement = Arrangement.spacedBy(armGap), verticalAlignment = Alignment.CenterVertically) {
                    tileFor(leftSlots[i])
                    tileFor(rightSlots[i])
                }
            }
            // Weapons + pet + mount close the U along the bottom.
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                bottomSlots.forEach { tileFor(it) }
            }
        }
    }
}

@Composable
private fun SlotTile(
    equipment: me.chosante.common.Equipment?,
    glyph: String,
    size: androidx.compose.ui.unit.Dp,
) {
    if (equipment != null) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WColor.raised)
                    .border(1.dp, equipment.rarity.color().copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        ) {
            ItemThumbnail(equipment = equipment, size = size)
        }
    } else {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.hairline, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = glyph, style = WTypography.labelSmall.copy(color = WColor.faint))
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
