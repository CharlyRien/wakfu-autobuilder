package me.chosante.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.UiState
import me.chosante.ui.state.ZenithState
import me.chosante.ui.state.isExact
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography
import java.text.NumberFormat
import java.util.Locale

@Composable
fun StatsPanel(
    ui: UiState,
    onOpenZenith: () -> Unit,
    onCopyZenith: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WDimens.gap),
        verticalArrangement = Arrangement.spacedBy(WDimens.gap)
    ) {
        MatchHero(ui)
        if (ui.phase == Phase.Idle && ui.build == null) {
            EmptyHint()
        } else {
            ActionsCard(
                ui = ui,
                onOpenZenith = onOpenZenith,
                onCopyZenith = onCopyZenith
            )
            MasterySummary(ui)
            DesiredVsAchieved(ui)
            SkillTree(ui.build?.characterSkills ?: CharacterSkills(ui.level))
        }
    }
}

@Composable
private fun MatchHero(ui: UiState) {
    ResultCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = ui.match.toInt().toString(),
                    style =
                        WTypography.displayLarge.copy(
                            fontSize = 46.sp,
                            lineHeight = 46.sp,
                            color = if (ui.match.toInt() == 100) WColor.success else WColor.text,
                            fontFamily = WType.display,
                            textAlign = TextAlign.Center
                        )
                )
                Text(
                    text = "%",
                    style =
                        WTypography.headlineMedium.copy(
                            color = WColor.muted,
                            lineHeight = 24.sp
                        )
                )
            }
            Text(
                text = tr(Tr.BUILD_MATCH),
                style = WTypography.labelMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (ui.build != null) {
                Text(
                    text = tr(if (ui.optimal) Tr.OPTIMAL_PROVEN else Tr.BEST_FOUND),
                    style = WTypography.labelSmall.copy(color = if (ui.optimal) WColor.success else WColor.warning),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Meter(
                fill = ui.match.toFloat() / 100f,
                color = if (ui.match.toInt() == 100) WColor.success else WColor.warning,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

@Composable
private fun DesiredVsAchieved(ui: UiState) {
    ResultCard(title = tr(Tr.DESIRED_VS_ACHIEVED)) {
        groupedTargets(ui.targets).forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                Hairline()
            }
            Text(
                text = tr(group.title),
                style = WTypography.labelMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else 10.dp, bottom = 2.dp)
            )
            group.targets.forEachIndexed { index, target ->
                if (index > 0) Hairline()
                StatRow(
                    target = target,
                    achieved = ui.achieved[target.characteristic] ?: 0,
                    mode = ui.mode
                )
            }
        }
    }
}

@Composable
private fun MasterySummary(ui: UiState) {
    val elementalMasteries =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        )
    val specializedMasteries =
        listOf(
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_HEALING
        )
    val elementalValues = elementalMasteries.map { it to (ui.achieved[it] ?: 0) }
    val specializedValues = specializedMasteries.map { it to (ui.achieved[it] ?: 0) }
    val trackedTotal = (elementalValues.minOfOrNull { it.second } ?: 0) + specializedValues.sumOf { it.second }

    ResultCard(
        title = tr(Tr.MASTERY_SUMMARY),
        trailing = trackedTotal.formatCompact()
    ) {
        SummaryMetric(label = tr(Tr.MASTERY_TOTAL), value = trackedTotal)
        Hairline()
        MasteryGroup(title = tr(Tr.MASTERY_ELEMENTALS), values = elementalValues)
        Hairline()
        MasteryGroup(title = tr(Tr.MASTERY_SPECIALIZED), values = specializedValues)
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value.formatCompact(),
            style = WTypography.headlineMedium.copy(fontFamily = WType.mono, color = WColor.text)
        )
    }
}

@Composable
private fun MasteryGroup(
    title: String,
    values: List<Pair<Characteristic, Int>>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = WTypography.labelSmall.copy(color = WColor.muted))
        values.forEach { (characteristic, value) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = characteristic.label(LocalLang.current),
                    style = WTypography.bodySmall.copy(color = WColor.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = value.formatCompact(),
                    style = WTypography.bodySmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
        }
    }
}

