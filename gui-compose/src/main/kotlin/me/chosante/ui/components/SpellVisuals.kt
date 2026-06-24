package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.common.I18nText
import me.chosante.common.SpellElement
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor

/**
 * Shared spell-presentation helpers used by both the "Class spells" tab and the build-comparison screen,
 * so the element colours, localized names and icon tile stay identical in both places. A spell's icon is
 * the same `assets/spells/<id>.png` sprite the passive icon uses ([PassiveIcon]).
 */
internal fun SpellElement?.elementColor(): Color =
    when (this) {
        SpellElement.FIRE -> WColor.fire
        SpellElement.WATER -> WColor.water
        SpellElement.EARTH -> WColor.earth
        SpellElement.AIR -> WColor.air
        null -> WColor.muted
    }

@Composable
internal fun SpellElement.elementLabel(): String =
    tr(
        when (this) {
            SpellElement.FIRE -> Tr.ELEMENT_FIRE
            SpellElement.WATER -> Tr.ELEMENT_WATER
            SpellElement.EARTH -> Tr.ELEMENT_EARTH
            SpellElement.AIR -> Tr.ELEMENT_AIR
        }
    )

/** The text in the active [lang], falling back to the other language when the preferred one is blank. */
internal fun I18nText.localized(lang: Lang): String = (if (lang == Lang.FR) fr else en).ifBlank { if (lang == Lang.FR) en else fr }

/** A spell's icon sprite in an element-tinted rounded tile. Renders an empty tile when the asset is absent. */
@Composable
internal fun SpellIcon(
    iconId: Int?,
    element: SpellElement?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val color = element.elementColor()
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(9.dp))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = iconId?.let { rememberClasspathBitmap("assets/spells/$it.png") }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size).padding(4.dp)
            )
        }
    }
}
