package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.chosante.common.Equipment
import me.chosante.common.Rarity
import me.chosante.ui.theme.WColor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Classpath path of an item's icon, falling back to a placeholder when the asset is missing. */
internal fun Equipment.itemResourcePath(): String {
    val path = "assets/items/$guiId.png"
    val loader = Thread.currentThread().contextClassLoader
    return if (loader.getResource(path) != null) path else "assets/items/0000000.png"
}

private fun Rarity.iconResourcePath(): String = "assets/rarities/${name.lowercase()}.png"

/**
 * Every icon path worth pre-decoding for [equipments]: the rarity badges, the stat/skill icons,
 * and one icon per item.
 */
internal fun warmUpPaths(equipments: List<Equipment>): List<String> =
    Rarity.entries.map { it.iconResourcePath() } +
        statIconWarmUpPaths() +
        equipments
            .asSequence()
            .map { "assets/items/${it.guiId}.png" }
            .distinct()
            .toList()

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()
private val decodeLock = Any()

/**
 * Decodes a classpath PNG into an [ImageBitmap], caching the result so each asset is read once.
 *
 * Unlike `painterResource`, this never re-reads the underlying jar entry on recomposition. All
 * reads are serialized through [decodeLock]: the JDK throws `ZipException: invalid LOC header`
 * when several threads read the same jar entry concurrently, and serializing the reads removes
 * that race entirely (cache hits, the common case, never take the lock). Any decode failure
 * falls back to `null` so the caller can show a placeholder instead of crashing the UI thread.
 */
internal fun loadClasspathBitmap(path: String): ImageBitmap? {
    bitmapCache[path]?.let { return it }
    synchronized(decodeLock) {
        bitmapCache[path]?.let { return it }
        val loader = Thread.currentThread().contextClassLoader
        return try {
            loader.getResourceAsStream(path)?.use { stream ->
                loadImageBitmap(stream).also { bitmapCache[path] = it }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Remembers the decoded [ImageBitmap] for [path], or `null` if it is missing/unreadable. */
@Composable
internal fun rememberClasspathBitmap(path: String): ImageBitmap? = remember(path) { loadClasspathBitmap(path) }

/**
 * Warms [bitmapCache] in the background so item icons are ready (and decoded once, off the UI
 * thread) by the time a build is shown. Progress is reported through [onProgress] — the caller is
 * responsible for marshalling those values onto the UI thread before touching Compose state.
 * Idempotent: only the first [warmUp] call does work.
 */
object IconPreloader {
    private val started = AtomicBoolean(false)

    /** @param onProgress invoked from a background thread with `(loaded, total)`. */
    fun warmUp(
        scope: CoroutineScope,
        paths: List<String>,
        onProgress: (loaded: Int, total: Int) -> Unit,
    ) {
        if (paths.isEmpty() || !started.compareAndSet(false, true)) {
            return
        }
        val total = paths.size
        scope.launch(Dispatchers.Default) {
            paths.forEachIndexed { index, path ->
                loadClasspathBitmap(path)
                // Throttle progress reports so we don't recompose once per icon.
                if ((index + 1) % 48 == 0 || index == paths.lastIndex) {
                    onProgress(index + 1, total)
                }
            }
        }
    }
}

/** The official Wakfu rarity badge (Common…Epic). */
@Composable
internal fun RarityIcon(
    rarity: Rarity,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val bitmap = rememberClasspathBitmap(rarity.iconResourcePath())
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = rarity.name,
            modifier = modifier.size(size)
        )
    } else {
        Box(modifier.size(size))
    }
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
        val bitmap = rememberClasspathBitmap(equipment.itemResourcePath())
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size).padding(3.dp)
            )
        }
    }
}
