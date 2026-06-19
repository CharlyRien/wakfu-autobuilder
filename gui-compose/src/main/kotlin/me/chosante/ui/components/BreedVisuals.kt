package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.chosante.common.CharacterClass

/**
 * Resolves the class ("breed") artwork baked under `assets/breeds/` (extracted from the local client's
 * `gui.jar` by `generateAssets`, keyed by [CharacterClass.breedId]). Three kinds:
 *  - [iconPath] — a small class badge (`breedsIcons`),
 *  - [illustrationPath] — the full character "T-pose" art (`breedsIllusrations`, male variant),
 *  - [backgroundPath] — a class-themed background (`breedsBackgrounds`).
 *
 * Returns `null` when the asset is missing (e.g. [CharacterClass.UNKNOWN], or the GUI was built without
 * running `generateAssets`), so every caller degrades gracefully instead of crashing.
 */
internal object BreedAssets {
    fun iconPath(clazz: CharacterClass): String? = existing("assets/breeds/icon/${clazz.breedId}.png")

    fun illustrationPath(clazz: CharacterClass): String? = existing("assets/breeds/illustration/${clazz.breedId}.png")

    fun backgroundPath(clazz: CharacterClass): String? = existing("assets/breeds/background/${clazz.breedId}.png")

    /** Every breed asset worth pre-decoding off the UI thread (paired with [IconPreloader]). */
    fun warmUpPaths(): List<String> =
        CharacterClass.entries
            .filter { it != CharacterClass.UNKNOWN }
            .flatMap { listOfNotNull(iconPath(it), illustrationPath(it), backgroundPath(it)) }

    private fun existing(path: String): String? = if (Thread.currentThread().contextClassLoader.getResource(path) != null) path else null
}

/** Small class badge — the icon-first class marker. Falls back to an empty box when art is missing. */
@Composable
internal fun BreedIcon(
    clazz: CharacterClass,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val bitmap = BreedAssets.iconPath(clazz)?.let { rememberClasspathBitmap(it) }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = clazz.name, contentScale = ContentScale.Fit, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size))
    }
}

/** The class "T-pose" character illustration. Renders nothing when art is missing. */
@Composable
internal fun BreedIllustration(
    clazz: CharacterClass,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    val bitmap = BreedAssets.illustrationPath(clazz)?.let { rememberClasspathBitmap(it) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = clazz.name,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        modifier = modifier
    )
}

/** The class-themed background, dimmed by [alpha] so foreground content stays legible. Nothing if missing. */
@Composable
internal fun BreedBackground(
    clazz: CharacterClass,
    modifier: Modifier = Modifier,
    alpha: Float = 0.18f,
) {
    val bitmap = BreedAssets.backgroundPath(clazz)?.let { rememberClasspathBitmap(it) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.alpha(alpha)
    )
}
