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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.RolePreset
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.SublimationRarity
import me.chosante.ui.components.BossResistanceChips
import me.chosante.ui.components.Hairline
import me.chosante.ui.components.MonsterIcon
import me.chosante.ui.components.RarityIcon
import me.chosante.ui.components.StatGlyphIcon
import me.chosante.ui.components.VerticalScrollHints
import me.chosante.ui.components.displayFamily
import me.chosante.ui.components.displayName
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.localized
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
import me.chosante.ui.theme.WRarityColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun RequestPanel(
    ui: UiState,
    onModeChange: (ScoreComputationMode) -> Unit,
    onScenarioChange: (DamageScenario) -> Unit,
    onOpenBossPicker: () -> Unit = {},
    onClearBoss: () -> Unit = {},
    onBossElementChange: (SpellElement?) -> Unit = {},
    onBossDifficultyChange: (String) -> Unit = {},
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
    onToggleSublimations: (Boolean) -> Unit = {},
    onMaxSublimationTierChange: (Int?) -> Unit = {},
    onOpenSublimationPicker: () -> Unit = {},
    onRemoveForcedSublimation: (String) -> Unit = {},
    onOpenExcludedSublimationPicker: () -> Unit = {},
    onRemoveExcludedSublimation: (String) -> Unit = {},
    onToggleExcludeAllSublimationsOfRarity: (SublimationRarity) -> Unit = {},
    onOpenPassivePicker: () -> Unit = {},
    onRemoveForcedPassive: (String) -> Unit = {},
    onVerifyOptimalityChange: (Boolean) -> Unit = {},
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
                duration = ui.duration,
                stopAtMatch = ui.stopAtMatch,
                verifyOptimality = ui.verifyOptimality,
                onSelect = onModeChange,
                onDurationChange = onDurationChange,
                onStopAtMatchChange = onStopAtMatchChange,
                onVerifyOptimalityChange = onVerifyOptimalityChange
            )
            if (ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                BossCard(
                    boss = ui.selectedBoss,
                    bossElement = ui.bossElement,
                    difficulty = ui.bossDifficulty,
                    onOpenPicker = onOpenBossPicker,
                    onClear = onClearBoss,
                    onElementChange = onBossElementChange,
                    onDifficultyChange = onBossDifficultyChange
                )
                DamageScenarioCard(
                    scenario = ui.scenario,
                    bossSelected = ui.selectedBoss != null,
                    onChange = onScenarioChange
                )
            }
            RarityCard(
                excludedRarities = ui.excludedRarities,
                onToggleRarity = onToggleRarity
            )
            TargetStatsCard(
                mode = ui.mode,
                targets = ui.targets,
                onValueChange = onTargetValueChange,
                onWeightChange = onTargetWeightChange,
                onRemove = onRemoveTarget,
                onAdd = onAddTarget,
                onToggleMastery = onToggleMastery
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
                maxSublimationTier = ui.maxSublimationTier,
                forcedSublimations = ui.forcedSublimations,
                excludedSublimations = ui.excludedSublimations,
                onToggleSublimations = onToggleSublimations,
                onMaxSublimationTierChange = onMaxSublimationTierChange,
                onOpenSublimationPicker = onOpenSublimationPicker,
                onRemoveForcedSublimation = onRemoveForcedSublimation,
                onOpenExcludedSublimationPicker = onOpenExcludedSublimationPicker,
                onRemoveExcludedSublimation = onRemoveExcludedSublimation,
                onToggleExcludeAllSublimationsOfRarity = onToggleExcludeAllSublimationsOfRarity
            )
            PassivesCard(
                forcedPassives = ui.forcedPassives,
                slots = PassiveCatalog.slotsForLevel(ui.level),
                onOpenPassivePicker = onOpenPassivePicker,
                onRemoveForcedPassive = onRemoveForcedPassive
            )
        }
        VerticalScrollHints(scroll)
    }
}

/** Test tag of the "Check optimality after the search" switch (see [SearchModeCard]). */
internal const val VERIFY_OPTIMALITY_TOGGLE_TAG = "verify-optimality-toggle"

