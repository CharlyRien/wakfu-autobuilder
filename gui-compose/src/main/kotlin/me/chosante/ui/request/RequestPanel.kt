package me.chosante.ui.request

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ItemChip
import me.chosante.ui.state.StatDef
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.UiState
import me.chosante.ui.state.color
import me.chosante.ui.state.isExact
import me.chosante.ui.state.statCatalog
import me.chosante.ui.state.statDefFor
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun RequestPanel(
    ui: UiState,
    onModeChange: (ScoreComputationMode) -> Unit,
    onTargetValueChange: (String, String) -> Unit,
    onRemoveTarget: (String) -> Unit,
    onAddTarget: () -> Unit,
    onToggleMastery: (Characteristic) -> Unit,
    onMaxRarityChange: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
    onSolverChange: (WakfuSolver) -> Unit,
    onAddForcedItem: () -> Unit,
    onRemoveForcedItem: (ItemChip) -> Unit,
    onAddExcludedItem: () -> Unit,
    onRemoveExcludedItem: (ItemChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WDimens.gap),
        verticalArrangement = Arrangement.spacedBy(WDimens.gap)
    ) {
        SearchModeCard(
            selected = ui.mode,
            onSelect = onModeChange
        )
        TargetStatsCard(
            mode = ui.mode,
            targets = ui.targets,
            onValueChange = onTargetValueChange,
            onRemove = onRemoveTarget,
            onAdd = onAddTarget,
            onToggleMastery = onToggleMastery
        )
        ConstraintsCard(
            maxRarity = ui.maxRarity,
            duration = ui.duration,
            stopAtMatch = ui.stopAtMatch,
            solver = ui.solver,
            onRarityChange = onMaxRarityChange,
            onDurationChange = onDurationChange,
            onStopAtMatchChange = onStopAtMatchChange,
            onSolverChange = onSolverChange
        )
        ItemChipsCard(
            title = tr(Tr.FORCED_ITEMS),
            addLabel = tr(Tr.REQUIRE_ITEM_CHIP),
            items = ui.forcedItems,
            accent = WColor.success,
            onAdd = onAddForcedItem,
            onRemove = onRemoveForcedItem
        )
        ItemChipsCard(
            title = tr(Tr.EXCLUDED_ITEMS),
            addLabel = tr(Tr.BAN_ITEM_CHIP),
            items = ui.excludedItems,
            accent = WColor.danger,
            onAdd = onAddExcludedItem,
            onRemove = onRemoveExcludedItem
        )
    }
}

