package me.chosante.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.StatGateViolation
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/**
 * One line per broken item stat gate: "Cartes And would be inactive in game: Range ≤ 3, this build has 4". The search never
 * returns such a build (AGENTS.md §4 "Item equip conditions"); a save made before the gates were enforced may.
 */
internal fun statGateWarnings(
    violations: List<StatGateViolation>,
    lang: Lang,
): List<String> = violations.map { Tr.STAT_GATE_INACTIVE.value(lang).format(it.item.name.localized(lang), statGateText(it.gate, lang), it.actual) }

/**
 * The stat gates [build] breaks for a [clazz] ([WakfuBestBuildFinderAlgorithm.statGateViolations], the catalog's criteria joined by
 * item id — a saved build carries none). Empty for no build, and for a build the catalog cannot read.
 */
@Composable
internal fun rememberStatGateViolations(
    build: BuildCombination?,
    clazz: CharacterClass,
): List<StatGateViolation> =
    remember(build, clazz) {
        if (build == null) emptyList() else runCatching { WakfuBestBuildFinderAlgorithm.statGateViolations(build, clazz) }.getOrDefault(emptyList())
    }

/**
 * The stats column's warning on a loaded build that wears an item the game would show inactive: each broken gate spelled out
 * and why a new search would not do it. Styled like the obsolete-build note; nothing is blocked.
 */
@Composable
internal fun StatGateCue(
    violations: List<StatGateViolation>,
    modifier: Modifier = Modifier,
) {
    if (violations.isEmpty()) return
    val lang = LocalLang.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(WColor.warning.copy(alpha = 0.08f))
                .border(1.dp, WColor.warning.copy(alpha = 0.35f), shape)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        val style = WTypography.labelMedium
        Text(text = "⚠", style = style.copy(color = WColor.warning, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            statGateWarnings(violations, lang).forEach { line ->
                Text(text = line, style = style.copy(color = WColor.text, lineHeight = 15.sp))
            }
            Text(text = tr(Tr.STAT_GATE_CUE_HINT), style = style.copy(color = WColor.muted, lineHeight = 15.sp))
        }
    }
}

/** The "Inactive item" pill of a saved build that breaks an item stat gate (My Builds cards); hovering it lists the gates. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatGateBadge(
    violations: List<StatGateViolation>,
    modifier: Modifier = Modifier,
) {
    if (violations.isEmpty()) return
    val explanation = statGateWarnings(violations, LocalLang.current).joinToString("\n")
    val shape = RoundedCornerShape(999.dp)
    TooltipArea(
        delayMillis = 250,
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, RoundedCornerShape(7.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(text = explanation, style = WTypography.labelSmall.copy(color = WColor.text))
            }
        }
    ) {
        Row(
            modifier =
                modifier
                    .semantics { contentDescription = explanation }
                    .clip(shape)
                    .background(WColor.warning.copy(alpha = 0.10f))
                    .border(1.dp, WColor.warning.copy(alpha = 0.45f), shape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚠", style = WTypography.labelSmall.copy(color = WColor.warning, fontWeight = FontWeight.SemiBold))
            Text(text = tr(Tr.STAT_GATE_BADGE), style = WTypography.labelSmall.copy(color = WColor.warning), maxLines = 1)
        }
    }
}
