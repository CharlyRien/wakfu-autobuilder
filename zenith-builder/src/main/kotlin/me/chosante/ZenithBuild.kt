package me.chosante

import com.github.kittinunf.fuel.core.extensions.jsonBody
import com.github.kittinunf.fuel.coroutines.awaitObjectResponse
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.serialization.kotlinxDeserializerOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.CharacterClass.CRA
import me.chosante.common.CharacterClass.ECAFLIP
import me.chosante.common.CharacterClass.ELIOTROPE
import me.chosante.common.CharacterClass.ENIRIPSA
import me.chosante.common.CharacterClass.ENUTROF
import me.chosante.common.CharacterClass.FECA
import me.chosante.common.CharacterClass.HUPPERMAGE
import me.chosante.common.CharacterClass.IOP
import me.chosante.common.CharacterClass.OSAMODAS
import me.chosante.common.CharacterClass.OUGINAK
import me.chosante.common.CharacterClass.PANDAWA
import me.chosante.common.CharacterClass.ROUBLARD
import me.chosante.common.CharacterClass.SACRIEUR
import me.chosante.common.CharacterClass.SADIDA
import me.chosante.common.CharacterClass.SRAM
import me.chosante.common.CharacterClass.STEAMER
import me.chosante.common.CharacterClass.UNKNOWN
import me.chosante.common.CharacterClass.XELOR
import me.chosante.common.CharacterClass.ZOBAL

internal data class ZenithBuild(
    val id: Long,
    private val linkId: String,
) {
    val link
        get() = "https://zenithwakfu.com/builder/$linkId"
}

private val CHARACTER_CLASS_TO_ZENITH_JOB_ID =
    mapOf(
        FECA to 1,
        OSAMODAS to 2,
        ENUTROF to 3,
        SRAM to 4,
        XELOR to 5,
        ECAFLIP to 6,
        ENIRIPSA to 7,
        IOP to 8,
        CRA to 9,
        SADIDA to 10,
        SACRIEUR to 11,
        PANDAWA to 12,
        ROUBLARD to 13,
        ZOBAL to 14,
        OUGINAK to 15,
        STEAMER to 16,
        ELIOTROPE to 18,
        HUPPERMAGE to 19,
        UNKNOWN to 1
    )

private val CharacterClass.zenithBuilderJobId: Int
    get() = CHARACTER_CLASS_TO_ZENITH_JOB_ID.getValue(this)

internal suspend fun createBuild(character: Character): ZenithBuild {
    val body =
        buildJsonObject {
            put("name", "${character.clazz.name}-${character.level}")
            put("level", character.level)
            put("id_job", character.clazz.zenithBuilderJobId)
            put("is_visible", false)
            putJsonArray("flags") {}
        }

    val (_, _, result) =
        "$baseAPIUrl/create"
            .httpPost()
            .header(apiZenithWakfuHeaders)
            .jsonBody(Json.encodeToString<JsonObject>(body))
            .awaitObjectResponse(kotlinxDeserializerOf<JsonElement>())

    val link =
        result.jsonObject
            .getValue("link")
            .jsonPrimitive.content
    val (_, _, getBuildJsonObject) =
        "$baseAPIUrl/build/$link"
            .httpGet()
            .header(apiZenithWakfuHeaders)
            .awaitObjectResponse(kotlinxDeserializerOf<JsonObject>())

    val buildId = getBuildJsonObject.getValue("id_build").jsonPrimitive.long

    return ZenithBuild(id = buildId, linkId = link)
}
