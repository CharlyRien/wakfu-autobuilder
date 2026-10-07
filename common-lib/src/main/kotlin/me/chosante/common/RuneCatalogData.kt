package me.chosante.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Rune identities and their shared item-level thresholds, decoded from CDN items.json shardsParameters. */
@Serializable
data class RuneCatalogData(
    val levelRequirements: List<Int>,
    val runes: List<RuneType>,
) {
    init {
        require(levelRequirements.size == 11 && levelRequirements.first() == 0) { "Expected eleven rune thresholds starting at zero" }
        require(levelRequirements.zipWithNext().all { (a, b) -> a < b }) { "Rune thresholds must increase strictly" }
    }

    companion object {
        /** The embedded catalog (autobuilder resources); a missing catalog or drifted thresholds fail loudly. */
        val embedded: RuneCatalogData by lazy {
            val text =
                requireNotNull(RuneCatalogData::class.java.getResourceAsStream("/runes.json")) { "Missing runes.json" }
                    .bufferedReader()
                    .use { it.readText() }
            Json.decodeFromString<RuneCatalogData>(text).also {
                require(it.levelRequirements == RuneType.RUNE_LEVEL_REQUIREMENTS) {
                    "runes.json level thresholds ${it.levelRequirements} differ from RuneType.RUNE_LEVEL_REQUIREMENTS: update the constant on purpose"
                }
            }
        }
    }
}
