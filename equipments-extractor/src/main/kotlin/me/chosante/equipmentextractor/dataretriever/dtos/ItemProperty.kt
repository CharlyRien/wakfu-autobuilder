package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.Serializable

/**
 * One entry of the CDN's `itemProperties.json`: the meaning of an id in an item's `properties` list (e.g. 12
 * `EXCLUSIVE_EQUIPMENT_ITEM_2`, "[Relique2] only one item with this property can be equipped at a time").
 */
@Serializable
data class ItemProperty(
    val id: Int,
    val name: String,
    val description: String? = null,
)
