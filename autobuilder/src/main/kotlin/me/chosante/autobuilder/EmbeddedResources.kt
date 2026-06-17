package me.chosante.autobuilder

import kotlinx.serialization.json.Json

/**
 * Single source for loading the baked `*-v<VERSION>.json` data artifacts off the classpath. Replaces the
 * `getResourceAsStream(name)?.readAllBytes()?.let { Json.decodeFromString<List<T>>(String(it)) }` idiom
 * that was copy-pasted across the equipments / runes / monsters / sublimations loaders in
 * [WakfuBestBuildFinderAlgorithm] and the spells / cast-limits loaders in
 * [me.chosante.autobuilder.domain.SpellCatalog] (the copies had already started to drift). Callers decide
 * the missing-resource policy: `!!` for a required artifact, `?: emptyList()` / `.orEmpty()` for optional.
 */
internal object EmbeddedResources {
    /** Lenient JSON for artifacts whose runtime model intentionally reads a *subset* of the file's keys. */
    val lenientJson: Json = Json { ignoreUnknownKeys = true }

    /**
     * Decode classpath resource [name] as a `List<T>`, or null when the resource is absent. [json] defaults
     * to the strict format (an unknown key is a bug for artifacts whose model mirrors the file exactly);
     * pass [lenientJson] for files whose runtime model deliberately ignores extra keys (e.g. passives).
     */
    inline fun <reified T> decodeList(
        name: String,
        json: Json = Json,
    ): List<T>? =
        EmbeddedResources::class.java.classLoader
            .getResourceAsStream(name)
            ?.readAllBytes()
            ?.let { json.decodeFromString<List<T>>(String(it)) }
}
