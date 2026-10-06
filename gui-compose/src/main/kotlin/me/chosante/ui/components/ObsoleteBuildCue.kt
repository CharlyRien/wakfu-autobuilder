package me.chosante.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.history.Obsolescence
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/**
 * The text of an obsolete build's explanation: one paragraph per reason ([Obsolescence.reasons]) and, when the build's score
 * moved under the current rules, the score it was saved with ([storedScore]).
 */
@Composable
internal fun obsoleteExplanation(
    obsolescence: Obsolescence,
    storedScore: String? = null,
): String {
    val lang = LocalLang.current
    return (obsolescence.reasons(lang) + listOfNotNull(storedScore?.let { Tr.OBSOLETE_STORED_SCORE.value(lang).format(it) }))
        .joinToString("\n\n")
}

/**
 * The "Obsolete" pill of a saved build that a new search may improve (game data updated, or engine improved, since it was
 * saved — see [Obsolescence]). It states a fact and blocks nothing; hovering it explains why (both reasons when both apply)
 * and, when the score moved, the one the build was saved with. Shown on the My Builds cards and in the compare view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ObsoleteBadge(
    obsolescence: Obsolescence,
    modifier: Modifier = Modifier,
    storedScore: String? = null,
) {
    val explanation = obsoleteExplanation(obsolescence, storedScore)
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
            Text(text = "↻", style = WTypography.labelSmall.copy(color = WColor.warning, fontWeight = FontWeight.SemiBold))
            Text(text = tr(Tr.OBSOLETE_BADGE), style = WTypography.labelSmall.copy(color = WColor.warning), maxLines = 1)
        }
    }
}

/** The "Re-run the search" link: restores the build's request and starts the search. */
@Composable
internal fun RerunSearchLink(
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = tr(Tr.ACTION_RERUN_SEARCH),
        style = WTypography.labelSmall.copy(color = WColor.accent, textDecoration = TextDecoration.Underline),
        modifier = modifier.clickable(onClick = onRerun)
    )
}

/**
 * The stats column's note on a loaded obsolete build: the reasons spelled out (nothing to hover there) and the "Re-run the
 * search" link. A slim card under the headline; the build stays fully usable.
 */
@Composable
internal fun ObsoleteCue(
    obsolescence: Obsolescence,
    onRerun: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Text(text = "↻", style = style.copy(color = WColor.warning, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            obsolescence.reasons(LocalLang.current).forEach { reason ->
                Text(text = reason, style = style.copy(color = WColor.muted, lineHeight = 15.sp))
            }
            RerunSearchLink(onRerun = onRerun)
        }
    }
}

/**
 * A stored "optimal proven" made by an older engine version (reason B of [Obsolescence]): shown dimmed rather than as a success,
 * with a tooltip (also its description) saying the proof belongs to that older version.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OlderEngineProof(
    text: String,
    style: TextStyle,
) {
    val explanation = tr(Tr.PROVEN_BY_OLDER_ENGINE)
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
        Text(text = text, style = style.copy(color = WColor.faint), modifier = Modifier.semantics { contentDescription = explanation })
    }
}
