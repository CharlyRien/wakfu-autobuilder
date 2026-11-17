package me.chosante.ui.request

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.components.Hairline
import me.chosante.ui.components.StepperButton
import me.chosante.ui.components.dotColor
import me.chosante.ui.components.iconResourcePath
import me.chosante.ui.components.labelKey
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.skillLabel
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ImposedSkillPoints
import me.chosante.ui.state.SkillPointsRule
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

internal const val SKILL_POINTS_CARD_TAG = "skill-points-card"

internal fun skillPointsBranchTag(branch: SkillBranch) = "skill-points-branch-${branch.name}"

internal fun skillPointsLineTag(line: SkillLine) = "skill-points-line-${line.name}"

/**
 * The skill points the player imposes before the search (issue #284): one foldable row per branch (its dot, its name, the
 * points imposed on it against the level's pool), and in each branch a −/value/＋ editor per line. The values are clamped by
 * [ImposedSkillPoints.withLine], so the card never builds a request `validateRequest` would refuse. The hint under the title
 * states the [ImposedSkillPoints.rule] in force — a future rule only needs its own hint key here.
 */
@Composable
internal fun SkillPointsCard(
    imposed: ImposedSkillPoints,
    level: Int,
    onChange: (SkillLine, Int) -> Unit,
    onReset: () -> Unit,
) {
    val skills = remember(level) { CharacterSkills(level) }
    val total = imposed.points.values.sum()
    // Branches with imposed points start open, so a restored request shows what it imposes.
    var expanded by remember { mutableStateOf(SkillBranch.entries.filter { imposed.branchTotal(it) > 0 }.toSet()) }
    Box(modifier = Modifier.testTag(SKILL_POINTS_CARD_TAG)) {
        RequestCard(title = tr(Tr.SKILL_POINTS_CARD), trailing = if (total > 0) total.toString() else null) {
            Text(
                text =
                    when (imposed.rule) {
                        SkillPointsRule.MINIMUM -> tr(Tr.SKILL_POINTS_HINT_MINIMUM)
                    },
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            SkillBranch.entries.forEach { branch ->
                Hairline()
                val open = branch in expanded
                BranchHeader(
                    branch = branch,
                    imposedPoints = imposed.branchTotal(branch),
                    pool = branch.of(skills).maxPointsToAssign,
                    open = open,
                    onToggle = { expanded = if (open) expanded - branch else expanded + branch }
                )
                if (open) {
                    Column(
                        modifier = Modifier.padding(start = 17.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SkillLine.entries.filter { it.branch == branch }.forEach { line ->
                            LineEditor(
                                line = line,
                                name = line.of(skills).name,
                                iconPath = line.of(skills).iconResourcePath(),
                                value = imposed[line],
                                max = imposed.maxFor(line, level),
                                onChange = { onChange(line, it) }
                            )
                        }
                    }
                }
            }
            if (!imposed.isEmpty) {
                Hairline()
                Text(
                    text = tr(Tr.SKILL_POINTS_RESET),
                    style = WTypography.labelSmall.copy(color = WColor.accent2),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onReset)
                            .padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun BranchHeader(
    branch: SkillBranch,
    imposedPoints: Int,
    pool: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(skillPointsBranchTag(branch))
                .clickable(onClick = onToggle)
                .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (open) "▾" else "▸",
            style = WTypography.labelSmall.copy(color = WColor.faint),
            modifier = Modifier.width(12.dp)
        )
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(branch.dotColor()))
        Spacer(modifier = Modifier.width(9.dp))
        Text(text = tr(branch.labelKey()), style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "$imposedPoints/$pool",
            style =
                WTypography.labelSmall.copy(
                    fontFamily = WType.mono,
                    color = if (imposedPoints > 0) WColor.text else WColor.muted
                )
        )
    }
}

@Composable
private fun LineEditor(
    line: SkillLine,
    name: String,
    iconPath: String?,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(skillPointsLineTag(line)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val bitmap = iconPath?.let { rememberClasspathBitmap(it) }
        if (bitmap != null) {
            Box(
                modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(WColor.iconTile),
                contentAlignment = Alignment.Center
            ) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            text = skillLabel(name, LocalLang.current),
            style = WTypography.bodySmall.copy(color = if (value > 0) WColor.text else WColor.muted, lineHeight = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        StepperButton(glyph = "−", enabled = value > 0, onClick = { onChange(value - 1) })
        NumberField(
            value = if (value > 0) value.toString() else "",
            onValueChange = { typed -> onChange(typed.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) },
            width = 44.dp
        )
        StepperButton(glyph = "＋", enabled = value < max, onClick = { onChange(value + 1) })
    }
}
