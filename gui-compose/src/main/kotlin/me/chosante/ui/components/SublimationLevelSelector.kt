package me.chosante.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.chosante.common.Sublimation
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/** Only socketable family levels are offered; an absent saved level means the highest available one. */
@Composable
fun SublimationLevelSelector(
    sub: Sublimation,
    selectedLevel: Int?,
    tierLimit: Int?,
    onSelect: (Int) -> Unit,
    tagPrefix: String,
) {
    val levels = sub.reachableLevels(tierLimit)
    if (levels.isEmpty()) return
    val selected = selectedLevel?.takeIf { it in levels } ?: levels.last()
    var expanded by remember(sub.stateId, tierLimit) { mutableStateOf(false) }
    val label = tr(Tr.SUBLIMATION_LEVEL_SHORT)
    Box {
        Text(
            text = label.format(selected, sub.maxStackLevel) + if (levels.size > 1) " ▾" else "",
            style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.success),
            modifier =
                Modifier
                    .testTag("$tagPrefix-level-${sub.stateId}")
                    .clip(RoundedCornerShape(6.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(6.dp))
                    .clickable(enabled = levels.size > 1) { expanded = true }
                    .padding(horizontal = 7.dp, vertical = 4.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            levels.forEach { level ->
                DropdownMenuItem(
                    modifier = Modifier.testTag("$tagPrefix-level-${sub.stateId}-$level"),
                    text = { Text(label.format(level, sub.maxStackLevel), style = WTypography.labelSmall.copy(color = if (selected == level) WColor.success else WColor.text)) },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    }
                )
            }
        }
    }
}
