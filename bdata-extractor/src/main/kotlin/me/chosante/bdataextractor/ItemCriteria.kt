package me.chosante.bdataextractor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.common.ItemEquipCriterion
import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.MethodModel
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.reflect.AccessFlag
import java.util.zip.ZipFile

/**
 * Item EQUIP criteria (`item-criteria.json`): the conditions the game checks before an item may be worn — "the
 * Brâkmar sword needs the Brâkmar ring", "class emblems are for their class only", "these rings exclude each
 * other" — decoded from the local client's Item table and typed by [classifyEquipCriterion].
 *
 * Everything is found STRUCTURALLY in the client bytecode (`lib/wakfu-client.jar`), never by an obfuscated name —
 * the same principle as [SchemaGenerator]:
 *  - the **table id**: the client's table-type enum (the `Enum` whose constants include `ITEM`, `ITEM_SET`,
 *    `MONSTER`, `SPELL`, `STATE`) maps its `ITEM` constant to the numeric id (35 in 1.93);
 *  - the **record prefix**: the binary-data classes whose `typeId()` returns that `ITEM` constant are read in their
 *    `read(reader)` method — every reader call, in order, up to and including the first `String[]` read, which is
 *    the criteria list (`i32 id, i16 itemSetId, i32 gfxId, i32 femaleGfxId, i32, i16 level, String[] criteria` in
 *    1.93). Only that prefix is decoded, each record on its own through its index entry's offset + seed
 *    ([BinaryDecoder.seekRecord]), so a field Ankama adds AFTER the criteria never breaks this decode;
 *  - the **criterion kinds**: the `Enum` holding `EQUIP`, `USE_IN_FIGHT` and `PICK_UP` (USE, USE_IN_FIGHT, EQUIP,
 *    DROP, EXCHANGE, CRAFT, PICK_UP, DELETE in 1.93); the criteria list alternates (kind name, expression);
 *  - the **breeds**: the `Enum` holding the class names (`FECA`, `SACRIER`, `HUPPERMAGE`…) maps each to its breed id.
 *
 * Guards — each fails the extraction loudly: the record's first field equals its index id for EVERY record, the
 * criteria list has an even length, every kind is a constant of the kind enum, a record has at most one EQUIP entry,
 * and a pool item's EQUIP expression is typed by [classifyEquipCriterion] (which rejects unknown functions / shapes).
 */
internal object ItemCriteria {
    /** The criterion kind the engine reads. */
    const val EQUIP_KIND = "EQUIP"

    private val TABLE_TYPE_ANCHORS = setOf("ITEM", "ITEM_SET", "MONSTER", "SPELL", "STATE")
    private val KIND_ANCHORS = setOf(EQUIP_KIND, "USE_IN_FIGHT", "PICK_UP")
    private val BREED_ANCHORS = setOf("FECA", "OSAMODAS", "SACRIER", "ELIOTROPE", "HUPPERMAGE", "OUGINAK")

    /** What the bytecode says about the Item table: its id, its record prefix candidates, the kinds and the breeds. */
    class Layout(
        val tableId: Int,
        /** One prefix per distinct `read()` layout of the ITEM classes (two classes, one prefix, in 1.93). */
        val prefixes: List<List<Field>>,
        val kinds: Set<String>,
        val breedIds: Map<String, Int>,
    )

    /** Reads the [Layout] from the client jar of [install]. */
    fun layout(install: File): Layout {
        val jar = ClientJar.load(install)
        val tableEnum = jar.singleEnum("table-type", TABLE_TYPE_ANCHORS)
        val item = tableEnum.constants.getValue("ITEM")
        val tableId = item.ints.getOrNull(1) ?: error("table-type enum ${tableEnum.name}: the ITEM constant carries no id (${item.ints})")
        val itemClasses = jar.binaryDataClassesOf(tableEnum, item.field)
        require(itemClasses.isNotEmpty()) { "no binary-data class returns the table-type ITEM constant" }
        val prefixes = itemClasses.map { jar.criteriaPrefix(it) }.distinct()
        val kinds = jar.singleEnum("criterion-kind", KIND_ANCHORS).constants.keys
        val breedIds =
            jar
                .singleEnum("breed", BREED_ANCHORS)
                .constants
                .mapNotNull { (name, c) -> c.ints.getOrNull(1)?.let { name to it } }
                .toMap()
        return Layout(tableId, prefixes, kinds, breedIds)
    }

