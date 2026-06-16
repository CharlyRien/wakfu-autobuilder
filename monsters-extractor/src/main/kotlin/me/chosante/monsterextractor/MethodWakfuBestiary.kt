package me.chosante.monsterextractor

import com.github.kittinunf.fuel.coroutines.awaitStringResult
import com.github.kittinunf.fuel.httpGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.I18nText
import me.chosante.common.Monster

/**
 * Primary data source: the MethodWakfu "Reborn" bestiary REST API (`db.methodwakfu.com/api/bestiary`).
 *
 * - `/api/bestiary?page=N` → a page of 60 monsters (id / name / level / family / rank), with the
 *   `lastPage` and `count` needed to walk every page;
 * - `/api/bestiary/<id>`   → one monster's full sheet, including HP and the **flat per-element
 *   resistances** (the value at index 2 of each `fire`/`water`/`earth`/`air` array).
 *
 * This is the source of truth for boss mode: flat resistances (kept signed, weaknesses included),
 * multilingual names (`[fr, en, es, pt]`), HP, level and the Ankama monster `rank` in one clean JSON
 * feed — strictly more than the Fandom `MonsterCard` template offers, and without Fandom's ambiguous
 * %-vs-flat resistance scale.
 */
object MethodWakfuBestiary {
    private const val API = "https://db.methodwakfu.com/api/bestiary"
    private const val USER_AGENT = "wakfu-autobuilder monsters-extractor (https://github.com/CharlyRien/wakfu-autobuilder)"

    // The server returns transient HTTP 500s under load, so keep concurrency modest and retry. A few
    // monster ids 500 permanently (broken entries) — they are skipped after the retries are exhausted.
    private const val FETCH_CONCURRENCY = 6
    private const val MAX_ATTEMPTS = 4

    private val json = Json { ignoreUnknownKeys = true }

    /** A monster whose detail endpoint could not be fetched (see [BestiaryFetch]). */
    data class FailedMonster(
        val id: Int,
        val name: I18nText,
        val level: Int,
        val rank: Int,
    ) {
        val isBoss: Boolean get() = rank >= 1
    }

    /** Outcome of a full bestiary crawl: the parsed [monsters] and the index stubs that [failed]. */
    data class BestiaryFetch(
        val monsters: List<Monster>,
        val failed: List<FailedMonster>,
    )

    suspend fun fetchAll(): BestiaryFetch =
        coroutineScope {
            val stubs = enumerateMonsters()
            val gate = Semaphore(FETCH_CONCURRENCY)
            val results =
                stubs
                    .map { stub ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                runCatching { parseMonster(fetchObject("$API/${stub.id}")) }
                                    .getOrElse {
                                        System.err.println("  ! skipped monster ${stub.id} (${stub.name.en}): ${it.message}")
                                        stub
                                    }
                            }
                        }
                    }.awaitAll()
            BestiaryFetch(
                monsters =
                    results
                        .filterIsInstance<Monster>()
                        .distinctBy { it.id }
                        .sortedWith(compareByDescending<Monster> { it.rank }.thenByDescending { it.level }.thenBy { it.name.en }),
                failed =
                    results
                        .filterIsInstance<FailedMonster>()
                        .distinctBy { it.id }
                        .sortedWith(compareByDescending<FailedMonster> { it.rank }.thenByDescending { it.level })
            )
        }

    /** Walks the paginated bestiary index and returns a stub (id / name / level / rank) per monster. */
    private suspend fun enumerateMonsters(): List<FailedMonster> {
        val firstPage = fetchObject("$API?page=1")
        val lastPage = firstPage["lastPage"]!!.jsonPrimitive.int
        val total = firstPage["count"]!!.jsonPrimitive.int
        println("Bestiary index: $total monsters across $lastPage pages.")
        val stubs = LinkedHashMap<Int, FailedMonster>()
        collectStubs(firstPage, stubs)
        for (page in 2..lastPage) collectStubs(fetchObject("$API?page=$page"), stubs)
        return stubs.values.toList()
    }

    private fun collectStubs(
        page: JsonObject,
        into: MutableMap<Int, FailedMonster>,
    ) {
        page["content"]!!.jsonArray.forEach {
            val entry = it.jsonObject
            val id = entry["id"]!!.jsonPrimitive.int
            into.getOrPut(id) {
                FailedMonster(
                    id = id,
                    name = i18n(entry["name"]!!.jsonArray),
                    level = entry["lvlMax"]!!.jsonPrimitive.int,
                    rank = entry["rank"]?.jsonPrimitive?.int ?: 0
                )
            }
        }
    }

    private suspend fun fetchObject(url: String): JsonObject {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            url
                .httpGet()
                .header("User-Agent", USER_AGENT)
                .awaitStringResult()
                .fold(
                    success = { return json.parseToJsonElement(it).jsonObject },
                    failure = { lastError = it }
                )
            delay(200L * (attempt + 1)) // linear backoff for transient HTTP 500s under load
        }
        throw IllegalStateException("GET $url failed after $MAX_ATTEMPTS attempts", lastError)
    }

    private fun parseMonster(data: JsonObject): Monster {
        val family = (data["family"] as? JsonObject)?.let { i18n(it["name"]!!.jsonArray) }
        return Monster(
            id = data["id"]!!.jsonPrimitive.int,
            name = i18n(data["name"]!!.jsonArray),
            level = data["lvlMax"]!!.jsonPrimitive.int,
            hp = firstInt(data["hp"]),
            family = family,
            rank = data["rank"]?.jsonPrimitive?.int ?: 0,
            fireResistance = flatResistance(data["fire"]),
            waterResistance = flatResistance(data["water"]),
            earthResistance = flatResistance(data["earth"]),
            airResistance = flatResistance(data["air"]),
            source = "methodwakfu"
        )
    }

    private fun i18n(parts: JsonArray): I18nText {
        fun at(i: Int) = (parts.getOrNull(i)?.jsonPrimitive?.content)?.trim().orEmpty()
        val fr = at(0)
        return I18nText(fr = fr, en = at(1).ifEmpty { fr }, es = at(2).ifEmpty { fr }, pt = at(3).ifEmpty { fr })
    }

    /** First element of a `[value, perLevel]`-style stat array. */
    private fun firstInt(value: kotlinx.serialization.json.JsonElement?): Int =
        value
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.int ?: 0

    /**
     * Flat resistance for an element array `[attackMastery, _, resistance, _]`: the resistance lives at
     * index 2 and is kept **signed** (a negative value is an elemental weakness).
     */
    private fun flatResistance(value: kotlinx.serialization.json.JsonElement?): Int =
        value
            ?.jsonArray
            ?.getOrNull(2)
            ?.jsonPrimitive
            ?.int ?: 0
}
