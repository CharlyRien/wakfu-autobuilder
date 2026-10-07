package me.chosante.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WTypography

/** Combat subs occupy a slot when forced, but none of their effects contribute to the search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SublimationCombatBadge(sub: Sublimation) {
    if (sub.kind != SublimationKind.COMBAT_CONDITIONAL) return
    val explanation = tr(Tr.SUBLIMATION_COMBAT_NOT_COUNTED_INFO)
    val shape = RoundedCornerShape(6.dp)
    TooltipArea(
        delayMillis = 250,
        tooltip = {
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(shape)
                        .background(WColor.raised)
                        .border(1.dp, WColor.border, shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(text = explanation, style = WTypography.labelSmall.copy(color = WColor.text))
            }
        }
    ) {
        Text(
            text = tr(Tr.SUBLIMATION_COMBAT_NOT_COUNTED),
            style = WTypography.labelSmall.copy(color = WColor.warning),
            modifier =
                Modifier
                    .semantics { contentDescription = explanation }
                    .clip(shape)
                    .background(WColor.warning.copy(alpha = 0.10f))
                    .border(1.dp, WColor.warning.copy(alpha = 0.45f), shape)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
