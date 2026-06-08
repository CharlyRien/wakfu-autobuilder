package me.chosante.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

@Composable
fun ZoneHeader(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(WColor.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WColor.accent)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(text = title, style = WTypography.headlineMedium)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = hint, style = WTypography.labelSmall)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
    }
}
