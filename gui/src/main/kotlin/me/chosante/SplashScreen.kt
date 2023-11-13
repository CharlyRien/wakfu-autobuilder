package me.chosante

import atlantafx.base.util.Animations
import javafx.geometry.Pos
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.javafx.JavaFx

object SplashScreen : StackPane(), CoroutineScope {
    private const val SPLASH_IMAGE = "logo.png"
    private val splashImage = ImageView(Image(SPLASH_IMAGE, 512.0, 512.0, true, false, false))

    init {
        alignment = Pos.CENTER
        children += splashImage
    }

    suspend fun animateSplashScreen() {
        val animations = listOf(
            Animations.pulse(splashImage, 1.15),
            Animations.pulse(splashImage, 1.35)
        )

        for (animation in animations) {
            suspendCoroutine { continuation ->
                with(animation) {
                    playFromStart()
                    setOnFinished { continuation.resume(Unit) }
                }
            }
            delay(300)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
