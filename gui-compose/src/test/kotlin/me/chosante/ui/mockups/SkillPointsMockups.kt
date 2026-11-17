package me.chosante.ui.mockups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.components.DialogButton
import me.chosante.ui.components.Hairline
import me.chosante.ui.components.StepperButton
import me.chosante.ui.components.dotColor
import me.chosante.ui.components.iconResourcePath
import me.chosante.ui.components.labelKey
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.skillLabel
import me.chosante.ui.request.RequestCard
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/*
 * Design mock-ups for the imposed skill points (issue #284 review). NOT wired to the model: each composable takes plain data
 * and is rendered off-screen by SkillPointsMockupRenderTest into docs/design-reference/skill-points/. They reuse the project's
 * theme and components (RequestCard, StepperButton, DialogButton, the skill icons and branch colours), in French.
 */

internal val LANG = Lang.FR
internal const val LEVEL = 230

private val skills = CharacterSkills(LEVEL)

internal fun SkillLine.label(): String = skillLabel(of(skills).name, LANG)

private fun SkillLine.capText(): String = of(skills).maxPointsAssignable.let { if (it == Int.MAX_VALUE) "∞" else it.toString() }

internal fun SkillBranch.pool(): Int = of(skills).maxPointsToAssign

internal fun SkillBranch.title(): String = labelKey().value(LANG)

/** A full result allocation at level 230 (what a search could return): every branch spent. */
internal val RESULT: Map<SkillLine, Int> =
    mapOf(
        SkillLine.INTELLIGENCE_HP_PERCENTAGE to 58,
        SkillLine.STRENGTH_MASTERY_DISTANCE to 40,
        SkillLine.STRENGTH_MASTERY_ELEMENTARY to 17,
        SkillLine.AGILITY_DODGE to 57,
        SkillLine.LUCK_CRITICAL_HIT to 20,
        SkillLine.LUCK_MASTERY_CRITICAL to 37,
        SkillLine.MAJOR_ACTION_POINT to 1,
        SkillLine.MAJOR_MOVEMENT_POINT to 1,
        SkillLine.MAJOR_RANGE to 1,
        SkillLine.MAJOR_DAMAGE_INFLICTED to 1
    )

/** The issue's request: all agility in dodge, luck in crit + crit mastery, the AP major. */
internal val ISSUE: Map<SkillLine, Int> =
    mapOf(
        SkillLine.AGILITY_DODGE to 57,
        SkillLine.LUCK_CRITICAL_HIT to 20,
        SkillLine.LUCK_MASTERY_CRITICAL to 37,
        SkillLine.MAJOR_ACTION_POINT to 1
    )

private fun Map<SkillLine, Int>.branchTotal(branch: SkillBranch) = entries.sumOf { if (it.key.branch == branch) it.value else 0 }

// ---------------------------------------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------------------------------------

/** The skill line's icon on its light tile, or an empty tile of the same size (so labels always align). */
@Composable
internal fun LineIcon(
    line: SkillLine,
    size: Dp = 20.dp,
) {
    val bitmap = line.of(skills).iconResourcePath()?.let { rememberClasspathBitmap(it) }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(if (bitmap != null) WColor.iconTile else WColor.raised),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.7f))
        } else {
            // Lines with no game icon (the paired "X and damage" majors…): the branch dot keeps the column aligned.
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(line.branch.dotColor()))
        }
    }
}

@Composable
private fun BranchDot(branch: SkillBranch) {
    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(branch.dotColor()))
}

/** A padlock drawn with shapes (no emoji font needed): closed + filled when [locked], open + outlined otherwise. */
@Composable
internal fun LockGlyph(
    locked: Boolean,
    color: Color,
    size: Dp = 14.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.11f)
        val shackleLeft = w * 0.27f
        val shackleWidth = w * 0.46f
        val shackleShift = if (locked) 0f else w * 0.18f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleLeft + shackleShift, w * 0.06f - if (locked) 0f else w * 0.06f),
            size = Size(shackleWidth, w * 0.56f),
            style = stroke
        )
        // Shackle legs down to the body (only the right one when open).
        val legTop = w * 0.34f - if (locked) 0f else w * 0.06f
        if (locked) drawLine(color, Offset(shackleLeft, legTop), Offset(shackleLeft, w * 0.47f), strokeWidth = stroke.width)
        drawLine(color, Offset(shackleLeft + shackleWidth + shackleShift, legTop), Offset(shackleLeft + shackleWidth + shackleShift, w * 0.47f), strokeWidth = stroke.width)
        val bodyTop = w * 0.45f
        if (locked) {
            drawRoundRect(color = color, topLeft = Offset(w * 0.14f, bodyTop), size = Size(w * 0.72f, w * 0.52f), cornerRadius = CornerRadius(w * 0.1f))
        } else {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.14f, bodyTop),
                size = Size(w * 0.72f, w * 0.52f),
                cornerRadius = CornerRadius(w * 0.1f),
                style = stroke
            )
        }
    }
}

