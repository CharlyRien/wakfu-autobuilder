package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.common.Equipment
import me.chosante.common.Rarity
import me.chosante.ui.theme.WColor

/** Classpath path of an item's icon, falling back to a placeholder when the asset is missing. */
internal fun Equipment.itemResourcePath(): String {
    val path = "assets/items/$guiId.png"
    val loader = Thread.currentThread().contextClassLoader
    return if (loader.getResource(path) != null) path else "assets/items/0000000.png"
}

private fun Rarity.iconResourcePath(): String = "assets/rarities/${name.lowercase()}.png"

/** The official Wakfu rarity badge (Common…Epic). */
@Composable
internal fun RarityIcon(
    rarity: Rarity,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Image(
        painter = painterResource(rarity.iconResourcePath()),
        contentDescription = rarity.name,
        modifier = modifier.size(size)
    )
}

/** An item's icon thumbnail in a rounded tile. */
@Composable
internal fun ItemThumbnail(
    equipment: Equipment,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(WColor.bg),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Image(
            painter = painterResource(equipment.itemResourcePath()),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).padding(3.dp)
        )
    }
}