@Composable
internal fun SearchModeCard(
    selected: ScoreComputationMode,
    duration: String,
    stopAtMatch: Boolean,
    verifyOptimality: Boolean,
    onSelect: (ScoreComputationMode) -> Unit,
    onDurationChange: (String) -> Unit,
    onStopAtMatchChange: (Boolean) -> Unit,
    onVerifyOptimalityChange: (Boolean) -> Unit,
) {
    RequestCard(title = tr(Tr.SEARCH_MODE)) {
        ModeSelector(selected = selected, onSelect = onSelect)
        Spacer(modifier = Modifier.height(12.dp))
        Hairline()
        ConstraintRow(label = tr(Tr.SEARCH_DURATION), sublabel = tr(Tr.SEARCH_DURATION_SUB)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberField(value = duration, onValueChange = onDurationChange, width = 56.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = tr(Tr.SECONDS_SHORT), style = WTypography.labelMedium)
            }
        }
        // The post-search optimality check only exists for the two maximizing modes (most masteries, max damage):
        // precision mode has no proof to run afterwards, so a switch there would do nothing — it is not offered
        // (the value persists, and is back as soon as another mode is picked).
        if (selected != ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
            Hairline()
            ConstraintRow(label = tr(Tr.VERIFY_OPTIMALITY), sublabel = tr(Tr.VERIFY_OPTIMALITY_SUB)) {
                Toggle(
                    checked = verifyOptimality,
                    onCheckedChange = onVerifyOptimalityChange,
                    modifier = Modifier.testTag(VERIFY_OPTIMALITY_TOGGLE_TAG)
                )
            }
        }
        // "Stop at 100% match" only makes sense in precision mode — it's the only mode with an exact
        // target to reach; the other modes maximise (mastery / damage) and never report a 100% match.
        if (selected == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
            Hairline()
            ConstraintRow(label = tr(Tr.STOP_AT_MATCH)) {
                Toggle(checked = stopAtMatch, onCheckedChange = onStopAtMatchChange)
            }
        }
    }
}

@Composable
private fun BossCard(
    boss: Monster?,
    bossElement: SpellElement?,
    difficulty: String,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
    onElementChange: (SpellElement?) -> Unit,
    onDifficultyChange: (String) -> Unit,
) {
    val lang = LocalLang.current
    val autoLabel = tr(Tr.BOSS_ELEMENT_AUTO)
    RequestCard(title = tr(Tr.BOSS)) {
        if (boss == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = tr(Tr.BOSS_NONE_HINT),
                    style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
                )
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                            .clickable { onOpenPicker() }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = tr(Tr.BOSS_PICK), style = WTypography.labelMedium.copy(color = WColor.accent))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonsterIcon(monster = boss, size = 44.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = boss.displayName(lang),
                            style = WTypography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text =
                                buildString {
                                    append(tr(Tr.BOSS_LEVEL_SHORT))
                                    append(' ')
                                    append(boss.level)
                                    boss.displayFamily(lang)?.let {
                                        append("  ·  ")
                                        append(it)
                                    }
                                },
                            style = WTypography.labelSmall.copy(color = WColor.muted)
                        )
                    }
                    Text(
                        text = tr(Tr.BOSS_CHANGE),
                        style = WTypography.labelSmall.copy(color = WColor.accent),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onOpenPicker() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                    Text(
                        text = tr(Tr.BOSS_REMOVE),
                        style = WTypography.labelSmall.copy(color = WColor.faint),
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onClear() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
                BossResistanceChips(boss = boss, highlight = bossElement, modifier = Modifier.fillMaxWidth())
                SegmentedEnumRow(
                    label = tr(Tr.BOSS_ELEMENT),
                    values = listOf<SpellElement?>(null) + SpellElement.entries,
                    selected = bossElement,
                    labelOf = { it?.label(lang) ?: autoLabel },
                    onSelect = onElementChange
                )
                ScenarioNumberField(
                    label = tr(Tr.BOSS_DIFFICULTY),
                    value = difficulty.toIntOrNull() ?: 1,
                    onValueChange = { onDifficultyChange(it.coerceAtLeast(1).toString()) },
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        }
    }
}