    /**
     * Item id → its EQUIP expression, for every record of the Item table whose criteria list has an EQUIP entry. The
     * first [Layout.prefixes] candidate that passes every guard wins; none passing fails with each one's reason.
     */
    fun decodeEquipExpressions(
        install: File,
        layout: Layout,
    ): Map<Int, String> {
        val bytes = readZipEntryBytes(File(install, "contents/bdata/${layout.tableId}.jar")) { it.endsWith(".bin") }
        val failures = mutableListOf<String>()
        for (prefix in layout.prefixes) {
            try {
                return decodeWith(bytes, layout, prefix)
            } catch (e: IllegalStateException) {
                failures += "${prefix.map { it.type }}: ${e.message}"
            }
        }
        error("Item table ${layout.tableId}: no ITEM record prefix decodes cleanly —\n  " + failures.joinToString("\n  "))
    }

    private fun decodeWith(
        bytes: ByteArray,
        layout: Layout,
        prefix: List<Field>,
    ): Map<Int, String> {
        val d = BinaryDecoder.create(bytes, layout.tableId)
        val entries = d.readIndex()
        d.reset(layout.tableId)
        val idField = prefix.first().name
        val criteriaField = prefix.last().name
        val out = LinkedHashMap<Int, String>()
        var idMismatches = 0
        for (entry in entries) {
            d.seekRecord(entry.offset, entry.seed)
            val record = d.readRecord(prefix)
            if ((record[idField] as? Int)?.toLong() != entry.id) {
                idMismatches++
                continue
            }

            @Suppress("UNCHECKED_CAST")
            val criteria = record[criteriaField] as List<String>
            check(criteria.size % 2 == 0) { "record ${entry.id}: odd criteria list (${criteria.size} strings) — not (kind, expression) pairs" }
            val equip = mutableListOf<String>()
            for (i in criteria.indices step 2) {
                val kind = criteria[i]
                check(kind in layout.kinds) { "record ${entry.id}: unknown criterion kind '$kind' (known: ${layout.kinds})" }
                if (kind == EQUIP_KIND) equip += criteria[i + 1]
            }
            check(equip.size <= 1) { "record ${entry.id}: ${equip.size} EQUIP criteria" }
            equip.singleOrNull()?.let { out[entry.id.toInt()] = it }
        }
        check(idMismatches == 0) { "$idMismatches of ${entries.size} records' first field differs from their index id" }
        check(entries.isNotEmpty()) { "empty Item table" }
        return out
    }

    /**
     * The typed EQUIP criteria of the [poolIds] items (`equipments.json`), sorted by item id. An expression that types
     * to nothing (a lone `True`) is left out.
     */
    fun build(
        expressions: Map<Int, String>,
        poolIds: Set<Int>,
        breedIds: Map<String, Int>,
    ): List<ItemEquipCriterion> =
        expressions
            .filterKeys { it in poolIds }
            .toSortedMap()
            .map { (id, raw) -> classifyEquipCriterion(id, raw, breedIds) }
            .filter { it.constrainsBuild || it.uniqueEquipped || it.statGates.isNotEmpty() || it.playerState.isNotEmpty() }

    /** The item ids of a committed `equipments.json`. */
    fun poolIds(equipmentsJson: File): Set<Int> {
        require(equipmentsJson.isFile) { "Missing $equipmentsJson — run :equipments-extractor first." }
        return Json
            .parseToJsonElement(equipmentsJson.readText())
            .jsonArray
            .mapTo(HashSet()) {
                it.jsonObject
                    .getValue("equipmentId")
                    .jsonPrimitive.int
            }
    }
}