/** The "add" chip of the request panel (same as "Imposer un objet"). */
@Composable
private fun AddChip(text: String) {
    Box(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, WColor.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = WTypography.labelMedium.copy(color = WColor.accent, lineHeight = 14.sp))
    }
}

/** One imposed line as a chip: icon, name, value, ✕ (the forced-item chip's look, with the branch colour as border). */
@Composable
private fun SkillChip(
    line: SkillLine,
    value: Int,
    selected: Boolean = false,
    locked: Boolean = false,
) {
    val accent = line.branch.dotColor()
    Row(
        modifier =
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) accent.copy(alpha = 0.14f) else WColor.raised)
                .border(1.dp, accent.copy(alpha = if (selected) 0.9f else 0.45f), RoundedCornerShape(8.dp))
                .padding(start = 5.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LineIcon(line, size = 20.dp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = line.label(), style = WTypography.labelMedium.copy(color = WColor.text))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = value.toString(), style = WTypography.labelMedium.copy(color = accent, fontFamily = WType.mono, fontWeight = FontWeight.Bold))
        if (locked) {
            Spacer(modifier = Modifier.width(5.dp))
            LockGlyph(locked = true, color = WColor.accent, size = 11.dp)
        }
        Spacer(modifier = Modifier.width(7.dp))
        Text(text = "×", style = WTypography.labelMedium.copy(color = WColor.faint, lineHeight = 14.sp))
    }
}

/** "Agilité 0 · Chance 0 · Majeur 3": the points each touched branch still leaves to the search. */
@Composable
private fun RemainingLine(imposed: Map<SkillLine, Int>) {
    val touched = SkillBranch.entries.filter { imposed.branchTotal(it) > 0 }
    if (touched.isEmpty()) return
    Text(
        text =
            "Points laissés à la recherche : " +
                touched.joinToString(" · ") { "${it.title()} ${it.pool() - imposed.branchTotal(it)}" },
        style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
    )
}

// ---------------------------------------------------------------------------------------------------------------------------
// A. Chips in the request card + a picker by branch
// ---------------------------------------------------------------------------------------------------------------------------

/** A: the request card shows only the imposed lines, as chips; [editing] shows the inline editor of one chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsCard(
    imposed: Map<SkillLine, Int>,
    editing: SkillLine? = null,
    locked: Set<SkillLine> = emptySet(),
) {
    RequestCard(title = "Points d'aptitude imposés") {
        if (imposed.isEmpty()) {
            Text(
                text = "Aucun : la recherche répartit tous les points. Imposez une ligne pour garder au moins ces points.",
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp),
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            imposed.entries.sortedBy { it.key.ordinal }.forEach { (line, value) ->
                SkillChip(line, value, selected = line == editing, locked = line in locked)
            }
            AddChip("＋ Ajouter une aptitude")
        }
        if (editing != null) {
            Spacer(modifier = Modifier.height(10.dp))
            ChipEditor(editing, imposed.getValue(editing), imposed)
        }
        if (imposed.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            RemainingLine(imposed)
        }
    }
}

/** The editor a chip opens in place: −/value/＋, the line's cap and the branch's room, a "Max" shortcut. */
@Composable
private fun ChipEditor(
    line: SkillLine,
    value: Int,
    imposed: Map<SkillLine, Int>,
) {
    val room = line.branch.pool() - imposed.branchTotal(line.branch)
    val lineMax = minOf(line.maxPoints(skills), value + room)
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
            LineIcon(line)
            Spacer(modifier = Modifier.width(7.dp))
            Text(text = line.label(), style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(text = "minimum", style = WTypography.labelSmall.copy(color = WColor.muted))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StepperButton(glyph = "−", enabled = value > 0, onClick = {})
            ValueBox(value.toString(), 48.dp)
            StepperButton(glyph = "＋", enabled = value < lineMax, onClick = {})
            Spacer(modifier = Modifier.width(4.dp))
            SmallAction("Max", enabled = value < lineMax)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "plafond ${line.capText()} · reste $room",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
        }
    }
}

@Composable
private fun ValueBox(
    text: String,
    width: Dp,
) {
    Box(
        modifier =
            Modifier
                .width(width)
                .height(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = WTypography.bodyMedium.copy(fontFamily = WType.mono, fontWeight = FontWeight.Medium))
    }
}