@Composable
private fun DamageScenarioCard(
    scenario: DamageScenario,
    bossSelected: Boolean,
    onChange: (DamageScenario) -> Unit,
) {
    val lang = LocalLang.current
    RequestCard(title = tr(Tr.DAMAGE_SCENARIO)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Role presets sit ABOVE the manual controls: one click sets orientation / range / survivability
            // for a play-style, then the individual rows below can still fine-tune. Applied through the same
            // onChange path (→ BuildSearchModel.setScenario).
            RolePresetRow(
                onPick = { preset -> onChange(preset.apply(scenario)) }
            )
            // The attack element is derived from the picked boss (auto-pick the best playable element, or the
            // boss card's element override), so this manual selector only applies to a free, no-boss search.
            if (!bossSelected) {
                SegmentedEnumRow(
                    label = tr(Tr.SCENARIO_ELEMENT),
                    values = SpellElement.entries,
                    selected = scenario.element,
                    labelOf = { it.label(lang) },
                    onSelect = { onChange(scenario.copy(element = it)) }
                )
            }
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
                // A picked boss supplies its real per-element resistances (the chips on the boss card), so this
                // manual enemy-resistance field only applies to a free, no-boss damage search.
                if (!bossSelected) {
                    ScenarioNumberField(
                        label = tr(Tr.SCENARIO_ENEMY_RES),
                        value = scenario.targetResistancePercent,
                        onValueChange = { onChange(scenario.copy(targetResistancePercent = it.coerceIn(0, DamageScenario.MAX_RESISTANCE_PERCENT))) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Survivability soft-floor: opt-in toggle + its effective-HP-proxy floor (only meaningful when on).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScenarioToggle(
                    label = tr(Tr.SCENARIO_SURVIVAL_FLOOR),
                    checked = scenario.survivabilityFloor,
                    onCheckedChange = { onChange(scenario.copy(survivabilityFloor = it)) }
                )
                if (scenario.survivabilityFloor) {
                    ScenarioNumberField(
                        label = tr(Tr.SCENARIO_MIN_EHP),
                        value = scenario.minEffectiveHp,
                        onValueChange = { onChange(scenario.copy(minEffectiveHp = it.coerceAtLeast(0))) },
                        maxDigits = 6,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RolePresetRow(onPick: (RolePreset) -> Unit) {
    val lang = LocalLang.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = tr(Tr.SCENARIO_ROLE), style = WTypography.labelSmall.copy(color = WColor.muted))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RolePreset.entries.forEach { preset ->
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WColor.bg)
                            .border(1.dp, WColor.border, RoundedCornerShape(6.dp))
                            .clickable { onPick(preset) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset.label(lang),
                        style = WTypography.labelSmall.copy(color = WColor.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun RolePreset.label(lang: Lang): String =
    when (this) {
        RolePreset.DISTANCE_DPS -> Tr.ROLE_DISTANCE_DPS.value(lang)
        RolePreset.MELEE_DPS -> Tr.ROLE_MELEE_DPS.value(lang)
        RolePreset.TANK -> Tr.ROLE_TANK.value(lang)
    }

private fun SpellElement.label(lang: Lang): String =
    when (this) {
        SpellElement.FIRE -> Tr.ELEMENT_FIRE.value(lang)
        SpellElement.WATER -> Tr.ELEMENT_WATER.value(lang)
        SpellElement.EARTH -> Tr.ELEMENT_EARTH.value(lang)
        SpellElement.AIR -> Tr.ELEMENT_AIR.value(lang)
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
    maxDigits: Int = 3,
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
                val filtered = raw.filter { it.isDigit() }.take(maxDigits)
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

/** A search mode as the selector offers it: its title, and the sentence that says what it does (caption + hover tooltip). */
private class ModeOption(
    val mode: ScoreComputationMode,
    val title: Tr,
    val description: Tr,
)

private val modeOptions =
    listOf(
        ModeOption(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, Tr.MODE_MASTERIES, Tr.MODE_MASTERIES_SUB),
        ModeOption(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, Tr.MODE_PRECISION, Tr.MODE_PRECISION_SUB),
        ModeOption(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, Tr.MODE_MAX_DAMAGE, Tr.MODE_MAX_DAMAGE_SUB)
    )

/**
 * Narrower than this the three titles no longer fit side by side without a word breaking in the middle: the longest word
 * ("Masteries", 57 dp at the segment font) needs a segment of ~68 dp, so three segments plus the control's padding want 220 dp.
 * The selector then stacks its options, one full-width row each.
 */
private val MODE_SELECTOR_SIDE_BY_SIDE_MIN_WIDTH = 220.dp

/**
 * The search-mode selector: the three modes as titled segments, and under them one sentence saying what the SELECTED mode does
 * (the others show theirs in a hover tooltip). The segments used to carry a second line each — "minimum constraints,",
 * "Précisi/on" — that was clipped at every width the panel can be resized to; wrapping a sentence under the control instead
 * fits at any width, and the options stack when the panel is too narrow for three titles side by side.
 */
@Composable
private fun ModeSelector(
    selected: ScoreComputationMode,
    onSelect: (ScoreComputationMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val sideBySide = maxWidth >= MODE_SELECTOR_SIDE_BY_SIDE_MIN_WIDTH
            val frame =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(10.dp))
                    .padding(4.dp)
            if (sideBySide) {
                Row(modifier = frame, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    modeOptions.forEach { option ->
                        ModeSegment(
                            option = option,
                            selected = selected == option.mode,
                            onClick = { onSelect(option.mode) },
                            modifier = Modifier.weight(1f).height(40.dp)
                        )
                    }
                }
            } else {
                Column(modifier = frame, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    modeOptions.forEach { option ->
                        ModeSegment(
                            option = option,
                            selected = selected == option.mode,
                            onClick = { onSelect(option.mode) },
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        )
                    }
                }
            }
        }
        modeOptions.firstOrNull { it.mode == selected }?.let { option ->
            Text(
                text = tr(option.description).replaceFirstChar { it.titlecase() },
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 14.sp),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeSegment(
    option: ModeOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TooltipArea(
        modifier = modifier,
        delayMillis = 350,
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 260.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(text = tr(option.description).replaceFirstChar { it.titlecase() }, style = WTypography.labelSmall.copy(color = WColor.text))
            }
        }
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) WColor.raised else Color.Transparent)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr(option.title),
                style =
                    WTypography.labelMedium.copy(
                        color = if (selected) WColor.text else WColor.muted,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
            )
        }
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
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tr(Tr.DI_AUTOMAX_HINT),
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetStatRow(
    target: TargetRow,
    kind: String,
    onValueChange: (String) -> Unit,
    onWeightChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val priority: @Composable () -> Unit = {
            PriorityMeter(
                weight = target.weight,
                onChange = onWeightChange,
                modifier = Modifier.testTag(priorityMeterTestTag(target.id))
            )
        }
        if (maxWidth >= TARGET_ROW_ROOMY_MIN_WIDTH) {
            // Glyph | name over (kind + priority) | value | remove. The name column gets everything the controls leave over.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphChip(characteristic = target.characteristic, label = target.glyph, color = target.color)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    TargetLabel(target)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = kind, style = WTypography.labelSmall, maxLines = 1)
                        priority()
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                NumberField(value = target.value, onValueChange = onValueChange, width = TARGET_VALUE_WIDTH)
                Spacer(modifier = Modifier.width(6.dp))
                RemoveTargetButton(onRemove)
            }
        } else {
            // Too narrow to leave the name room beside value + priority (the name read "Cri…" / "He…"): the name gets the
            // full width of its own line, with the controls on a second line under it.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    GlyphChip(characteristic = target.characteristic, label = target.glyph, color = target.color)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        TargetLabel(target)
                        Text(text = kind, style = WTypography.labelSmall, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    RemoveTargetButton(onRemove)
                }
                Row(
                    modifier = Modifier.padding(start = 36.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NumberField(value = target.value, onValueChange = onValueChange, width = TARGET_VALUE_WIDTH)
                    priority()
                }
            }
        }
    }
}

/** Width of a target row's value field: five digits ("10000" HP) fit. */
private val TARGET_VALUE_WIDTH = 62.dp

/**
 * Below this row width the stat name can no longer sit beside the value field and the priority bar without being cut to
 * "Cri…": the name column would have to hold the "minimum + priority" line (~104 dp) and still be allowed to wrap, and the
 * fixed parts (glyph 28 + value 62 + remove 24 + gaps 20) already take 134 dp. The row then uses its two-line layout.
 */
private val TARGET_ROW_ROOMY_MIN_WIDTH = 240.dp

/** The stat's name, wrapping at word boundaries (two lines) instead of being cut; a hover tooltip shows it in full. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetLabel(target: TargetRow) {
    val fullLabel = target.characteristic.label(LocalLang.current)
    TooltipArea(
        delayMillis = 350,
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(text = fullLabel, style = WTypography.labelMedium.copy(color = WColor.text))
            }
        }
    ) {
        Text(
            text = fullLabel,
            style = WTypography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RemoveTargetButton(onRemove: () -> Unit) {
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

private const val PRIORITY_MAX = 5

/**
 * The priority bar is 5 × 9 dp + 4 × 2 dp = 53 dp (it was 77): the whole bar is still ONE click/drag target, so narrower blocks
 * lose nothing, and the 24 dp saved go to the stat name that the bar used to squeeze to "Cri…".
 */
private val PRIORITY_BLOCK_WIDTH = 9.dp
private val PRIORITY_BLOCK_GAP = 2.dp

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
            horizontalArrangement = Arrangement.spacedBy(PRIORITY_BLOCK_GAP),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (segment in 1..PRIORITY_MAX) {
                val lit = segment <= level
                Box(
                    modifier =
                        Modifier
                            .size(width = PRIORITY_BLOCK_WIDTH, height = 16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (lit) priorityColor(segment) else WColor.raised)
                            .then(if (lit) Modifier else Modifier.border(1.dp, WColor.border, RoundedCornerShape(4.dp)))
                )
            }
        }
    }
}

@Composable
private fun RarityCard(
    excludedRarities: Set<Rarity>,
    onToggleRarity: (Rarity) -> Unit,
) {
    RequestCard(title = tr(Tr.RARITIES)) {
        RarityFilter(excludedRarities = excludedRarities, onToggleRarity = onToggleRarity)
    }
}

@Composable
private fun RarityFilter(
    excludedRarities: Set<Rarity>,
    onToggleRarity: (Rarity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(text = tr(Tr.RARITIES_SUB), style = WTypography.labelSmall)
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
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
    maxSublimationTier: Int?,
    forcedSublimations: List<String>,
    excludedSublimations: List<String>,
    onToggleSublimations: (Boolean) -> Unit,
    onMaxSublimationTierChange: (Int?) -> Unit,
    onOpenSublimationPicker: () -> Unit,
    onRemoveForcedSublimation: (String) -> Unit,
    onOpenExcludedSublimationPicker: () -> Unit,
    onRemoveExcludedSublimation: (String) -> Unit,
    onToggleExcludeAllSublimationsOfRarity: (SublimationRarity) -> Unit,
) {
    val forcedSublimationColors =
        remember {
            WakfuBestBuildFinderAlgorithm.sublimations
                .distinctBy { it.stateId }
                .flatMap { sub ->
                    listOf(
                        sub.name.fr to sub.rarity.displayColor(),
                        sub.name.en to sub.rarity.displayColor()
                    )
                }.toMap()
        }
    val availableLevels =
        remember {
            WakfuBestBuildFinderAlgorithm.sublimations
                .map { it.nameTier }
                .filter { it > 0 }
                .distinct()
                .sorted()
        }
    val sublimationNamesByRarity =
        remember {
            WakfuBestBuildFinderAlgorithm.sublimations
                .distinctBy { it.stateId }
                .groupBy({ it.rarity }, { it.name.fr })
        }
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
            SublimationLevelCapFilter(
                levels = availableLevels,
                selected = maxSublimationTier,
                enabled = useSublimations,
                onSelect = onMaxSublimationTierChange
            )
            ForcedNameChips(
                label = tr(Tr.FORCED_SUBLIMATIONS),
                addLabel = tr(Tr.ADD_SUBLIMATION_CHIP),
                selected = forcedSublimations,
                accent = WColor.success,
                accentForName = { name -> forcedSublimationColors[name] ?: WColor.success },
                onAdd = onOpenSublimationPicker,
                onRemove = onRemoveForcedSublimation
            )
            ForcedNameChips(
                label = tr(Tr.EXCLUDED_SUBLIMATIONS),
                addLabel = tr(Tr.BAN_SUBLIMATION_CHIP),
                selected = excludedSublimations,
                accent = WColor.danger,
                onAdd = onOpenExcludedSublimationPicker,
                onRemove = onRemoveExcludedSublimation
            )
            SublimationRarityQuickExcludeRow(
                namesByRarity = sublimationNamesByRarity,
                excludedSublimations = excludedSublimations,
                onToggle = onToggleExcludeAllSublimationsOfRarity
            )
            Text(
                text = tr(Tr.RUNES_PER_ITEM_HINT),
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
            )
            Text(
                text = tr(Tr.RUNES_ALLGOLD_HINT),
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
            )
        }
    }
}

private fun SublimationRarity.displayColor(): Color =
    when (this) {
        SublimationRarity.EPIC -> WRarityColor.epic
        SublimationRarity.RELIC -> WRarityColor.relic
        SublimationRarity.NORMAL -> WColor.success
    }

/**
 * One toggle chip per [SublimationRarity] that has any sublimations at all (so a data set with no
 * normal-tier subs, say, doesn't show a dead button) — bulk-excludes or re-allows every sublimation of
 * that rarity in one click. A chip reads as "active" (danger-tinted) once every name of that rarity is
 * already in [excludedSublimations]; clicking it then undoes the bulk exclusion instead of re-applying it.
 */
@Composable
private fun SublimationRarityQuickExcludeRow(
    namesByRarity: Map<SublimationRarity, List<String>>,
    excludedSublimations: List<String>,
    onToggle: (SublimationRarity) -> Unit,
) {
    val lang = LocalLang.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SublimationRarity.entries.forEach { rarity ->
            val names = namesByRarity[rarity].orEmpty()
            if (names.isNotEmpty()) {
                val allExcluded = names.all { it in excludedSublimations }
                val color = rarity.displayColor()
                Row(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (allExcluded) WColor.danger.copy(alpha = 0.16f) else WColor.surface)
                            .border(1.dp, if (allExcluded) WColor.danger.copy(alpha = 0.5f) else WColor.border, RoundedCornerShape(8.dp))
                            .clickable { onToggle(rarity) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color.copy(alpha = if (allExcluded) 0.4f else 1f))
                    )
                    Text(
                        text =
                            if (allExcluded) {
                                tr(Tr.UNEXCLUDE_ALL_SUBLIMATIONS_RARITY).format(rarity.label(lang))
                            } else {
                                tr(Tr.EXCLUDE_ALL_SUBLIMATIONS_RARITY).format(rarity.label(lang))
                            },
                        style =
                            WTypography.labelSmall.copy(
                                color = if (allExcluded) WColor.danger else WColor.muted,
                                fontWeight = if (allExcluded) FontWeight.SemiBold else FontWeight.Normal
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun SublimationLevelCapFilter(
    levels: List<Int>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Text(text = tr(Tr.SUBLIMATION_LEVEL_CAP), style = WTypography.labelMedium.copy(color = WColor.muted))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SublimationLevelChip(
                label = tr(Tr.SUBLIMATION_LEVEL_ALL),
                selected = selected == null,
                enabled = enabled,
                onClick = { onSelect(null) }
            )
            levels.forEach { level ->
                SublimationLevelChip(
                    label = tr(Tr.SUBLIMATION_LEVEL_UP_TO).format(level),
                    selected = selected == level,
                    enabled = enabled,
                    onClick = { onSelect(level) }
                )
            }
        }
        Text(
            text = tr(Tr.SUBLIMATION_LEVEL_CAP_HINT),
            style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
        )
    }
}

@Composable
private fun SublimationLevelChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) WColor.accent else WColor.border
    Box(
        modifier =
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) WColor.accent.copy(alpha = 0.16f) else WColor.bg)
                .border(1.dp, if (selected) WColor.accent.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(8.dp))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style =
                WTypography.labelSmall.copy(
                    color = if (selected) color else WColor.muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
        )
    }
}

