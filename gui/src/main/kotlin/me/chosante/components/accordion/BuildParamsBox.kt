package me.chosante.components.accordion

import atlantafx.base.controls.Tile
import atlantafx.base.controls.ToggleSwitch
import javafx.collections.FXCollections
import javafx.scene.control.Alert
import javafx.scene.control.ComboBox
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import me.chosante.alert
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.CharacterClass
import me.chosante.common.Rarity

class BuildParamsBox : VBox(5.0) {
    private val classComboBox = ComboBox(
        FXCollections.observableArrayList(
            CharacterClass.entries.filter { it != CharacterClass.UNKNOWN }.map { it.name }
        )
    )

    private val classTile = Tile(
        "Class",
        "Class to use for the build"
    ).apply {
        action = classComboBox.apply {
            selectionModel.selectFirst()
        }
        actionHandler = Runnable { }
    }

    private val maxRarityComboBox = ComboBox(
        FXCollections.observableArrayList(
            Rarity.entries.map { it.name }
        )
    )
    private val maxRarityTile = Tile(
        "Max Rarity",
        "No items will be used for the build above the selected rarity"
    ).apply {
        action = maxRarityComboBox.apply {
            selectionModel.selectLast()
        }
        actionHandler = Runnable { }
    }

    private val stopWhenBuildMatchToggleSwitch = ToggleSwitch()
    private val stopWhenBuildMatchTile = Tile(
        "Stop searching when build match 100%",
        null
    ).apply {
        action = stopWhenBuildMatchToggleSwitch.apply {
            isSelected = false
        }
        actionHandler = Runnable { }
    }

    private val minLevelSpinner = Spinner<Int>(1, 230, 1)
    private val minLevelTile = Tile(
        "Minimum Level",
        "The minimum level used for the build. Items under this level will be excluded"
    ).apply {
        action = minLevelSpinner.apply {
            isEditable = true
        }
        actionHandler = Runnable { }
    }

    private val levelSpinner = Spinner<Int>(1, 230, 20)
    private val levelTile = Tile(
        "Level",
        "The level used for the build. Items above this level will be excluded"
    ).apply {
        action = levelSpinner.apply {
            isEditable = true
        }
        actionHandler = Runnable { }
    }

    private val durationTextField = TextField("20")
    private val durationTile = Tile(
        "Search duration",
        "Search duration used for the build in seconds"
    ).apply {
        action = durationTextField.apply {
            isEditable = true
            textProperty().addListener { _, _, newValue ->
                if (newValue.toIntOrNull() == null) {
                    alert(Alert.AlertType.ERROR, headerText = "Input not valid", contentText = "The value must be a number")
                    cancelEdit()
                }
            }
        }
        actionHandler = Runnable { }
    }

    init {
        children += classTile
        children += durationTile
        children += minLevelTile
        children += levelTile
        children += stopWhenBuildMatchTile
        children += maxRarityTile
        prefWidth = 300.0
        prefHeight = 300.0
    }

    val character
        get() = Character(CharacterClass.fromValue(classComboBox.value), levelSpinner.value, minLevelSpinner.value)

    val searchDuration
        get() = durationTextField.textProperty().value.toIntOrNull()

    val stopWhenBuildMatch: Boolean
        get() = stopWhenBuildMatchToggleSwitch.selectedProperty().value

    val maxRarity
        get() = Rarity.valueOf(maxRarityComboBox.value)
}