/** The classes of the client jar, parsed on demand, with the few structural queries [ItemCriteria] needs. */
internal class ClientJar(
    private val bytes: Map<String, ByteArray>,
) {
    private val models = HashMap<String, ClassModel?>()

    fun model(name: String): ClassModel? = models.getOrPut(name) { bytes[name]?.let { runCatching { ClassFile.of().parse(it) }.getOrNull() } }

    private val all: List<Pair<String, ClassModel>> by lazy { bytes.keys.sorted().mapNotNull { n -> model(n)?.let { n to it } } }

    /** The name lookup belongs to the achievement UI model, identified by its public field keys, not its class name. */
    fun achievementNameNamespace(): Int {
        val anchors = setOf("achievementId", "isCompleted", "isFollowed")
        val methods =
            all.mapNotNull { (_, m) ->
                val strings =
                    m
                        .methods()
                        .flatMap { method ->
                            method
                                .code()
                                .map { code ->
                                    code.elementList().filterIsInstance<ConstantInstruction>().mapNotNull {
                                        val value: Any = it.constantValue()
                                        value as? String
                                    }
                                }.orElse(emptyList())
                        }.toSet()
                if (!strings.containsAll(anchors)) return@mapNotNull null
                m.methods().singleOrNull { it.methodName().stringValue() == "getName" && it.methodTypeSymbol().descriptorString() == "()Ljava/lang/String;" }
            }
        val method = methods.singleOrNull() ?: error("achievement name lookup drift: expected one model with $anchors and getName(), found ${methods.size}")
        val code = method.code().orElseThrow().elementList()
        check(code.filterIsInstance<InvokeInstruction>().any { it.typeSymbol().descriptorString() == "(IJ[Ljava/lang/Object;)Ljava/lang/String;" }) {
            "achievement name lookup drift: no namespace + id + arguments string lookup"
        }
        val namespaces =
            code
                .filterIsInstance<ConstantInstruction>()
                .mapNotNull {
                    val value: Any = it.constantValue()
                    value as? Int
                }.filter { it > 0 }
                .distinct()
        return namespaces.singleOrNull() ?: error("achievement name lookup drift: ambiguous namespace constants $namespaces")
    }

    /** An enum constant: its static [field] name and the int arguments of its constructor call (ordinal first). */
    class EnumConstant(
        val field: String,
        val ints: List<Int>,
    )

    class EnumInfo(
        val name: String,
        val constants: Map<String, EnumConstant>,
    )

    /** The ONE `Enum` subclass whose constant names include every [anchors] name. */
    fun singleEnum(
        label: String,
        anchors: Set<String>,
    ): EnumInfo {
        val matches =
            all
                .filter { (_, m) -> m.superclass().map { it.asInternalName() == "java/lang/Enum" }.orElse(false) }
                .map { (n, m) -> EnumInfo(n, enumConstants(m)) }
                .filter { it.constants.keys.containsAll(anchors) }
        return matches.singleOrNull()
            ?: error("expected exactly one $label enum holding $anchors in the client jar, found ${matches.map { it.name }}")
    }

    /**
     * Walks [m]'s `<clinit>`: each `new <enum or subclass>; dup; ldc "NAME"; <ints…>; invokespecial <init>; putstatic`
     * is one constant — its name (the first string), its constructor ints and the static field it is stored in.
     */
    private fun enumConstants(m: ClassModel): Map<String, EnumConstant> {
        val self = m.thisClass().asInternalName()
        val code =
            m
                .methods()
                .firstOrNull { it.methodName().stringValue() == "<clinit>" }
                ?.code()
                ?.orElse(null) ?: return emptyMap()
        val out = LinkedHashMap<String, EnumConstant>()
        var building: String? = null
        var name: String? = null
        val ints = ArrayList<Int>()
        var pending: Pair<String, List<Int>>? = null
        for (element in code.elementList()) {
            when (element) {
                is NewObjectInstruction -> {
                    val cls = element.className().asInternalName()
                    if (cls == self || model(cls)?.superclass()?.map { it.asInternalName() == self }?.orElse(false) == true) {
                        building = cls
                        name = null
                        ints.clear()
                    }
                }
                is ConstantInstruction ->
                    if (building != null) {
                        when (val v: Any = element.constantValue()) {
                            is String -> if (name == null) name = v
                            is Int -> ints.add(v)
                            else -> {}
                        }
                    }
                is InvokeInstruction ->
                    if (building != null && element.name().stringValue() == "<init>" && element.owner().asInternalName() == building) {
                        name?.let { pending = it to ints.toList() }
                        building = null
                    }
                is FieldInstruction ->
                    if (element.opcode() == Opcode.PUTSTATIC && element.owner().asInternalName() == self) {
                        pending?.let { (n, i) -> out[n] = EnumConstant(element.name().stringValue(), i) }
                        pending = null
                    }
                else -> {}
            }
        }
        return out
    }

    /** The binary-data interface: exactly three methods — `read(reader)`, `reset()`, `int typeId()`. */
    private val dataInterface: ClassModel by lazy {
        all
            .map { it.second }
            .filter { m -> m.flags().has(AccessFlag.INTERFACE) && m.methods().size == 3 }
            .singleOrNull { m ->
                val sigs = m.methods().map { it.methodTypeSymbol() }
                sigs.any { it.returnType().descriptorString() == "V" && it.parameterCount() == 1 && !it.parameterType(0).isPrimitive && !it.parameterType(0).isArray } &&
                    sigs.any { it.returnType().descriptorString() == "V" && it.parameterCount() == 0 } &&
                    sigs.any { it.returnType().descriptorString() == "I" && it.parameterCount() == 0 }
            } ?: error("binary-data interface (read/reset/typeId) not found (or ambiguous) in the client jar")
    }

    private val readMethod: MethodModel by lazy { dataInterface.methods().single { it.methodTypeSymbol().parameterCount() == 1 } }
    private val typeIdMethod: MethodModel by lazy { dataInterface.methods().single { it.methodTypeSymbol().descriptorString() == "()I" } }

    /** The binary-data classes whose `typeId()` reads the [constantField] constant of [tableEnum]. */
    fun binaryDataClassesOf(
        tableEnum: EnumInfo,
        constantField: String,
    ): List<ClassModel> {
        val iface = dataInterface.thisClass().asInternalName()
        return all
            .map { it.second }
            .filter { m -> m.interfaces().any { it.asInternalName() == iface } }
            .filter { m ->
                m.methods().any { method ->
                    method.methodName().stringValue() == typeIdMethod.methodName().stringValue() &&
                        method.methodTypeSymbol().descriptorString() == "()I" &&
                        method
                            .code()
                            .map { code ->
                                code.elementList().any { el ->
                                    el is FieldInstruction &&
                                        el.opcode() == Opcode.GETSTATIC &&
                                        el.owner().asInternalName() == tableEnum.name &&
                                        el.name().stringValue() == constantField
                                }
                            }.orElse(false)
                }
            }
    }

    /**
     * The record prefix [m]'s `read(reader)` decodes up to and including its first `String[]` read: one [Field] per
     * reader call, typed by the call's return descriptor. The first field is named `id`, the `String[]` `criteria`.
     */
    fun criteriaPrefix(m: ClassModel): List<Field> {
        val reader = readMethod.methodTypeSymbol().parameterType(0).descriptorString()
        val read =
            m.methods().singleOrNull {
                it.methodName().stringValue() == readMethod.methodName().stringValue() &&
                    it.methodTypeSymbol().descriptorString() == readMethod.methodTypeSymbol().descriptorString()
            } ?: error("${m.thisClass().asInternalName()}: no read(reader) method")
        val fields = ArrayList<Field>()
        for (el in read.code().orElseThrow().elementList()) {
            if (el !is InvokeInstruction || "L${el.owner().asInternalName()};" != reader) continue
            val ret = el.typeSymbol().returnType().descriptorString()
            val type =
                when (ret) {
                    "I" -> FieldType.I32
                    "S" -> FieldType.I16
                    "B" -> FieldType.I8
                    "Z" -> FieldType.Bool
                    "J" -> FieldType.I64
                    "F" -> FieldType.F32
                    "D" -> FieldType.F64
                    "Ljava/lang/String;" -> FieldType.Str
                    "[Ljava/lang/String;" -> FieldType.Vec(FieldType.Str)
                    "[I" -> FieldType.Vec(FieldType.I32)
                    else -> error("${m.thisClass().asInternalName()}: reader call returning $ret before the criteria list")
                }
            val last = ret == "[Ljava/lang/String;"
            fields +=
                Field(
                    if (fields.isEmpty()) {
                        "id"
                    } else if (last) {
                        "criteria"
                    } else {
                        "f${fields.size}"
                    },
                    type
                )
            if (last) return fields
        }
        error("${m.thisClass().asInternalName()}: read(reader) reads no String[] (the criteria list)")
    }

    companion object {
        fun load(install: File): ClientJar =
            ClientJar(
                ZipFile(File(install, "lib/wakfu-client.jar")).use { zf ->
                    zf
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .associate { it.name.removeSuffix(".class") to zf.getInputStream(it).readBytes() }
                }
            )
    }
}
