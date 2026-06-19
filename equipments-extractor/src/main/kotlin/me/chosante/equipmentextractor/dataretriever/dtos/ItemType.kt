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
        // NB: Ankama dropped `parentId` here in 1.92.x. It was never read, so it is not modeled; the
        // deserializer uses `ignoreUnknownKeys` (see WakfuDataRetriever.CDN_JSON), so if Ankama re-adds it
        // — or any other field — decoding keeps working.
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
