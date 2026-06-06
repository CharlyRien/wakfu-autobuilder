package me.chosante.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Modal
import me.chosante.ui.state.PickerMode
import me.chosante.ui.state.color
import me.chosante.ui.state.statCatalog
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun ModalHost(
    modal: Modal?,
    excludedCharacteristics: Set<Characteristic>,
    equipmentCatalog: List<Equipment>?,
    onSelectStat: (Characteristic) -> Unit,
    onPickItem: (Equipment) -> Unit,
    onDismiss: () -> Unit,
) {
    if (modal == null) return
    Scrim(onDismiss = onDismiss) {
        when (modal) {
            Modal.AddStat ->
                AddStatModal(
                    excluded = excludedCharacteristics,
                    onSelect = onSelectStat
                )

            is Modal.ItemPicker ->
                ItemPickerModal(
                    mode = modal.mode,
                    equipmentCatalog = equipmentCatalog,
                    onPick = onPickItem
                )
        }
    }
}

@Composable
private fun Scrim(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xCC0B0C0F))
                .noRippleClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Card swallows its own clicks so it does not dismiss the scrim.
        Box(modifier = Modifier.noRippleClickable {}) {
            content()
        }
    }
}

@Composable
private fun AddStatModal(
    excluded: Set<Characteristic>,
    onSelect: (Characteristic) -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    val sections =
        remember(query, excluded, lang) {
            val normalizedQuery = query.trim()
            val results =
                statCatalog.filter { def ->
                    def.characteristic !in excluded &&
                        def.label(lang).contains(normalizedQuery, ignoreCase = true)
                }
            statSections.mapNotNull { section ->
                val sectionStats =
                    results
                        .filter { section.accepts(it.characteristic) }
                        .sortedBy { it.label(lang) }
                if (sectionStats.isEmpty()) {
                    null
                } else {
                    section to sectionStats
                }
            }
        }
    ModalCard(title = tr(Tr.ADD_TARGET_STAT_TITLE)) {
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.FILTER_STATS))
        Spacer(modifier = Modifier.height(WDimens.gap))
        Column(
            modifier =
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            sections.forEachIndexed { sectionIndex, (section, stats) ->
                if (sectionIndex > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = tr(section.title),
                    style = WTypography.labelMedium.copy(color = WColor.muted)
                )
                stats.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        pair.forEach { def ->
                            CatalogTile(
                                characteristic = def.characteristic,
                                glyph = def.glyph,
                                color = def.color,
                                label = def.label(lang),
                                onClick = { onSelect(def.characteristic) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (sections.isEmpty()) {
                Text(
                    text = tr(Tr.NO_MATCHING_STAT),
                    style = WTypography.bodyMedium.copy(color = WColor.muted),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

private data class StatSection(
    val title: Tr,
    val accepts: (Characteristic) -> Boolean,
)

private val coreStats =
    setOf(
        Characteristic.ACTION_POINT,
        Characteristic.MOVEMENT_POINT,
        Characteristic.RANGE,
        Characteristic.WAKFU_POINT,
        Characteristic.CRITICAL_HIT,
        Characteristic.HP
    )

private val elementalMasteryStats =
    setOf(
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val specializedMasteryStats =
    setOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

private val masteryStats = elementalMasteryStats + specializedMasteryStats

private val statSections =
    listOf(
        StatSection(Tr.STAT_GROUP_CORE) { it in coreStats },
        StatSection(Tr.MASTERY_ELEMENTALS) { it in elementalMasteryStats },
        StatSection(Tr.MASTERY_SPECIALIZED) { it in specializedMasteryStats },
        StatSection(Tr.STAT_GROUP_RESISTANCES) { it.name.startsWith("RESISTANCE") },
        StatSection(Tr.STAT_GROUP_SECONDARY) {
            it !in coreStats &&
                it !in masteryStats &&
                !it.name.startsWith("RESISTANCE")
        }
    )

@Composable
private fun CatalogTile(
    characteristic: Characteristic,
    glyph: String,
    color: Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(WColor.surface)
                    .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            StatGlyphIcon(characteristic = characteristic, glyph = glyph, color = color, iconSize = 19.dp)
        }
        Text(
            text = label,
            style = WTypography.bodyMedium.copy(color = WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ItemPickerModal(
    mode: PickerMode,
    equipmentCatalog: List<Equipment>?,
    onPick: (Equipment) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results =
        remember(query, equipmentCatalog) {
            val catalog = equipmentCatalog ?: return@remember emptyList()
            val q = query.trim()
            if (q.isBlank()) {
                catalog.take(60)
            } else {
                catalog
                    .filter { it.name.fr.contains(q, ignoreCase = true) || it.name.en.contains(q, ignoreCase = true) }
                    .take(120)
            }
        }
    val title = if (mode == PickerMode.Forced) tr(Tr.REQUIRE_ITEM_TITLE) else tr(Tr.BAN_ITEM_TITLE)
    val accent = if (mode == PickerMode.Forced) WColor.success else WColor.danger
    ModalCard(title = title) {
        if (equipmentCatalog == null) {
            LoadingState(message = tr(Tr.LOADING_ITEMS))
            return@ModalCard
        }
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_ITEMS))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(results, key = { it.equipmentId }) { equipment ->
                ItemResultRow(equipment = equipment, mode = mode, accent = accent, onClick = { onPick(equipment) })
            }
        }
        if (results.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_ITEM),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = WColor.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Text(text = message, style = WTypography.bodyMedium.copy(color = WColor.muted))
    }
}

@Composable
private fun ItemResultRow(
    equipment: Equipment,
    mode: PickerMode,
    accent: Color,
    onClick: () -> Unit,
) {
    val lang = LocalLang.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ItemThumbnail(equipment = equipment, size = 38.dp)
        Column(modifier = Modifier.weight(1f)) {
            val name = if (lang == Lang.FR) equipment.name.fr.ifBlank { equipment.name.en } else equipment.name.en.ifBlank { equipment.name.fr }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RarityIcon(rarity = equipment.rarity, size = 14.dp)
                Text(
                    text = name,
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "Lv ${equipment.level} · ${equipment.itemType.label(lang)} · ${equipment.rarity.label(lang)}",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = tr(if (mode == PickerMode.Forced) Tr.REQUIRE else Tr.BAN),
            style = WTypography.labelMedium.copy(color = accent)
        )
    }
}

@Composable
private fun ModalCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .widthIn(min = 380.dp, max = 460.dp)
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Text(
            text = title,
            style = WTypography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        content()
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp),
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
            Text(text = placeholder, style = WTypography.bodyMedium.copy(color = WColor.faint))
        }
    }
}

private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick
        )
    )
