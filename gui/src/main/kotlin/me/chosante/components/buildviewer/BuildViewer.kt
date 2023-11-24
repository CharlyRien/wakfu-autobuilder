package me.chosante.components.buildviewer

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.javafx.JavaFx
import kotlinx.coroutines.launch
import me.chosante.common.ItemType
import me.chosante.eventbus.DefaultEventBus
import me.chosante.eventbus.Listener
import me.chosante.events.AutobuildEndSearchEvent
import me.chosante.events.AutobuildStartSearchEvent
import me.chosante.events.AutobuildUpdateSearchEvent

@Suppress("UNUSED_PARAMETER")
class BuildViewer : ScrollPane(), CoroutineScope {

    private val helmetCard = SimpleObjectProperty(EquipmentCard("Helmet"))
    private val capeCard = SimpleObjectProperty(EquipmentCard("Cape"))
    private val amuletCard = SimpleObjectProperty(EquipmentCard("Amulet"))
    private val shoulderPadsCard = SimpleObjectProperty(EquipmentCard("Shoulder Pads"))
    private val chestPlateCard = SimpleObjectProperty(EquipmentCard("Chest Plate"))
    private val beltCard = SimpleObjectProperty(EquipmentCard("Belt"))
    private val ring1Card = SimpleObjectProperty(EquipmentCard("Ring 1"))
    private val ring2Card = SimpleObjectProperty(EquipmentCard("Ring 2"))
    private val bootsCard = SimpleObjectProperty(EquipmentCard("Boots"))
    private val petCard = SimpleObjectProperty(EquipmentCard("Pet"))
    private val weapon1Card = SimpleObjectProperty(EquipmentCard("Weapon 1"))
    private val weapon2Card = SimpleObjectProperty(EquipmentCard("Weapon 2"))
    private val emblemCard = SimpleObjectProperty(EquipmentCard("Emblem"))
    private val mountCard = SimpleObjectProperty(EquipmentCard("Mount"))

    private val gridPane = GridPane(5.0, 5.0).apply {
        addRow(0, helmetCard.value, capeCard.value)
        addRow(1, amuletCard.value, shoulderPadsCard.value)
        addRow(2, chestPlateCard.value, beltCard.value)
        addRow(3, ring1Card.value, ring2Card.value)
        addRow(4, bootsCard.value, petCard.value)
        addRow(5, weapon1Card.value, weapon2Card.value)
        addRow(6, emblemCard.value, mountCard.value)
        children.forEach {
            GridPane.setVgrow(it, Priority.ALWAYS)
            GridPane.setHgrow(it, Priority.ALWAYS)
            GridPane.setFillHeight(it, true)
            GridPane.setFillWidth(it, true)
        }
        alignment = Pos.TOP_CENTER
    }

    init {
        isFitToWidth = true
        padding = Insets(10.0)
        content = gridPane
        hbarPolicy = ScrollBarPolicy.AS_NEEDED
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        gridPane.prefWidthProperty().bind(Bindings.add(-1, widthProperty()))
        gridPane.prefHeightProperty().bind(Bindings.add(-1, heightProperty()))
        DefaultEventBus.subscribe(AutobuildUpdateSearchEvent::class, ::buildUpdate)
        DefaultEventBus.subscribe(AutobuildStartSearchEvent::class, ::buildStart)
        DefaultEventBus.subscribe(AutobuildEndSearchEvent::class, ::buildEnd)
    }

    @Listener
    private fun buildStart(event: AutobuildStartSearchEvent) {
        launch {
            gridPane.isDisable = true
        }
    }

    @Listener
    private fun buildEnd(event: AutobuildEndSearchEvent) {
        launch {
            gridPane.isDisable = false
        }
    }

    @Listener
    private fun buildUpdate(autobuildUpdateSearchEvent: AutobuildUpdateSearchEvent) {
        launch {
            val ringCards = listOf(ring1Card, ring2Card).iterator()
            val equipments = autobuildUpdateSearchEvent
                .buildCombination
                .individual
                .equipments

            val offHandedWeapon = equipments.firstOrNull {
                it.itemType == ItemType.OFF_HAND_WEAPONS
            }?.let { EquipmentCard(it) }

            val oneHandedWeapon = equipments.firstOrNull {
                it.itemType == ItemType.ONE_HANDED_WEAPONS
            }?.let { EquipmentCard(it) }

            equipments
                .forEach {
                    val newEquipmentCard = EquipmentCard(it)
                    when (it.itemType) {
                        ItemType.AMULET -> amuletCard.value.replaceWith(newEquipmentCard)
                        ItemType.EMBLEM -> emblemCard.value.replaceWith(newEquipmentCard)
                        ItemType.SHOULDER_PADS -> shoulderPadsCard.value.replaceWith(newEquipmentCard)
                        ItemType.RING -> ringCards.next().value.replaceWith(newEquipmentCard)
                        ItemType.BOOTS -> bootsCard.value.replaceWith(newEquipmentCard)
                        ItemType.CHEST_PLATE -> chestPlateCard.value.replaceWith(newEquipmentCard)
                        ItemType.CAPE -> capeCard.value.replaceWith(newEquipmentCard)
                        ItemType.HELMET -> helmetCard.value.replaceWith(newEquipmentCard)
                        ItemType.PETS -> petCard.value.replaceWith(newEquipmentCard)
                        ItemType.ONE_HANDED_WEAPONS -> {
                            weapon1Card.value.replaceWith(newEquipmentCard)
                            weapon2Card.value.replaceWith(offHandedWeapon ?: EquipmentCard(title = "Weapon 2"))
                        }

                        ItemType.OFF_HAND_WEAPONS -> {
                            weapon1Card.value.replaceWith(newEquipmentCard)
                            weapon2Card.value.replaceWith(oneHandedWeapon ?: EquipmentCard(title = "Weapon 2"))
                        }

                        ItemType.TWO_HANDED_WEAPONS -> {
                            weapon1Card.value.replaceWith(newEquipmentCard)
                            val twoHandedWeaponCard = EquipmentCard(it)
                            weapon2Card.value.replaceWith(twoHandedWeaponCard)
                        }

                        ItemType.MOUNTS -> mountCard.value.replaceWith(newEquipmentCard)
                        ItemType.BELT -> beltCard.value.replaceWith(newEquipmentCard)
                    }
                }
        }
    }

    private fun EquipmentCard.replaceWith(equipmentCard: EquipmentCard) {
        if (this.equipment?.equipmentId == equipmentCard.equipment?.equipmentId) {
            return
        }

        this.equipment = equipmentCard.equipment
        this.header = equipmentCard.header
        this.body = equipmentCard.body
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.JavaFx
}
