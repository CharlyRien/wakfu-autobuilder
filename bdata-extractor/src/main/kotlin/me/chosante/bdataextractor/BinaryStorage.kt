package me.chosante.bdataextractor

import java.io.File
import java.util.zip.ZipFile

/** One index entry of a table: the record's [id], its byte [offset] and [size], plus a per-entry [seed]. */
data class Entry(
    val id: Long,
    val offset: Int,
    val size: Int,
    val seed: Int,
)

/** A decoded table: the index [entries] and the [records] (one name→value map per entry, per the schema). */
class Table(
    val entries: List<Entry>,
    val records: List<Map<String, Any?>>,
)

/**
 * Reads and decodes one Ankama static-data table (`contents/bdata/<typeId>.jar` → `<typeId>.bin`).
 *
 * The container layout (clean-room from the documented `SimpleBinaryStorage` format): a `Vec<Entry>`
 * index (`{ i64 id, i32 offset, i32 size, i8 seed }`), then an `i8` index-group count followed by that
 * many secondary indexes, then — after a [BinaryDecoder.reset] that re-seeds for the records block — the
 * record bodies, read back-to-back. Records are read **sequentially** (offsets are not used to seek);
 * the seed is continuous across records, so every field of every record must be decoded in order.
 *
 * The per-record invariant `bytes-consumed == Entry.size` is enforced as a hard error — the only way to
 * catch silent positional-layout drift (a field added/reordered in a future client) instead of decoding
 * plausible garbage.
 */
fun loadTable(
    installRoot: File,
    typeId: Int,
    schema: List<Field>,
): Table {
    val d = BinaryDecoder.create(readBin(installRoot, typeId), typeId)
    val entries = d.readIndex()

    // --- records block ---
    d.reset(typeId)
    val records = ArrayList<Map<String, Any?>>(entries.size)
    for (i in entries.indices) {
        val before = d.position
        val rec = d.readRecord(schema)
        val consumed = d.position - before
        if (consumed != entries[i].size) {
            error(
                "Table $typeId record $i (id=${entries[i].id}) size mismatch: schema consumed " +
                    "$consumed bytes but the index says ${entries[i].size}. Positional layout drift — " +
                    "re-derive the field schema against this client version."
            )
        }
        records.add(rec)
    }
    return Table(entries, records)
}

/**
 * True if [schema] cleanly decodes the first [sample] records of table [typeId] — every record consumes
 * exactly its indexed byte length (size-guard) and its leading field equals the index id. Records are read
 * INDEPENDENTLY via their per-entry seed + offset, so this works as a fast "does this schema fit this table"
 * probe without walking the whole table. [SchemaGenerator] uses it to pick which binary-data class a table id
 * corresponds to. [schema]'s first field is taken to be the record id.
 */
internal fun decodesCleanly(
    installRoot: File,
    typeId: Int,
    schema: List<Field>,
    sample: Int = 50,
): Boolean {
    if (schema.isEmpty()) return false
    val d = BinaryDecoder.create(readBin(installRoot, typeId), typeId)
    val entries = d.readIndex()
    if (entries.isEmpty()) return false
    d.reset(typeId)
    val idName = schema.first().name
    for (i in 0 until minOf(sample, entries.size)) {
        val e = entries[i]
        d.seekRecord(e.offset, e.seed)
        val before = d.position
        val rec = runCatching { d.readRecord(schema) }.getOrNull() ?: return false
        if (d.position - before != e.size) return false
        if ((rec[idName] as? Int)?.toLong() != e.id) return false
    }
    return true
}

/** Reads `contents/bdata/<typeId>.jar` and returns the bytes of its single `.bin` entry. */
private fun readBin(
    installRoot: File,
    typeId: Int,
): ByteArray = readZipEntryBytes(File(installRoot, "contents/bdata/$typeId.jar")) { it.endsWith(".bin") }

/** Opens [jar] and returns the bytes of the first non-directory entry whose name satisfies [predicate]. */
internal fun readZipEntryBytes(
    jar: File,
    predicate: (String) -> Boolean,
): ByteArray {
    require(jar.isFile) { "Missing $jar — is this a Wakfu install root?" }
    return ZipFile(jar).use { zf ->
        val entry =
            zf
                .entries()
                .asSequence()
                .firstOrNull { !it.isDirectory && predicate(it.name) }
                ?: error("$jar contains no entry matching the expected name — unexpected jar layout.")
        zf.getInputStream(entry).use { it.readBytes() }
    }
}

/** Reads the index block (entry table + secondary index groups), leaving the decoder ready for [BinaryDecoder.reset]. */
private fun BinaryDecoder.readIndex(): List<Entry> {
    val entryCount = i32()
    val entries = ArrayList<Entry>(entryCount)
    repeat(entryCount) { entries.add(Entry(i64(), i32(), i32(), i8())) }
    val indexGroups = i8()
    repeat(indexGroups) {
        val unique = bool()
        string() // index name (unused)
        val count = i32()
        repeat(count) {
            i64() // indexed id
            if (unique) i32() else repeat(i32()) { i32() }
        }
    }
    return entries
}
