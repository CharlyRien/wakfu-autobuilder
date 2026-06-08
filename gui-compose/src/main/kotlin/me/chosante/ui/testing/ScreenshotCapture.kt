package me.chosante.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame

@Composable
fun ScreenshotCapture(
    path: String?,
    onFinished: () -> Unit,
) {
    if (path == null) {
        return
    }

    LaunchedEffect(path) {
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
