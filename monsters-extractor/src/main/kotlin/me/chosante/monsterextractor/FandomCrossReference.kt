package me.chosante.monsterextractor

import com.github.kittinunf.fuel.coroutines.awaitStringResult
import com.github.kittinunf.fuel.httpGet
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.Monster
import java.net.URLEncoder

/**
 * Complementary source: the Wakfu Fandom wiki's `Template:MonsterCard` (English), via the MediaWiki API.
 *
 * Two roles:
 *  1. **Provenance** — [monsterTitles] lists every page that transcludes the template, so MethodWakfu
 *     monsters that also exist on Fandom are tagged `methodwakfu+fandom`.
 *  2. **Fallback** — [recover] tries to source a monster that MethodWakfu could not serve (its detail
 *     endpoint HTTP 500s on some endgame bosses). Fandom's `MonsterCard` stores **flat, signed**
 *     resistances on the same scale as MethodWakfu (verified: e.g. Dominant Tofu fire=120 ≈ 23%), so
 *     they can be used directly — but only when the Fandom page's **level matches** the MethodWakfu
 *     listing's level, otherwise it is a same-named but different monster (Wakfu reuses names) and is
 *     rejected rather than risk wrong data.
 *
 * Best-effort throughout: any network/parse failure is swallowed so extraction still succeeds on
 * MethodWakfu alone.
 */
object FandomCrossReference {
    private const val API = "https://wakfu.fandom.com/api.php"
    private const val USER_AGENT = "wakfu-autobuilder monsters-extractor"
    private const val LEVEL_TOLERANCE = 3
    private val json = Json { ignoreUnknownKeys = true }

    /** Normalized English monster name → its original Fandom page title (every `MonsterCard` page). */
    suspend fun monsterTitles(): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        var continueToken: String? = null
        do {
            val url =
                buildString {
                    append("$API?action=query&list=embeddedin&eititle=Template:MonsterCard&eilimit=500&format=json")
                    if (continueToken != null) append("&eicontinue=$continueToken")
                }
            val root = json.parseToJsonElement(get(url)).jsonObject
            root["query"]?.jsonObject?.get("embeddedin")?.jsonArray?.forEach {
                val title = it.jsonObject["title"]!!.jsonPrimitive.content
                titles.putIfAbsent(normalize(title), title)
            }
            continueToken = (root["continue"] as? JsonObject)?.get("eicontinue")?.jsonPrimitive?.content
        } while (continueToken != null)
        return titles
    }

    /**
     * Attempts to recover [failed] from Fandom. Returns a [Monster] (source `"fandom"`) when a
     * level-matching `MonsterCard` with elemental resistances is found, else null.
     */
    suspend fun recover(
        failed: MethodWakfuBestiary.FailedMonster,
        titles: Map<String, String>,
    ): Monster? {
        for (key in candidateKeys(failed.name.en)) {
            val title = titles[key] ?: continue
            val card = runCatching { fetchCard(title) }.getOrNull() ?: continue
            if (card.fire == null && card.water == null && card.earth == null && card.air == null) continue
            // Same name, wrong level ⇒ a different monster; don't fabricate stats for it.
            if (card.levelLow != null && failed.level !in (card.levelLow - LEVEL_TOLERANCE)..(card.levelHigh + LEVEL_TOLERANCE)) continue
            return Monster(
                id = failed.id,
                name = failed.name,
                level = failed.level,
                hp = card.hp ?: 0,
                rank = failed.rank,
                fireResistance = card.fire ?: 0,
                waterResistance = card.water ?: 0,
                earthResistance = card.earth ?: 0,
                airResistance = card.air ?: 0,
                source = "fandom"
            )
        }
        return null
    }

    /** Candidate normalized titles for an English name, covering the "X Dominant" / "Dominant X" forms. */
    private fun candidateKeys(en: String): List<String> {
        val lower = en.lowercase().trim()
        val base = lower.replace(Regex("\\b(dominant|the)\\b"), "").replace(Regex("\\s+"), " ").trim()
        return listOf(lower, "dominant $base", "$base dominant", base)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private data class Card(
        val levelLow: Int?,
        val levelHigh: Int,
        val hp: Int?,
        val fire: Int?,
        val water: Int?,
        val earth: Int?,
        val air: Int?,
    )

    private suspend fun fetchCard(title: String): Card {
        val url = "$API?action=parse&page=${URLEncoder.encode(title, "UTF-8")}&prop=wikitext&format=json"
        val wikitext =
            json
                .parseToJsonElement(get(url))
                .jsonObject["parse"]
                ?.jsonObject
                ?.get("wikitext")
                ?.jsonObject
                ?.get("*")
                ?.jsonPrimitive
                ?.content
                ?: error("no wikitext for $title")
        require("MonsterCard" in wikitext) { "no MonsterCard in $title" }

        fun intField(key: String): Int? =
            Regex("\\|\\s*$key\\s*=\\s*(-?\\d+)")
                .find(wikitext)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val levelMatch = Regex("\\|\\s*level\\s*=\\s*(\\d+)(?:\\s*-\\s*(\\d+))?").find(wikitext)
        val low = levelMatch?.groupValues?.get(1)?.toIntOrNull()
        val high = levelMatch?.groupValues?.get(2)?.toIntOrNull() ?: low ?: 0
        return Card(low, high, intField("hp"), intField("fireresist"), intField("waterresist"), intField("earthresist"), intField("airresist"))
    }

    private suspend fun get(url: String): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            url
                .httpGet()
                .header("User-Agent", USER_AGENT)
                .awaitStringResult()
                .fold(success = { return it }, failure = { lastError = it })
            delay(250L * (attempt + 1))
        }
        throw IllegalStateException("Fandom GET $url failed", lastError)
    }

    /** Lowercased, with the disambiguation suffixes the wiki appends (e.g. " (Monster)") removed. */
    fun normalize(name: String): String =
        name
            .lowercase()
            .replace(Regex("\\s*\\((monster|creature|boss|mob)\\)\\s*$"), "")
            .trim()
}
