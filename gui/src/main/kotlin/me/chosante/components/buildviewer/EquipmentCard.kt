package me.chosante.components.buildviewer

import atlantafx.base.controls.Card
import atlantafx.base.controls.Tile
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TitledPane
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import me.chosante.GuiCharacteristic
import me.chosante.common.Equipment
import me.chosante.i18n.I18n.usePreferredLanguageIfFoundOrEnglish

class EquipmentCard(title: String, var equipment: Equipment? = null) : Card(), CoroutineScope {

    constructor(equipment: Equipment) : this(equipment.name.usePreferredLanguageIfFoundOrEnglish(), equipment)

    init {
        val currentEquipment = equipment
        header = SimpleObjectProperty(
            Tile(
                title,
                null,
                when (currentEquipment) {
                    null -> ImageView(Image("assets/items/0000000.png", 40.0, 40.0, true, false, false))
                    else -> currentEquipment.toHBoxImages()
                }
            )
        ).value
        body = SimpleObjectProperty(currentEquipment?.toVBoxCharacteristics()).value
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}

private fun Equipment.toVBoxCharacteristics(): VBox {
    val itemLevel = Label("Level $level")
    val characteristicsBoxes = characteristics
        .mapNotNull { (characteristic, characteristicValue) ->
            val guiCharacteristic = GuiCharacteristic.from(characteristic)
            when {
                guiCharacteristic == null -> null
                else -> HBox(
                    3.0,
                    Label(characteristicValue.toString()),
                    Label(guiCharacteristic.guiName)
                ).apply {
                    alignment = Pos.CENTER_LEFT
                }
            }
        }
    val characteristicTitledPane = TitledPane(
        "Characteristics",
        VBox(*characteristicsBoxes.toTypedArray<HBox>())
    ).apply {
        isExpanded = false
        isCollapsible = true
    }
    return VBox(itemLevel, characteristicTitledPane)
}

private fun Equipment.toHBoxImages(): HBox {
    val itemImageView = runCatching {
        ImageView(Image("assets/items/$guiId.png", 40.0, 40.0, true, false, false))
    }.getOrDefault(
        ImageView(Image("assets/items/0000000.png", 40.0, 40.0, true, false, false))
    )
    return HBox(
        itemImageView,
        ImageView(Image("assets/rarities/${rarity.name.lowercase()}.png")),
        ImageView(Image("assets/itemTypes/${itemType.id}.png"))
    )
}
