package me.chosante.autobuilder.zenithbuilder

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
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.CharacterClass
import me.chosante.autobuilder.domain.CharacterClass.CRA
import me.chosante.autobuilder.domain.CharacterClass.ECAFLIP
import me.chosante.autobuilder.domain.CharacterClass.ELIOTROPE
import me.chosante.autobuilder.domain.CharacterClass.ENIRIPSA
import me.chosante.autobuilder.domain.CharacterClass.ENUTROF
import me.chosante.autobuilder.domain.CharacterClass.FECA
import me.chosante.autobuilder.domain.CharacterClass.HUPPERMAGE
import me.chosante.autobuilder.domain.CharacterClass.IOP
import me.chosante.autobuilder.domain.CharacterClass.OSAMODAS
import me.chosante.autobuilder.domain.CharacterClass.OUGINAK
import me.chosante.autobuilder.domain.CharacterClass.PANDAWA
import me.chosante.autobuilder.domain.CharacterClass.ROUBLARD
import me.chosante.autobuilder.domain.CharacterClass.SACRIEUR
import me.chosante.autobuilder.domain.CharacterClass.SADIDA
import me.chosante.autobuilder.domain.CharacterClass.SRAM
import me.chosante.autobuilder.domain.CharacterClass.STEAMER
import me.chosante.autobuilder.domain.CharacterClass.UNKNOWN
import me.chosante.autobuilder.domain.CharacterClass.XELOR
import me.chosante.autobuilder.domain.CharacterClass.ZOBAL

data class ZenithBuild(
    val id: Long,
    private val linkId: String,
) {
    val link
        get() = "https://zenithwakfu.com/builder/$linkId"
}

val CHARACTER_CLASS_TO_ZENITH_JOB_ID =
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

suspend fun createBuild(character: Character): ZenithBuild {
    val body = buildJsonObject {
        put("name", "${character.clazz.name}-${character.level}")
        put("level", character.level)
        put("id_job", character.clazz.zenithBuilderJobId)
        put("is_visible", false)
        putJsonArray("flags") {}
    }

    val (_, _, result) = "$baseAPIUrl/create"
        .httpPost()
        .header(apiZenithWakfuHeaders)
        .jsonBody(Json.encodeToString<JsonObject>(body))
        .awaitObjectResponse(kotlinxDeserializerOf<JsonElement>())

    val link = result.jsonObject.getValue("link").jsonPrimitive.content
    val (_, _, getBuildJsonObject) = "$baseAPIUrl/build/$link"
        .httpGet()
        .header(apiZenithWakfuHeaders)
        .awaitObjectResponse(kotlinxDeserializerOf<JsonObject>())

    val buildId = getBuildJsonObject.getValue("id_build").jsonPrimitive.long

    return ZenithBuild(id = buildId, linkId = link)
}
