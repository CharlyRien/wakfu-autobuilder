package me.chosante.components.accordion

import atlantafx.base.theme.Styles
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections.observableArrayList
import javafx.event.EventHandler
import javafx.scene.control.Alert
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.cell.TextFieldTableCell
import javafx.util.Callback
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.GuiCharacteristic
import me.chosante.alert
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.eventbus.DefaultEventBus.subscribe
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent

@Suppress("UNUSED_PARAMETER", "UNCHECKED_CAST")
class CharacteristicTable(private val getCharacter: () -> Character) : TableView<TableViewContent>(), CoroutineScope {

    init {
        items = observableArrayList(GuiCharacteristic.entries.map { TableViewContent(guiCharacteristic = SimpleObjectProperty(it)) })

        val col1 = TableColumn<TableViewContent, String>("Name").apply {
            cellValueFactory = Callback { c -> SimpleStringProperty(c.value.guiCharacteristic.value.guiName) }
            isEditable = false
        }

        val col2 = TableColumn<TableViewContent, String>("Desired value").apply {
            cellValueFactory = Callback { c -> SimpleStringProperty(c.value.desiredValue.value) }
            cellFactory = TextFieldTableCell.forTableColumn()
            isEditable = true
            onEditCommit = EventHandler { event ->
                val newValue = event.newValue.toIntOrNull()
                if (event.rowValue.guiCharacteristic.value in listOf(GuiCharacteristic.CRITICAL_HIT) && newValue != null && newValue > 100) {
                    alert(Alert.AlertType.ERROR, headerText = "Input not valid", contentText = "The value must be a number not greater than 100")

                    event.tableColumn.cellFactory.call(event.tableColumn).startEdit()
                    event.tableColumn.cellFactory.call(event.tableColumn).cancelEdit()
                } else {
                    event.rowValue.desiredValue.value = newValue?.toString() ?: ""
                }
            }
        }

        val col3 = TableColumn<TableViewContent, Int>("Actual value").apply {
            cellValueFactory = Callback { c -> c.value.actualValue as ObservableValue<Int> }
            isEditable = false
        }

        columnResizePolicy = CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        styleClass.addAll(
            Styles.BORDERED,
            Styles.STRIPED
        )
        columns.setAll(col1, col2, col3)
        isEditable = true
        rowFactory = Callback {
            object : TableRow<TableViewContent>() {
                override fun isItemChanged(oldItem: TableViewContent?, newItem: TableViewContent?) = this@CharacteristicTable.isDisabled

                override fun updateItem(item: TableViewContent?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val desiredValue = item?.desiredValue?.value
                    style = when {
                        desiredValue.isNullOrEmpty() -> ""
                        item.actualValue.value >= desiredValue.toInt() -> "-fx-background-color: green;"
                        item.actualValue.value < desiredValue.toInt() -> "-fx-background-color: coral;"
                        else -> ""
                    }
                }
            }
        }
        subscribe(AutobuildUpdateSearchEvent::class, ::searchUpdate)
        subscribe(AutobuildStartSearchEvent::class, ::searchStart)
        subscribe(AutobuildEndSearchEvent::class, ::searchComplete)
    }

    val targetStats
        get() = itemsProperty().value.mapNotNull {
            if (it.desiredValue.value.isNotBlank()) {
                TargetStat(it.guiCharacteristic.value.characteristic, it.desiredValue.value.toInt())
            } else {
                null
            }
        }.let {
            TargetStats(it)
        }

    @Listener
    fun searchUpdate(event: AutobuildUpdateSearchEvent) {
        val actualValues = computeCharacteristicsValues(
            buildCombination = event.buildCombination.individual,
            characterBaseCharacteristics = getCharacter().baseCharacteristicValues,
            masteryElementsWanted = targetStats.masteryElementsWanted,
            resistanceElementsWanted = targetStats.resistanceElementsWanted
        )

        launch {
            items.forEach {
                it.actualValue.value = actualValues[it.guiCharacteristic.value.characteristic] ?: 0
            }
        }
    }

    @Listener
    fun searchStart(event: AutobuildStartSearchEvent) {
        this.isDisabled = true
    }

    @Listener
    fun searchComplete(event: AutobuildEndSearchEvent) {
        this.isDisabled = false
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}

data class TableViewContent(
    val guiCharacteristic: SimpleObjectProperty<GuiCharacteristic>,
    val desiredValue: SimpleStringProperty = SimpleStringProperty(""),
    val actualValue: SimpleIntegerProperty = SimpleIntegerProperty(0),
)
