package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.common.Characteristic
import me.chosante.common.skills.IntelligenceCharacteristic
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/*
 * Maps the domain stats and skill-tree lines onto the HUD artwork shipped under `assets/icons`
 * (sourced from https://github.com/Vertylo/wakassets). Every mapping is a `when` over the domain
 * enum so a new Characteristic fails to compile until it is given an icon (or an explicit `null`).
 *
 * Resolution is always icon-first with a graceful fallback: stats with no representative icon keep
 * their text glyph, and skill lines with no icon keep their text name — so a missing or unmapped
 * asset never renders as a blank.
 */

/** Classpath path of the HUD icon for [this] stat, or `null` when no icon represents it. */
internal fun Characteristic.iconResourcePath(): String? = iconFileName()?.let { "assets/icons/$it.png" }

private fun Characteristic.iconFileName(): String? =
    when (this) {
        Characteristic.ACTION_POINT, Characteristic.MAX_ACTION_POINT -> "ap"
        Characteristic.MOVEMENT_POINT, Characteristic.MAX_MOVEMENT_POINT -> "mp"
        Characteristic.WAKFU_POINT, Characteristic.MAX_WAKFU_POINTS -> "wp"
        Characteristic.RANGE -> "range"
        Characteristic.HP -> "hp"
        Characteristic.CRITICAL_HIT -> "critical"
        Characteristic.CONTROL -> "control"
        Characteristic.WISDOM -> "wisdom"
        Characteristic.PROSPECTION -> "prosp"
        Characteristic.INITIATIVE -> "init"
        Characteristic.WILLPOWER -> "will"
        Characteristic.DODGE -> "dodge"
        Characteristic.LOCK -> "lock"
        Characteristic.BLOCK_PERCENTAGE -> "block"
        Characteristic.GIVEN_ARMOR_PERCENTAGE -> "armor_given"
        Characteristic.RECEIVED_ARMOR_PERCENTAGE -> "armor_received"
        // Masteries
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT,
        -> "di"
        Characteristic.MASTERY_ELEMENTARY_WATER -> "water"
        Characteristic.MASTERY_ELEMENTARY_FIRE -> "fire"
        Characteristic.MASTERY_ELEMENTARY_EARTH -> "earth"
        Characteristic.MASTERY_ELEMENTARY_WIND -> "air"
        Characteristic.MASTERY_DISTANCE -> "mastery_dist"
        Characteristic.MASTERY_MELEE -> "mastery_mel"
        Characteristic.MASTERY_CRITICAL -> "mastery_crit"
        Characteristic.MASTERY_BACK -> "mastery_back"
        Characteristic.MASTERY_BERSERK -> "mastery_berserk"
        Characteristic.MASTERY_HEALING -> "mastery_heal"
        // Resistances
        Characteristic.RESISTANCE_ELEMENTARY_WATER -> "water_res"
        Characteristic.RESISTANCE_ELEMENTARY_FIRE -> "fire_res"
        Characteristic.RESISTANCE_ELEMENTARY_EARTH -> "earth_res"
        Characteristic.RESISTANCE_ELEMENTARY_WIND -> "air_res"
        Characteristic.RESISTANCE_CRITICAL -> "res_crit"
        Characteristic.RESISTANCE_BACK -> "res_back"
        // No representative single icon: keep the text glyph.
        Characteristic.RESISTANCE_ELEMENTARY,
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT,
        Characteristic.HERBALIST_HARVEST_QUANTITY_PERCENTAGE,
        Characteristic.LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE,
        Characteristic.TRAPPER_HARVEST_QUANTITY_PERCENTAGE,
        Characteristic.MINER_HARVEST_QUANTITY_PERCENTAGE,
        Characteristic.FARMER_HARVEST_QUANTITY_PERCENTAGE,
        Characteristic.FISHERMAN_HARVEST_QUANTITY_PERCENTAGE,
        -> null
    }

/**
 * Icon for a skill-tree line: its [characteristic]'s icon, or a per-line special case for the three
 * Intelligence lines that have no domain characteristic (barrier / heals received / armor).
 */
internal fun SkillCharacteristic.iconResourcePath(): String? =
    characteristic?.iconResourcePath() ?: when (this) {
        is IntelligenceCharacteristic.Shield -> "assets/icons/shield.png"
        is IntelligenceCharacteristic.HealReceivedPercentage -> "assets/icons/heal.png"
        is IntelligenceCharacteristic.HpPercentageAsArmor -> "assets/icons/armor.png"
        else -> null
    }

/**
 * A standalone HUD icon for [characteristic], for plain stat lines (no surrounding chip/glyph). Falls
 * back to a small muted dot when the stat has no mapped icon, so every line still gets a marker.
 */
@Composable
internal fun CharacteristicIcon(
    characteristic: Characteristic,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    fallbackColor: Color = WColor.muted,
) {
    val bitmap = characteristic.iconResourcePath()?.let { rememberClasspathBitmap(it) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier =
                modifier
                    .size(size * 0.5f)
                    .clip(CircleShape)
                    .background(fallbackColor.copy(alpha = 0.6f))
        )
    }
}

/** Every stat/skill icon worth pre-decoding off the UI thread (paired with [warmUpPaths]). */
internal fun statIconWarmUpPaths(): List<String> =
    (
        Characteristic.entries.mapNotNull { it.iconResourcePath() } +
            listOf("assets/icons/shield.png", "assets/icons/heal.png", "assets/icons/armor.png")
    ).distinct()

/**
 * Renders [characteristic]'s icon, falling back to its text [glyph] (in [color]) when no icon is
 * mapped or the asset is missing. Sized to drop into the existing colored chips/tiles.
 */
@Composable
internal fun StatGlyphIcon(
    characteristic: Characteristic,
    glyph: String,
    color: Color,
    iconSize: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = characteristic.iconResourcePath()?.let { rememberClasspathBitmap(it) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(iconSize)
        )
    } else {
        Text(
            text = glyph,
            style =
                WTypography.labelSmall.copy(
                    color = color,
                    fontFamily = WType.mono,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                ),
            modifier = modifier
        )
    }
}
