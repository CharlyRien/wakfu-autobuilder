package me.chosante.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.BuildSpellDamage
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.common.Character
import me.chosante.common.Spell
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.components.BreedIcon
import me.chosante.ui.components.CharacteristicIcon
import me.chosante.ui.components.InfoTip
import me.chosante.ui.components.ItemThumbnail
import me.chosante.ui.components.SpellIcon
import me.chosante.ui.components.elementLabel
import me.chosante.ui.components.localized
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.MAX_COMPARE_SLOTS
import me.chosante.ui.state.MIN_COMPARE_SLOTS
import me.chosante.ui.state.UiState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.state.isEngineInternalStat
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/** Fixed width of a per-build value column, shared by the stat and spell-damage tables so they align. */
private val COMPARE_CELL = 80.dp

/** Column label (A, B, C, D) for compare slot [index] — ties a side column to its table column. */
private fun columnLetter(index: Int): String = ('A' + index).toString()

/**
 * Side-by-side comparison of two to four saved builds (one column each). Each column is picked from the
 * library; below them, the stat table shows every characteristic the builds carry, and the spell-damage
 * table shows each class spell's expected hit per build — the best cell highlighted in both. Cheap to
 * render: the stat table reads each build's stored `achieved` map; the spell table reconstructs each build
 * once and reuses [BuildSpellDamage]. The spell table is same-class only (spell kits differ by class).
 */
