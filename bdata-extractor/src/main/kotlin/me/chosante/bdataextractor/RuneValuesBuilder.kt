package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.RuneValues
import java.io.File
import kotlin.math.floor

/** The leveling curve is an XP cost curve. Stat formulas are the equip effects, with level bands only in bdata. */
internal fun buildRuneValues(
    install: File,
    itemsJson: String,
    actions: ActionCatalog,
): RuneValues {
    val definitions = Json.parseToJsonElement(itemsJson).jsonArray.map { it.jsonObject.getValue("definition").jsonObject }
    val runes = ItemsCatalog.parseRunes(itemsJson, actions)
    val runeIds = runes.runes.map { it.id }.toSet()
    val selectedIds =
        definitions
            .filter {
                it
                    .getValue("item")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.int in runeIds
            }.flatMap { it.getValue("equipEffects").jsonArray }
            .map {
                it.jsonObject
                    .getValue("effect")
                    .jsonObject
                    .getValue("definition")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.int
            }.toSet()
    val typeId = runeEffectTableId(install)
    val schema = SchemaGenerator.load(install).schemaFor(install, typeId)
    // Full schema is client-derived; semantic positions are type-asserted against the existing decoder.
    check(schema.map { it.type } == Tables.STATIC_EFFECT_SCHEMA.map { it.type }) { "StaticEffect schema drift — recheck rune field positions" }
    val names = schema.map { it.name }
    val effects = loadSelectedTableRecords(install, typeId, schema, selectedIds)
    val values =
        runes.runes.associate { rune ->
            val item =
                definitions.single {
                    it
                        .getValue("item")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.int == rune.id
                }
            val runeEffects =
                item.getValue("equipEffects").jsonArray.map { wrapper ->
                    val cdn =
                        wrapper.jsonObject
                            .getValue("effect")
                            .jsonObject
                            .getValue("definition")
                            .jsonObject
                    val effect = effects.getValue(cdn.getValue("id").jsonPrimitive.int)
                    check(effect.getValue(names[1]) == cdn.getValue("actionId").jsonPrimitive.int) { "Rune ${rune.id} action differs between CDN and client" }
                    check(actions.kind(effect.getValue(names[1]) as Int) == ActionKind.Stat(rune.characteristic, 1)) { "Mixed rune effects on ${rune.id}" }
                    val params = (effect.getValue(names[25]) as List<*>).map { (it as Float).toDouble() }
                    check(params == cdn.getValue("params").jsonArray.map { it.jsonPrimitive.double }) { "Rune ${rune.id} parameters differ between CDN and client" }
                    check(
                        effect.getValue(names[2]) == rune.id &&
                            (effect.getValue(names[16]) as String).trim() == "NORMAL" &&
                            (effect.getValue(names[41]) as String).trim() == "ITEM_EQUIP" &&
                            effect.getValue(names[42]) == "ITEM_EQUIP" &&
                            effect.getValue(names[49]) == 0
                    ) { "Rune ${rune.id} effect is no longer a normal unscripted item equip effect" }
                    check(effect.getValue(names[40]) == "") { "Conditional rune effect on ${rune.id}" }
                    Triple(effect.getValue(names[38]) as Int, effect.getValue(names[39]) as Int, params)
                }
            rune.characteristic to
                (1..runes.levelRequirements.size).map { level ->
                    val (_, _, params) =
                        runeEffects.singleOrNull { (min, max, _) -> level in min..max }
                            ?: error("Rune ${rune.id}: expected exactly one effect at level $level")
                    floor(params[0] + params[1] * level).toInt()
                }
        }
    check(values.size == runes.runes.size) { "Duplicate rune characteristics" }
    return RuneValues(values)
}

// Keep ClientJar's parsed bytecode out of the live set when the second schema scan and full decode begin.
private fun runeEffectTableId(install: File): Int {
    val jar = ClientJar.load(install)
    jar.requireRuneFloorBeforeDoubling()
    val types = jar.singleEnum("table-type", setOf("ITEM", "SPELL", "STATE", "STATIC_EFFECT", "MONSTER"))
    val typeId = types.constants.getValue("STATIC_EFFECT").ints[1]
    check(typeId == Tables.STATIC_EFFECT) { "StaticEffect table id changed: $typeId" }
    return typeId
}
