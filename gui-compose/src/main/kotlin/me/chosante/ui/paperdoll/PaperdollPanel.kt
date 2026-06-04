package me.chosante.ui.paperdoll

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.Equipment
import me.chosante.common.ItemType
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
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlotColumn(
                        slots = leftSlots,
                        assignments = slots,
                        ui = ui,
                        rightAlign = false,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    SlotColumn(
                        slots = rightSlots,
                        assignments = slots,
                        ui = ui,
                        rightAlign = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    bottomSlots.forEach { slot ->
                        EquipmentSlot(
                            slot = slot,
                            equipment = slots[slot.id],
                            idle = ui.phase == Phase.Idle,
                            justLanded = slots[slot.id]?.equipmentId == ui.lastLandedEquipmentId,
                            rightAlign = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
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
private fun SlotColumn(
    slots: List<DollSlot>,
    assignments: Map<String, Equipment>,
    ui: UiState,
    rightAlign: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(13.dp, Alignment.CenterVertically)
    ) {
        slots.forEach { slot ->
            EquipmentSlot(
                slot = slot,
                equipment = assignments[slot.id],
                idle = ui.phase == Phase.Idle,
                justLanded = assignments[slot.id]?.equipmentId == ui.lastLandedEquipmentId,
                rightAlign = rightAlign,
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
    modifier: Modifier = Modifier,
) {
    if (equipment == null) {
        SlotRowContent(slot, null, idle, justLanded, rightAlign, modifier)
        return
    }
    TooltipArea(
        modifier = modifier,
        delayMillis = 350,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp)),
        tooltip = { ItemTooltip(slot = slot, equipment = equipment) }
    ) {
        SlotRowContent(slot, equipment, idle, justLanded, rightAlign, Modifier.fillMaxWidth())
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
            .height(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (idle) 0.48f else 1f)
            .clip(RoundedCornerShape(11.dp))
            .background(WColor.surface)
            .border(
                width = 1.dp,
                color = if (filled) WColor.hairline else WColor.border.copy(alpha = 0.75f),
                shape = RoundedCornerShape(11.dp)
            )
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
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
        Box(
            modifier =
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
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
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
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
            .size(42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (equipment == null) WColor.raised else color.copy(alpha = 0.14f))
            .border(1.dp, if (equipment == null) WColor.border else color.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (equipment == null) {
            Text(
                text = slot.glyph,
                style =
                WTypography.headlineMedium.copy(
                    color = WColor.faint,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            )
        } else {
            Image(
                painter = painterResource(equipment.itemResourcePath()),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
            Box(
                modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
                    .border(2.dp, WColor.surface, RoundedCornerShape(999.dp))
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
            WTypography.bodySmall.copy(
                color = if (equipment == null) WColor.faint else WColor.text,
                fontWeight = if (equipment == null) FontWeight.Normal else FontWeight.Medium,
                textAlign = align,
                lineHeight = 15.sp
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

private fun Equipment.itemResourcePath(): String {
    val path = "assets/items/$guiId.png"
    val loader = Thread.currentThread().contextClassLoader
    return if (loader.getResource(path) != null) path else "assets/items/0000000.png"
}
