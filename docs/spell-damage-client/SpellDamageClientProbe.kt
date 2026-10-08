package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.chosante.common.Spell
import me.chosante.common.SpellDamageScaling
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in research probe, attached only through probe.init.gradle. Never regenerates a game artifact. */
class SpellDamageClientProbe {
    @Test
    fun dump() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        val root = findRepositoryRoot()
        val out = File(root, "bdata-extractor/build/spell-damage-client").apply { mkdirs() }
        require(File(install, "lib/wakfu-client.jar").isFile) { "The research probe requires a local Wakfu install" }
        verifyEffectSchema(install)
        val spells = checkedTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
        val effects = checkedTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val states = checkedTable(install, Tables.STATE, Tables.STATE_SCHEMA)
        val resources = File(root, "autobuilder/src/main/resources")
        val encyclopedia = LENIENT_JSON.decodeFromString(ListSerializer(Spell.serializer()), File(resources, "spells.json").readText())
        val oracle = LENIENT_JSON.decodeFromString(ListSerializer(SpellDamageScaling.serializer()), File(resources, "spell-damage.json").readText())
        check(oracle.map { it.spellId }.toSet() == encyclopedia.filter { it.baseDamage != null }.map { it.id }.toSet())
        val oracleById = oracle.associateBy { it.spellId }
        val regenerated = buildSpellDamageScalings(spells, effects, encyclopedia).associateBy { it.spellId }
        val spellById = spells.records.associateBy { it["id"] as Int }
        val effectById = effects.records.associateBy { it["effect_id"] as Int }
        val children = effects.records.groupBy { it["parent_id"] as Int }

        fun graph(record: Map<String, Any?>?): List<Map<String, Any?>> {
            val seen = hashSetOf<Int>()
            val result = arrayListOf<Map<String, Any?>>()

            fun visit(
                id: Int,
                path: List<Int>,
            ) {
                if (!seen.add(id)) return
                val effect = effectById[id] ?: error("Missing referenced effect $id")
                result.add(effect + mapOf("depth" to path.size, "order" to result.size, "path" to (path + id)))
                children[id].orEmpty().forEach { visit(it["effect_id"] as Int, path + id) }
            }
            (record?.get("effect_ids") as? List<*>)?.forEach { visit(it as Int, emptyList()) }
            return result
        }

        val referencedStates = sortedSetOf<Int>()
        File(out, "spell-graphs.jsonl").bufferedWriter().use { writer ->
            for (spell in encyclopedia.filter { it.baseDamage != null }.sortedBy { it.id }) {
                val record = spellById[spell.id]
                val graph = graph(record)
                // Diagnostic state links only: no guess about runtime state level, ticks or expected damage.
                if (!oracleById.getValue(spell.id).matched) {
                    graph.filter { it["action_id"] == 304 }.forEach { effect ->
                        val params = effect["params"] as List<*>
                        if (params.size >= 2 && params[1] == 0f) referencedStates.add((params[0] as Float).toInt())
                    }
                }
                val row =
                    mapOf(
                        "spell" to LENIENT_JSON.encodeToJsonElement(Spell.serializer(), spell),
                        "record" to record,
                        "oracle" to LENIENT_JSON.encodeToJsonElement(SpellDamageScaling.serializer(), oracleById.getValue(spell.id)),
                        "regenerated" to LENIENT_JSON.encodeToJsonElement(SpellDamageScaling.serializer(), regenerated.getValue(spell.id)),
                        "effects" to graph
                    )
                writer.appendLine(asJson(row).toString())
            }
        }
        val stateById = states.records.associateBy { it["id"] as Int }
        File(out, "state-graphs.jsonl").bufferedWriter().use { writer ->
            for (id in referencedStates) {
                val record = stateById[id]
                writer.appendLine(asJson(mapOf("stateId" to id, "record" to record, "effects" to graph(record))).toString())
            }
        }
        val inputs =
            listOf(
                File(install, "lib/wakfu-client.jar"),
                File(install, "contents/bdata/66.jar"),
                File(install, "contents/bdata/67.jar"),
                File(install, "contents/bdata/68.jar"),
                File(resources, "spells.json"),
                File(resources, "spell-damage.json")
            )
        File(out, "fingerprints.txt").writeText(inputs.joinToString("\n", postfix = "\n") { "${sha256(it)}  ${it.path}" })
        println("Research dump: $out (${oracle.size} spells, ${referencedStates.size} referenced fallback states)")
    }

    private fun verifyEffectSchema(install: File) {
        // Stable enum anchors and explicit constructor id, never an obfuscated class name or ordinal.
        val typeId =
            ClientJar
                .load(install)
                .singleEnum("table-type", setOf("ITEM", "SPELL", "STATE", "STATIC_EFFECT", "MONSTER"))
                .constants
                .getValue("STATIC_EFFECT")
                .ints[1]
        check(typeId == Tables.STATIC_EFFECT) { "StaticEffect table id drift: $typeId" }
        val derived = SchemaGenerator.load(install).schemaFor(install, typeId)
        check(derived.map { it.type } == Tables.STATIC_EFFECT_SCHEMA.map { it.type }) { "StaticEffect schema drift: recheck semantic positions" }
        println("All ${derived.size} StaticEffect field types agree with the client-derived schema")
    }

    private fun checkedTable(
        install: File,
        id: Int,
        schema: List<Field>,
    ): Table {
        val table = loadTable(install, id, schema)
        check(table.records.zip(table.entries).all { (record, entry) -> (record[schema.first().name] as Int).toLong() == entry.id })
        println("Table $id: ${table.records.size} records, all size/id guards passed")
        return table
    }

    private fun asJson(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to asJson(it.value) })
            is List<*> -> JsonArray(value.map(::asJson))
            // Float.toString() would round 0.23999999463558197 to 0.24 and hide exact oracle differences.
            is Float -> JsonPrimitive(value.toDouble())
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
