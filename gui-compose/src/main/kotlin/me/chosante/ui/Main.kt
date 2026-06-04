package me.chosante.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import me.chosante.ui.shell.AppShell
import me.chosante.ui.testing.ScreenshotCapture
import me.chosante.ui.testing.ScreenshotPathProperty
import me.chosante.ui.theme.WTheme

fun main() = application {
    val screenshotPath = System.getProperty(ScreenshotPathProperty) ?: System.getenv("WAKFU_COMPOSE_SCREENSHOT")
    Window(
        onCloseRequest = { exitApplication() },
        title = "Wakfu Autobuilder",
        state = WindowState(
            position = androidx.compose.ui.window.WindowPosition.Aligned(Alignment.Center),
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

@Composable
fun App() {
    WTheme {
        AppShell()
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}