/** A text action ("Max", "Tout ici") — readable when enabled, plainly absent-looking when not. */
@Composable
private fun SmallAction(
    text: String,
    enabled: Boolean,
) {
    Box(
        modifier =
            Modifier
                .height(26.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, if (enabled) WColor.accent.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = WTypography.labelSmall.copy(color = if (enabled) WColor.accent else WColor.faint, fontWeight = FontWeight.SemiBold))
    }
}

/** A: the picker "Ajouter une aptitude" opens (a modal, like the item / sublimation pickers): branch tabs, then the lines. */
@Composable
internal fun SkillPicker(
    imposed: Map<SkillLine, Int>,
    branch: SkillBranch,
) {
    val room = branch.pool() - imposed.branchTotal(branch)
    Column(
        modifier =
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Text(text = "Ajouter une aptitude", style = WTypography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Niveau $LEVEL · minimum gardé par la recherche",
            style = WTypography.labelSmall.copy(color = WColor.muted)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkillBranch.entries.forEach { b ->
                val left = b.pool() - imposed.branchTotal(b)
                val active = b == branch
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) b.dotColor().copy(alpha = 0.16f) else WColor.bg)
                            .border(1.dp, if (active) b.dotColor() else WColor.border, RoundedCornerShape(8.dp))
                            .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BranchDot(b)
                        Text(text = b.title(), style = WTypography.labelSmall.copy(color = if (active) WColor.text else WColor.muted, fontWeight = FontWeight.SemiBold))
                    }
                    Text(
                        text = "reste $left",
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = if (left == 0) WColor.faint else WColor.text)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (room == 0) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(WColor.warning.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "${branch.title()} pleine : ${branch.pool()} points sur ${branch.pool()} imposés. Retirez-en d'une ligne pour en imposer une autre.",
                    style = WTypography.labelSmall.copy(color = WColor.warning, lineHeight = 15.sp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        SkillLine.entries.filter { it.branch == branch }.forEach { line ->
            val current = imposed[line] ?: 0
            val canAdd = room > 0 && current < line.maxPoints(skills)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (current > 0) branch.dotColor().copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LineIcon(line, size = 22.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = line.label(), style = WTypography.bodySmall.copy(color = if (canAdd || current > 0) WColor.text else WColor.faint))
                    Text(
                        text = "plafond ${line.capText()}",
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
                when {
                    current > 0 -> Text(text = "imposé · $current", style = WTypography.labelSmall.copy(color = branch.dotColor(), fontWeight = FontWeight.SemiBold))
                    canAdd -> {
                        SmallAction("+1", enabled = true)
                        Spacer(modifier = Modifier.width(6.dp))
                        SmallAction("Tout ici · ${minOf(room, line.maxPoints(skills))}", enabled = true)
                    }
                    else -> Text(text = "—", style = WTypography.labelSmall.copy(color = WColor.faint))
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        DialogButton(text = "Terminé", filled = true, color = WColor.accent, onClick = {}, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// B. Pin from the result: padlocks in the stats panel's skill tree
// ---------------------------------------------------------------------------------------------------------------------------

/** B: the stats panel's skill tree, each spent line with a padlock; [locked] lines are imposed on the next search. */
@Composable
internal fun LockableSkillTree(
    result: Map<SkillLine, Int>,
    locked: Map<SkillLine, Int>,
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
            Text(text = "Répartition des aptitudes", style = WTypography.labelMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f).height(1.dp).background(WColor.hairline))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (locked.isEmpty()) "5 branches" else "${locked.size} imposée(s)",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, fontWeight = FontWeight.SemiBold, color = if (locked.isEmpty()) WColor.muted else WColor.accent)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        SkillBranch.entries.forEachIndexed { index, branch ->
            if (index > 0) Hairline()
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BranchDot(branch)
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(text = branch.title(), style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = "${result.branchTotal(branch)}/${branch.pool()}", style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted))
                }
                Column(modifier = Modifier.padding(start = 17.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    SkillLine.entries.filter { it.branch == branch }.forEach { line ->
                        val points = result[line] ?: 0
                        val isLocked = line in locked
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            LineIcon(line)
                            Text(
                                text = line.label(),
                                style = WTypography.bodySmall.copy(color = if (points > 0) WColor.text else WColor.muted, lineHeight = 15.sp),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$points/${line.capText()}",
                                style =
                                    WTypography.bodySmall.copy(
                                        fontFamily = WType.mono,
                                        color =
                                            if (isLocked) {
                                                WColor.accent
                                            } else if (points > 0) {
                                                WColor.muted
                                            } else {
                                                WColor.faint
                                            }
                                    )
                            )
                            // The padlock only on spent lines (locking 0 imposes nothing): a 22-dp hit area, closed + accent when locked.
                            Box(
                                modifier =
                                    Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isLocked) WColor.accent.copy(alpha = 0.16f) else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (points > 0) LockGlyph(locked = isLocked, color = if (isLocked) WColor.accent else WColor.faint.copy(alpha = 0.7f), size = 13.dp)
                            }
                        }
                    }
                }
            }
        }
        Hairline()
        Spacer(modifier = Modifier.height(12.dp))
        val allLocked = result.keys.all { it in locked }
        DialogButton(
            text = if (allLocked) "Retirer tous les verrous" else "Imposer cette répartition",
            filled = !allLocked,
            color = WColor.accent,
            onClick = {},
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text =
                if (allLocked) {
                    "Toute la répartition est imposée à la prochaine recherche."
                } else {
                    "Le cadenas garde une ligne à sa valeur (minimum) pour la prochaine recherche."
                },
            style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp)
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// C. A summary card + a dedicated five-column editor
// ---------------------------------------------------------------------------------------------------------------------------

/** C: the request card only summarizes, one row per touched branch, and opens the editor. */
@Composable
internal fun SummaryCard(imposed: Map<SkillLine, Int>) {
    RequestCard(title = "Points d'aptitude imposés", trailing = if (imposed.isEmpty()) null else "${imposed.size} lignes") {
        if (imposed.isEmpty()) {
            Text(text = "Aucun : la recherche répartit tous les points.", style = WTypography.labelSmall.copy(color = WColor.muted))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SkillBranch.entries.filter { imposed.branchTotal(it) > 0 }.forEach { branch ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.padding(top = 5.dp)) { BranchDot(branch) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                imposed.entries
                                    .filter { it.key.branch == branch }
                                    .sortedBy { it.key.ordinal }
                                    .joinToString(" · ") { "${it.key.label()} ${it.value}" },
                            style = WTypography.bodySmall.copy(color = WColor.text, lineHeight = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${imposed.branchTotal(branch)} sur ${branch.pool()}",
                            style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        DialogButton(text = "Répartir les points…", filled = false, color = WColor.accent, onClick = {}, modifier = Modifier.fillMaxWidth())
    }
}

/** C: the editor window, one column per branch like the game's skill window. */
@Composable
internal fun SkillWindow(imposed: Map<SkillLine, Int>) {
    Column(
        modifier =
            Modifier
                .width(1080.dp)
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Points d'aptitude imposés · niveau $LEVEL", style = WTypography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "minimum par ligne : la recherche place le reste", style = WTypography.labelSmall.copy(color = WColor.muted))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            SkillBranch.entries.forEach { branch ->
                val used = imposed.branchTotal(branch)
                val room = branch.pool() - used
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(WColor.bg)
                            .border(1.dp, WColor.hairline, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BranchDot(branch)
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(text = branch.title(), style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "reste $room / ${branch.pool()}",
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = if (room == 0) WColor.faint else branch.dotColor())
                    )
                    // A thin gauge of the branch's pool.
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 5.dp)
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(WColor.raised)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(used.toFloat() / branch.pool().coerceAtLeast(1)).height(3.dp).background(branch.dotColor()))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkillLine.entries.filter { it.branch == branch }.forEach { line ->
                            val value = imposed[line] ?: 0
                            val max = minOf(line.maxPoints(skills), value + room)
                            Column {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LineIcon(line, size = 18.dp)
                                    Text(
                                        text = line.label(),
                                        style = WTypography.labelSmall.copy(color = if (value > 0) WColor.text else WColor.muted, lineHeight = 14.sp),
                                        modifier = Modifier.weight(1f).heightIn(min = 28.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    StepperButton(glyph = "−", enabled = value > 0, onClick = {})
                                    Box(modifier = Modifier.widthIn(min = 30.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = value.toString(),
                                            style =
                                                WTypography.bodySmall.copy(
                                                    fontFamily = WType.mono,
                                                    color =
                                                        if (value >
                                                            0
                                                        ) {
                                                            WColor.text
                                                        } else {
                                                            WColor.faint
                                                        },
                                                    textAlign = TextAlign.Center
                                                )
                                        )
                                    }
                                    StepperButton(glyph = "＋", enabled = value < max, onClick = {})
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(text = "/${line.capText()}", style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.faint))
                                    SmallAction("max", enabled = value < max)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Remettre à zéro", style = WTypography.labelMedium.copy(color = WColor.accent2))
            Spacer(modifier = Modifier.weight(1f))
            DialogButton(text = "Annuler", filled = false, color = WColor.accent, onClick = {}, modifier = Modifier.width(120.dp))
            DialogButton(text = "Valider", filled = true, color = WColor.accent, onClick = {}, modifier = Modifier.width(120.dp))
        }
    }
}