@Composable
fun CompareScreen(
    ui: UiState,
    onPick: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // The filled columns, paired with their A/B/C/D label, in slot order — what the tables compare.
    val columns =
        ui.compareSlots.mapIndexedNotNull { index, id ->
            ui.savedBuilds.firstOrNull { it.id == id }?.let { columnLetter(index) to it }
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(WColor.bg)
                .verticalScroll(scroll)
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(WDimens.gap)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack = onBack)
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = tr(Tr.COMPARE_TITLE), style = WTypography.headlineLarge.copy(fontWeight = FontWeight.Bold))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WDimens.gap),
            verticalAlignment = Alignment.Top
        ) {
            ui.compareSlots.forEachIndexed { index, id ->
                SideColumn(
                    index = index,
                    entry = ui.savedBuilds.firstOrNull { it.id == id },
                    builds = ui.savedBuilds,
                    canRemove = ui.compareSlots.size > MIN_COMPARE_SLOTS,
                    onPick = onPick,
                    onClear = onClear,
                    modifier = Modifier.weight(1f)
                )
            }
            if (ui.compareSlots.size < MAX_COMPARE_SLOTS) {
                AddColumnTile(onClick = onAdd)
            }
        }
        if (columns.size >= MIN_COMPARE_SLOTS) {
            ComparisonTable(columns = columns)
            SpellDamageTable(columns = columns)
        } else {
            Text(
                text = tr(Tr.COMPARE_EMPTY),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SideColumn(
    index: Int,
    entry: HistoryEntry?,
    builds: List<HistoryEntry>,
    canRemove: Boolean,
    onPick: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LetterBadge(columnLetter(index))
            BuildPicker(
                index = index,
                current = entry,
                builds = builds,
                canRemove = canRemove,
                onPick = onPick,
                onClear = onClear,
                modifier = Modifier.weight(1f)
            )
        }
        if (entry != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BreedIcon(clazz = entry.restoredClass(), size = 20.dp)
                Text(
                    text = "${entry.classDisplayName()} · ${tr(Tr.LEVEL_SHORT)} ${entry.request.level}",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
            val headline =
                if (entry.isMasteryMode()) {
                    "${entry.requestedMasteryTotal().formatCompact()} ${tr(Tr.MASTERY_SHORT)}"
                } else {
                    "${entry.result.match.toInt()}% ${tr(Tr.MATCH)}"
                }
            Text(
                text = headline + if (entry.result.optimal) " · ${tr(Tr.OPTIMAL_PROVEN)}" else "",
                style = WTypography.labelMedium.copy(color = if (entry.result.optimal) WColor.success else WColor.text)
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                entry.result.equipments.take(14).forEach { equipment ->
                    ItemThumbnail(equipment = equipment, size = 22.dp)
                }
            }
        }
    }
}

/** The A/B/C/D chip identifying a compare column (matches the table column headers). */
@Composable
private fun LetterBadge(letter: String) {
    Box(
        modifier =
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = letter, style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.accent, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun BuildPicker(
    index: Int,
    current: HistoryEntry?,
    builds: List<HistoryEntry>,
    canRemove: Boolean,
    onPick: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = current?.name ?: tr(Tr.COMPARE_PICK),
                style = WTypography.bodyMedium.copy(color = if (current == null) WColor.faint else WColor.text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // ✕ both empties a base column and removes an extra one (the model decides which); shown for a
            // filled column, or for any column once there are extras to remove.
            if (current != null || canRemove) {
                Box(
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).clickable { onClear(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", style = WTypography.labelSmall.copy(color = WColor.muted))
                }
            } else {
                Text(text = "▾", style = WTypography.labelSmall.copy(color = WColor.muted))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            builds.forEach { build ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = build.name,
                            style = WTypography.bodyMedium.copy(color = if (build.id == current?.id) WColor.accent else WColor.text)
                        )
                    },
                    onClick = {
                        onPick(index, build.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AddColumnTile(onClick: () -> Unit) {
    Column(
        modifier =
            Modifier
                .width(150.dp)
                .heightIn(min = 92.dp)
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(WDimens.radius))
                .clickable(onClick = onClick)
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "+", style = WTypography.headlineLarge.copy(color = WColor.faint))
        Text(text = tr(Tr.COMPARE_ADD), style = WTypography.labelSmall.copy(color = WColor.muted))
    }
}

@Composable
private fun CompareCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad),
        content = content
    )
}

@Composable
private fun ComparisonTable(columns: List<Pair<String, HistoryEntry>>) {
    val lang = LocalLang.current
    val entries = columns.map { it.second }
    val rows =
        remember(columns.map { it.second.id }) {
            val keys = entries.flatMap { it.result.achieved.keys }.toSet().filterNot { it.isEngineInternalStat() }
            keys
                .map { key -> key to entries.map { entry -> entry.result.achieved[key] ?: 0 } }
                .filter { (_, values) -> values.any { it != 0 } }
                .sortedBy { it.first.ordinal }
        }
    CompareCard {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(text = tr(Tr.COMPARE_STAT), style = WTypography.labelMedium.copy(color = WColor.muted), modifier = Modifier.weight(1f))
            columns.forEach { (letter, _) ->
                Text(text = letter, style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted), modifier = Modifier.width(COMPARE_CELL))
            }
        }
        // Headline: the value the engine actually maximized (specialized summed + min of elements) — the
        // row that says which build the solver judges best overall, unlike the per-stat rows below.
        ValueRow(values = entries.map { it.requestedMasteryTotal() }, bold = true) {
            Text(
                text = tr(Tr.COMPARE_ENGINE_SCORE),
                style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
        rows.forEachIndexed { index, (characteristic, values) ->
            if (index > 0) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
            }
            ValueRow(values = values, bold = false) {
                CharacteristicIcon(characteristic = characteristic, size = 16.dp)
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = characteristic.label(lang),
                    style = WTypography.bodyMedium.copy(color = WColor.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One table row: a [leading] label (filling the width) followed by an integer value cell per build, the
 * best cell(s) highlighted green. Ties highlight nothing (matching the original two-build behaviour).
 * [bold] forces every cell bold (used for the headline engine-score row).
 */
@Composable
private fun ValueRow(
    values: List<Int>,
    bold: Boolean,
    leading: @Composable RowScope.() -> Unit,
) {
    val best = values.maxOrNull() ?: 0
    val tie = values.all { it == best }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        values.forEach { value ->
            NumberCell(text = value.formatCompact(), highlighted = value == best && !tie, bold = bold)
        }
    }
}

@Composable
private fun NumberCell(
    text: String,
    highlighted: Boolean,
    bold: Boolean,
) {
    Text(
        text = text,
        style =
            WTypography.bodyMedium.copy(
                fontFamily = WType.mono,
                fontWeight = if (highlighted || bold) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) WColor.success else WColor.text
            ),
        modifier = Modifier.width(COMPARE_CELL)
    )
}

/** Spell damage of every compared build, computed once off the UI thread. [mixedClass] short-circuits the table. */
private data class SpellDamageData(
    val mixedClass: Boolean,
    val spells: List<Spell>,
    val damageByEntry: List<Map<Int, Double>>,
)

/**
 * Reconstructs each compared build and scores every class damage spell via [BuildSpellDamage] — the same
 * neutral 0%-resistance maths the Class spells tab uses, so the numbers agree. Returns [SpellDamageData.mixedClass]
 * when the builds aren't all the same class (their spell kits differ, so a per-spell comparison is meaningless).
 * Spells are pre-sorted by the strongest build's hit, so the heaviest hitters lead.
 */
private fun computeSpellDamage(entries: List<HistoryEntry>): SpellDamageData {
    if (entries.map { it.restoredClass() }.toSet().size > 1) {
        return SpellDamageData(mixedClass = true, spells = emptyList(), damageByEntry = emptyList())
    }
    val clazz = entries.firstOrNull()?.restoredClass() ?: return SpellDamageData(false, emptyList(), emptyList())
    val spells = SpellCatalog.damageSpells(clazz)
    val damageByEntry =
        entries.map { entry ->
            val build = entry.toBuildCombination()
            val character = Character(entry.restoredClass(), entry.request.level, entry.request.minLevel, build.characterSkills)
            spells.associate { spell -> spell.id to (BuildSpellDamage.expectedDamage(spell, build, character)?.expected ?: 0.0) }
        }
    val ordered = spells.sortedByDescending { spell -> damageByEntry.maxOfOrNull { it[spell.id] ?: 0.0 } ?: 0.0 }
    return SpellDamageData(mixedClass = false, spells = ordered, damageByEntry = damageByEntry)
}

/**
 * Per-spell expected damage of each compared build, side by side: one row per damage spell, one value column
 * per build, the best cell(s) highlighted. The spell is listed once (not repeated per build) so it scales to
 * several builds. Only meaningful for same-class builds; a mixed-class selection shows a note instead.
 */
@Composable
private fun SpellDamageTable(columns: List<Pair<String, HistoryEntry>>) {
    val lang = LocalLang.current
    val entries = columns.map { it.second }
    val data = remember(columns.map { it.second.id }) { computeSpellDamage(entries) }
    val showColumns = !data.mixedClass && data.spells.isNotEmpty()
    CompareCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = tr(Tr.COMPARE_SPELL_DAMAGE), style = WTypography.labelMedium.copy(color = WColor.muted))
            Spacer(modifier = Modifier.width(6.dp))
            InfoTip(text = tr(Tr.SPELL_EXPECTED_HIT_INFO))
            if (showColumns) {
                Spacer(modifier = Modifier.weight(1f))
                columns.forEach { (letter, _) ->
                    Text(text = letter, style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted), modifier = Modifier.width(COMPARE_CELL))
                }
            }
        }
        when {
            data.mixedClass ->
                Text(text = tr(Tr.COMPARE_SPELLS_MIXED_CLASS), style = WTypography.bodySmall.copy(color = WColor.muted))

            data.spells.isEmpty() ->
                Text(text = tr(Tr.CLASS_SPELLS_EMPTY), style = WTypography.bodySmall.copy(color = WColor.muted))

            else ->
                data.spells.forEachIndexed { index, spell ->
                    if (index > 0) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
                    }
                    SpellDamageRow(
                        spell = spell,
                        values = data.damageByEntry.map { it[spell.id] ?: 0.0 },
                        lang = lang
                    )
                }
        }
    }
}

@Composable
private fun SpellDamageRow(
    spell: Spell,
    values: List<Double>,
    lang: Lang,
) {
    val bestLong = values.maxOfOrNull { it.toLong() } ?: 0L
    val tie = values.all { it.toLong() == bestLong }
    val elementLabel = spell.element?.elementLabel()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpellIcon(iconId = spell.iconId, element = spell.element, size = 26.dp)
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = spell.name.localized(lang),
                style = WTypography.bodyMedium.copy(color = WColor.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(elementLabel, spell.apCost?.let { "$it AP" }).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(text = meta, style = WTypography.labelSmall.copy(color = WColor.muted, fontFamily = WType.mono))
            }
        }
        values.forEach { v ->
            val vl = v.toLong()
            NumberCell(text = if (vl > 0) vl.formatCompact() else "—", highlighted = vl == bestLong && vl > 0 && !tie, bold = false)
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Box(
        modifier =
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "← ${tr(Tr.BACK)}", style = WTypography.labelMedium.copy(color = WColor.text))
    }
}
