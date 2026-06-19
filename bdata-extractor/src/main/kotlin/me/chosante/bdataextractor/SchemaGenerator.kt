package me.chosante.bdataextractor

import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.FieldModel
import java.lang.constant.ClassDesc
import java.lang.reflect.AccessFlag
import java.util.zip.ZipFile

/**
 * Derives a static-data table's positional [Field] schema **directly from the local client's JVM bytecode**
 * (`lib/wakfu-client.jar`) — a pure-JVM port of `jac3km4/wakfu-bdata-gen` built on JDK's `java.lang.classfile`
 * (finalized in JDK 24; **zero dependencies**). This removes hand-written / hand-reverse-engineered schemas:
 * on a client bump the layout is re-derived automatically, so a field added/reordered between versions (e.g.
 * the `Vec` Ankama inserted into the Monster record in 1.92.x) "just works" instead of silently desyncing.
 *
 * A table's layout is its binary-data class's **`protected` instance fields, in class-file order** (the wire
 * order). Field types map from the JVM descriptor: primitives + boxed wrappers → ints/floats/bool, `String`
 * → [FieldType.Str], arrays → [FieldType.Vec], a nested object → [FieldType.Struct] (recursed). The
 * binary-data classes are found structurally (the upstream heuristic): they implement an interface with
 * exactly three methods — `read(reader)` / `reset()` / `int typeId()`. The class for a given table id is the
 * one whose derived schema **cleanly decodes that table's `.bin`** (size-guard + id-alignment), so we don't
 * need the obfuscated type-id enum at all.
 *
 * Field NAMES in the jar are obfuscated, so callers that need specific fields address them by position with a
 * type assertion (see [monsterSchema]) — the byte layout is always correct; only the semantic naming is
 * pinned. NOTE: generic `HashMap` fields (only the Spell table uses them) are not yet modeled here; those
 * tables keep their hand-written [Tables] schemas for now.
 */
