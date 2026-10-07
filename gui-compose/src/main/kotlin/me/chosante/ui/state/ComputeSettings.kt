package me.chosante.ui.state

import me.chosante.autobuilder.genetic.wakfu.ComputeBudget

enum class ProcessorUse { MAXIMUM, BALANCED, LOW, CUSTOM }

/** Durable app preference; never part of a saved build's request. */
data class ComputeSettings(
    val preset: ProcessorUse = ProcessorUse.MAXIMUM,
    val customCores: Int = ComputeBudget.availableCores,
) {
    fun cores(available: Int = ComputeBudget.availableCores): Int {
        require(available >= 1)
        return when (preset) {
            ProcessorUse.MAXIMUM -> available
            ProcessorUse.BALANCED -> available / 2 + available % 2
            ProcessorUse.LOW -> minOf(2, available)
            ProcessorUse.CUSTOM -> customCores.coerceIn(1, available)
        }
    }

    fun budget(): ComputeBudget = ComputeBudget(cores())
}
