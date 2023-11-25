package me.chosante.components.accordion

import atlantafx.base.controls.Tile
import atlantafx.base.controls.ToggleSwitch
import generated.I18nKey
import javafx.collections.FXCollections
import javafx.scene.control.Alert
import javafx.scene.control.ComboBox
import javafx.scene.control.ListCell
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.VBox
import javafx.util.Callback
import me.chosante.alert
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.CharacterClass
import me.chosante.common.Rarity
import me.chosante.i18n.I18n

class BuildParamsBox : VBox(5.0) {
    private val classComboBox = ComboBox(
        FXCollections.observableArrayList(
            CharacterClass.entries.filter { it != CharacterClass.UNKNOWN }.map { it.name }
        )
    )

    private val classTile = Tile(
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_CLASS_TITLE),
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_CLASS_DESCRIPTION)
    ).apply {
        action = classComboBox.apply {
            selectionModel.selectFirst()
        }
        actionHandler = Runnable { }
    }

    private data class RarityItem(val text: String, val image: Image)
    private class RarityItemCell : ListCell<RarityItem>() {
        override fun updateItem(item: RarityItem?, empty: Boolean) {
            super.updateItem(item, empty)

            if (empty || item == null) {
                graphic = null
                text = null
            } else {
                graphic = ImageView(item.image)
                text = item.text
            }
        }
    }

    private val maxRarityComboBox = ComboBox(
        FXCollections.observableArrayList(
            Rarity.entries.map { RarityItem(it.name, Image("assets/rarities/${it.name.lowercase()}.png")) }
        )
    ).apply {
        buttonCell = RarityItemCell()
        cellFactory = Callback { RarityItemCell() }
    }

    private val maxRarityTile = Tile(
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_MAX_RARITY_TITLE),
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_MAX_RARITY_DESCRIPTION)
    ).apply {
        action = maxRarityComboBox.apply {
            selectionModel.selectLast()
        }
        actionHandler = Runnable { }
    }

    private val stopWhenBuildMatchToggleSwitch = ToggleSwitch()
    private val stopWhenBuildMatchTile = Tile(
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_STOP_WHEN_BUILD_MATCH_TITLE),
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
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_LEVEL_TITLE),
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_LEVEL_DESCRIPTION)
    ).apply {
        action = levelSpinner.apply {
            isEditable = true
        }
        actionHandler = Runnable { }
    }

    private val durationTextField = TextField("20")
    private val durationTile = Tile(
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_SEARCH_DURATION_TITLE),
        I18n.valueOf(I18nKey.BUILD_PARAMETERS_SEARCH_DURATION_DESCRIPTION)
    ).apply {
        action = durationTextField.apply {
            isEditable = true
            textProperty().addListener { _, _, newValue ->
                if (newValue.isEmpty()) return@addListener

                if (newValue.toIntOrNull() == null) {
                    alert(
                        Alert.AlertType.ERROR,
                        headerText = I18n.valueOf(I18nKey.BUILD_PARAMETERS_SEARCH_DURATION_ALERT_HEADER),
                        contentText = I18n.valueOf(I18nKey.BUILD_PARAMETERS_SEARCH_DURATION_ALERT_CONTENT)
                    )
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
        get() = durationTextField.textProperty().value?.toIntOrNull()

    val stopWhenBuildMatch: Boolean
        get() = stopWhenBuildMatchToggleSwitch.selectedProperty().value

    val maxRarity
        get() = Rarity.valueOf(maxRarityComboBox.value.text)
}
