package me.chosante.equipmentextractor.dataretriever

import me.chosante.equipmentextractor.dataretriever.dtos.Effect
import me.chosante.equipmentextractor.dataretriever.dtos.Item
import me.chosante.equipmentextractor.dataretriever.dtos.ItemType
import me.chosante.equipmentextractor.dataretriever.dtos.Jobs

data class WakfuData(
    val items: List<Item>,
    val jobs: List<Jobs>,
    val effects: List<Effect>,
    val itemTypes: List<ItemType>,
)
