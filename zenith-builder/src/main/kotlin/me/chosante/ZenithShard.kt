package me.chosante

import com.github.kittinunf.fuel.core.awaitUnit
import com.github.kittinunf.fuel.core.extensions.jsonBody
import com.github.kittinunf.fuel.httpPost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ADD_SHARD_URL = "$BASE_API_URL/shard/add"

/**
 * Sockets one shard into an item already added to the Zenith build — a rune (with a [level]) or a
 * sublimation (id = the sub's item id, [level] = null; sublimations socket like runes on Zenith).
 * [side] must be the same per-slot value [addEquipment] used for that item (so rings target their
 * specific left/right side), and [position] is the socket index on the item (0-based). Mirrors the
 * zenithwakfu builder's `/builder/api/shard/add` call: `{id_build, id_shard, position, side, level}`.
 */
internal suspend fun addShard(
    buildId: Long,
    shardId: Int,
    position: Int,
    side: Int,
    level: Int?,
) {
    val payload =
        buildJsonObject {
            put("id_build", buildId)
            put("id_shard", shardId)
            put("position", position)
            put("side", side)
            put("level", level)
        }

    ADD_SHARD_URL
        .httpPost()
        .header(apiZenithWakfuHeaders)
        .jsonBody(Json.encodeToString<JsonObject>(payload))
        .awaitUnit()
}