@Composable
private fun SearchModeCard(
    selected: ScoreComputationMode,
    onSelect: (ScoreComputationMode) -> Unit,
) {
    RequestCard(title = tr(Tr.SEARCH_MODE)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(10.dp))
                    .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeSegment(
                title = tr(Tr.MODE_MASTERIES),
                subtitle = tr(Tr.MODE_MASTERIES_SUB),
                selected = selected == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                onClick = { onSelect(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) },
                modifier = Modifier.weight(1f)
            )
            ModeSegment(
                title = tr(Tr.MODE_PRECISION),
                subtitle = tr(Tr.MODE_PRECISION_SUB),
                selected = selected == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                onClick = { onSelect(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeSegment(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .height(58.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) WColor.raised else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style =
                WTypography.labelMedium.copy(
                    color = if (selected) WColor.text else WColor.muted,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
        )
        Text(
            text = subtitle,
            style = WTypography.labelSmall.copy(textAlign = TextAlign.Center, lineHeight = 11.sp),
            maxLines = 2
        )
    }
}

@Composable
private fun TargetStatsCard(
    mode: ScoreComputationMode,
    targets: List<TargetRow>,
    onValueChange: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onToggleMastery: (Characteristic) -> Unit,
) {
    val maximizedMasteriesMode = mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    RequestCard(
        title = tr(Tr.TARGET_STATS),
        trailing = targets.size.toString()
    ) {
        val sections =
            if (maximizedMasteriesMode) {
                maxMasteryInputSections
            } else {
                targetInputSections
            }
        if (maximizedMasteriesMode) {
            SelectedMasteriesSummary(targets = targets)
            Spacer(modifier = Modifier.height(12.dp))
        }
        sections.forEachIndexed { sectionIndex, section ->
            val rows = targets.filter { section.accepts(it.characteristic) }.sortedBy { section.orderOf(it.characteristic) }
            val shouldRenderMasteryPicker = maximizedMasteriesMode && section.renderAsMasteryPicker
            if (rows.isNotEmpty() || shouldRenderMasteryPicker) {
                if (sectionIndex > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
                SectionHeader(title = tr(section.title))
                if (shouldRenderMasteryPicker) {
                    MasteryCheckboxGrid(
                        options = section.characteristics,
                        selected = targets.map { it.characteristic }.toSet(),
                        onToggle = onToggleMastery
                    )
                } else {
                    TargetRowList(
                        mode = mode,
                        targets = rows,
                        onValueChange = onValueChange,
                        onRemove = onRemove
                    )
                }
            }
        }
        AddTargetButton(onAdd = onAdd)
    }
}

@Composable
private fun SelectedMasteriesSummary(targets: List<TargetRow>) {
    val selected =
        allMasteryCharacteristics
            .filter { characteristic -> targets.any { it.characteristic == characteristic } }
            .mapNotNull { statDefFor(it) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tr(Tr.MAXIMIZED_MASTERIES),
                style = WTypography.labelSmall.copy(color = WColor.muted, fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = selected.size.toString(),
                style = WTypography.labelSmall.copy(color = WColor.faint, fontFamily = WType.mono)
            )
        }
        if (selected.isEmpty()) {
            Text(text = tr(Tr.NO_MASTERY_SELECTED), style = WTypography.bodySmall.copy(color = WColor.faint))
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                selected.forEach { def ->
                    SelectedMasteryPill(def = def)
                }
            }
        }
    }
}

@Composable
private fun SelectedMasteryPill(def: StatDef) {
    Row(
        modifier =
            Modifier
                .height(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(def.color.copy(alpha = 0.14f))
                .border(1.dp, def.color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = def.glyph,
            style =
                WTypography.labelSmall.copy(
                    color = def.color,
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 10.sp
                )
        )
        Text(
            text = def.characteristic.masteryOptionLabel(LocalLang.current),
            style = WTypography.labelSmall.copy(color = WColor.text, lineHeight = 10.sp),
            maxLines = 1
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = WTypography.labelSmall.copy(color = WColor.muted, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 5.dp)
    )
}

@Composable
private fun TargetRowList(
    mode: ScoreComputationMode,
    targets: List<TargetRow>,
    onValueChange: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    targets.forEachIndexed { index, target ->
        if (index > 0) Hairline()
        TargetStatRow(
            target = target,
            kind = tr(if (target.isExact(mode)) Tr.KIND_EXACT else Tr.KIND_MAXIMIZE),
            onValueChange = { onValueChange(target.id, it) },
            onRemove = { onRemove(target.id) }
        )
    }
}

@Composable
private fun MasteryCheckboxGrid(
    options: List<Characteristic>,
    selected: Set<Characteristic>,
    onToggle: (Characteristic) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.mapNotNull { statDefFor(it) }.forEach { def ->
            MasteryCheckboxChip(
                def = def,
                checked = def.characteristic in selected,
                onClick = { onToggle(def.characteristic) }
            )
        }
    }
}

@Composable
private fun MasteryCheckboxChip(
    def: StatDef,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .width(118.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) def.color.copy(alpha = 0.16f) else WColor.bg)
                .border(
                    width = if (checked) 2.dp else 1.dp,
                    color = if (checked) def.color.copy(alpha = 0.82f) else WColor.border,
                    shape = RoundedCornerShape(8.dp)
                ).clickable(onClick = onClick)
                .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (checked) def.color.copy(alpha = 0.24f) else WColor.surface)
                    .border(
                        width = 1.dp,
                        color = def.color.copy(alpha = if (checked) 0.85f else 0.35f),
                        shape = RoundedCornerShape(7.dp)
                    ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = def.glyph,
                style =
                    WTypography.labelSmall.copy(
                        color = def.color,
                        fontFamily = WType.mono,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 10.sp
                    )
            )
        }
        Text(
            text = def.characteristic.masteryOptionLabel(LocalLang.current),
            style = WTypography.labelMedium.copy(color = if (checked) WColor.text else WColor.muted, lineHeight = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (checked) def.color else WColor.surface)
                    .border(1.dp, if (checked) def.color else WColor.border, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text(
                    text = "✓",
                    style =
                        WTypography.labelSmall.copy(
                            color = def.color,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = 10.sp
                        )
                )
            }
        }
    }
}

@Composable
private fun AddTargetButton(onAdd: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onAdd),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tr(Tr.ADD_TARGET_STAT),
            style =
                WTypography.labelMedium.copy(
                    color = WColor.accent,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                )
        )
    }
}

@Composable
private fun TargetStatRow(
    target: TargetRow,
    kind: String,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphChip(label = target.glyph, color = target.color)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = target.characteristic.label(LocalLang.current), style = WTypography.bodyLarge)
            Text(text = kind, style = WTypography.labelSmall)
        }
        NumberField(
            value = target.value,
            onValueChange = onValueChange,
            width = 66.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                style =
                    WTypography.bodySmall.copy(
                        color = WColor.faint,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
            )
        }
    }
}

