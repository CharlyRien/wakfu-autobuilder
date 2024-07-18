package me.chosante.equipmentextractor.dataretriever

import com.github.kittinunf.fuel.core.awaitResult
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.serialization.kotlinxDeserializerOf
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

@Serializable
data class Version(
    val version: String,
)

suspend fun wakfuAPILatestVersion(): String =
    coroutineScope {
        "https://wakfu.cdn.ankama.com/gamedata/config.json"
            .httpGet()
            .awaitResult(kotlinxDeserializerOf<Version>())
            .fold(
                success = { it.version },
                failure = { throw IllegalStateException(it) }
            )
    }
