package me.chosante.common

import kotlinx.serialization.Serializable

/** A positive resource expenditure; the extractor resolves each resource's sign convention. Default sheet only. */
@Serializable
data class SpellResourceCost(
    val characteristicId: Int,
    val scriptName: String,
    val amount: Int,
)
