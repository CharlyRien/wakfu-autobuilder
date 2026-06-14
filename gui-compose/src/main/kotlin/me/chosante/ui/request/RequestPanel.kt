package me.chosante.ui.request

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.ui.components.RarityIcon
import me.chosante.ui.components.StatGlyphIcon
import me.chosante.ui.components.VerticalScrollHints
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
    onScenarioChange: (DamageScenario) -> Unit,
    onTargetValueChange: (String, String) -> Unit,
    onTargetWeightChange: (String, Int) -> Unit,
    onRemoveTarget: (String) -> Unit,
    onAddTarget: () -> Unit,
    onToggleMastery: (Characteristic) -> Unit,
    onToggleRarity: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
    onAddForcedItem: () -> Unit,
    onRemoveForcedItem: (ItemChip) -> Unit,
    onAddExcludedItem: () -> Unit,
    onRemoveExcludedItem: (ItemChip) -> Unit,
    sublimationCatalog: List<String> = emptyList(),
    runeCatalog: List<String> = emptyList(),
    onToggleSublimations: (Boolean) -> Unit = {},
    onAddForcedSublimation: (String) -> Unit = {},
    onRemoveForcedSublimation: (String) -> Unit = {},
    onAddForcedRune: (String) -> Unit = {},
    onRemoveForcedRune: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    me.chosante.ui.testing
        .ScreenshotAutoScrollToBottom(scroll, enabled = true, key = "REQUEST_BOTTOM")
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(WDimens.gap),
            verticalArrangement = Arrangement.spacedBy(WDimens.gap)
        ) {
            SearchModeCard(
                selected = ui.mode,
                onSelect = onModeChange
            )
            if (ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                DamageScenarioCard(scenario = ui.scenario, onChange = onScenarioChange)
            }
            TargetStatsCard(
                mode = ui.mode,
                targets = ui.targets,
                onValueChange = onTargetValueChange,
                onWeightChange = onTargetWeightChange,
                onRemove = onRemoveTarget,
                onAdd = onAddTarget,
                onToggleMastery = onToggleMastery
            )
            ConstraintsCard(
                excludedRarities = ui.excludedRarities,
                duration = ui.duration,
                stopAtMatch = ui.stopAtMatch,
                onToggleRarity = onToggleRarity,
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
            SublimationsRunesCard(
                useSublimations = ui.useSublimations,
                forcedSublimations = ui.forcedSublimations,
                forcedRunes = ui.forcedRunes,
                sublimationCatalog = sublimationCatalog,
                runeCatalog = runeCatalog,
                onToggleSublimations = onToggleSublimations,
                onAddForcedSublimation = onAddForcedSublimation,
                onRemoveForcedSublimation = onRemoveForcedSublimation,
                onAddForcedRune = onAddForcedRune,
                onRemoveForcedRune = onRemoveForcedRune
            )
        }
        VerticalScrollHints(scroll)
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
            ModeSegment(
                title = tr(Tr.MODE_MAX_DAMAGE),
                subtitle = tr(Tr.MODE_MAX_DAMAGE_SUB),
                selected = selected == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                onClick = { onSelect(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DamageScenarioCard(
    scenario: DamageScenario,
    onChange: (DamageScenario) -> Unit,
) {
    val lang = LocalLang.current
    RequestCard(title = tr(Tr.DAMAGE_SCENARIO)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedEnumRow(
                label = tr(Tr.SCENARIO_ELEMENT),
                values = SpellElement.entries,
                selected = scenario.element,
                labelOf = { it.label(lang) },
                onSelect = { onChange(scenario.copy(element = it)) }
            )
            SegmentedEnumRow(
                label = tr(Tr.SCENARIO_RANGE),
                values = RangeBand.entries,
                selected = scenario.rangeBand,
                labelOf = { it.label(lang) },
                onSelect = { onChange(scenario.copy(rangeBand = it)) }
            )
            SegmentedEnumRow(
                label = tr(Tr.SCENARIO_ORIENTATION),
                values = Orientation.entries,
                selected = scenario.orientation,
                labelOf = { it.label(lang) },
                onSelect = { onChange(scenario.copy(orientation = it)) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ScenarioToggle(
                    label = tr(Tr.SCENARIO_BERSERK),
                    checked = scenario.berserk,
                    onCheckedChange = { onChange(scenario.copy(berserk = it)) }
                )
                ScenarioToggle(
                    label = tr(Tr.SCENARIO_HEALING),
                    checked = scenario.healing,
                    onCheckedChange = { onChange(scenario.copy(healing = it)) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScenarioNumberField(
                    label = tr(Tr.SCENARIO_CRIT_CAP),
                    value = scenario.critCapPercent,
                    onValueChange = { onChange(scenario.copy(critCapPercent = it.coerceIn(0, 100))) },
                    modifier = Modifier.weight(1f)
                )
                ScenarioNumberField(
                    label = tr(Tr.SCENARIO_ENEMY_RES),
                    value = scenario.targetResistancePercent,
                    onValueChange = { onChange(scenario.copy(targetResistancePercent = it.coerceIn(0, DamageScenario.MAX_RESISTANCE_PERCENT))) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun SpellElement.label(lang: Lang): String =
    when (this) {
        SpellElement.FIRE -> if (lang == Lang.FR) "Feu" else "Fire"
        SpellElement.WATER -> if (lang == Lang.FR) "Eau" else "Water"
        SpellElement.EARTH -> if (lang == Lang.FR) "Terre" else "Earth"
        SpellElement.AIR -> if (lang == Lang.FR) "Air" else "Air"
    }

private fun RangeBand.label(lang: Lang): String =
    when (this) {
        RangeBand.MELEE -> if (lang == Lang.FR) "Mêlée" else "Melee"
        RangeBand.DISTANCE -> if (lang == Lang.FR) "Distance" else "Distance"
    }

private fun Orientation.label(lang: Lang): String =
    when (this) {
        Orientation.FACE -> if (lang == Lang.FR) "Face" else "Face"
        Orientation.SIDE -> if (lang == Lang.FR) "Côté" else "Side"
        Orientation.BACK -> if (lang == Lang.FR) "Dos" else "Back"
    }

@Composable
private fun <T> SegmentedEnumRow(
    label: String,
    values: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = WTypography.labelSmall.copy(color = WColor.muted))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                    .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            values.forEach { value ->
                val isSelected = value == selected
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) WColor.raised else Color.Transparent)
                            .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelOf(value),
                        style =
                            WTypography.labelSmall.copy(
                                color = if (isSelected) WColor.text else WColor.muted,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenarioToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) WColor.accent else WColor.bg)
                    .border(1.dp, if (checked) WColor.accent else WColor.border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", style = WTypography.labelSmall.copy(color = WColor.bg, fontWeight = FontWeight.Bold))
            }
        }
        Text(text = label, style = WTypography.labelSmall.copy(color = WColor.text))
    }
}

@Composable
private fun ScenarioNumberField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hold the raw text locally so the user can clear the field / type intermediate values without the
    // cursor jumping (driving a BasicTextField directly from value.toString() reformats every keystroke).
    var text by remember { mutableStateOf(value.toString()) }
    // Resync only when the external value actually diverges (e.g. a mode reset), never mid-typing.
    LaunchedEffect(value) { if ((text.toIntOrNull() ?: 0) != value) text = value.toString() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = WTypography.labelSmall.copy(color = WColor.muted))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() }.take(3)
                text = filtered
                onValueChange(filtered.toIntOrNull() ?: 0)
            },
            singleLine = true,
            textStyle = WTypography.bodySmall.copy(color = WColor.text, fontFamily = WType.mono),
            cursorBrush = SolidColor(WColor.accent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
        )
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
    onWeightChange: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onToggleMastery: (Characteristic) -> Unit,
) {
    val maximizedMasteriesMode = mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    RequestCard(
        title = tr(Tr.TARGET_STATS),
        trailing = targets.size.toString()
    ) {
        // One-line legend so the per-row priority bars read as priority without crowding each row.
        Text(
            text = tr(Tr.PRIORITY_HINT),
            style = WTypography.labelSmall.copy(color = WColor.faint),
            modifier = Modifier.padding(bottom = 10.dp)
        )
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
                        onWeightChange = onWeightChange,
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
                selected.forEach { def -> SelectedMasteryPill(def = def) }
            }
        }
    }
}

@Composable
private fun SelectedMasteryPill(def: StatDef) {
    Row(
        modifier =
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(def.color.copy(alpha = 0.14f))
                .border(1.dp, def.color.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Light tile behind the dark line-art mastery glyph (#127) so it stays visible on the card.
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(WColor.iconTile),
            contentAlignment = Alignment.Center
        ) {
            StatGlyphIcon(characteristic = def.characteristic, glyph = def.glyph, color = def.color, iconSize = 15.dp)
        }
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
internal fun TargetRowList(
    mode: ScoreComputationMode,
    targets: List<TargetRow>,
    onValueChange: (String, String) -> Unit,
    onWeightChange: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    targets.forEachIndexed { index, target ->
        // Stable per-row identity: without it Compose tracks rows positionally, so adding/removing a row
        // leaves each slot's remembered state (notably the PriorityMeter gesture coroutine) bound to the
        // row that *used* to sit there — clicks then land on the wrong row. `target.id` is the unique,
        // stable characteristic name, the same key the state model updates by.
        key(target.id) {
            if (index > 0) Hairline()
            TargetStatRow(
                target = target,
                kind = tr(if (target.isExact(mode)) Tr.KIND_EXACT else Tr.KIND_MAXIMIZE),
                onValueChange = { onValueChange(target.id, it) },
                onWeightChange = { onWeightChange(target.id, it) },
                onRemove = { onRemove(target.id) }
            )
        }
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
        // Light tile behind the symbol: the Specialized masteries (distance/melee/crit/back/berserk/
        // heal) and the "all elements" icon are dark, near-monochrome line-art that melts into the
        // dark tile; the elemental icons carry their own coloured disc and read fine on it. A constant
        // light backdrop guarantees contrast in both states — same treatment as the skill icons (#127).
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(WColor.iconTile)
                    .border(
                        width = 1.dp,
                        color = def.color.copy(alpha = if (checked) 0.85f else 0.35f),
                        shape = RoundedCornerShape(7.dp)
                    ),
            contentAlignment = Alignment.Center
        ) {
            StatGlyphIcon(characteristic = def.characteristic, glyph = def.glyph, color = def.color, iconSize = 18.dp)
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
    onWeightChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphChip(characteristic = target.characteristic, label = target.glyph, color = target.color)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target.characteristic.label(LocalLang.current),
                style = WTypography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = kind, style = WTypography.labelSmall)
        }
        NumberField(
            value = target.value,
            onValueChange = onValueChange,
            width = 62.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        PriorityMeter(
            weight = target.weight,
            onChange = onWeightChange,
            modifier = Modifier.testTag(priorityMeterTestTag(target.id))
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

private const val PRIORITY_MAX = 5

/** Test tag for a row's priority meter, keyed by the row id so UI tests can target one specific row. */
internal fun priorityMeterTestTag(rowId: String): String = "priority-meter-$rowId"

/** Gradient ends for the priority bar: yellow = least important, red = most important. */
private val PriorityLow = Color(0xFFE6C34A)
private val PriorityHigh = Color(0xFFD9655C)

/** Yellow→red colour for segment [level] (1..[PRIORITY_MAX]). */
private fun priorityColor(level: Int): Color = lerp(PriorityLow, PriorityHigh, if (PRIORITY_MAX <= 1) 0f else (level - 1f) / (PRIORITY_MAX - 1))

/** Maps a pointer x (px) on a [width]-px bar to a 1..[PRIORITY_MAX] level. */
private fun levelForX(
    x: Float,
    width: Int,
): Int {
    if (width <= 0) return 1
    return ((x / width) * PRIORITY_MAX).toInt().coerceIn(0, PRIORITY_MAX - 1) + 1
}

/**
 * Priority meter (#123): a segmented level bar of [PRIORITY_MAX] blocks, filled from the left up to
 * [weight] on a yellow→red gradient (1 = a single yellow block, [PRIORITY_MAX] = the full red-tipped
 * bar — higher is more important). Click or drag anywhere on the bar to set the level — the whole bar
 * is one forgiving target, so there are no tiny per-segment hit areas. A hover tooltip spells out what
 * the control is and means. Used on both constraint rows and the maximized-mastery summary so priority
 * is set the same way everywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PriorityMeter(
    weight: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val level = weight.coerceIn(1, PRIORITY_MAX)
    // `pointerInput(Unit)` launches its gesture coroutine once and captures `onChange` from that first
    // composition only. When this meter is reused for a different row (rows added/removed), a captured
    // stale lambda would drive clicks to the wrong row, so read the latest one via rememberUpdatedState.
    val currentOnChange by rememberUpdatedState(onChange)
    TooltipArea(
        delayMillis = 350,
        tooltip = {
            Column(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "${tr(Tr.PRIORITY)} · $level/$PRIORITY_MAX",
                    style = WTypography.labelMedium.copy(color = WColor.text, fontWeight = FontWeight.SemiBold)
                )
                Text(text = tr(Tr.PRIORITY_HINT), style = WTypography.labelSmall.copy(color = WColor.muted))
            }
        }
    ) {
        Row(
            modifier =
                modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> currentOnChange(levelForX(offset.x, size.width)) }
                    }.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> currentOnChange(levelForX(offset.x, size.width)) }
                        ) { change, _ ->
                            change.consume()
                            currentOnChange(levelForX(change.position.x, size.width))
                        }
                    },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (segment in 1..PRIORITY_MAX) {
                val lit = segment <= level
                Box(
                    modifier =
                        Modifier
                            .size(width = 13.dp, height = 16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (lit) priorityColor(segment) else WColor.raised)
                            .then(if (lit) Modifier else Modifier.border(1.dp, WColor.border, RoundedCornerShape(4.dp)))
                )
            }
        }
    }
}

@Composable
private fun ConstraintsCard(
    excludedRarities: Set<Rarity>,
    duration: String,
    stopAtMatch: Boolean,
    onToggleRarity: (Rarity) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
) {
    RequestCard(title = tr(Tr.CONSTRAINTS)) {
        RarityFilter(excludedRarities = excludedRarities, onToggleRarity = onToggleRarity)
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
private fun RarityFilter(
    excludedRarities: Set<Rarity>,
    onToggleRarity: (Rarity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Column {
            Text(text = tr(Tr.RARITIES), style = WTypography.bodyLarge)
            Text(text = tr(Tr.RARITIES_SUB), style = WTypography.labelSmall)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Rarity.entries.forEach { rarity ->
                RarityToggleChip(
                    rarity = rarity,
                    allowed = rarity !in excludedRarities,
                    onClick = { onToggleRarity(rarity) }
                )
            }
        }
    }
}

@Composable
private fun RarityToggleChip(
    rarity: Rarity,
    allowed: Boolean,
    onClick: () -> Unit,
) {
    val color = rarity.color()
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (allowed) color.copy(alpha = 0.16f) else WColor.surface)
                .border(1.dp, if (allowed) color.copy(alpha = 0.5f) else WColor.border, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        RarityIcon(rarity = rarity, size = 12.dp, modifier = Modifier.alpha(if (allowed) 1f else 0.4f))
        Text(
            text = rarity.label(LocalLang.current),
            style =
                WTypography.labelSmall.copy(
                    color = if (allowed) color else WColor.faint,
                    fontWeight = if (allowed) FontWeight.SemiBold else FontWeight.Normal
                )
        )
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
private fun SublimationsRunesCard(
    useSublimations: Boolean,
    forcedSublimations: List<String>,
    forcedRunes: List<String>,
    sublimationCatalog: List<String>,
    runeCatalog: List<String>,
    onToggleSublimations: (Boolean) -> Unit,
    onAddForcedSublimation: (String) -> Unit,
    onRemoveForcedSublimation: (String) -> Unit,
    onAddForcedRune: (String) -> Unit,
    onRemoveForcedRune: (String) -> Unit,
) {
    RequestCard(title = tr(Tr.SUBLIMATIONS_RUNES)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleSublimations(!useSublimations) }
                        .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (useSublimations) WColor.success else WColor.bg)
                            .border(1.dp, if (useSublimations) WColor.success else WColor.border, RoundedCornerShape(4.dp))
                )
                Text(text = tr(Tr.SOLVER_PICKS_SUBLIMATIONS), style = WTypography.labelMedium.copy(color = WColor.text))
            }
            NameChipsRow(
                label = tr(Tr.FORCED_SUBLIMATIONS),
                addLabel = tr(Tr.ADD_SUBLIMATION_CHIP),
                selected = forcedSublimations,
                catalog = sublimationCatalog,
                accent = WColor.success,
                onAdd = onAddForcedSublimation,
                onRemove = onRemoveForcedSublimation
            )
            NameChipsRow(
                label = tr(Tr.FORCED_RUNES),
                addLabel = tr(Tr.ADD_RUNE_CHIP),
                selected = forcedRunes,
                catalog = runeCatalog,
                accent = WColor.accent2,
                onAdd = onAddForcedRune,
                onRemove = onRemoveForcedRune
            )
        }
    }
}

@Composable
private fun NameChipsRow(
    label: String,
    addLabel: String,
    selected: List<String>,
    catalog: List<String>,
    accent: Color,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = WTypography.labelMedium.copy(color = WColor.muted))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            selected.forEach { name ->
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
                    Text(text = name, style = WTypography.labelMedium.copy(color = WColor.text))
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "×",
                        style = WTypography.labelMedium.copy(color = WColor.faint, lineHeight = 14.sp),
                        modifier = Modifier.clickable { onRemove(name) }
                    )
                }
            }
            Box {
                Box(
                    modifier =
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = addLabel, style = WTypography.labelMedium.copy(color = WColor.accent, lineHeight = 14.sp))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = {
                        expanded = false
                        query = ""
                    }
                ) {
                    val options =
                        catalog
                            .asSequence()
                            .filter { it !in selected }
                            .filter { query.isBlank() || it.contains(query, ignoreCase = true) }
                            .take(50)
                            .toList()
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = WTypography.labelMedium.copy(color = WColor.text),
                        cursorBrush = SolidColor(WColor.accent),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    options.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(text = name, style = WTypography.labelMedium.copy(color = WColor.text)) },
                            onClick = {
                                onAdd(name)
                                expanded = false
                                query = ""
                            }
                        )
                    }
                }
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
        RarityIcon(rarity = item.rarity, size = 13.dp)
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
    characteristic: Characteristic,
    label: String,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                // Light tile so dark line-art stat symbols stay legible on the dark theme (see WColor.iconTile).
                .background(WColor.iconTile)
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        StatGlyphIcon(characteristic = characteristic, glyph = label, color = color, iconSize = 20.dp)
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
