package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.EquipmentPositions
import me.chosante.common.ItemType
import java.io.File
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.LoadInstruction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** No obfuscated names: enum constant anchors identify the raw-position enum, not the smaller UI-position enum. */
internal fun buildEquipmentPositions(
    install: File,
    typesJson: String,
): EquipmentPositions {
    val jar = ClientJar.load(install)
    val positions = jar.singleEnum("equipment raw position", setOf("HEAD", "CHEST", "LEFT_HAND", "RIGHT_HAND", "FIRST_WEAPON", "BAG_1", "HAIR"))
    val model = requireNotNull(jar.model(positions.name))
    val constructor =
        model.methods().singleOrNull {
            it.methodName().stringValue() == "<init>" &&
                it.methodTypeSymbol().descriptorString() == "(Ljava/lang/String;IILjava/lang/String;Ljava/lang/String;I)V"
        } ?: error("Equipment raw-position constructor shape drift")
    val code = constructor.code().orElseThrow().elementList()
    val stores =
        code.withIndex().filter { (_, el) ->
            el is FieldInstruction && el.opcode() == Opcode.PUTFIELD && el.typeSymbol().descriptorString() == "B"
        }
    check(stores.size == 1) { "Equipment raw-position byte field drift" }
    val storeIndex = stores.single().index
    check(
        code
            .take(storeIndex)
            .takeLast(3)
            .filterIsInstance<LoadInstruction>()
            .any { it.slot() == 3 }
    ) {
        "Equipment raw id is no longer constructor argument 3 (name, ordinal, rawId)"
    }
    // Constructor (String name, int ordinal, int rawId, String label, String background[, int itemType]).
    // ClientJar collects literal integer arguments in order: read the explicit id, never the ordinal.
    val ids =
        positions.constants.mapValues { (name, constant) ->
            check(constant.ints.size in 2..3) { "Equipment position constructor drift at $name: ${constant.ints}" }
            constant.ints[1].also { check(it in 0..127) { "Equipment position id out of byte range: $name=$it" } }
        }
    check(ids.values.distinct().size == ids.size) { "Duplicate raw equipment position ids" }
    val definitions = Json.parseToJsonElement(typesJson).jsonArray.map { it.jsonObject.getValue("definition").jsonObject }
    val items =
        ItemType.entries.associateWith { item ->
            val definition = definitions.single { it.getValue("id").jsonPrimitive.int == item.id }
            definition
                .getValue("equipmentPositions")
                .jsonArray
                .map { position ->
                    position.jsonPrimitive.content.also { check(it in ids) { "CDN position $it absent from client enum" } }
                }.also { check(it.isNotEmpty() && it.distinct().size == it.size) { "Invalid occupied positions for $item" } }
        }
    return EquipmentPositions(ids, items)
}

internal fun fetchEquipmentTypes(version: String): String =
    HttpClient.newHttpClient().use { client ->
        val url = "https://wakfu.cdn.ankama.com/gamedata/$version/equipmentItemTypes.json"
        val response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "GET $url -> HTTP ${response.statusCode()}" }
        response.body()
    }
