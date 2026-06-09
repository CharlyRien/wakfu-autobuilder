package me.chosante.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import me.chosante.ui.components.loadClasspathBitmap
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.shell.AppShell
import me.chosante.ui.shell.LoadingScreen
import me.chosante.ui.state.BuildSearchModel
import me.chosante.ui.testing.ScreenshotCapture
import me.chosante.ui.theme.WTheme
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import javax.imageio.ImageIO

const val SCREENSHOT_PATH_PROPERTY = "wakfu.compose.screenshot"

/** Classpath path of the square app icon (used for the window/taskbar and the macOS dock). */
private const val APP_ICON_PATH = "assets/branding/app-icon.png"

fun main() {
    setDockIcon()
    application {
        val screenshotPath = System.getProperty(SCREENSHOT_PATH_PROPERTY) ?: System.getenv("WAKFU_COMPOSE_SCREENSHOT")
        val appIcon = remember { loadClasspathBitmap(APP_ICON_PATH)?.let(::BitmapPainter) }
        // Hoisted here (instead of inside App()) so the screenshot smoke test can observe the search
        // state and capture once a build has landed.
        val scope = rememberCoroutineScope()
        val model = remember { BuildSearchModel(scope) }
        Window(
            onCloseRequest = { exitApplication() },
            title = "Wakfu Autobuilder",
            icon = appIcon,
            state =
                WindowState(
                    // Open maximised so the app fills the screen from the first frame. This also avoids
                    // the macOS freeze that hit when the window was zoomed/full-screened *during* OR-Tools'
                    // native cold start: that resize transition has to keep rendering while every core is
                    // busy paying the one-time startup cost behind the loading screen, so it stalls and the
                    // app appears frozen. Opening already maximised means there is no zoom to trigger while
                    // warm-up runs. (Must pair with a resizable window — AWT can refuse to maximise a
                    // non-resizable frame on macOS, and Compose applies the placement only once.)
                    placement = WindowPlacement.Maximized,
                    // Un-maximised (restore) size/position, clamped so the title bar stays reachable.
                    position = WindowPosition.Aligned(Alignment.Center),
                    size = fittedWindowSize(DpSize(1440.dp, 900.dp))
                )
        ) {
            // Pull the window to the front as soon as it is shown. Two reasons:
            //  • Launched via `gradle run` (an un-bundled JVM) the window can open *behind* the
            //    launching app, hiding the loading screen so the warm-up progress isn't visible.
            //  • macOS pauses rendering for an occluded window, so raising a *maximised* window later
            //    forces the first full-surface render of the big window at that moment — which stalled
            //    when it coincided with the native warm-up. Rendering it in the foreground now, while
            //    the cheap loading screen is up, avoids that deferred heavy first paint entirely.
            // Best-effort: a window-ordering hint must never break startup, so failures are swallowed.
            LaunchedEffect(Unit) {
                runCatching {
                    window.toFront()
                    window.requestFocus()
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        desktop.requestForeground(true)
                    }
                }
            }
            ScreenshotCapture(
                path = screenshotPath,
                ready = { model.ui.build != null },
                onFinished = { exitApplication() }
            )
            App(model)
        }
    }
}

/**
 * Clamps the desired window size to the usable screen so the title bar and window controls always
 * land on-screen. Compose sizes the window in [androidx.compose.ui.unit.Dp], which it multiplies by
 * the OS display scaling — so the hard-coded 1440×900 becomes e.g. ~2160×1350 px at 150 % scaling (a
 * common Windows-laptop default) and overflows a 1080p screen on every side, pushing the title bar
 * out of reach: the window then can't be dragged or maximised.
 * [GraphicsEnvironment.getMaximumWindowBounds] is reported in the same logical units as `Dp` (device
 * px ÷ scaling) and already excludes the taskbar, so clamping against it keeps the default fitting on
 * small/scaled displays while leaving roomy screens untouched. Best-effort: falls back to [desired]
 * if the screen can't be queried (e.g. headless CI).
 */
private fun fittedWindowSize(desired: DpSize): DpSize =
    try {
        val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        clampToUsable(desired, usable.width, usable.height)
    } catch (_: Exception) {
        desired
    }

/** Pure part of [fittedWindowSize]: clamp [desired] to [margin] of the usable screen (logical px). */
internal fun clampToUsable(
    desired: DpSize,
    usableWidthPx: Int,
    usableHeightPx: Int,
    margin: Float = 0.92f,
): DpSize =
    DpSize(
        width = minOf(desired.width.value, usableWidthPx * margin).dp,
        height = minOf(desired.height.value, usableHeightPx * margin).dp
    )

/**
 * Sets the macOS dock icon for the `:gui-compose:run` dev launch. Compose's `Window(icon = …)`
 * covers the Windows/Linux taskbar, but on macOS the dock icon of a JVM process is driven by the
 * AWT [Taskbar] API. Packaged builds get their icon from Conveyor instead, so this is best-effort
 * and silently no-ops where unsupported.
 */
private fun setDockIcon() {
    try {
        val url = object {}.javaClass.classLoader.getResource(APP_ICON_PATH) ?: return
        if (!Taskbar.isTaskbarSupported()) return
        val taskbar = Taskbar.getTaskbar()
        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            taskbar.iconImage = ImageIO.read(url)
        }
    } catch (_: Exception) {
        // Best-effort: a missing dock icon must never stop the app from launching.
    }
}

@Composable
fun App(model: BuildSearchModel) {
    WTheme {
        CompositionLocalProvider(LocalLang provides model.ui.lang) {
            // Sober crossfade from the loader to the main UI once warm-up is done — by this point
            // the native load is finished, so mounting AppShell during the fade is contention-free.
            Crossfade(
                targetState = model.isReady,
                animationSpec = tween(durationMillis = 320),
                label = "loader-to-app"
            ) { ready ->
                if (ready) {
                    AppShell(model = model)
                } else {
                    LoadingScreen(progress = model.warmupProgress, etaSeconds = model.warmupEtaSeconds)
                }
            }
        }
    }
}

@Preview
@Composable
fun AppPreview() {
    val scope = rememberCoroutineScope()
    App(remember { BuildSearchModel(scope) })
}
