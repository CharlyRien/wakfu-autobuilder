package me.chosante

import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType

fun alert(alertType: AlertType, headerText: String = "", contentText: String = "") {
    Alert(alertType).apply {
        this.headerText = headerText
        this.contentText = contentText
    }.showAndWait()
}
