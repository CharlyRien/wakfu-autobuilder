package me.chosante.ui.paperdoll

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Equipment
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
import me.chosante.ui.components.RarityIcon
import me.chosante.ui.components.iconResourcePath
import me.chosante.ui.components.itemResourcePath
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.state.WhatsNew
import me.chosante.ui.state.color
import me.chosante.ui.state.statColor
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun PaperdollPanel(
    ui: UiState,
    onForceItem: (Equipment) -> Unit,
    onExcludeItem: (Equipment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = remember(ui.build) { slotAssignments(ui.build?.equipments.orEmpty()) }
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(WDimens.pad)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlotColumn(
                        slots = leftSlots,
                        assignments = slots,
                        ui = ui,
                        rightAlign = false,
                        onForceItem = onForceItem,
                        onExcludeItem = onExcludeItem,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    SlotColumn(
                        slots = rightSlots,
                        assignments = slots,
                        ui = ui,
                        rightAlign = true,
                        onForceItem = onForceItem,
                        onExcludeItem = onExcludeItem,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    bottomSlots.forEach { slot ->
                        // The weight must sit on a direct Row child: a filled slot wraps its
                        // content in ContextMenuArea (no modifier param), which would otherwise
                        // swallow the weight and let the weapon expand over its neighbours.
                        Box(modifier = Modifier.weight(1f)) {
                            val weaponEquipment = slots[slot.id]
                            EquipmentSlot(
                                slot = slot,
                                equipment = weaponEquipment,
                                runes = weaponEquipment?.let { ui.build?.runes?.get(it) }.orEmpty(),
                                subs = weaponEquipment?.let { ui.build?.sublimations?.get(it) }.orEmpty(),
                                idle = ui.phase == Phase.Idle,
                                justLanded = weaponEquipment?.equipmentId == ui.lastLandedEquipmentId,
                                rightAlign = false,
                                onForceItem = onForceItem,
                                onExcludeItem = onExcludeItem,
                                forced = ui.isForcedEquipment(slots[slot.id]),
                                excluded = ui.isExcludedEquipment(slots[slot.id]),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            if (ui.phase == Phase.Searching && ui.build == null) {
                SolverLoadingOverlay(
                    ui = ui,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        val appVersion = WhatsNew.appVersion
        val versionPrefix = tr(Tr.APP_VERSION_LABEL)
        val gameDataPrefix = tr(Tr.GAME_DATA_LABEL)
        val dataVersion = WakfuBestBuildFinderAlgorithm.dataVersion
        val versionLine =
            if (appVersion != null) {
                "$versionPrefix $appVersion  ·  $gameDataPrefix $dataVersion"
            } else {
                "$gameDataPrefix $dataVersion"
            }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, WColor.hairline)
                    .padding(vertical = 10.dp, horizontal = WDimens.pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = tr(Tr.DISCLAIMER),
                style = WTypography.labelSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = versionLine,
                style = WTypography.labelSmall,
                color = WColor.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SolverLoadingOverlay(
    ui: UiState,
    modifier: Modifier = Modifier,
) {
    val title = tr(Tr.PREPARING_OR_TOOLS_MODEL)
    Column(
        modifier =
            modifier
                .width(310.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(WColor.surface.copy(alpha = 0.96f))
                .border(1.dp, WColor.border, RoundedCornerShape(11.dp))
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center
        )
        LinearProgressIndicator(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp)),
            color = WColor.accent,
            trackColor = WColor.border
        )
        Text(
            text = tr(Tr.FIRST_RESULT_HINT),
            style = WTypography.labelSmall.copy(color = WColor.muted, textAlign = TextAlign.Center),
            textAlign = TextAlign.Center,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun SlotColumn(
    slots: List<DollSlot>,
    assignments: Map<String, Equipment>,
    ui: UiState,
    rightAlign: Boolean,
    onForceItem: (Equipment) -> Unit,
    onExcludeItem: (Equipment) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Size the slots to the available height so all of them fit uniformly. With a hard-coded
    // per-slot height the column overflowed on shorter windows and Compose squeezed only the *last*
    // slot (cape / boots) into the leftover space — they then rendered tiny. Splitting the height
    // evenly (clamped) makes the slots shrink together instead.
    val spacing = 14.dp
    BoxWithConstraints(modifier = modifier) {
        val cardHeight = ((maxHeight - spacing * (slots.size - 1)) / slots.size).coerceIn(64.dp, 84.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically)
        ) {
            slots.forEach { slot ->
                val equipment = assignments[slot.id]
                EquipmentSlot(
                    slot = slot,
                    equipment = equipment,
                    runes = equipment?.let { ui.build?.runes?.get(it) }.orEmpty(),
                    subs = equipment?.let { ui.build?.sublimations?.get(it) }.orEmpty(),
                    idle = ui.phase == Phase.Idle,
                    justLanded = equipment?.equipmentId == ui.lastLandedEquipmentId,
                    rightAlign = rightAlign,
                    onForceItem = onForceItem,
                    onExcludeItem = onExcludeItem,
                    forced = ui.isForcedEquipment(assignments[slot.id]),
                    excluded = ui.isExcludedEquipment(assignments[slot.id]),
                    cardHeight = cardHeight,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EquipmentSlot(
    slot: DollSlot,
    equipment: Equipment?,
    runes: List<RuneType>,
    subs: List<Sublimation>,
    idle: Boolean,
    justLanded: Boolean,
    rightAlign: Boolean,
    onForceItem: (Equipment) -> Unit,
    onExcludeItem: (Equipment) -> Unit,
    forced: Boolean = false,
    excluded: Boolean = false,
    cardHeight: Dp = 84.dp,
    modifier: Modifier = Modifier,
) {
    if (equipment == null) {
        SlotRowContent(slot, null, emptyList(), emptyList(), idle, justLanded, rightAlign, forced = false, excluded = false, cardHeight = cardHeight, modifier = modifier)
        return
    }
    val forceLabel = tr(Tr.REQUIRE)
    val excludeLabel = tr(Tr.BAN)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem(forceLabel) { onForceItem(equipment) },
                ContextMenuItem(excludeLabel) { onExcludeItem(equipment) }
            )
        }
    ) {
        TooltipArea(
            modifier = modifier,
            delayMillis = 350,
            tooltipPlacement = SlotTooltipPlacement,
            tooltip = { ItemTooltip(slot = slot, equipment = equipment, runes = runes, subs = subs) }
        ) {
            Box(modifier = Modifier.fillMaxWidth().hoverable(interaction)) {
                SlotRowContent(slot, equipment, runes, subs, idle, justLanded, rightAlign, forced, excluded, cardHeight, Modifier.fillMaxWidth())
                val cornerAlign = if (rightAlign) Alignment.TopStart else Alignment.TopEnd
                if (hovered) {
                    SlotActions(
                        onForce = { onForceItem(equipment) },
                        onExclude = { onExcludeItem(equipment) },
                        forced = forced,
                        excluded = excluded,
                        modifier = Modifier.align(cornerAlign).padding(5.dp)
                    )
                } else if (forced || excluded) {
                    // Persistent feedback (#125): the green/red slot actions add the item to the
                    // required/excluded lists silently; show a badge so the click visibly "sticks".
                    SlotStatusBadge(forced = forced, modifier = Modifier.align(cornerAlign).padding(7.dp))
                }
            }
        }
    }
}

/**
 * Tooltip placement that anchors the item card to the *slot row* instead of the moving cursor.
 *
 * The stock [TooltipPlacement.CursorPoint] positions the popup relative to the pointer; for a tall
 * tooltip near the bottom of the window its built-in clamp flips the popup *up over the cursor*. The
 * pointer then rests on the popup, [TooltipArea] sees it leave the slot and hides the tooltip, the
 * pointer is back on the slot so it re-shows — a position-dependent flicker (notably on Windows,
 * where the enter/exit event ordering differs). Anchoring the card below the slot — or above it when
 * there is no room below — keeps it clear of the pointer, so that loop can never start.
 */
@OptIn(ExperimentalFoundationApi::class)
private object SlotTooltipPlacement : TooltipPlacement {
    @Composable
    override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
        val density = LocalDensity.current
        val gapPx = with(density) { 6.dp.roundToPx() }
        val marginPx = with(density) { 8.dp.roundToPx() }
        return remember(gapPx, marginPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = slotTooltipOffset(anchorBounds, windowSize, popupContentSize, gapPx, marginPx)
            }
        }
    }
}

/**
 * Pure geometry behind [SlotTooltipPlacement]: place the [popupContentSize] card below the slot
 * ([anchorBounds]) — or above it when the side below has less room — and clamp it horizontally into
 * the window. The card's top edge is always at/below the slot's bottom (below case) or its bottom
 * edge at/above the slot's top (above case): it never covers the pointer sitting inside the slot,
 * which is precisely what stops the hover-flicker loop. Window-edge clipping is preferred over ever
 * overlapping the slot. Kept side-effect free so it can be unit-tested without Compose.
 */
internal fun slotTooltipOffset(
    anchorBounds: IntRect,
    windowSize: IntSize,
    popupContentSize: IntSize,
    gapPx: Int,
    marginPx: Int,
): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
    val x = anchorBounds.left.coerceIn(marginPx, maxX)
    val roomBelow = windowSize.height - marginPx - anchorBounds.bottom
    val roomAbove = anchorBounds.top - marginPx
    val fitsBelow = roomBelow >= popupContentSize.height + gapPx
    val y =
        if (fitsBelow || roomBelow >= roomAbove) {
            anchorBounds.bottom + gapPx
        } else {
            anchorBounds.top - gapPx - popupContentSize.height
        }
    return IntOffset(x, y)
}

@Composable
private fun SlotActions(
    onForce: () -> Unit,
    onExclude: () -> Unit,
    forced: Boolean,
    excluded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.raised.copy(alpha = 0.96f))
                .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlotActionButton(glyph = "＋", color = WColor.success, active = forced, onClick = onForce)
        SlotActionButton(glyph = "⊘", color = WColor.danger, active = excluded, onClick = onExclude)
    }
}

@Composable
private fun SlotActionButton(
    glyph: String,
    color: Color,
    active: Boolean,
    onClick: () -> Unit,
) {
    // `active` = this item is already required/excluded: render the button filled so the user sees
    // the current state (and that clicking again was registered). See #125.
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(color.copy(alpha = if (active) 0.9f else 0.18f))
                .border(1.dp, color.copy(alpha = if (active) 0.95f else 0.55f), RoundedCornerShape(7.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style =
                WTypography.labelMedium.copy(
                    color = if (active) WColor.bg else color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp
                )
        )
    }
}

/**
 * Small persistent corner badge shown on a slot whose item is currently required (green ＋) or
 * excluded (red ⊘). Sits where the hover actions appear, so it reads as the lasting result of having
 * clicked them — the missing feedback from #125.
 */
@Composable
private fun SlotStatusBadge(
    forced: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (forced) WColor.success else WColor.danger
    Box(
        modifier =
            modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (forced) "＋" else "⊘",
            style =
                WTypography.labelSmall.copy(
                    color = WColor.bg,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 12.sp
                )
        )
    }
}

@Composable
private fun SlotRowContent(
    slot: DollSlot,
    equipment: Equipment?,
    runes: List<RuneType> = emptyList(),
    subs: List<Sublimation> = emptyList(),
    idle: Boolean,
    justLanded: Boolean,
    rightAlign: Boolean,
    forced: Boolean,
    excluded: Boolean,
    cardHeight: Dp = 84.dp,
    modifier: Modifier = Modifier,
) {
    val filled = equipment != null
    val scale by animateFloatAsState(targetValue = if (justLanded) 1.03f else 1f, label = "slot-land")
    val color = equipment?.rarity?.color() ?: WColor.border
    Row(
        modifier =
            modifier
                .height(cardHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.alpha(if (idle) 0.48f else 1f)
                .clip(RoundedCornerShape(13.dp))
                .background(WColor.surface)
                .border(
                    width = if (forced || excluded) 1.5.dp else 1.dp,
                    color =
                        when {
                            excluded -> WColor.danger
                            forced -> WColor.success
                            filled -> WColor.hairline
                            else -> WColor.border.copy(alpha = 0.75f)
                        },
                    shape = RoundedCornerShape(13.dp)
                ).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (rightAlign) {
            SlotMeta(slot = slot, equipment = equipment, runes = runes, subs = subs, modifier = Modifier.weight(1f), align = TextAlign.End)
            SlotIcon(slot = slot, equipment = equipment, color = color, cardHeight = cardHeight)
        } else {
            SlotIcon(slot = slot, equipment = equipment, color = color, cardHeight = cardHeight)
            SlotMeta(slot = slot, equipment = equipment, runes = runes, subs = subs, modifier = Modifier.weight(1f), align = TextAlign.Start)
        }
    }
}

@Composable
private fun ItemTooltip(
    slot: DollSlot,
    equipment: Equipment,
    runes: List<RuneType>,
    subs: List<Sublimation>,
) {
    val lang = LocalLang.current
    val shape = RoundedCornerShape(10.dp)
    val statsScroll = rememberScrollState()
    Column(
        modifier =
            Modifier
                .widthIn(min = 230.dp, max = 300.dp)
                .shadow(elevation = 18.dp, shape = shape, clip = false)
                .clip(shape)
                .background(WColor.raised)
                .border(1.dp, WColor.border, shape)
                .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = equipment.localizedName(lang),
            style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = equipment.secondaryLine(slot, lang),
            style = WTypography.labelSmall.copy(color = WColor.faint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RarityPill(rarity = equipment.rarity, lang = lang)
            Text(
                text = "${tr(Tr.LEVEL_PREFIX_LONG)} ${equipment.level}",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
        }
        val stats = equipment.characteristics.entries.sortedBy { it.key.ordinal }
        if (stats.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(statsScroll),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                stats.forEach { (characteristic, value) ->
                    TooltipStatRow(characteristic = characteristic, value = value, lang = lang)
                }
            }
        }
        if (runes.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
            Text(
                text = tr(Tr.RUNES),
                style = WTypography.labelSmall.copy(color = WColor.faint, fontWeight = FontWeight.SemiBold)
            )
            runes
                .groupBy { it.characteristic }
                .forEach { (_, sameStatRunes) ->
                    TooltipRuneRow(
                        rune = sameStatRunes.first(),
                        count = sameStatRunes.size,
                        // Enchantment level is gated by the carrier item's level (Ankama's table).
                        level = sameStatRunes.first().maxLevel(equipment.level),
                        lang = lang
                    )
                }
        }
        if (subs.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
            Text(
                text = "Sublimations",
                style = WTypography.labelSmall.copy(color = WColor.faint, fontWeight = FontWeight.SemiBold)
            )
            subs.forEach { sub ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (lang == Lang.FR) sub.name.fr else sub.name.en,
                            style = WTypography.labelSmall.copy(color = WColor.accent, fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = sub.rarity.name,
                            style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                        )
                        // A normal sub's required 3-socket colour pattern (epic/relic carry none).
                        sub.colors.forEach { color -> RuneShape(color = color, size = 13.dp) }
                    }
                    sub.rawText?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = WTypography.labelSmall.copy(color = WColor.muted),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun RuneColor.displayColor(): Color =
    when (this) {
        RuneColor.RED -> Color(0xFFE05A5A)
        RuneColor.GREEN -> Color(0xFF5FB76A)
        RuneColor.BLUE -> Color(0xFF5A8FE0)
    }

/**
 * A socketed rune's "symbol" = its official socket-shape shard, by socket colour: red = square,
 * green = pentagon, blue = triangle. These are the same shard PNGs WakForge uses (sourced from Wakfu's
 * assets into assets/runes/). The stat each rune carries is read from the tooltip text; the shape +
 * colour identify it at a glance. Falls back to a plain colour dot if an asset is missing.
 */
@Composable
private fun RuneShape(
    color: RuneColor,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val asset =
        when (color) {
            RuneColor.RED -> "assets/runes/shard_red.png"
            RuneColor.GREEN -> "assets/runes/shard_green.png"
            RuneColor.BLUE -> "assets/runes/shard_blue.png"
        }
    val bitmap = rememberClasspathBitmap(asset)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier =
                modifier
                    .size(size)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color.displayColor())
        )
    }
}

@Composable
private fun TooltipRuneRow(
    rune: RuneType,
    count: Int,
    level: Int,
    lang: Lang,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        RuneShape(color = rune.color, size = 16.dp)
        Text(
            text = "${count}x",
            style =
                WTypography.bodySmall.copy(
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    color = rune.color.displayColor()
                )
        )
        Text(
            text = rune.characteristic.label(lang),
            style = WTypography.bodySmall.copy(color = WColor.muted),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Enchantment level, capped by the carrier item's level (e.g. "Lv 3").
        Text(
            text = "${tr(Tr.LEVEL_PREFIX_SHORT)} $level",
            style =
                WTypography.labelSmall.copy(
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    color = WColor.faint
                )
        )
    }
}

@Composable
private fun TooltipStatRow(
    characteristic: me.chosante.common.Characteristic,
    value: Int,
    lang: Lang,
) {
    val color = characteristic.statColor()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        val iconBitmap = characteristic.iconResourcePath()?.let { rememberClasspathBitmap(it) }
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(14.dp)
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(color)
            )
        }
        Text(
            text = if (value > 0) "+$value" else value.toString(),
            style =
                WTypography.bodySmall.copy(
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value < 0) WColor.danger else color
                )
        )
        Text(
            text = characteristic.label(lang),
            style = WTypography.bodySmall.copy(color = WColor.muted),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RarityPill(
    rarity: me.chosante.common.Rarity,
    lang: Lang,
) {
    val color = rarity.color()
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = 0.16f))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RarityIcon(rarity = rarity, size = 12.dp)
        Text(
            text = rarity.label(lang),
            style = WTypography.labelSmall.copy(color = color, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun SlotIcon(
    slot: DollSlot,
    equipment: Equipment?,
    color: androidx.compose.ui.graphics.Color,
    cardHeight: Dp = 84.dp,
) {
    // Scale the icon tile with the card so it always fits inside the (12.dp) padding, even when the
    // slots shrink to share a shorter column.
    val iconSize = (cardHeight - 26.dp).coerceIn(38.dp, 58.dp)
    Box(
        modifier =
            Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(11.dp))
                .background(if (equipment == null) WColor.raised else color.copy(alpha = 0.14f))
                .border(1.dp, if (equipment == null) WColor.border else color.copy(alpha = 0.35f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (equipment == null) {
            Text(
                text = slot.glyph,
                style =
                    WTypography.headlineMedium.copy(
                        color = WColor.faint,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp
                    )
            )
        } else {
            val bitmap = rememberClasspathBitmap(equipment.itemResourcePath())
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(5.dp)
                )
            }
            RarityIcon(
                rarity = equipment.rarity,
                modifier = Modifier.align(Alignment.BottomEnd),
                size = 14.dp
            )
        }
    }
}

@Composable
private fun SlotMeta(
    slot: DollSlot,
    equipment: Equipment?,
    runes: List<RuneType> = emptyList(),
    subs: List<Sublimation> = emptyList(),
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start
    ) {
        Text(
            text = tr(slot.labelKey),
            style = WTypography.labelSmall,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = equipment?.localizedName(LocalLang.current) ?: tr(Tr.EMPTY),
            style =
                WTypography.bodyMedium.copy(
                    color = if (equipment == null) WColor.faint else WColor.text,
                    fontWeight = if (equipment == null) FontWeight.Normal else FontWeight.Medium,
                    textAlign = align,
                    lineHeight = 18.sp
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (equipment != null) {
            Text(
                text = "Lv ${equipment.level} · ${equipment.rarity.label(LocalLang.current)}",
                style =
                    WTypography.labelSmall.copy(
                        fontFamily = WType.mono,
                        color = WColor.muted,
                        textAlign = align
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subs.isNotEmpty()) {
                // The sublimation(s) hosted on this item; a normal sub also pins a 3-socket colour pattern.
                Text(
                    text = subs.joinToString("  ") { "✦ ${if (lang == Lang.FR) it.name.fr else it.name.en}" },
                    style = WTypography.labelSmall.copy(color = WColor.accent, textAlign = align),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Socket loadout: a normal sub's 3 pattern colours first, then the item's runes — the at-a-glance
            // "rune + sublimation loadout". Shapes shrink uniformly to fit the card width without clipping.
            val loadout =
                subs.filter { it.rarity == SublimationRarity.NORMAL }.flatMap { it.colors } + runes.map { it.color }
            if (loadout.isNotEmpty()) {
                val shapeCount = loadout.size.coerceAtMost(equipment.maxShardSlots.coerceAtLeast(1))
                val gap = 3.dp
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    val shapeSize = ((maxWidth - gap * (shapeCount - 1)) / shapeCount).coerceIn(0.dp, 16.dp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(gap, if (align == TextAlign.End) Alignment.End else Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        loadout.take(shapeCount).forEach { color ->
                            RuneShape(color = color, size = shapeSize)
                        }
                    }
                }
            }
        }
    }
}

/** True when [equipment] is currently pinned as required. Matched on the French name, like ItemChip. */
private fun UiState.isForcedEquipment(equipment: Equipment?): Boolean = equipment != null && forcedItems.any { it.matchName == equipment.name.fr }

/** True when [equipment] is currently excluded from the next search. */
private fun UiState.isExcludedEquipment(equipment: Equipment?): Boolean = equipment != null && excludedItems.any { it.matchName == equipment.name.fr }

private fun Equipment.localizedName(lang: Lang): String =
    when (lang) {
        Lang.FR -> name.fr.ifBlank { name.en }
        Lang.EN -> name.en.ifBlank { name.fr }
    }

private fun Equipment.secondaryLine(
    slot: DollSlot,
    lang: Lang,
): String {
    val secondaryName =
        when (lang) {
            Lang.FR -> name.en
            Lang.EN -> name.fr
        }.ifBlank { null }
            ?.takeUnless { it == localizedName(lang) }
    return listOfNotNull(secondaryName, slot.labelKey.value(lang)).joinToString(" · ")
}