private data class TargetGroup(
    val title: Tr,
    val targets: List<TargetRow>,
)

private fun groupedTargets(targets: List<TargetRow>): List<TargetGroup> {
    val groups =
        listOf(
            TargetGroup(Tr.STAT_GROUP_CORE, targets.filter { it.characteristic in coreTargetStats }),
            TargetGroup(Tr.MASTERY_ELEMENTALS, targets.filter { it.characteristic in elementalTargetMasteries }),
            TargetGroup(Tr.MASTERY_SPECIALIZED, targets.filter { it.characteristic in specializedTargetMasteries }),
            TargetGroup(Tr.STAT_GROUP_RESISTANCES, targets.filter { it.characteristic.name.startsWith("RESISTANCE") }),
            TargetGroup(
                Tr.STAT_GROUP_SECONDARY,
                targets.filter {
                    it.characteristic !in coreTargetStats &&
                        it.characteristic !in allTargetMasteries &&
                        !it.characteristic.name.startsWith("RESISTANCE")
                }
            )
        )
    return groups.filter { it.targets.isNotEmpty() }
}

private val coreTargetStats =
    setOf(
        Characteristic.ACTION_POINT,
        Characteristic.MOVEMENT_POINT,
        Characteristic.RANGE,
        Characteristic.WAKFU_POINT,
        Characteristic.CRITICAL_HIT,
        Characteristic.HP
    )

private val elementalTargetMasteries =
    setOf(
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val specializedTargetMasteries =
    setOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

private val allTargetMasteries = elementalTargetMasteries + specializedTargetMasteries

@Composable
private fun StatRow(
    target: TargetRow,
    achieved: Int,
    mode: ScoreComputationMode,
) {
    val targetValue = target.value.toIntOrNull() ?: 0
    val exact = target.isExact(mode)
    val status =
        when {
            exact && achieved >= targetValue -> StatStatus.Ok
            exact -> StatStatus.Miss
            targetValue > 0 && achieved >= targetValue -> StatStatus.Ok
            targetValue > 0 -> StatStatus.Miss
            else -> StatStatus.Over
        }
    val progress = if (targetValue > 0) (achieved.toFloat() / targetValue).coerceIn(0f, 1f) else 1f
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatGlyph(label = target.glyph, color = target.color)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.characteristic.label(LocalLang.current),
                    style = WTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tr(if (exact) Tr.TAG_EXACT else Tr.TAG_MAXIMIZE),
                    style = WTypography.labelSmall
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = achieved.formatCompact(),
                    style =
                        WTypography.bodyMedium.copy(
                            fontFamily = WType.mono,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status == StatStatus.Miss) WColor.warning else WColor.text
                        )
                )
                if (targetValue > 0) {
                    Text(text = " / ", style = WTypography.bodySmall.copy(color = WColor.faint))
                    Text(
                        text = targetValue.formatCompact(),
                        style = WTypography.bodySmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
            }
            Text(
                text = status.icon,
                style =
                    WTypography.bodyMedium.copy(
                        color = status.color,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    ),
                modifier = Modifier.width(18.dp)
            )
        }
        if (targetValue > 0) {
            Meter(
                fill = progress,
                color = status.color,
                modifier = Modifier.padding(top = 8.dp).height(3.dp)
            )
        }
    }
}

