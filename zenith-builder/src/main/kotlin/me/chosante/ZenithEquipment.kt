package me.chosante

import com.github.kittinunf.fuel.core.awaitUnit
import com.github.kittinunf.fuel.core.extensions.jsonBody
import com.github.kittinunf.fuel.coroutines.awaitObjectResponse
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.serialization.kotlinxDeserializerOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.chosante.common.Equipment
import me.chosante.common.ItemType

private const val urlAddEquipment = "$baseAPIUrl/equipment/add"
private const val urlGetEquipments = "$baseAPIUrl/equipment"

private val elements =
    buildJsonArray {
        addJsonObject {
            put("id_element", 1)
            put("id_inner_stats", 122)
            put("image_element", "fire.png")
        }
        addJsonObject {
            put("id_element", 2)
            put("id_inner_stats", 124)
            put("image_element", "water.png")
        }
        addJsonObject {
            put("id_element", 3)
            put("id_inner_stats", 123)
            put("image_element", "earth.png")
        }
        addJsonObject {
            put("id_element", 4)
            put("id_inner_stats", 125)
            put("image_element", "wind.png")
        }
    }

internal suspend fun addEquipment(
    equipment: Equipment,
    buildId: Long,
    sideValue: Int,
) {
    val itemTypesZenithId =
        if (equipment.itemType == ItemType.OFF_HAND_WEAPONS) {
            listOf(equipment.itemType.id, 189) // 189 equals shield type
        } else {
            listOf(equipment.itemType.id)
        }
    val parameters =
        listOf(
            "maxLvl" to equipment.level,
            "name" to equipment.name.fr
        ) + itemTypesZenithId.map { "type[]" to it }

    val (request, _, result) =
        urlGetEquipments
            .httpGet(parameters = parameters)
            .header(apiZenithWakfuHeaders)
            .awaitObjectResponse(kotlinxDeserializerOf<JsonArray>())

    val equipmentInformation =
        try {
            result.first { it.jsonObject["id_equipment"]?.jsonPrimitive?.int == equipment.equipmentId }.jsonObject
        } catch (_: Exception) {
            println(
                "Problem with the equipment: (id: ${equipment.equipmentId} name: ${equipment.name}), when getting the information from zenithwakfu" +
                    " via the url: ${request.url}"
            )
            return
        }

    val effects = equipmentInformation["effects"]!!.jsonArray.toMutableList()
    for (i in effects.indices) {
        val obj = effects[i].jsonObject
        if (obj["id_effect"]?.jsonPrimitive?.int == 345474) {
            val effectValues = (obj["values"] as JsonArray)[0].jsonObject.toMutableMap()
            val randomNumber = effectValues.getValue("random_number").jsonPrimitive.int
            effectValues["elements"] = JsonArray(elements.take(randomNumber))
            effects[i] = JsonObject(obj + ("values" to JsonArray(listOf(JsonObject(effectValues)))))
        }
    }

    val equipmentInformationBody =
        JsonObject(
            equipmentInformation + (
                "metadata" to
                    buildJsonObject {
                        put("side", sideValue)
                    }
            ) + (
                "effects" to
                    JsonArray(
                        effects
                    )
            )
        )
    val jsonPayloadAddEquipment: JsonObject =
        buildJsonObject {
            put("equipment", equipmentInformationBody)
            put("id_build", buildId)
        }

    urlAddEquipment
        .httpPost()
        .header(apiZenithWakfuHeaders)
        .jsonBody(Json.encodeToString<JsonObject>(jsonPayloadAddEquipment))
        .awaitUnit()
}
