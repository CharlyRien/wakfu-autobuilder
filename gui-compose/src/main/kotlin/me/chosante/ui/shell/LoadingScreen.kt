package me.chosante.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography
import kotlin.math.roundToInt

/** Classpath path of the translucent app wordmark used on the loading screen. */
private const val WORDMARK_PATH = "assets/branding/wordmark.png"

/**
 * Full-window startup screen shown while OR-Tools' native engine is loading. It is intentionally
 * cheap to render so the window appears instantly; the heavy main UI ([AppShell]) only mounts once
 * the engine is warm, avoiding the first-frame stall caused by loading the native library
 * concurrently with Compose's (already heavy) initial render.
 */
@Composable
fun LoadingScreen(
    progress: Float,
    etaSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(WColor.bg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val wordmark = rememberClasspathBitmap(WORDMARK_PATH)
            if (wordmark != null) {
                Image(
                    bitmap = wordmark,
                    contentDescription = "Wakfu Auto-Builder",
                    modifier = Modifier.width(360.dp)
                )
            } else {
                Text(text = "Wakfu Auto-Builder", style = WTypography.headlineLarge)
            }
            Spacer(modifier = Modifier.height(34.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier =
                    Modifier
                        .width(220.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp)),
                color = WColor.accent,
                trackColor = WColor.hairline
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "${tr(Tr.PRELOAD_WARMUP)}  ${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                style = WTypography.labelMedium.copy(color = WColor.muted)
            )
            if (etaSeconds != null && etaSeconds > 0) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "≈ ${etaSeconds}s",
                    style = WTypography.labelSmall.copy(color = WColor.faint)
                )
            }
        }
    }
}
