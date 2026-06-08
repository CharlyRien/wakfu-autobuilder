package me.chosante.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import me.chosante.ui.components.loadClasspathBitmap
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.shell.AppShell
import me.chosante.ui.shell.LoadingScreen
import me.chosante.ui.state.BuildSearchModel
import me.chosante.ui.testing.ScreenshotCapture
import me.chosante.ui.theme.WTheme
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
        Window(
            onCloseRequest = { exitApplication() },
            title = "Wakfu Autobuilder",
            icon = appIcon,
            state =
                WindowState(
                    position =
                        androidx.compose.ui.window.WindowPosition
                            .Aligned(Alignment.Center),
                    size = DpSize(1440.dp, 900.dp)
                )
        ) {
            ScreenshotCapture(
                path = screenshotPath,
                onFinished = { exitApplication() }
            )
            App()
        }
    }
}

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
fun App() {
    WTheme {
        val scope = rememberCoroutineScope()
        val model = remember { BuildSearchModel(scope) }
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
    App()
}
