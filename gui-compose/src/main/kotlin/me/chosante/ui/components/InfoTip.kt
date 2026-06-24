package me.chosante.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/**
 * A small circular "i" affordance that reveals [text] in a styled tooltip on hover — the same
 * [TooltipArea] mechanism the passives result card uses (so the look and reveal delay match). Reused
 * wherever a number needs a one-line "what does this mean" explainer, e.g. the spell "expected hit".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoTip(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
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
                Text(text = text, style = WTypography.labelSmall.copy(color = WColor.text))
            }
        }
    ) {
        Box(
            modifier =
                modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.dp, WColor.faint, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "i",
                style =
                    WTypography.labelSmall.copy(
                        color = WColor.muted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
            )
        }
    }
}
