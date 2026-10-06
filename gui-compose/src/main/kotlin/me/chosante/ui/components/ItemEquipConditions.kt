package me.chosante.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.Equipment
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemStatGate
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

internal data class ItemConditionLine(
    val text: String,
    val unenforced: Boolean = false,
)

/** Pure presentation of the typed criterion. No stat evaluation here: the gates are checked on a whole build ([statGateWarnings]). */
internal fun formatItemEquipConditions(
    criterion: ItemEquipCriterion,
    catalog: Map<Int, Equipment>,
    lang: Lang,
): List<ItemConditionLine> =
    buildList {
        fun itemName(id: Int): String = catalog[id]?.name?.localized(lang) ?: Tr.EQUIP_ITEM_FALLBACK.value(lang).format(id)

        fun unchecked(
            text: String,
            marker: Tr,
        ) = ItemConditionLine("$text (${marker.value(lang)})", unenforced = true)

        criterion.requiresItems.distinct().forEach { add(ItemConditionLine(Tr.EQUIP_NEEDS.value(lang).format(itemName(it)))) }
        // The game re-checks the whole set: the reverse ban is just as binding as a ban on this item's own criterion.
        val conflicts = criterion.forbidsItems + catalog.values.filter { criterion.itemId in it.equipCriterion?.forbidsItems.orEmpty() }.map { it.equipmentId }
        if (conflicts.isNotEmpty()) {
            add(
                ItemConditionLine(
                    Tr.EQUIP_INCOMPATIBLE.value(lang).format(
                        conflicts
                            .distinct()
                            .map(::itemName)
                            .distinct()
                            .joinToString(", ")
                    )
                )
            )
        }
        if (criterion.classes.isNotEmpty()) {
            add(ItemConditionLine(Tr.EQUIP_CLASS_ONLY.value(lang).format(criterion.classes.distinct().joinToString(", ") { it.label(lang) })))
        }
        if (criterion.never) add(ItemConditionLine(Tr.EQUIP_NEVER.value(lang)))
        // Stat gates are enforced by the search (the out-of-combat sheet must meet them), so they read like the other rules.
        criterion.statGates.forEach { gate -> add(ItemConditionLine(statGateText(gate, lang))) }
        criterion.playerState.forEach { atom ->
            val text =
                when (atom.function) {
                    "IsAchievementComplete" ->
                        (if (atom.negated) Tr.EQUIP_ACHIEVEMENT_NOT_COMPLETED else Tr.EQUIP_ACHIEVEMENT).value(lang).format(atom.args.firstOrNull() ?: "?")
                    else -> {
                        val label =
                            when (atom.function) {
                                "GetCompanyRank" -> Tr.EQUIP_MILITIA_RANK.value(lang)
                                "GetStasisGauge" -> Tr.EQUIP_STASIS_GAUGE.value(lang)
                                "GetWakfuGauge" -> Tr.EQUIP_WAKFU_GAUGE.value(lang)
                                "GetCrimeScore" -> Tr.EQUIP_CRIME_SCORE.value(lang)
                                // A function a later client version adds: shown as the game writes it, never a crash of the
                                // picker or a tooltip after a game-data update.
                                else -> "${atom.function}(${atom.args.joinToString(", ")})"
                            }
                        listOfNotNull(label, atom.comparison?.displaySymbol(), atom.value?.toString()).joinToString(" ")
                    }
                }
            add(unchecked(text, Tr.EQUIP_ASSUMED_MET))
        }
        // uniqueEquipped is the generic ring rule, not useful item-specific secondary text.
    }

/** "Range ≤ 3", "Max AP ≤ 11": a stat gate as the item conditions and the inactive-item warning write it. */
internal fun statGateText(
    gate: ItemStatGate,
    lang: Lang,
): String {
    val characteristic =
        if (gate.max) {
            when (gate.characteristic) {
                Characteristic.ACTION_POINT -> Characteristic.MAX_ACTION_POINT
                Characteristic.MOVEMENT_POINT -> Characteristic.MAX_MOVEMENT_POINT
                Characteristic.WAKFU_POINT -> Characteristic.MAX_WAKFU_POINTS
                else -> gate.characteristic
            }
        } else {
            gate.characteristic
        }
    return "${characteristic.label(lang)} ${gate.comparison.displaySymbol()} ${gate.value}"
}

private fun CriterionComparison.displaySymbol(): String =
    when (this) {
        CriterionComparison.LE -> "≤"
        CriterionComparison.GE -> "≥"
        CriterionComparison.EQ -> "="
        CriterionComparison.NE -> "≠"
        else -> symbol
    }

/** Also restores criteria for saved items: equipCriterion is transient and isn't serialized into a build. */
internal object ItemConditionCatalog {
    val byId: Map<Int, Equipment> by lazy { WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId } }
}

@Composable
internal fun rememberItemConditionLines(equipment: Equipment): List<ItemConditionLine> {
    val lang = LocalLang.current
    return remember(equipment, lang) {
        val catalog = ItemConditionCatalog.byId
        formatItemEquipConditions(
            catalog[equipment.equipmentId]?.equipCriterion ?: equipment.equipCriterion ?: ItemEquipCriterion(equipment.equipmentId, raw = ""),
            catalog,
            lang
        )
    }
}

/** One condition line keeps the picker row to three lines overall; hover cards wrap every condition. */
@Composable
internal fun ItemConditionLines(
    lines: List<ItemConditionLine>,
    compact: Boolean = false,
) {
    if (lines.isEmpty()) return
    if (compact) {
        val first = lines.first()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = first.text,
                style = WTypography.labelSmall.copy(color = if (first.unenforced) WColor.warning else WColor.muted),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (lines.size > 1) {
                Text(
                    text = Tr.EQUIP_MORE.value(LocalLang.current).format(lines.size - 1),
                    style = WTypography.labelSmall.copy(color = if (lines.drop(1).any { it.unenforced }) WColor.warning else WColor.muted)
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = WTypography.labelSmall.copy(color = if (line.unenforced) WColor.warning else WColor.muted)
                )
            }
        }
    }
}

/** Full conditions remain accessible when the picker row truncates them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemConditionsHover(
    lines: List<ItemConditionLine>,
    content: @Composable () -> Unit,
) {
    if (lines.isEmpty()) {
        content()
    } else {
        TooltipArea(
            delayMillis = 350,
            tooltip = {
                val shape = RoundedCornerShape(8.dp)
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 320.dp)
                            .clip(shape)
                            .background(WColor.raised)
                            .border(1.dp, WColor.border, shape)
                            .padding(10.dp)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                ) {
                    ItemConditionLines(lines)
                }
            },
            content = content
        )
    }
}
