package me.chosante.common

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Displayed normal rune values, generated from CDN shard effects and client StaticEffect level bands. */
@Serializable
data class RuneValues(
    val byCharacteristic: Map<Characteristic, List<Int>>,
) {
    companion object {
        val embedded: RuneValues by lazy {
            val stream = requireNotNull(RuneValues::class.java.getResourceAsStream("/rune-values.json")) { "Missing common-lib rune-values.json" }
            val values = stream.bufferedReader().use { Json.decodeFromString<RuneValues>(it.readText()) }
            require(values.byCharacteristic.keys == RuneType.VALUED_CHARACTERISTICS) { "Incomplete rune value catalog" }
            values.byCharacteristic.forEach { (stat, table) ->
                require(table.size == RuneType.RUNE_LEVEL_REQUIREMENTS.size && table.all { it > 0 } && table.zipWithNext().all { (a, b) -> a <= b }) {
                    "Invalid rune value table for $stat: $table"
                }
            }
            values
        }
    }
}
