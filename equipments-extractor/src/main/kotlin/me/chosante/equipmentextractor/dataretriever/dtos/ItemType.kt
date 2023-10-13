package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.Serializable

@Serializable
data class ItemType(
    val definition: Definition,
    val title: Title,
) {
    @Serializable
    data class Definition(
        val id: Int,
        val parentId: Int,
        val equipmentPositions: List<String>,
        val equipmentDisabledPositions: List<String>,
        val isRecyclable: Boolean,
        val isVisibleInAnimation: Boolean,
    )

    @Serializable
    data class Title(
        val fr: String,
        val en: String,
        val es: String,
        val pt: String,
    )
}
