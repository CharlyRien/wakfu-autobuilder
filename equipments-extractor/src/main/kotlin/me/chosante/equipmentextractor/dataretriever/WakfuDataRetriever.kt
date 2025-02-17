package me.chosante.equipmentextractor.dataretriever

import com.github.kittinunf.fuel.coroutines.awaitResult
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.serialization.kotlinxDeserializerOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import me.chosante.equipmentextractor.dataretriever.dtos.Effect
import me.chosante.equipmentextractor.dataretriever.dtos.ItemSerializer
import me.chosante.equipmentextractor.dataretriever.dtos.ItemType
import me.chosante.equipmentextractor.dataretriever.dtos.Jobs

const val GAMEDATA_BASE_URL = "https://wakfu.cdn.ankama.com/gamedata/:version"
val ioDispatcher = Dispatchers.IO

suspend fun getWakfuRawData(version: String): WakfuData =
    coroutineScope {
        val baseUrlWithVersion = GAMEDATA_BASE_URL.replace(":version", version)
        val items =
            async(ioDispatcher) {
                "$baseUrlWithVersion/items.json"
                    .httpGet()
                    .awaitResult(
                        kotlinxDeserializerOf(
                            loader = ListSerializer(ItemSerializer)
                        )
                    ).fold(
                        success = { it },
                        failure = { throw IllegalStateException(it) }
                    )
            }

        val itemTypes =
            async(ioDispatcher) {
                "$baseUrlWithVersion/equipmentItemTypes.json"
                    .httpGet()
                    .awaitResult(
                        kotlinxDeserializerOf(
                            loader = ListSerializer(ItemType.serializer())
                        )
                    ).fold(
                        success = { it },
                        failure = { throw IllegalStateException(it) }
                    )
            }

        val effect =
            async(ioDispatcher) {
                "$baseUrlWithVersion/actions.json"
                    .httpGet()
                    .awaitResult(kotlinxDeserializerOf(loader = ListSerializer(Effect.serializer())))
                    .fold(
                        success = { it },
                        failure = { throw IllegalStateException(it) }
                    )
            }

        val jobs =
            async(ioDispatcher) {
                "$baseUrlWithVersion/recipeCategories.json"
                    .httpGet()
                    .awaitResult(
                        kotlinxDeserializerOf(
                            loader = ListSerializer(Jobs.serializer())
                        )
                    ).fold(
                        success = { it },
                        failure = { throw IllegalStateException(it) }
                    )
            }

        WakfuData(
            items = items.await(),
            jobs = jobs.await(),
            effects = effect.await(),
            itemTypes = itemTypes.await()
        )
    }