@Composable
private fun ConstraintsCard(
    maxRarity: Rarity,
    duration: String,
    stopAtMatch: Boolean,
    solver: WakfuSolver,
    onRarityChange: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
    onSolverChange: (WakfuSolver) -> Unit,
) {
    RequestCard(title = tr(Tr.CONSTRAINTS)) {
        ConstraintRow(label = tr(Tr.MAX_RARITY)) {
            RarityDropdown(
                rarity = maxRarity,
                onRarityChange = onRarityChange
            )
        }
        Hairline()
        ConstraintRow(label = tr(Tr.SEARCH_DURATION), sublabel = tr(Tr.SEARCH_DURATION_SUB)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberField(value = duration, onValueChange = onDurationChange, width = 56.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = tr(Tr.SECONDS_SHORT), style = WTypography.labelMedium)
            }
        }
        Hairline()
        ConstraintRow(label = tr(Tr.STOP_AT_MATCH)) {
            Toggle(
                checked = stopAtMatch,
                onCheckedChange = onStopAtMatchChange
            )
        }
        Hairline()
        ConstraintRow(label = tr(Tr.DEBUG_SOLVER)) {
            SolverSegmentedControl(
                selected = solver,
                onSelect = onSolverChange
            )
        }
    }
}

@Composable
private fun SolverSegmentedControl(
    selected: WakfuSolver,
    onSelect: (WakfuSolver) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .width(146.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        SolverSegment(
            title = tr(Tr.SOLVER_OR_TOOLS),
            subtitle = tr(Tr.SOLVER_OR_TOOLS_SUB),
            selected = selected == WakfuSolver.OR_TOOLS,
            onClick = { onSelect(WakfuSolver.OR_TOOLS) },
            modifier = Modifier.weight(1f)
        )
        SolverSegment(
            title = tr(Tr.SOLVER_GA),
            subtitle = tr(Tr.SOLVER_GA_SUB),
            selected = selected == WakfuSolver.GENETIC_ALGORITHM,
            onClick = { onSelect(WakfuSolver.GENETIC_ALGORITHM) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SolverSegment(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) WColor.raised else Color.Transparent)
                .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = WTypography.labelSmall.copy(color = if (selected) WColor.text else WColor.muted, lineHeight = 10.sp),
            maxLines = 1
        )
        Text(
            text = subtitle,
            style = WTypography.labelSmall.copy(fontSize = 9.sp, lineHeight = 9.sp),
            maxLines = 1
        )
    }
}

@Composable
private fun RarityDropdown(
    rarity: Rarity,
    onRarityChange: (Rarity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier =
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RarityDot(rarity = rarity)
            Spacer(modifier = Modifier.width(7.dp))
            Text(text = rarity.label(LocalLang.current), style = WTypography.labelMedium.copy(color = WColor.text))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "▾", style = WTypography.labelSmall.copy(textAlign = TextAlign.Center, lineHeight = 10.sp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            Rarity.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RarityDot(rarity = item)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.label(LocalLang.current),
                                style =
                                    WTypography.bodyMedium.copy(
                                        color = if (item == rarity) WColor.accent else WColor.text
                                    )
                            )
                        }
                    },
                    onClick = {
                        onRarityChange(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ConstraintRow(
    label: String,
    sublabel: String? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = WTypography.bodyLarge)
            if (sublabel != null) {
                Text(text = sublabel, style = WTypography.labelSmall)
            }
        }
        content()
    }
}

@Composable
private fun Toggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (checked) WColor.accent2 else WColor.raised)
                .border(1.dp, if (checked) WColor.accent2 else WColor.border, RoundedCornerShape(999.dp))
                .clickable { onCheckedChange(!checked) }
                .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier =
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (checked) WColor.bg else WColor.faint)
        )
    }
}

@Composable
private fun ItemChipsCard(
    title: String,
    addLabel: String,
    items: List<ItemChip>,
    accent: Color,
    onAdd: () -> Unit,
    onRemove: (ItemChip) -> Unit,
) {
    RequestCard(title = title) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items.forEach { item ->
                ItemChipView(item = item, accent = accent, onRemove = { onRemove(item) })
            }
            Box(
                modifier =
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = addLabel,
                    style =
                        WTypography.labelMedium.copy(
                            color = WColor.accent,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp
                        )
                )
            }
        }
    }
}

