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
    val jar = File(installRoot, "contents/bdata/$typeId.jar")
    require(jar.isFile) { "Missing $jar — is this a Wakfu install root?" }
    val bin =
        ZipFile(jar).use { zf ->
            val entry =
                zf
                    .entries()
                    .asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".bin") }
                    ?: error("$jar contains no .bin entry — unexpected table layout for type $typeId.")
            zf.getInputStream(entry).use { it.readBytes() }
        }

    val d = BinaryDecoder.create(bin, typeId)

    // --- index block ---
    val entryCount = d.i32()
    val entries = ArrayList<Entry>(entryCount)
    repeat(entryCount) { entries.add(Entry(d.i64(), d.i32(), d.i32(), d.i8())) }
    val indexGroups = d.i8()
    repeat(indexGroups) {
        val unique = d.bool()
        d.string() // index name (unused)
        val count = d.i32()
        repeat(count) {
            d.i64() // indexed id
            if (unique) d.i32() else repeat(d.i32()) { d.i32() }
        }
    }

    // --- records block ---
    d.reset(typeId)
    val records = ArrayList<Map<String, Any?>>(entryCount)
    for (i in 0 until entryCount) {
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
