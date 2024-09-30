package me.chosante.components.accordion

import generated.I18nKey
import java.util.Optional
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.util.Callback
import me.chosante.common.Rarity
import me.chosante.i18n.I18n

class ItemsForcedTable : TableView<ItemForced>() {
    private val selectAllCheckBox = CheckBox()
    private val addButton = Button("Ajouter")

    init {
        val columnSelection =
            TableColumn<ItemForced, Boolean>().apply {
                graphic = selectAllCheckBox
                isSortable = false
                cellValueFactory = Callback { it.value.status }
                isEditable = true
                cellFactory = CheckBoxTableCell.forTableColumn(this)
            }

        val columnItemName =
            TableColumn<ItemForced, String>(I18n.valueOf(I18nKey.ITEM_FORCED_TABLE_COLUMN_ITEM_NAME_TITLE))
                .apply {
                    cellValueFactory = Callback { SimpleStringProperty(it.value.name) }
                    cellFactory = TextFieldTableCell.forTableColumn()
                }
        val columnItemLevel =
            TableColumn<ItemForced, String>(I18n.valueOf(I18nKey.ITEM_FORCED_TABLE_COLUMN_ITEM_LEVEL_TITLE))
                .apply {
                    cellValueFactory = Callback { SimpleStringProperty(it.value.level.toString()) }
                    cellFactory = TextFieldTableCell.forTableColumn()
                }
        val columnItemRarity =
            TableColumn<ItemForced, String>(I18n.valueOf(I18nKey.ITEM_FORCED_TABLE_COLUMN_ITEM_RARITY_TITLE)).apply {
                cellValueFactory = Callback { SimpleStringProperty(it.value.rarity.name) }
                cellFactory = TextFieldTableCell.forTableColumn()
            }

        columns.addAll(
            columnSelection,
            columnItemName,
            columnItemLevel,
            columnItemRarity
        )

        selectAllCheckBox.setOnAction { event ->
            this.items.forEach {
                it.status.value = true
            }
            event.consume()
        }
        addButton.setOnAction { event ->
            val dialog: Dialog<ItemForced> = Dialog()
            dialog.title = "Ajouter un nouvel élément"

            val result: Optional<ItemForced> = dialog.showAndWait()

            result.ifPresent { item: ItemForced -> items.add(item) }
        }
    }
}

data class ItemForced(
    val name: String,
    val level: Int,
    val rarity: Rarity,
    val status: SimpleBooleanProperty,
)