@Composable
private fun ItemChipView(
    item: ItemChip,
    accent: Color,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.raised)
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier =
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(item.rarity.color())
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(text = item.name, style = WTypography.labelMedium.copy(color = WColor.text))
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = "×",
            style =
                WTypography.labelMedium.copy(
                    color = WColor.faint,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp
                ),
            modifier = Modifier.clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun RequestCard(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = WTypography.labelMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f).height(1.dp).background(WColor.hairline))
            if (trailing != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = trailing,
                    style =
                        WTypography.labelSmall.copy(
                            fontFamily = WType.mono,
                            fontWeight = FontWeight.SemiBold
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun GlyphChip(
    label: String,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.raised)
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style =
                WTypography.labelSmall.copy(
                    color = color,
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                )
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    width: Dp,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(WColor.accent),
        textStyle =
            WTypography.bodyMedium.copy(
                color = WColor.text,
                fontFamily = WType.mono,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                lineHeight = 16.sp
            ),
        modifier =
            Modifier
                .width(width)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(8.dp)),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                innerTextField()
            }
        }
    )
}

@Composable
private fun RarityDot(rarity: Rarity) {
    Box(
        modifier =
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(rarity.color())
    )
}

@Composable
private fun Hairline() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
}

private data class TargetInputSection(
    val title: Tr,
    val characteristics: List<Characteristic>,
    val renderAsMasteryPicker: Boolean = false,
) {
    fun accepts(characteristic: Characteristic): Boolean = characteristic in characteristics

    fun orderOf(characteristic: Characteristic): Int {
        val index = characteristics.indexOf(characteristic)
        return if (index == -1) Int.MAX_VALUE else index
    }
}

private val coreInputCharacteristics =
    listOf(
        Characteristic.ACTION_POINT,
        Characteristic.MOVEMENT_POINT,
        Characteristic.RANGE,
        Characteristic.WAKFU_POINT,
        Characteristic.CRITICAL_HIT,
        Characteristic.HP
    )

private val elementalMasteryCharacteristics =
    listOf(
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val specializedMasteryCharacteristics =
    listOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

private val resistanceInputCharacteristics =
    statCatalog
        .map { it.characteristic }
        .filter { it.name.startsWith("RESISTANCE") }

private val secondaryInputCharacteristics =
    statCatalog
        .map { it.characteristic }
        .filter {
            it !in coreInputCharacteristics &&
                it !in elementalMasteryCharacteristics &&
                it !in specializedMasteryCharacteristics &&
                it !in resistanceInputCharacteristics
        }

private val targetInputSections =
    listOf(
        TargetInputSection(Tr.STAT_GROUP_CORE, coreInputCharacteristics),
        TargetInputSection(Tr.MASTERY_ELEMENTALS, elementalMasteryCharacteristics, renderAsMasteryPicker = true),
        TargetInputSection(Tr.MASTERY_SPECIALIZED, specializedMasteryCharacteristics, renderAsMasteryPicker = true),
        TargetInputSection(Tr.STAT_GROUP_RESISTANCES, resistanceInputCharacteristics),
        TargetInputSection(Tr.STAT_GROUP_SECONDARY, secondaryInputCharacteristics)
    )

private val maxMasteryInputSections =
    listOf(
        TargetInputSection(Tr.MASTERY_SPECIALIZED, specializedMasteryCharacteristics, renderAsMasteryPicker = true),
        TargetInputSection(Tr.MASTERY_ELEMENTALS, elementalMasteryCharacteristics, renderAsMasteryPicker = true),
        TargetInputSection(Tr.STAT_GROUP_CORE, coreInputCharacteristics),
        TargetInputSection(Tr.STAT_GROUP_RESISTANCES, resistanceInputCharacteristics),
        TargetInputSection(Tr.STAT_GROUP_SECONDARY, secondaryInputCharacteristics)
    )

private val allMasteryCharacteristics = specializedMasteryCharacteristics + elementalMasteryCharacteristics

private fun Characteristic.masteryOptionLabel(lang: Lang): String {
    val fr = lang == Lang.FR
    return when (this) {
        Characteristic.MASTERY_ELEMENTARY -> if (fr) "Toutes" else "All"
        Characteristic.MASTERY_ELEMENTARY_WATER -> if (fr) "Eau" else "Water"
        Characteristic.MASTERY_ELEMENTARY_FIRE -> if (fr) "Feu" else "Fire"
        Characteristic.MASTERY_ELEMENTARY_EARTH -> if (fr) "Terre" else "Earth"
        Characteristic.MASTERY_ELEMENTARY_WIND -> if (fr) "Air" else "Air"
        Characteristic.MASTERY_DISTANCE -> "Distance"
        Characteristic.MASTERY_MELEE -> if (fr) "Mêlée" else "Melee"
        Characteristic.MASTERY_CRITICAL -> if (fr) "Critique" else "Critical"
        Characteristic.MASTERY_BACK -> if (fr) "Dos" else "Rear"
        Characteristic.MASTERY_BERSERK -> "Berserk"
        Characteristic.MASTERY_HEALING -> if (fr) "Soin" else "Healing"
        else -> label(lang)
    }
}
