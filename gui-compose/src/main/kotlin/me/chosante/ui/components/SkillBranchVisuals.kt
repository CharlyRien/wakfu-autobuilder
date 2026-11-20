package me.chosante.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.i18n.Tr
import me.chosante.ui.theme.WColor

/** The branch's title in the skill tree and the skill-points card. */
internal fun SkillBranch.labelKey(): Tr =
    when (this) {
        SkillBranch.INTELLIGENCE -> Tr.BRANCH_INTELLIGENCE
        SkillBranch.STRENGTH -> Tr.BRANCH_STRENGTH
        SkillBranch.AGILITY -> Tr.BRANCH_AGILITY
        SkillBranch.LUCK -> Tr.BRANCH_LUCK
        SkillBranch.MAJOR -> Tr.BRANCH_MAJOR
    }

/** The branch's dot colour — the same as the stats column's skill tree. */
internal fun SkillBranch.dotColor(): Color =
    when (this) {
        SkillBranch.INTELLIGENCE -> WColor.earth
        SkillBranch.STRENGTH -> WColor.fire
        SkillBranch.AGILITY -> WColor.accent2
        SkillBranch.LUCK -> Color(0xFFE8C24A)
        SkillBranch.MAJOR -> WColor.accent
    }

private val ANY_LEVEL_SKILLS = CharacterSkills(1)

/** The line's English name (the key of [me.chosante.ui.i18n.skillLabel]); the same at every level. */
internal val SkillLine.englishName: String get() = of(ANY_LEVEL_SKILLS).name

/** The line's own cap as shown in the skill tree: a number, or ∞ for an uncapped line. */
internal val SkillLine.capText: String get() = of(ANY_LEVEL_SKILLS).maxPointsAssignable.let { if (it == Int.MAX_VALUE) "∞" else it.toString() }

/**
 * The line's icon on its light tile — or, for a line with no game icon (the paired "… and damage" majors), an empty tile with the
 * branch's dot, so every label of a list stays aligned.
 */
@Composable
internal fun SkillLineIcon(
    line: SkillLine,
    size: Dp = 20.dp,
) {
    val bitmap = line.of(ANY_LEVEL_SKILLS).iconResourcePath()?.let { rememberClasspathBitmap(it) }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(if (bitmap != null) WColor.iconTile else WColor.raised),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.7f))
        } else {
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(line.branch.dotColor()))
        }
    }
}

/** A padlock drawn with shapes (no emoji font needed): closed and filled when [locked], open and outlined otherwise. */
@Composable
internal fun LockGlyph(
    locked: Boolean,
    color: Color,
    size: Dp = 13.dp,
) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.11f)
        val shackleLeft = w * 0.27f
        val shackleWidth = w * 0.46f
        val shift = if (locked) 0f else w * 0.18f
        val lift = if (locked) 0f else w * 0.06f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleLeft + shift, w * 0.06f - lift),
            size = Size(shackleWidth, w * 0.56f),
            style = stroke
        )
        val legTop = w * 0.34f - lift
        if (locked) drawLine(color, Offset(shackleLeft, legTop), Offset(shackleLeft, w * 0.47f), strokeWidth = stroke.width)
        drawLine(color, Offset(shackleLeft + shackleWidth + shift, legTop), Offset(shackleLeft + shackleWidth + shift, w * 0.47f), strokeWidth = stroke.width)
        val bodyTopLeft = Offset(w * 0.14f, w * 0.45f)
        val bodySize = Size(w * 0.72f, w * 0.52f)
        if (locked) {
            drawRoundRect(color = color, topLeft = bodyTopLeft, size = bodySize, cornerRadius = CornerRadius(w * 0.1f))
        } else {
            drawRoundRect(color = color, topLeft = bodyTopLeft, size = bodySize, cornerRadius = CornerRadius(w * 0.1f), style = stroke)
        }
    }
}
