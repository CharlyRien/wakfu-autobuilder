package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.Serializable

@Serializable
data class Effect(
    val definition: EffectDefinition,
    val description: EffectDescription? = null,
)

@Serializable
data class EffectDefinition(
    val id: Int,
    val effect: String,
)

@Serializable
data class EffectDescription(
    val fr: String,
    val en: String,
    val es: String,
    val pt: String,
)
