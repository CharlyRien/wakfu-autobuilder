package me.chosante.equipmentextractor.dataretriever.dtos

import kotlinx.serialization.Serializable
import me.chosante.common.I18nText

@Serializable
data class Effect(
    val definition: EffectDefinition,
    val description: I18nText? = null,
)

@Serializable
data class EffectDefinition(
    val id: Int,
    val effect: String,
)
