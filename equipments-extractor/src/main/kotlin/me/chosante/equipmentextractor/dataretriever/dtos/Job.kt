package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.Serializable

@Serializable
data class Definition(
    val id: Int,
    val isArchive: Boolean,
    val isNoCraft: Boolean,
    val isHidden: Boolean,
    val xpFactor: Int,
    val isInnate: Boolean,
)

@Serializable
data class Title(
    val fr: String,
    val en: String,
    val es: String,
    val pt: String,
)

@Serializable
data class Jobs(
    val definition: Definition,
    val title: Title,
)
