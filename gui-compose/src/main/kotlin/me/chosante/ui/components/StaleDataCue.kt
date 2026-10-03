package me.chosante.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/**
 * The quiet note on a build that was computed with other game data than the app now ships — "Saved with game data 1.92.1.58 —
 * re-run the search to update". It states a fact and suggests the fix; it blocks nothing (the build stays fully usable), and it
 * is shown on the My Builds card of such a build ([boxed] = false, a line of small text) and in the stats column once it is
 * loaded ([boxed] = true, a slim card of its own).
 */
@Composable
internal fun StaleDataCue(
    version: String,
    modifier: Modifier = Modifier,
    boxed: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (boxed) {
                        Modifier
                            .clip(shape)
                            .background(WColor.warning.copy(alpha = 0.08f))
                            .border(1.dp, WColor.warning.copy(alpha = 0.35f), shape)
                            .padding(horizontal = 11.dp, vertical = 9.dp)
                    } else {
                        Modifier
                    }
                ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        val style = if (boxed) WTypography.labelMedium else WTypography.labelSmall
        Text(text = "↻", style = style.copy(color = WColor.warning, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp))
        Text(
            text = tr(Tr.SAVED_WITH_OTHER_DATA).format(version),
            style = style.copy(color = WColor.muted, lineHeight = 15.sp),
            modifier = Modifier.weight(1f)
        )
    }
}
