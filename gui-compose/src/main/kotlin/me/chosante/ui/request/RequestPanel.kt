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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Rarity
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ItemChip
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.UiState
import me.chosante.ui.state.color
import me.chosante.ui.state.isExact
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
    onMaxRarityChange: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
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
            onAdd = onAddTarget
        )
        ConstraintsCard(
            maxRarity = ui.maxRarity,
            duration = ui.duration,
            stopAtMatch = ui.stopAtMatch,
            onRarityChange = onMaxRarityChange,
            onDurationChange = onDurationChange,
            onStopAtMatchChange = onStopAtMatchChange
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
) {
    RequestCard(
        title = tr(Tr.TARGET_STATS),
        trailing = targets.size.toString()
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
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
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
    onRarityChange: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
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
