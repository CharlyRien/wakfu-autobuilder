package me.chosante.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Client equipment-position ids joined with CDN equipmentItemTypes.json occupied positions (not disabled positions). */
@Serializable
data class EquipmentPositions(
    val positionIds: Map<String, Int>,
    val itemPositions: Map<ItemType, List<String>>,
) {
    fun rawIds(itemType: ItemType): List<Int> = itemPositions.getValue(itemType).map { positionIds.getValue(it) }

    companion object {
        // Shipped by common-lib itself: Zenith and synthetic RuneType callers need no autobuilder resources.
        val embedded: EquipmentPositions by lazy {
            val stream =
                requireNotNull(EquipmentPositions::class.java.getResourceAsStream("/equipment-positions.json")) {
                    "Missing common-lib equipment-positions.json"
                }
            val data = stream.bufferedReader().use { Json.decodeFromString<EquipmentPositions>(it.readText()) }
            require(data.itemPositions.keys == ItemType.entries.toSet()) { "Incomplete equipment position catalog" }
            require(
                data.positionIds.values
                    .distinct()
                    .size == data.positionIds.size
            ) { "Duplicate client equipment position ids" }
            data.itemPositions.values.forEach { positions ->
                require(positions.isNotEmpty() && positions.distinct().size == positions.size) { "Invalid occupied positions: $positions" }
                positions.forEach { require(it in data.positionIds) { "Unknown client position $it" } }
            }
            data
        }
    }
}
