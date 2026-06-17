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
    /** Decode classpath resource [name] as a `List<T>`, or null when the resource is absent. */
    inline fun <reified T> decodeList(name: String): List<T>? =
        EmbeddedResources::class.java.classLoader
            .getResourceAsStream(name)
            ?.readAllBytes()
            ?.let { Json.decodeFromString<List<T>>(String(it)) }
}
