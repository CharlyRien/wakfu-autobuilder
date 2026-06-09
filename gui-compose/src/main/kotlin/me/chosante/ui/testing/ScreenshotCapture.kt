package me.chosante.ui.testing

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Headless screenshot smoke-test: when [path] is set (via `WAKFU_COMPOSE_SCREENSHOT` / the
 * `wakfu.compose.screenshot` property), render the real app, wait until [ready] reports the state
 * worth capturing (e.g. a populated build), grab the window content to a PNG and exit.
 *
 * [ready] is polled through snapshot state so the capture fires as soon as the search lands a build,
 * but is bounded by a timeout so a failed/empty search still produces a screenshot of whatever is on
 * screen instead of hanging the process forever.
 */
@Composable
fun ScreenshotCapture(
    path: String?,
    ready: () -> Boolean = { true },
    onFinished: () -> Unit,
) {
    if (path == null) {
        return
    }

    LaunchedEffect(path) {
        // Wait for the capture-worthy state (build rendered), but never block the smoke test forever.
        withTimeoutOrNull(120_000) {
            snapshotFlow { ready() }.first { it }
        }
        // Let the final frame (icon decode, land animation, layout) settle before grabbing pixels.
        delay(1_500)
        withContext(Dispatchers.Swing) {
            val frame = Frame.getFrames().first { it.title == "Wakfu Autobuilder" && it.isShowing }
            val content = (frame as JFrame).contentPane
            val point = content.locationOnScreen
            val size = content.size
            val capture = Rectangle(point.x, point.y, size.width, size.height)
            val image = Robot(frame.graphicsConfiguration.device).createScreenCapture(capture)
            ImageIO.write(image, "png", File(path))
        }
        onFinished()
    }
}

/**
 * Screenshot-only helper: when `WAKFU_COMPOSE_SCREENSHOT_STATS_BOTTOM` (or the
 * `wakfu.compose.screenshot.statsBottom` property) is set, scroll [scroll] to the bottom once
 * [enabled] (a build has landed) so the smoke test can capture the cards below the fold — notably the
 * skill-allocation tree at the end of the stats column. A no-op in normal runs.
 */
@Composable
fun ScreenshotAutoScrollToBottom(
    scroll: ScrollState,
    enabled: Boolean,
    key: String = "STATS_BOTTOM",
) {
    val active = System.getenv("WAKFU_COMPOSE_SCREENSHOT_$key") != null
    if (!active) {
        return
    }
    LaunchedEffect(enabled) {
        if (enabled) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            scroll.scrollTo(scroll.maxValue)
        }
    }
}

/**
 * Screenshot-only override for an initial [Dp] value (e.g. a resizable column's starting width), read
 * from `WAKFU_COMPOSE_SCREENSHOT_<key>` / `wakfu.compose.screenshot.<key>`. Returns [default] when
 * unset, so it has no effect outside the smoke test. Lets a capture render the columns at a chosen
 * width to show the resize result deterministically, without driving a live drag gesture.
 */
fun screenshotInitialDp(
    key: String,
    default: Dp,
): Dp {
    val raw =
        System.getProperty("wakfu.compose.screenshot.$key")
            ?: System.getenv("WAKFU_COMPOSE_SCREENSHOT_${key.uppercase()}")
    return raw?.toFloatOrNull()?.dp ?: default
}
