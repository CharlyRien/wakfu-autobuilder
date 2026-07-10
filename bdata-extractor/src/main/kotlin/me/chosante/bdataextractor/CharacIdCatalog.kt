package me.chosante.bdataextractor

import me.chosante.common.Characteristic
import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.util.zip.ZipFile

/**
 * Ankama's characteristic-id → project [Characteristic] map, read **first-party** from the local client bytecode
 * (`lib/wakfu-client.jar`) — the same jar [SchemaGenerator] reads for table schemas.
 *
 * The game's characteristics live in an obfuscated `enum`. Each constant is built in the enum's `<clinit>` with
 * its **script name** (`"MP"`, `"BLOCK"`, `"CRITICAL_BONUS"`, … — in clear, since combat scripts reference them
 * by string via `GetCharac("…")`, so obfuscation can't rename them) followed by the constant's numeric id:
 * `new <enum>; dup; ldc "NAME"; <ordinal>; <id>; …; invokespecial <enum>.<init>`. That **id** is exactly what
 * action-913 (Ankama's derived-stat / ramp formula) references as its *source* stat (e.g. Featherweight reads
 * `id=3` = `"MP"`). So this lets the sublimation decoder resolve a 913 source id to a [Characteristic] without a
 * hand-maintained id table.
 *
 * The enum class is found by **structure** (extends `Enum` + holds the most characteristic-name anchors), so the
 * obfuscated class name changing between client versions doesn't matter — same robustness principle as
 * [SchemaGenerator]. `id → scriptName` is fully decoded; only the small, stable `scriptName → Characteristic`
 * correspondence is curated (like `ActionCatalog.CHARAC_CODE`); an unmapped source stat → `null` (the sub stays
 * forced-only, never a wrong stat).
 */
class CharacIdCatalog private constructor(
    private val idToScriptName: Map<Int, String>,
) {
    /** The [Characteristic] for Ankama characteristic id [id], or null if unknown / not modeled as a build stat. */
    fun characteristicFor(id: Int): Characteristic? = idToScriptName[id]?.let { SCRIPT_NAME_TO_CHARAC[it] }

    /** The raw script name (`"MP"`, `"BLOCK"`, …) for [id], or null — for diagnostics. */
    fun scriptNameFor(id: Int): String? = idToScriptName[id]

    companion object {
        /**
         * Distinctive characteristic-name strings that (together) appear only in the characteristic enum — used to
         * find its obfuscated class structurally. The enum holds ALL of them; incidental script classes hold few.
         */
        private val ANCHORS = listOf("CRITICAL_BONUS", "DMG_FIRE_PERCENT", "FIRE_MASTERY", "LEADERSHIP", "DODGE", "PROSPECTION")

        /**
         * Script name (as used in `GetCharac("…")` and the enum) → project [Characteristic]. Only the stats a 913
         * ramp can plausibly read as its *source* are mapped; anything else resolves to null (forced-only) — the
         * small key set is DELIBERATE. The VALUES are sourced from the module's single script-code table
         * ([ActionCatalog.CHARAC_CODE]) so a mapping fix propagates and the two can't drift (C3 of
         * docs/code-review-followups.md); `getValue` fails loudly if any key ever leaves that table.
         */
        private val SCRIPT_NAME_TO_CHARAC: Map<String, Characteristic> =
            listOf("AP", "MP", "WP", "BLOCK", "DODGE", "CRITICAL_BONUS")
                .associateWith { ActionCatalog.CHARAC_CODE.getValue(it) }

        fun load(install: File): CharacIdCatalog {
            val classes =
                ZipFile(File(install, "lib/wakfu-client.jar")).use { zf ->
                    zf
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .associate { it.name.removeSuffix(".class") to zf.getInputStream(it).readBytes() }
                }
            val enumBytes =
                findCharacteristicEnum(classes)
                    ?: error("characteristic enum not found in lib/wakfu-client.jar (anchors: $ANCHORS)")
            return CharacIdCatalog(parseEnumIds(enumBytes))
        }

        /** The client class that extends `Enum` and contains the most [ANCHORS] characteristic-name strings. */
        private fun findCharacteristicEnum(classes: Map<String, ByteArray>): ByteArray? {
            fun anchorCount(bytes: ByteArray): Int {
                val text = String(bytes, Charsets.ISO_8859_1)
                return ANCHORS.count { it in text }
            }
            return classes.values
                .filter { anchorCount(it) >= 3 }
                .filter { bytes ->
                    val m = runCatching { ClassFile.of().parse(bytes) }.getOrNull()
                    m != null && m.superclass().map { it.asInternalName() == "java/lang/Enum" }.orElse(false)
                }.maxByOrNull { anchorCount(it) }
        }

        /**
         * Walks the enum's `<clinit>` and, for each `new <enum> … invokespecial <enum>.<init>` construction,
         * pairs the constant's script-name string with its id (the **2nd** int argument — after the enum ordinal).
         */
        private fun parseEnumIds(bytes: ByteArray): Map<Int, String> {
            val model: ClassModel = ClassFile.of().parse(bytes)
            val self = model.thisClass().asInternalName()
            val clinit = model.methods().firstOrNull { it.methodName().stringValue() == "<clinit>" } ?: return emptyMap()
            val code = clinit.code().orElse(null) ?: return emptyMap()

            val out = LinkedHashMap<Int, String>()
            var capturing = false
            var name: String? = null
            val ints = ArrayList<Int>()
            for (element in code.elementList()) {
                when (element) {
                    is NewObjectInstruction ->
                        if (element.className().asInternalName() == self) {
                            capturing = true
                            name = null
                            ints.clear()
                        }
                    is ConstantInstruction ->
                        if (capturing) {
                            // constantValue() is typed ConstantDesc; widen to Any so the String/Int runtime checks
                            // are valid (Kotlin's java.lang.String/Integer ↔ kotlin.String/Int mapping hides the
                            // ConstantDesc subtyping). ldc "NAME" → String; iconst/bipush/sipush/ldc int → Int.
                            when (val v: Any = element.constantValue()) {
                                is String -> name = v
                                is Int -> ints.add(v)
                                else -> {}
                            }
                        }
                    is InvokeInstruction ->
                        if (capturing && element.name().stringValue() == "<init>" && element.owner().asInternalName() == self) {
                            val constantName = name
                            // ints = [ordinal, characteristicId, …other ctor args]; the id is the first user arg.
                            if (constantName != null && ints.size >= 2) out[ints[1]] = constantName
                            capturing = false
                        }
                    else -> {}
                }
            }
            return out
        }
    }
}
