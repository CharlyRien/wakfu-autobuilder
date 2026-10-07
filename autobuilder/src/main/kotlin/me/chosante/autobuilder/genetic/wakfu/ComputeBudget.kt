package me.chosante.autobuilder.genetic.wakfu

/** Immutable logical-core allowance, captured by a search or proof rather than read from mutable app settings. */
data class ComputeBudget(
    val logicalCores: Int = availableCores,
) {
    init {
        require(logicalCores in 1..availableCores) { "Threads must be between 1 and $availableCores" }
    }

    /** Preserve the existing margins; zero chunk workers means a sequential DP. */
    val chunkWorkers: Int get() = logicalCores - 1
    val searchWorkers: Int get() = (logicalCores - 1).coerceAtLeast(1)
    val oracleWorkers: Int get() = cap((logicalCores - 2).coerceIn(4, 8))
    val warmupWorkers: Int get() = cap(2)

    fun cap(workers: Int): Int = workers.coerceIn(1, logicalCores)

    companion object {
        /** The only production hardware-core read. */
        val availableCores: Int get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }
}