@Composable
private fun PassivesCard(
    forcedPassives: List<String>,
    slots: Int,
    onOpenPassivePicker: () -> Unit,
    onRemoveForcedPassive: (String) -> Unit,
) {
    RequestCard(title = "${tr(Tr.FORCED_PASSIVES)}  ${forcedPassives.size}/$slots") {
        ForcedNameChips(
            label = tr(Tr.FORCED_PASSIVES),
            addLabel = if (forcedPassives.size < slots) tr(Tr.ADD_PASSIVE_CHIP) else tr(Tr.PASSIVE_SLOTS_FULL),
            selected = forcedPassives,
            accent = WColor.accent,
            onAdd = { if (forcedPassives.size < slots) onOpenPassivePicker() },
            onRemove = onRemoveForcedPassive
        )
    }
}

/**
 * Removable name chips plus a button that opens a centered picker modal. Used for forced sublimations,
 * which are chosen by translated title + effect text in [me.chosante.ui.components.ModalHost]'s
 * sublimation picker.
 */
@Composable
private fun ForcedNameChips(
    label: String,
    addLabel: String,
    selected: List<String>,
    accent: Color,
    accentForName: (String) -> Color = { accent },
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = WTypography.labelMedium.copy(color = WColor.muted))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            selected.forEach { name ->
                val chipAccent = accentForName(name)
                Row(
                    modifier =
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(WColor.raised)
                            .border(1.dp, chipAccent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
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
            Box(
                modifier =
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                        .clickable { onAdd() }
                        .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = addLabel, style = WTypography.labelMedium.copy(color = WColor.accent, lineHeight = 14.sp))
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

private fun Characteristic.masteryOptionLabel(lang: Lang): String =
    when (this) {
        Characteristic.MASTERY_ELEMENTARY -> localized(lang, fr = "Toutes", en = "All", es = "Todas", pt = "Todos")
        Characteristic.MASTERY_ELEMENTARY_WATER -> localized(lang, fr = "Eau", en = "Water", es = "Agua", pt = "Água")
        Characteristic.MASTERY_ELEMENTARY_FIRE -> localized(lang, fr = "Feu", en = "Fire", es = "Fuego", pt = "Fogo")
        Characteristic.MASTERY_ELEMENTARY_EARTH -> localized(lang, fr = "Terre", en = "Earth", es = "Tierra", pt = "Terra")
        Characteristic.MASTERY_ELEMENTARY_WIND -> localized(lang, fr = "Air", en = "Air", es = "Aire", pt = "Ar")
        Characteristic.MASTERY_DISTANCE -> localized(lang, fr = "Distance", en = "Distance", es = "Distancia", pt = "Distância")
        Characteristic.MASTERY_MELEE -> localized(lang, fr = "Mêlée", en = "Melee", es = "Melé", pt = "Curta distância")
        Characteristic.MASTERY_CRITICAL -> localized(lang, fr = "Critique", en = "Critical", es = "Crítica", pt = "Crítico")
        Characteristic.MASTERY_BACK -> localized(lang, fr = "Dos", en = "Rear", es = "Espalda", pt = "Costas")
        Characteristic.MASTERY_BERSERK -> localized(lang, fr = "Berserk", en = "Berserk", es = "Berserker", pt = "Berserk")
        Characteristic.MASTERY_HEALING -> localized(lang, fr = "Soin", en = "Healing", es = "Cura", pt = "Cura")
        else -> label(lang)
    }
