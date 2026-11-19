package me.chosante.ui.request

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.components.SkillLineIcon
import me.chosante.ui.components.StepperButton
import me.chosante.ui.components.capText
import me.chosante.ui.components.dotColor
import me.chosante.ui.components.englishName
import me.chosante.ui.components.labelKey
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.skillLabel
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ImposedSkillPoints
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

internal const val SKILL_POINTS_CARD_TAG = "skill-points-card"
internal const val SKILL_POINTS_ADD_TAG = "skill-points-add"

internal fun skillChipTag(line: SkillLine) = "skill-chip-${line.name}"

internal fun skillPickerTabTag(branch: SkillBranch) = "skill-picker-tab-${branch.name}"

internal fun skillPickerLineTag(line: SkillLine) = "skill-picker-line-${line.name}"

/**
 * The skill points the player imposes before the search (issue #284), as the forced items are shown: one chip per imposed line
 * ("Esquive 57 ×"), an "Add a skill" chip opening the picker ([SkillPickerModal]), and, under them, what each touched branch still
 * leaves to the search. A click on a chip opens its editor in place: −/value/＋, "Max", the line's cap and its branch's room. Each
 * value is EXACT (the engine's `forcedSkillPoints`) and clamped by [ImposedSkillPoints.withLine], so the card never builds a request
 * `validateRequest` would refuse.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillPointsCard(
    imposed: ImposedSkillPoints,
    level: Int,
    onChange: (SkillLine, Int) -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
) {
    var editing by remember { mutableStateOf<SkillLine?>(null) }
    val editingLine = editing?.takeIf { it in imposed }
    Box(modifier = Modifier.testTag(SKILL_POINTS_CARD_TAG)) {
        RequestCard(title = tr(Tr.SKILL_POINTS_CARD)) {
            if (imposed.isEmpty) {
                Text(
                    text = tr(Tr.SKILL_POINTS_EMPTY),
                    style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                imposed.points.entries.sortedBy { it.key.ordinal }.forEach { (line, value) ->
                    SkillChip(
                        line = line,
                        value = value,
                        selected = line == editingLine,
                        onClick = { editing = if (line == editingLine) null else line },
                        onRemove = {
                            if (line == editingLine) editing = null
                            onChange(line, 0)
                        }
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                            .testTag(SKILL_POINTS_ADD_TAG)
                            .clickable(onClick = onAdd)
                            .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = tr(Tr.SKILL_POINTS_ADD), style = WTypography.labelMedium.copy(color = WColor.accent, lineHeight = 14.sp))
                }
            }
            if (editingLine != null) {
                Spacer(modifier = Modifier.height(10.dp))
                ChipEditor(
                    line = editingLine,
                    value = imposed[editingLine],
                    max = imposed.maxFor(editingLine, level),
                    room = imposed.remaining(editingLine.branch, level),
                    onChange = { onChange(editingLine, it) }
                )
            }
            if (!imposed.isEmpty) {
                val lang = LocalLang.current
                val touched = SkillBranch.entries.filter { imposed.branchTotal(it) > 0 }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = tr(Tr.SKILL_POINTS_LEFT).format(touched.joinToString(" · ") { "${it.labelKey().value(lang)} ${imposed.remaining(it, level)}" }),
                    style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
                )
                Text(
                    text = tr(Tr.SKILL_POINTS_RESET),
                    style = WTypography.labelSmall.copy(color = WColor.accent2),
                    modifier =
                        Modifier
                            .clickable {
                                editing = null
                                onReset()
                            }.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SkillChip(
    line: SkillLine,
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = line.branch.dotColor()
    Row(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) accent.copy(alpha = 0.14f) else WColor.raised)
                .border(1.dp, accent.copy(alpha = if (selected) 0.9f else 0.45f), RoundedCornerShape(8.dp))
                .testTag(skillChipTag(line))
                .clickable(onClick = onClick)
                .padding(start = 5.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkillLineIcon(line)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = skillLabel(line.englishName, LocalLang.current), style = WTypography.labelMedium.copy(color = WColor.text))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = value.toString(), style = WTypography.labelMedium.copy(color = accent, fontFamily = WType.mono, fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = "×",
            style = WTypography.labelMedium.copy(color = WColor.faint, lineHeight = 14.sp),
            modifier = Modifier.clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun ChipEditor(
    line: SkillLine,
    value: Int,
    max: Int,
    room: Int,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(10.dp))
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkillLineIcon(line)
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = skillLabel(line.englishName, LocalLang.current),
                style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f)
            )
            Text(text = tr(Tr.SKILL_EDITOR_EXACT), style = WTypography.labelSmall.copy(color = WColor.muted))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepperButton(glyph = "−", enabled = value > 0, onClick = { onChange(value - 1) })
            NumberField(
                value = value.toString(),
                onValueChange = { typed -> onChange(typed.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) },
                width = 48.dp
            )
            StepperButton(glyph = "＋", enabled = value < max, onClick = { onChange(value + 1) })
            SkillTextAction(text = tr(Tr.SKILL_EDITOR_MAX), enabled = value < max, onClick = { onChange(max) })
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = tr(Tr.SKILL_EDITOR_CAP_ROOM).format(line.capText, room),
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
        }
    }
}

/** A text action ("Max", "All here · N"): readable when enabled, plainly inert when not. */
@Composable
internal fun SkillTextAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(26.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, if (enabled) WColor.accent.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(7.dp))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = WTypography.labelSmall.copy(color = if (enabled) WColor.accent else WColor.faint, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * The "Add a skill" picker: one tab per branch with what its pool still leaves, then the branch's lines with their cap — "+1" and
 * "All here · N" on a free line, −/value/＋ on an imposed one, a banner when the branch is full.
 */
@Composable
internal fun SkillPickerContent(
    imposed: ImposedSkillPoints,
    level: Int,
    onChange: (SkillLine, Int) -> Unit,
    onDone: () -> Unit,
) {
    var branch by remember { mutableStateOf(SkillBranch.entries.firstOrNull { imposed.branchTotal(it) > 0 } ?: SkillBranch.AGILITY) }
    val lang = LocalLang.current
    val skills = remember(level) { CharacterSkills(level) }
    val room = imposed.remaining(branch, level)
    Column(
        modifier =
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(me.chosante.ui.theme.WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(me.chosante.ui.theme.WDimens.radius))
                .padding(me.chosante.ui.theme.WDimens.pad)
    ) {
        Text(text = tr(Tr.SKILL_PICKER_TITLE), style = WTypography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = tr(Tr.SKILL_PICKER_SUBTITLE).format(level), style = WTypography.labelSmall.copy(color = WColor.muted))
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkillBranch.entries.forEach { b ->
                val active = b == branch
                val left = imposed.remaining(b, level)
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) b.dotColor().copy(alpha = 0.16f) else WColor.bg)
                            .border(1.dp, if (active) b.dotColor() else WColor.border, RoundedCornerShape(8.dp))
                            .testTag(skillPickerTabTag(b))
                            .clickable { branch = b }
                            .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier =
                                Modifier
                                    .width(7.dp)
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(b.dotColor())
                        )
                        Text(
                            text = b.labelKey().value(lang),
                            style = WTypography.labelSmall.copy(color = if (active) WColor.text else WColor.muted, fontWeight = FontWeight.SemiBold),
                            maxLines = 1
                        )
                    }
                    Text(
                        text = tr(Tr.SKILL_PICKER_REMAINING).format(left),
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = if (left == 0) WColor.faint else WColor.text)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (room == 0) {
            val pool = branch.of(skills).maxPointsToAssign
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(WColor.warning.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = tr(Tr.SKILL_PICKER_FULL).format(branch.labelKey().value(lang), imposed.branchTotal(branch), pool),
                    style = WTypography.labelSmall.copy(color = WColor.warning, lineHeight = 15.sp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        SkillLine.entries.filter { it.branch == branch }.forEach { line ->
            val current = imposed[line]
            val max = imposed.maxFor(line, level)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (current > 0) branch.dotColor().copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                        .testTag(skillPickerLineTag(line))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SkillLineIcon(line, size = 22.dp)
                Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(
                        text = skillLabel(line.englishName, lang),
                        style = WTypography.bodySmall.copy(color = if (current > 0 || max > 0) WColor.text else WColor.faint)
                    )
                    Text(text = tr(Tr.SKILL_PICKER_CAP).format(line.capText), style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted))
                }
                when {
                    current > 0 -> {
                        StepperButton(glyph = "−", enabled = true, onClick = { onChange(line, current - 1) })
                        Text(
                            text = current.toString(),
                            style = WTypography.labelMedium.copy(color = branch.dotColor(), fontFamily = WType.mono, fontWeight = FontWeight.Bold)
                        )
                        StepperButton(glyph = "＋", enabled = current < max, onClick = { onChange(line, current + 1) })
                    }
                    max > 0 -> {
                        SkillTextAction(text = "+1", enabled = true, onClick = { onChange(line, 1) })
                        SkillTextAction(text = tr(Tr.SKILL_PICKER_ALL_HERE).format(max), enabled = true, onClick = { onChange(line, max) })
                    }
                    else -> Text(text = "—", style = WTypography.labelSmall.copy(color = WColor.faint))
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        me.chosante.ui.components.DialogButton(
            text = tr(Tr.SKILL_PICKER_DONE),
            filled = true,
            color = WColor.accent,
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
