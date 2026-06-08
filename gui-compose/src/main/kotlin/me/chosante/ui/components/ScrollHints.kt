package me.chosante.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/**
 * Top/bottom scroll affordances for a vertically scrollable column. Each chevron badge + fade only
 * shows while there is more content to reveal in that direction, so it doubles as a clear
 * "you can scroll" hint without cluttering the panel once fully scrolled. Purely visual: it adds no
 * pointer handling, so scroll gestures and clicks pass straight through to the content underneath.
 *
 * Call from inside the [Box] that also hosts the scrollable content. [edgeColor] should match the
 * panel background so the content appears to fade out under the edge.
 */
@Composable
fun BoxScope.VerticalScrollHints(
    scroll: ScrollState,
    edgeColor: Color = WColor.bg,
) {
    ScrollChevron(
        visible = scroll.value > 0,
        pointingUp = true,
        edgeColor = edgeColor,
        modifier = Modifier.align(Alignment.TopCenter)
    )
    ScrollChevron(
        visible = scroll.value < scroll.maxValue,
        pointingUp = false,
        edgeColor = edgeColor,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
}

@Composable
private fun ScrollChevron(
    visible: Boolean,
    pointingUp: Boolean,
    edgeColor: Color,
    modifier: Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                if (pointingUp) {
                                    listOf(edgeColor, Color.Transparent)
                                } else {
                                    listOf(Color.Transparent, edgeColor)
                                }
                        )
                    ),
            contentAlignment = if (pointingUp) Alignment.TopCenter else Alignment.BottomCenter
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(vertical = 5.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (pointingUp) "▲" else "▼",
                    style =
                        WTypography.labelMedium.copy(
                            color = WColor.muted,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp,
                            lineHeight = 11.sp
                        )
                )
            }
        }
    }
}
