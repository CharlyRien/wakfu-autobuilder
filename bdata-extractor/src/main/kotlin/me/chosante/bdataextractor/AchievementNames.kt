package me.chosante.bdataextractor

import me.chosante.common.I18nText
import me.chosante.common.ItemEquipCriterion
import java.io.File

/** Only achievement names used by equip conditions. No client class/field names or table/namespace ids are pinned. */
internal object AchievementNames {
    fun referencedIds(criteria: List<ItemEquipCriterion>): Set<Int> =
        criteria
            .flatMap { it.playerState }
            .filter { it.function == "IsAchievementComplete" }
            .mapTo(sortedSetOf()) { atom ->
                check(atom.args.size == 1) { "Achievement criterion has ${atom.args.size} arguments: $atom" }
                atom.args
                    .single()
                    .toIntOrNull()
                    ?.takeIf { it > 0 } ?: error("Invalid achievement id: $atom")
            }

    fun build(
        install: File,
        criteria: List<ItemEquipCriterion>,
    ): Map<Int, I18nText> {
        val jar = ClientJar.load(install)
        val types = jar.singleEnum("table-type", setOf("ACHIEVEMENT", "ITEM", "ITEM_SET", "MONSTER", "SPELL", "STATE"))
        val constant = types.constants.getValue("ACHIEVEMENT")
        val tableId = constant.ints.getOrNull(1)?.takeIf { it > 0 } ?: error("ACHIEVEMENT table id missing: ${constant.ints}")
        check(jar.binaryDataClassesOf(types, constant.field).isNotEmpty()) { "No binary-data class for ACHIEVEMENT" }
        val schema = SchemaGenerator.load(install).schemaFor(install, tableId)
        check(schema.firstOrNull()?.type == FieldType.I32) { "Achievement schema drift: first field is not the id" }
        val table = loadTable(install, tableId, schema) // size guard on EVERY record, not just the schema probe
        check(table.entries.isNotEmpty()) { "Empty Achievement table" }
        val idField = schema.first().name
        table.entries.zip(table.records).forEach { (entry, record) ->
            check((record[idField] as? Int)?.toLong() == entry.id) { "Achievement record id differs from index id ${entry.id}" }
        }
        val ids = table.entries.map { it.id.toInt() }.toSet()
        val namespace = jar.achievementNameNamespace()
        val i18n = I18nBundle.load(install, setOf(namespace))
        return referencedIds(criteria).associateWith { id ->
            check(id in ids) { "Referenced achievement $id is missing from table $tableId" }
            i18n.requireText(namespace, id)
        }
    }
}
