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
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.toSpellDamageRangeBand
import me.chosante.common.Character
import me.chosante.common.Characteristic
import me.chosante.common.Spell
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.components.BreedIcon
import me.chosante.ui.components.CharacteristicIcon
import me.chosante.ui.components.Hairline
import me.chosante.ui.components.InfoTip
import me.chosante.ui.components.ItemThumbnail
import me.chosante.ui.components.ObsoleteBadge
import me.chosante.ui.components.OlderEngineProof
import me.chosante.ui.components.RerunSearchLink
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
import me.chosante.ui.state.shownEntry
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
    onRerun: (String) -> Unit = {},
) {
    val scroll = rememberScrollState()
    // The filled columns, paired with their A/B/C/D label, in slot order — what the tables compare. Each build reads with the
    // numbers of the current rules once its background re-score is ready ([shownEntry]), like the library cards.
    val columns =
        ui.compareSlots.mapIndexedNotNull { index, id ->
            ui.savedBuilds.firstOrNull { it.id == id }?.let { columnLetter(index) to ui.shownEntry(it) }
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
                    entry = ui.savedBuilds.firstOrNull { it.id == id }?.let(ui::shownEntry),
                    stored = ui.savedBuilds.firstOrNull { it.id == id },
                    builds = ui.savedBuilds,
                    canRemove = ui.compareSlots.size > MIN_COMPARE_SLOTS,
                    onPick = onPick,
                    onClear = onClear,
                    onRerun = onRerun,
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
    stored: HistoryEntry?,
    builds: List<HistoryEntry>,
    canRemove: Boolean,
    onPick: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onRerun: (String) -> Unit,
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
                    text = "${entry.classDisplayName()} · ${tr(Tr.LEVEL_SHORT)} ${entry.request.level} · ${tr(entry.modeLabel())}",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
            val headline = compareHeadline(entry)
            // A proof made by an older engine (reason B of the obsolete badge) shows dimmed, with a tooltip saying so.
            val olderEngineProof = entry.result.optimal && entry.provenByOlderEngine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline,
                    style = WTypography.labelMedium.copy(color = if (entry.result.optimal && !olderEngineProof) WColor.success else WColor.text)
                )
                if (entry.result.optimal) {
                    val proven = " · ${tr(Tr.OPTIMAL_PROVEN)}"
                    if (olderEngineProof) {
                        OlderEngineProof(text = proven, style = WTypography.labelMedium)
                    } else {
                        Text(text = proven, style = WTypography.labelMedium.copy(color = WColor.success))
                    }
                }
            }
            entry.restoredBoss()?.let { boss ->
                Text(
                    text = tr(Tr.VS_BOSS).format(boss.name.localized(LocalLang.current)),
                    style = WTypography.labelSmall.copy(color = WColor.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // A search may now find better: the pill says why on hover (with the score it was saved with when the current rules
            // moved it), and the link re-runs that search.
            entry.obsolescence()?.let { obsolescence ->
                val storedScore = stored?.let { compareHeadline(it) }?.takeIf { it != headline }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ObsoleteBadge(obsolescence = obsolescence, storedScore = storedScore)
                    RerunSearchLink(onRerun = { onRerun(entry.id) })
                }
            }
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

/**
 * What this build's mode maximized: the mastery score, the expected damage per turn (a max-damage build stores it as its match —
 * it is not a percentage), or the % match to the exact targets.
 */
@Composable
private fun compareHeadline(entry: HistoryEntry): String =
    when {
        entry.isMasteryMode() -> "${entry.requestedMasteryTotal().formatCompact()} ${tr(Tr.MASTERY_SHORT)}"
        entry.isDamageMode() -> "${entry.expectedDamage().formatCompact()} ${tr(Tr.EXPECTED_DAMAGE)}"
        else -> "${entry.matchPercent()}% ${tr(if (entry.meetsAllTargets()) Tr.TARGETS_MET else Tr.MATCH)}"
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

/**
 * The damage-driver masteries, listed first in the compare table so a player can read off *why* two builds
 * differ in spell damage. Shown only when at least one build has a non-zero value (no all-zero clutter).
 */
private val COMPARE_DAMAGE_MASTERIES =
    listOf(
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND,
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_HEALING
    )

/**
 * The two global damage multipliers, **always** shown (even at 0) so a damage gap they cause — e.g. a
 * −20% Damage Inflicted from a sublimation — is never hidden by the "non-zero only" rule.
 */
private val COMPARE_DAMAGE_ALWAYS =
    listOf(Characteristic.DAMAGE_INFLICTED, Characteristic.CRITICAL_HIT)

@Composable
private fun ComparisonTable(columns: List<Pair<String, HistoryEntry>>) {
    val lang = LocalLang.current
    val entries = columns.map { it.second }
    val (damageRows, otherRows) =
        remember(columns.map { it.second.id }) {
            fun valuesOf(key: Characteristic) = entries.map { entry -> entry.result.achieved[key] ?: 0 }
            val damage =
                COMPARE_DAMAGE_MASTERIES.filter { key -> valuesOf(key).any { it != 0 } }.map { it to valuesOf(it) } +
                    COMPARE_DAMAGE_ALWAYS.map { it to valuesOf(it) }
            val damageKeys = damage.map { it.first }.toSet()
            val others =
                entries
                    .flatMap { it.result.achieved.keys }
                    .toSet()
                    .filterNot { it.isEngineInternalStat() || it in damageKeys }
                    .map { key -> key to valuesOf(key) }
                    .filter { (_, values) -> values.any { it != 0 } }
                    .sortedBy { it.first.ordinal }
            damage to others
        }
    CompareCard {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(text = tr(Tr.COMPARE_STAT), style = WTypography.labelMedium.copy(color = WColor.muted), modifier = Modifier.weight(1f))
            columns.forEach { (letter, _) ->
                Text(text = letter, style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted), modifier = Modifier.width(COMPARE_CELL))
            }
        }
        // Headline: the value the engine actually maximized — the row that says which build the solver judges best
        // overall, unlike the per-stat rows below. A mastery build maximized its mastery (specialized summed + min of
        // elements), a max-damage build its expected damage per turn: each gets its own row, with a dash under the builds
        // the row does not apply to (a max-damage build has no mastery score, and a mastery build no damage score).
        if (entries.any { !it.isDamageMode() }) {
            ValueRow(values = entries.map { if (it.isDamageMode()) null else it.requestedMasteryTotal().toLong() }, bold = true) {
                Text(
                    text = tr(Tr.COMPARE_ENGINE_SCORE),
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (entries.any { it.isDamageMode() }) {
            ValueRow(values = entries.map { if (it.isDamageMode()) it.expectedDamage() else null }, bold = true) {
                Text(
                    text = tr(Tr.COMPARE_ENGINE_DAMAGE),
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        CompareGroupLabel(text = tr(Tr.COMPARE_GROUP_DAMAGE))
        damageRows.forEachIndexed { index, (characteristic, values) ->
            if (index > 0) Hairline()
            StatValueRow(characteristic = characteristic, values = values, lang = lang)
        }
        if (otherRows.isNotEmpty()) {
            CompareGroupLabel(text = tr(Tr.COMPARE_GROUP_OTHER))
            otherRows.forEachIndexed { index, (characteristic, values) ->
                if (index > 0) Hairline()
                StatValueRow(characteristic = characteristic, values = values, lang = lang)
            }
        }
    }
}

/** A small muted subheading separating the compare-table stat groups (Damage / Other). */
@Composable
private fun CompareGroupLabel(text: String) {
    Text(
        text = text,
        style = WTypography.labelMedium.copy(color = WColor.muted),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

/** One stat row: the characteristic's icon + localized label, then its per-build value cells. */
@Composable
private fun StatValueRow(
    characteristic: Characteristic,
    values: List<Int>,
    lang: Lang,
) {
    ValueRow(values = values.map { it.toLong() }, bold = false) {
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

/**
 * One table row: a [leading] label (filling the width) followed by an integer value cell per build, the
 * best cell(s) highlighted green. Ties highlight nothing (matching the original two-build behaviour).
 * A `null` value (the row does not apply to that build) shows a dash and never wins.
 * [bold] forces every cell bold (used for the headline engine-score rows).
 */
@Composable
private fun ValueRow(
    values: List<Long?>,
    bold: Boolean,
    leading: @Composable RowScope.() -> Unit,
) {
    val present = values.filterNotNull()
    val best = present.maxOrNull() ?: 0L
    val tie = present.all { it == best }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        values.forEach { value ->
            NumberCell(text = value?.formatCompact() ?: "—", highlighted = value != null && value == best && !tie, bold = bold)
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
    /** The range band each build's numbers credit (its own saved scenario), shown per column for transparency. */
    val rangeBands: List<RangeBand> = emptyList(),
)

/**
 * Reconstructs each compared build and scores every class damage spell via [BuildSpellDamage], crediting
 * **each build's own range band** (distance/melee, from its saved scenario) so a distance build isn't
 * understated by dropping its biggest secondary mastery — at the cost of builds with different bands not
 * being perfectly apples-to-apples (the band used is shown per column). Resistance/rear/berserk stay neutral,
 * matching the Class spells tab. Returns [SpellDamageData.mixedClass] when the builds aren't all the same
 * class (their spell kits differ, so a per-spell comparison is meaningless). Spells are pre-sorted by the
 * strongest build's hit, so the heaviest hitters lead.
 */
private fun computeSpellDamage(entries: List<HistoryEntry>): SpellDamageData {
    if (entries.map { it.restoredClass() }.toSet().size > 1) {
        return SpellDamageData(mixedClass = true, spells = emptyList(), damageByEntry = emptyList())
    }
    val clazz = entries.firstOrNull()?.restoredClass() ?: return SpellDamageData(false, emptyList(), emptyList())
    val spells = SpellCatalog.damageSpells(clazz)
    val rangeBands = entries.map { it.restoredScenario().rangeBand }
    val damageByEntry =
        entries.mapIndexed { index, entry ->
            val build = entry.toBuildCombination()
            val character = Character(entry.restoredClass(), entry.request.level, entry.request.minLevel, build.characterSkills)
            val band = rangeBands[index].toSpellDamageRangeBand()
            spells.associate { spell -> spell.id to (BuildSpellDamage.expectedDamage(spell, build, character, rangeBand = band)?.expected ?: 0.0) }
        }
    val ordered = spells.sortedByDescending { spell -> damageByEntry.maxOfOrNull { it[spell.id] ?: 0.0 } ?: 0.0 }
    return SpellDamageData(mixedClass = false, spells = ordered, damageByEntry = damageByEntry, rangeBands = rangeBands)
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
                columns.forEachIndexed { index, (letter, _) ->
                    // Each column credits its own build's range band; surface it so a distance-vs-melee gap is explained.
                    val band = data.rangeBands.getOrNull(index)
                    Column(modifier = Modifier.width(COMPARE_CELL), horizontalAlignment = Alignment.Start) {
                        Text(text = letter, style = WTypography.labelMedium.copy(fontFamily = WType.mono, color = WColor.muted))
                        if (band != null) {
                            Text(text = band.label(lang), style = WTypography.labelSmall.copy(color = WColor.faint))
                        }
                    }
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
