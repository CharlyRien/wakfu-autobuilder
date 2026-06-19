package me.chosante.bdataextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.chosante.common.I18nText
import me.chosante.common.SublimationRarity
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Sublimation **metadata**, sourced from the Ankama CDN `items.json` (the same file
 * `equipments-extractor` reads) — NOT from bdata. A sublimation is an item of type [SUBLIMATION_ITEM_TYPE]
 * (812) whose `equipEffects` carry the state-application action [APPLY_STATE_ACTION] (304); that action's
 * `params[0]` is the [stateId] joining this metadata to the bdata State/StaticEffect effect decode.
 *
 * 467 item rows collapse to 232 distinct sublimations (one per [stateId]); each distinct stateId has a single
 * `slotColorPattern`, so the first item row in CDN order is authoritative (verified: 0 stateIds carry >1 pattern).
 */
data class SublimationMeta(
    val stateId: Int,
    /** The sublimation's item id — used as Zenith's `/shard/add` `id_shard`. */
    val zenithId: Int,
    val name: I18nText,
    val rarity: SublimationRarity,
    /** Normal subs: 3 socket colours (1=red, 2=green, 3=blue). Empty for epic/relic. */
    val slotColorPattern: List<Int>,
)

/**
 * Reads sublimation identities + display metadata from the CDN `items.json`. This is the "from the official
 * CDN" half of the sublimation pipeline; the effects/conditions/maxLevel half is decoded from bdata
 * ([buildSublimations]). Replaces the previously-committed `subs-game.json` snapshot.
 */
object ItemsCatalog {
    const val SUBLIMATION_ITEM_TYPE = 812
    const val APPLY_STATE_ACTION = 304

    private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

    fun fetchSublimationMeta(version: String): List<SublimationMeta> {
        val url = "https://wakfu.cdn.ankama.com/gamedata/$version/items.json"
        val body =
            HttpClient.newHttpClient().use { client ->
                val resp =
                    client.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                    )
                require(resp.statusCode() == 200) { "GET $url -> HTTP ${resp.statusCode()}" }
                resp.body()
            }
        return parse(body)
    }

    internal fun parse(json: String): List<SublimationMeta> {
        val items = LENIENT_JSON.decodeFromString<List<ItemDto>>(json)
        val seen = HashSet<Int>()
        val out = ArrayList<SublimationMeta>()
        for (it in items) {
            val core = it.definition.item
            if (core.baseParameters.itemTypeId != SUBLIMATION_ITEM_TYPE) continue
            val sp = core.sublimationParameters ?: continue
            val stateId =
                it.definition.equipEffects
                    .firstOrNull { e -> e.effect.definition.actionId == APPLY_STATE_ACTION }
                    ?.effect
                    ?.definition
                    ?.params
                    ?.firstOrNull()
                    ?.toInt() ?: continue
            if (!seen.add(stateId)) continue // first CDN-order row wins (patterns are unique per stateId)
            val title = it.title ?: continue
            out.add(
                SublimationMeta(
                    stateId = stateId,
                    zenithId = core.id,
                    name =
                        I18nText(
                            fr = title.fr ?: title.en ?: "",
                            en = title.en ?: title.fr ?: "",
                            es = title.es ?: title.en ?: title.fr ?: "",
                            pt = title.pt ?: title.en ?: title.fr ?: ""
                        ),
                    rarity =
                        when {
                            sp.isEpic -> SublimationRarity.EPIC
                            sp.isRelic -> SublimationRarity.RELIC
                            else -> SublimationRarity.NORMAL
                        },
                    slotColorPattern = sp.slotColorPattern
                )
            )
        }
        return out
    }

    @Serializable
    private data class ItemDto(
        val definition: Def,
        val title: Title? = null,
    )

    @Serializable
    private data class Def(
        val item: ItemCore,
        val equipEffects: List<EffectWrap> = emptyList(),
    )

    @Serializable
    private data class ItemCore(
        val id: Int,
        val baseParameters: BaseParams,
        val sublimationParameters: SubParams? = null,
    )

    @Serializable
    private data class BaseParams(
        val itemTypeId: Int,
    )

    @Serializable
    private data class SubParams(
        val slotColorPattern: List<Int> = emptyList(),
        val isEpic: Boolean = false,
        val isRelic: Boolean = false,
    )

    @Serializable
    private data class EffectWrap(
        val effect: EffectDef,
    )

    @Serializable
    private data class EffectDef(
        val definition: EffectInner,
    )

    @Serializable
    private data class EffectInner(
        val actionId: Int,
        val params: List<Double> = emptyList(),
    )

    @Serializable
    private data class Title(
        val fr: String? = null,
        val en: String? = null,
        val es: String? = null,
        val pt: String? = null,
    )
}