class SchemaGenerator private constructor(
    private val classes: Map<String, ByteArray>,
    private val implementors: List<String>,
) {
    private val schemaCache = HashMap<Int, List<Field>>()

    private fun parse(internalName: String): ClassModel = ClassFile.of().parse(classes[internalName] ?: error("class '$internalName' not in client jar"))

    /** The `protected` instance fields of [internalName], in class-file (wire) order, as decode [Field]s. */
    private fun deriveFields(
        internalName: String,
        depth: Int,
    ): List<Field> {
        require(depth < 12) { "binary-data struct recursion too deep at $internalName" }
        return parse(internalName)
            .fields()
            .filter { it.flags().has(AccessFlag.PROTECTED) && !it.flags().has(AccessFlag.STATIC) }
            .map { f -> Field(f.fieldName().stringValue(), typeOf(f, depth)) }
    }

    private fun typeOf(
        field: FieldModel,
        depth: Int,
    ): FieldType = descToType(field.fieldTypeSymbol(), depth)

    private fun descToType(
        cd: ClassDesc,
        depth: Int,
    ): FieldType =
        when {
            cd.isArray -> FieldType.Vec(descToType(cd.componentType(), depth))
            cd.isPrimitive ->
                when (cd.descriptorString()) {
                    "Z" -> FieldType.Bool
                    "B", "C" -> FieldType.I8
                    "S" -> FieldType.I16
                    "I" -> FieldType.I32
                    "J" -> FieldType.I64
                    "F" -> FieldType.F32
                    "D" -> FieldType.F64
                    else -> error("unexpected primitive ${cd.descriptorString()}")
                }
            else ->
                when (val d = cd.descriptorString()) {
                    "Ljava/lang/Boolean;" -> FieldType.Bool
                    "Ljava/lang/Byte;", "Ljava/lang/Character;" -> FieldType.I8
                    "Ljava/lang/Short;" -> FieldType.I16
                    "Ljava/lang/Integer;" -> FieldType.I32
                    "Ljava/lang/Long;" -> FieldType.I64
                    "Ljava/lang/Float;" -> FieldType.F32
                    "Ljava/lang/Double;" -> FieldType.F64
                    "Ljava/lang/String;" -> FieldType.Str
                    "Ljava/util/HashMap;" ->
                        error("HashMap field needs generic-signature parsing — not yet supported (Spell table only)")
                    else -> FieldType.Struct(deriveFields(d.removePrefix("L").removeSuffix(";"), depth + 1))
                }
        }

    /** Derived schema for [typeId]: the implementor whose layout cleanly decodes that table's `.bin`. Cached. */
    fun schemaFor(
        install: File,
        typeId: Int,
    ): List<Field> =
        schemaCache.getOrPut(typeId) {
            implementors
                .asSequence()
                .mapNotNull { runCatching { deriveFields(it, 0) }.getOrNull() }
                .firstOrNull { decodesCleanly(install, typeId, it) }
                ?: error("no binary-data class produced a schema that decodes table $typeId")
        }

    /**
     * Monster (42) schema with the boss-mode fields renamed by their stable positions so [buildMonsters] can
     * read them by name. The head (id..resistances) is stable; `gfx` is the 3rd-from-last field. Each renamed
     * position is type-asserted, so a future head/tail drift fails loudly here rather than mislabeling a field.
     */
    fun monsterSchema(install: File): List<Field> {
        val raw = schemaFor(install, Tables.MONSTER)
        val n = raw.size
        val names =
            mapOf(
                0 to "id",
                1 to "family_id",
                2 to "level_min",
                3 to "level_max",
                6 to "base_hp",
                39 to "base_fire_resistance",
                40 to "base_water_resistance",
                41 to "base_earth_resistance",
                42 to "base_wind_resistance",
                n - 3 to "gfx"
            )

        fun assertType(
            i: Int,
            expected: FieldType,
        ) = require(i in raw.indices && raw[i].type == expected) {
            "Monster schema drift: field $i expected $expected but derived ${raw.getOrNull(i)?.type} — re-check the position map."
        }
        listOf(0, 1, 6, 39, 40, 41, 42, n - 3).forEach { assertType(it, FieldType.I32) }
        assertType(2, FieldType.I16)
        assertType(3, FieldType.I16)
        return raw.mapIndexed { i, f -> names[i]?.let { Field(it, f.type) } ?: f }
    }

    companion object {
        fun load(install: File): SchemaGenerator {
            val classes =
                ZipFile(File(install, "lib/wakfu-client.jar")).use { zf ->
                    zf
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .associate { it.name.removeSuffix(".class") to zf.getInputStream(it).readBytes() }
                }
            val dataInterface =
                classes.entries.firstNotNullOfOrNull { (name, bytes) ->
                    val m = runCatching { ClassFile.of().parse(bytes) }.getOrNull() ?: return@firstNotNullOfOrNull null
                    if (m.flags().has(AccessFlag.INTERFACE) && m.methods().size == 3 && isBinaryDataInterface(m)) name else null
                } ?: error("binary-data interface (read/reset/typeId) not found in client jar")
            val implementors =
                classes.entries.mapNotNull { (name, bytes) ->
                    val m = runCatching { ClassFile.of().parse(bytes) }.getOrNull() ?: return@mapNotNull null
                    if (m.interfaces().any { it.asInternalName() == dataInterface }) name else null
                }
            return SchemaGenerator(classes, implementors)
        }

        /** The interface has exactly the three binary-data methods: `read(ref)` / `reset()` / `int typeId()`. */
        private fun isBinaryDataInterface(m: ClassModel): Boolean {
            val sigs =
                m.methods().map { method ->
                    val t = method.methodTypeSymbol()
                    Triple(
                        t.returnType().descriptorString(),
                        t.parameterCount(),
                        (0 until t.parameterCount()).map { t.parameterType(it) }
                    )
                }
            val read = sigs.any { (ret, n, ps) -> ret == "V" && n == 1 && !ps[0].isPrimitive && !ps[0].isArray }
            val reset = sigs.any { (ret, n, _) -> ret == "V" && n == 0 }
            val typeId = sigs.any { (ret, n, _) -> ret == "I" && n == 0 }
            return read && reset && typeId
        }
    }
}
