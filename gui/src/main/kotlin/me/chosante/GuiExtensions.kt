package me.chosante

import javafx.animation.Animation
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun alert(
    alertType: AlertType,
    headerText: String = "",
    contentText: String = "",
) {
    Alert(alertType)
        .apply {
            this.headerText = headerText
            this.contentText = contentText
        }.showAndWait()
}

suspend fun Animation.awaitPlay() =
    suspendCoroutine { cont ->
        playFromStart()
        setOnFinished { cont.resume(Unit) }
    }
