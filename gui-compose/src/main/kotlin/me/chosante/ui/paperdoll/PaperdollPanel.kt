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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.common.Equipment
import me.chosante.common.ItemType
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
                            EquipmentSlot(
                                slot = slot,
                                equipment = slots[slot.id],
                                idle = ui.phase == Phase.Idle,
                                justLanded = slots[slot.id]?.equipmentId == ui.lastLandedEquipmentId,
                                rightAlign = false,
                                onForceItem = onForceItem,
                                onExcludeItem = onExcludeItem,
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
        Text(
            text = tr(Tr.DISCLAIMER),
            style = WTypography.labelSmall,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, WColor.hairline)
                    .padding(vertical = 10.dp, horizontal = WDimens.pad)
        )
    }
}

@Composable
private fun SolverLoadingOverlay(
    ui: UiState,
    modifier: Modifier = Modifier,
) {
    val title =
        if (ui.solver == WakfuSolver.OR_TOOLS) {
            tr(Tr.PREPARING_OR_TOOLS_MODEL)
        } else {
            tr(Tr.SEARCHING_FIRST_BUILD)
        }
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
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        slots.forEach { slot ->
            EquipmentSlot(
                slot = slot,
                equipment = assignments[slot.id],
                idle = ui.phase == Phase.Idle,
                justLanded = assignments[slot.id]?.equipmentId == ui.lastLandedEquipmentId,
                rightAlign = rightAlign,
                onForceItem = onForceItem,
                onExcludeItem = onExcludeItem,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EquipmentSlot(
    slot: DollSlot,
    equipment: Equipment?,
    idle: Boolean,
    justLanded: Boolean,
    rightAlign: Boolean,
    onForceItem: (Equipment) -> Unit,
    onExcludeItem: (Equipment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (equipment == null) {
        SlotRowContent(slot, null, idle, justLanded, rightAlign, modifier)
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
            tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
            tooltip = { ItemTooltip(slot = slot, equipment = equipment) }
        ) {
            Box(modifier = Modifier.fillMaxWidth().hoverable(interaction)) {
                SlotRowContent(slot, equipment, idle, justLanded, rightAlign, Modifier.fillMaxWidth())
                if (hovered) {
                    SlotActions(
                        onForce = { onForceItem(equipment) },
                        onExclude = { onExcludeItem(equipment) },
                        modifier =
                            Modifier
                                .align(if (rightAlign) Alignment.TopStart else Alignment.TopEnd)
                                .padding(5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotActions(
    onForce: () -> Unit,
    onExclude: () -> Unit,
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
        SlotActionButton(glyph = "＋", color = WColor.success, onClick = onForce)
        SlotActionButton(glyph = "⊘", color = WColor.danger, onClick = onExclude)
    }
}

@Composable
private fun SlotActionButton(
    glyph: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(color.copy(alpha = 0.18f))
                .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style =
                WTypography.labelMedium.copy(
                    color = color,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp
                )
        )
    }
}

@Composable
private fun SlotRowContent(
    slot: DollSlot,
    equipment: Equipment?,
    idle: Boolean,
    justLanded: Boolean,
    rightAlign: Boolean,
    modifier: Modifier = Modifier,
) {
    val filled = equipment != null
    val scale by animateFloatAsState(targetValue = if (justLanded) 1.03f else 1f, label = "slot-land")
    val color = equipment?.rarity?.color() ?: WColor.border
    Row(
        modifier =
            modifier
                .height(84.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.alpha(if (idle) 0.48f else 1f)
                .clip(RoundedCornerShape(13.dp))
                .background(WColor.surface)
                .border(
                    width = 1.dp,
                    color = if (filled) WColor.hairline else WColor.border.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(13.dp)
                ).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (rightAlign) {
            SlotMeta(slot = slot, equipment = equipment, modifier = Modifier.weight(1f), align = TextAlign.End)
            SlotIcon(slot = slot, equipment = equipment, color = color)
        } else {
            SlotIcon(slot = slot, equipment = equipment, color = color)
            SlotMeta(slot = slot, equipment = equipment, modifier = Modifier.weight(1f), align = TextAlign.Start)
        }
    }
}

@Composable
private fun ItemTooltip(
    slot: DollSlot,
    equipment: Equipment,
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
) {
    Box(
        modifier =
            Modifier
                .size(58.dp)
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
    align: TextAlign,
    modifier: Modifier = Modifier,
) {
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
        }
    }
}

private data class DollSlot(
    val id: String,
    val labelKey: Tr,
    val glyph: String,
)

private val leftSlots =
    listOf(
        DollSlot("helmet", Tr.SLOT_HELMET, "⛨"),
        DollSlot("amulet", Tr.SLOT_AMULET, "◌"),
        DollSlot("epaul", Tr.SLOT_EPAULETTES, "▱"),
        DollSlot("chest", Tr.SLOT_BREASTPLATE, "▢"),
        DollSlot("cape", Tr.SLOT_CAPE, "⊳")
    )

private val rightSlots =
    listOf(
        DollSlot("emblem", Tr.SLOT_EMBLEM, "✦"),
        DollSlot("belt", Tr.SLOT_BELT, "═"),
        DollSlot("ring1", Tr.SLOT_RING_I, "◯"),
        DollSlot("ring2", Tr.SLOT_RING_II, "◯"),
        DollSlot("boots", Tr.SLOT_BOOTS, "⊓")
    )

private val bottomSlots =
    listOf(
        DollSlot("weapon", Tr.SLOT_WEAPON, "⚔"),
        DollSlot("weapon2", Tr.SLOT_SECOND_WEAPON, "⛉"),
        DollSlot("pet", Tr.SLOT_PET, "❀"),
        DollSlot("mount", Tr.SLOT_MOUNT, "≋")
    )

private fun slotAssignments(equipments: List<Equipment>): Map<String, Equipment> {
    val rings = equipments.filter { it.itemType == ItemType.RING }
    val twoHanded = equipments.firstOrNull { it.itemType == ItemType.TWO_HANDED_WEAPONS }
    val oneHanded = equipments.firstOrNull { it.itemType == ItemType.ONE_HANDED_WEAPONS }
    val offHand = equipments.firstOrNull { it.itemType == ItemType.OFF_HAND_WEAPONS }
    return buildMap {
        putFirst("helmet", equipments, ItemType.HELMET)
        putFirst("amulet", equipments, ItemType.AMULET)
        putFirst("epaul", equipments, ItemType.SHOULDER_PADS)
        putFirst("chest", equipments, ItemType.CHEST_PLATE)
        putFirst("cape", equipments, ItemType.CAPE)
        putFirst("emblem", equipments, ItemType.EMBLEM)
        putFirst("belt", equipments, ItemType.BELT)
        rings.getOrNull(0)?.let { put("ring1", it) }
        rings.getOrNull(1)?.let { put("ring2", it) }
        putFirst("boots", equipments, ItemType.BOOTS)
        (twoHanded ?: oneHanded)?.let { put("weapon", it) }
        (if (twoHanded != null) twoHanded else offHand)?.let { put("weapon2", it) }
        putFirst("pet", equipments, ItemType.PETS)
        putFirst("mount", equipments, ItemType.MOUNTS)
    }
}

private fun MutableMap<String, Equipment>.putFirst(
    key: String,
    equipments: List<Equipment>,
    type: ItemType,
) {
    equipments.firstOrNull { it.itemType == type }?.let { put(key, it) }
}

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
