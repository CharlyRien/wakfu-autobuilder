package me.chosante

import atlantafx.base.util.Animations
import javafx.animation.FadeTransition
import javafx.geometry.Pos
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.util.Duration
import kotlin.coroutines.CoroutineContext
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
            animation.awaitPlay()
            delay(300)
        }

        FadeTransition(Duration(1000.0), SplashScreen).apply {
            fromValue = 1.0
            toValue = 0.0
        }.awaitPlay()
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