@Composable
private fun SkillTree(skills: CharacterSkills) {
    ResultCard(
        title = tr(Tr.SKILL_ALLOCATION),
        trailing = tr(Tr.BRANCHES_COUNT)
    ) {
        skillBranches(skills).forEachIndexed { index, branch ->
            if (index > 0) Hairline()
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(branch.color)
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = tr(branch.labelKey),
                        style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${branch.points}/${branch.max}",
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 17.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    branch.lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = line.name,
                                style =
                                    WTypography.bodySmall.copy(
                                        color = if (line.points > 0) WColor.text else WColor.muted,
                                        lineHeight = 15.sp
                                    ),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${line.points}/${line.maxText}",
                                style =
                                    WTypography.bodySmall.copy(
                                        fontFamily = WType.mono,
                                        color = if (line.points > 0) WColor.muted else WColor.faint
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    ui: UiState,
    onOpenZenith: () -> Unit,
    onCopyZenith: () -> Unit,
) {
    ResultCard {
        if (ui.error != null) {
            ErrorBanner(error = ui.error)
            Spacer(modifier = Modifier.height(10.dp))
        }
        ActionButton(
            text = if (ui.zenith == ZenithState.Loading) tr(Tr.OPENING) else tr(Tr.OPEN_IN_ZENITH),
            color = WColor.accent2,
            contentColor = Color(0xFF072824),
            enabled = ui.phase == Phase.Done && ui.zenith != ZenithState.Loading,
            onClick = onOpenZenith
        )
        Spacer(modifier = Modifier.height(9.dp))
        ActionButton(
            text = tr(Tr.COPY_BUILD_LINK),
            color = Color.Transparent,
            contentColor = WColor.text,
            enabled = ui.phase == Phase.Done && ui.zenith != ZenithState.Loading,
            borderColor = WColor.border,
            onClick = onCopyZenith
        )
        ui.zenithUrl?.let { link ->
            Text(
                text = link,
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.accent2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        ui.toast?.let { toast ->
            Text(
                text = toast,
                style = WTypography.labelSmall.copy(color = WColor.success),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    ResultCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tr(Tr.NO_BUILD_YET),
                style = WTypography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = tr(Tr.NO_BUILD_HINT),
                style =
                    WTypography.bodyMedium.copy(
                        color = WColor.muted,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    borderColor: Color = color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(RoundedCornerShape(10.dp))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style =
                WTypography.labelLarge.copy(
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
        )
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(WColor.danger.copy(alpha = 0.1f))
                .border(1.dp, WColor.danger.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "!", style = WTypography.bodyMedium.copy(color = WColor.danger))
        Text(
            text = error,
            style = WTypography.bodySmall.copy(color = WColor.text),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ResultCard(
    title: String? = null,
    trailing: String? = null,
    content: @Composable () -> Unit,
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
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = WTypography.labelMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f).height(1.dp).background(WColor.hairline))
                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = trailing, style = WTypography.labelSmall.copy(fontFamily = WType.mono))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
private fun StatGlyph(
    label: String,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(WColor.raised)
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style =
                WTypography.labelSmall.copy(
                    color = color,
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                )
        )
    }
}

@Composable
private fun Meter(
    fill: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(WColor.raised)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fill.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
        )
    }
}

@Composable
private fun Hairline() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
}

private enum class StatStatus(
    val icon: String,
    val color: Color,
) {
    Ok("✓", WColor.success),
    Miss("▾", WColor.warning),
    Over("↑", WColor.accent2),
}

private data class SkillBranch(
    val labelKey: Tr,
    val color: Color,
    val points: Int,
    val max: Int,
    val lines: List<SkillLine>,
)

private data class SkillLine(
    val name: String,
    val points: Int,
    val maxText: String,
)

private fun skillBranches(skills: CharacterSkills): List<SkillBranch> =
    listOf(
        branch(Tr.BRANCH_INTELLIGENCE, WColor.earth, skills.intelligence),
        branch(Tr.BRANCH_STRENGTH, WColor.fire, skills.strength),
        branch(Tr.BRANCH_AGILITY, WColor.accent2, skills.agility),
        branch(Tr.BRANCH_LUCK, Color(0xFFE8C24A), skills.luck),
        branch(Tr.BRANCH_MAJOR, WColor.accent, skills.major)
    )

private fun branch(
    labelKey: Tr,
    color: Color,
    assignable: Assignable<*>,
): SkillBranch =
    SkillBranch(
        labelKey = labelKey,
        color = color,
        points = assignable.pointsAssigned(),
        max = assignable.maxPointsToAssign,
        lines = assignable.getCharacteristics().map { it.toSkillLine() }
    )

private fun SkillCharacteristic.toSkillLine(): SkillLine =
    SkillLine(
        name = name,
        points = pointsAssigned,
        maxText = if (maxPointsAssignable == Int.MAX_VALUE) "∞" else maxPointsAssignable.toString()
    )

private fun Int.formatCompact(): String =
    if (this >= 1000) {
        NumberFormat.getIntegerInstance(Locale.US).format(this)
    } else {
        toString()
    }
