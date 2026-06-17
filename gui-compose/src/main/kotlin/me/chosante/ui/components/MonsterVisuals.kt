package me.chosante.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.resistancePercent
import me.chosante.common.Monster
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/** Accent colour for an element's resistance badge, matching the elemental-mastery palette. */
internal fun SpellElement.accent(): Color =
    when (this) {
        SpellElement.FIRE -> WColor.fire
        SpellElement.WATER -> WColor.water
        SpellElement.EARTH -> WColor.earth
        SpellElement.AIR -> WColor.air
    }

/** Two-letter element code, matching the stat-catalog glyphs (Fi/Wa/Ea/Ai). */
internal fun SpellElement.shortCode(): String =
    when (this) {
        SpellElement.FIRE -> "Fi"
        SpellElement.WATER -> "Wa"
        SpellElement.EARTH -> "Ea"
        SpellElement.AIR -> "Ai"
    }

/**
 * The boss's four elemental resistances as compact coloured badges (lower % = a weaker, better-to-hit
 * element). [highlight] emphasises a specific element (a forced choice); otherwise the weakest — the
 * one the objective would most likely auto-pick — is emphasised.
 */
@Composable
internal fun BossResistanceChips(
    boss: Monster,
    highlight: SpellElement? = null,
    modifier: Modifier = Modifier,
) {
    val resistances = SpellElement.entries.map { it to boss.resistancePercent(it) }
    val emphasised = highlight ?: resistances.minByOrNull { it.second }?.first
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        resistances.forEach { (element, percent) ->
            val on = element == emphasised
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(element.accent().copy(alpha = if (on) 0.22f else 0.1f))
                        .border(1.dp, element.accent().copy(alpha = if (on) 0.7f else 0.25f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = element.shortCode(),
                    style = WTypography.labelSmall.copy(color = element.accent(), fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "$percent%",
                    style = WTypography.labelSmall.copy(color = if (on) WColor.text else WColor.muted, fontFamily = WType.mono)
                )
            }
        }
    }
}
