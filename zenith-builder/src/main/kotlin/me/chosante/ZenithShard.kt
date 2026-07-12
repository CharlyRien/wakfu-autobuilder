package me.chosante

import com.github.kittinunf.fuel.core.awaitUnit
import com.github.kittinunf.fuel.core.extensions.jsonBody
import com.github.kittinunf.fuel.coroutines.awaitObjectResponse
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.serialization.kotlinxDeserializerOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val ADD_SHARD_URL = "$BASE_API_URL/shard/add"
private const val TRANSFORM_SHARD_URL = "$BASE_API_URL/shard/transform"
private const val SHARDS_CATALOG_URL = "$BASE_API_URL/shards"

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

/**
 * Whitens an already-socketed rune (`/shard/transform`, `isWhited = 1`) — Zenith's equivalent of the
 * in-game socket-colour re-roll. A whited shard's socket counts as a wildcard for every sublimation
 * colour pattern, and Zenith's double-bonus computation ignores `is_whited` (it only checks the item
 * type), so whitening every exported rune makes any sublimation activable at zero stat cost.
 */
internal suspend fun whitenShard(
    buildId: Long,
    shardId: Int,
    position: Int,
    side: Int,
) {
    val payload =
        buildJsonObject {
            put("id_build", buildId)
            put("id_shard", shardId)
            put("position", position)
            put("side", side)
            put("isWhited", 1)
        }

    TRANSFORM_SHARD_URL
        .httpPost()
        .header(apiZenithWakfuHeaders)
        .jsonBody(Json.encodeToString<JsonObject>(payload))
        .awaitUnit()
}

/**
 * Zenith's sublimation catalog, resolved to `family root id -> (shard level -> shard id)`.
 *
 * On Zenith every sublimation generation (I/II/III) is its OWN shard id whose intrinsic `level` (1/2/3)
 * is what the build accumulates — the `/shard/add` `level` parameter is ignored for sublimations
 * (verified against the live API). Our [me.chosante.common.Sublimation] is a per-FAMILY record whose
 * `zenithId` is the family root (tier I) and whose effects are valued at `maxTier`, so the export must
 * socket the family member whose level equals `maxTier` — e.g. Ambition (root 29591) at maxTier 3
 * sockets 29593 ("Ambition III", level 3), not the root (which Zenith shows as level 1).
 */
internal suspend fun fetchSublimationLevelIds(): Map<Int, Map<Int, Int>> {
    val (_, _, catalog) =
        SHARDS_CATALOG_URL
            .httpGet()
            .header(apiZenithWakfuHeaders)
            .awaitObjectResponse(kotlinxDeserializerOf<JsonObject>())

    val byRoot = mutableMapOf<Int, MutableMap<Int, Int>>()
    for (key in listOf("sublimations", "special_sublimations")) {
        val records = catalog[key]?.jsonArray ?: continue
        for (record in records) {
            val root = record.jsonObject
            val rootId = root["id_shard"]?.jsonPrimitive?.int ?: continue
            val family = byRoot.getOrPut(rootId) { mutableMapOf() }
            (root["level"]?.jsonPrimitive?.int)?.let { family[it] = rootId }
            val children = root["children"]?.jsonArray ?: continue
            for (child in children) {
                val childObject = child.jsonObject
                val childId = childObject["id_shard"]?.jsonPrimitive?.int ?: continue
                val childLevel = childObject["level"]?.jsonPrimitive?.int ?: continue
                family[childLevel] = childId
            }
        }
    }
    return byRoot
}
