package me.chosante.bdataextractor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoder for Ankama's obfuscated `SimpleBinaryStorage` stream format (the `.bin` inside each
 * `contents/bdata/<id>.jar`). Clean-room implementation from the format facts documented in
 * `docs/SPELL_CAST_LIMITS_EXTRACTION.md` §2-4 (de-obfuscation magic `+756423`, the seed/mul/add
 * scramble, little-endian records, length-prefixed UTF-8 strings) — not a transcription of any
 * third-party reader.
 *
 * ## The scramble
 * A running `seed: Byte` is recomputed **before every primitive read** from the running [position]
 * counter: `seed += (mul * position + add)` truncated to a byte. Integer reads then **subtract** the
 * seed; `f32`/`f64` are read **raw** (no seed subtraction — only [position] advances). Strings are an
 * `i32` length (scrambled) followed by that many **raw** bytes.
 *
 * ## Two counters
 * [cursor] is the real byte offset we read from; [position] is the seed counter. They start aligned
 * (both 4, after the 4-byte `add` header) but [reset] rewinds **only** [position] to 4 while [cursor]
 * keeps going — the index block is read with [DecodeState.new]'s state, then [reset] re-seeds for the
 * records block. Keeping them separate is required for the seed to stay correct across that boundary.
 */
class BinaryDecoder private constructor(
    private val buf: ByteBuffer,
    private var mul: Int,
) {
    private var add: Int = 0
    private var seed: Byte = 0
    private var cursor: Int = 0

    /** Seed counter (distinct from [cursor]); exposed so the container can size-guard each record. */
    var position: Int = 0
        private set

    companion object {
        private const val DEOBFUSCATION_MAGIC = 756_423

        fun create(
            bytes: ByteArray,
            typeId: Int,
        ): BinaryDecoder {
            val d = BinaryDecoder(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), typeId)
            d.add = d.buf.getInt(0) + DEOBFUSCATION_MAGIC
            d.cursor = 4
            d.position = 4
            d.seed = (d.mul xor d.add).toByte()
            return d
        }
    }

    /** Re-seed for a new block (records block) — reads a fresh `add` at the current [cursor]. */
    fun reset(typeId: Int) {
        mul = typeId
        add = buf.getInt(cursor) + DEOBFUSCATION_MAGIC
        cursor += 4
        position = 4
        seed = (mul xor add).toByte()
    }

    /** Advance the seed (using the pre-read [position]) and both counters by [size]; return the read offset. */
    private fun advance(size: Int): Int {
        ensureAvailable(size, "primitive($size)")
        seed = (seed + (mul * position + add)).toByte()
        val at = cursor
        cursor += size
        position += size
        return at
    }

    /**
     * Guards a read of [need] bytes against the buffer end. A positional/seed desync (a field
     * added/reordered in a future client) makes a scrambled length/offset point past the buffer or go
     * negative; without this the JVM would throw a bare NegativeArraySize/OutOfMemory/IndexOutOfBounds
     * mid-record, BEFORE [loadTable]'s per-record size guard can report the drift. This turns every such
     * desync into the same actionable "re-derive the field schema" diagnostic.
     */
    private fun ensureAvailable(
        need: Int,
        field: String,
    ) {
        if (need < 0 || cursor.toLong() + need > buf.limit()) {
            error(
                "bdata decode out of bounds reading $field: need $need byte(s) at offset $cursor of " +
                    "${buf.limit()} — positional layout drift; re-derive the field schema against this client version."
            )
        }
    }

    /** Decode a length/element-count and reject implausible values (negative, or larger than the bytes left). */
    private fun count(field: String): Int {
        val n = i32()
        ensureAvailable(n, field) // each element/byte needs >= 1 byte; n must fit in what remains
        return n
    }

    fun i8(): Int {
        val at = advance(1)
        return (buf.get(at) - seed).toByte().toInt()
    }

    fun i16(): Int {
        val at = advance(2)
        return (buf.getShort(at) - seed).toShort().toInt()
    }

    fun i32(): Int {
        val at = advance(4)
        return buf.getInt(at) - seed
    }

    fun i64(): Long {
        val at = advance(8)
        return buf.getLong(at) - seed.toLong()
    }

    fun bool(): Boolean {
        val at = advance(1)
        return (buf.get(at) - seed).toByte().toInt() != 0
    }

    fun f32(): Float {
        val at = advance(4)
        return buf.getFloat(at)
    }

    fun f64(): Double {
        val at = advance(8)
        return buf.getDouble(at)
    }

    fun string(): String {
        val len = count("string")
        val at = cursor
        cursor += len
        position += len
        val out = ByteArray(len)
        buf.get(at, out) // absolute bulk get (JDK 13+) — faster than a per-byte loop on the hottest path
        return String(out, Charsets.UTF_8)
    }

    /** Decode one value of the given [FieldType]. */
    fun decode(type: FieldType): Any? =
        when (type) {
            FieldType.I8 -> i8()
            FieldType.I16 -> i16()
            FieldType.I32 -> i32()
            FieldType.I64 -> i64()
            FieldType.F32 -> f32()
            FieldType.F64 -> f64()
            FieldType.Bool -> bool()
            FieldType.Str -> string()
            is FieldType.Vec -> List(count("vec")) { decode(type.element) }
            is FieldType.MapField -> buildMap { repeat(count("map")) { put(decode(type.key)!!, decode(type.value)) } }
            is FieldType.Struct -> readRecord(type.fields)
        }

    /** Decode an ordered list of [Field]s (one record / nested struct) into a name→value map. */
    fun readRecord(fields: List<Field>): Map<String, Any?> = buildMap { for (fld in fields) put(fld.name, decode(fld.type)) }
}

/** A positional field in an untagged Ankama table record: a name and its wire [FieldType]. */
data class Field(
    val name: String,
    val type: FieldType,
)

/**
 * Wire types of the `SimpleBinaryStorage` format. The layout is **positional/untagged**, so every field
 * must be decoded in order (variable-length [Str]/[Vec]/[MapField] make seeking impossible) — but a
 * schema only needs to model fields up to the last one of interest *as long as* the trailing fields are
 * still walked for seed alignment (see the table schemas, which model the full record).
 */
sealed interface FieldType {
    data object I8 : FieldType

    data object I16 : FieldType

    data object I32 : FieldType

    data object I64 : FieldType

    data object F32 : FieldType

    data object F64 : FieldType

    data object Bool : FieldType

    data object Str : FieldType

    data class Vec(
        val element: FieldType,
    ) : FieldType

    data class MapField(
        val key: FieldType,
        val value: FieldType,
    ) : FieldType

    data class Struct(
        val fields: List<Field>,
    ) : FieldType
}
