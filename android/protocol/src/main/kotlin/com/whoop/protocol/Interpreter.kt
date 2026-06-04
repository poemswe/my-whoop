package com.whoop.protocol

data class DecodedField(
    val off: Int,
    val len: Int,
    val name: String,
    val cat: String,
    val value: ParsedValue?,
    val raw: String,
    val note: String? = null,
)

data class ParsedFrame(
    val ok: Boolean,
    val typeName: String,
    val seq: Int?,
    val cmdName: String?,
    val crcOK: Boolean?,
    val lenBytes: Int,
    val rawHex: String,
    val fields: List<DecodedField>,
    val parsed: Map<String, ParsedValue>,
)

internal fun readU8(f: UByteArray, off: Int): Int? =
    if (off + 1 <= f.size) f[off].toInt() else null

internal fun readU16(f: UByteArray, off: Int): Int? =
    if (off + 2 <= f.size) f[off].toInt() or (f[off + 1].toInt() shl 8) else null

internal fun readU32(f: UByteArray, off: Int): Long? {
    if (off + 4 > f.size) return null
    return (f[off].toLong() or (f[off + 1].toLong() shl 8) or
        (f[off + 2].toLong() shl 16) or (f[off + 3].toLong() shl 24)) and 0xFFFFFFFFL
}

internal fun readI16(f: UByteArray, off: Int): Int? {
    if (off + 2 > f.size) return null
    val raw = f[off].toInt() or (f[off + 1].toInt() shl 8)
    return raw.toShort().toInt()
}

private fun readDType(f: UByteArray, off: Int, dtype: String): Long? = when (dtype) {
    "u8" -> readU8(f, off)?.toLong()
    "u16" -> readU16(f, off)?.toLong()
    "u32" -> readU32(f, off)
    "i16" -> readI16(f, off)?.toLong()
    else -> null
}

internal fun hex(b: UByteArray, from: Int, until: Int): String {
    if (from >= until) return ""
    val sb = StringBuilder((until - from) * 2)
    for (i in from until until) sb.append("%02x".format(b[i].toInt()))
    return sb.toString()
}


class FieldBuilder internal constructor(val frame: UByteArray) {
    val fields: MutableList<DecodedField> = mutableListOf()
    val parsed: MutableMap<String, ParsedValue> = mutableMapOf()

    fun add(
        off: Int,
        length: Int,
        name: String,
        cat: String,
        value: ParsedValue? = null,
        note: String? = null,
    ): FieldBuilder {
        val end = minOf(off + length, frame.size)
        val raw = if (off <= frame.size) hex(frame, maxOf(0, off), maxOf(off, end)) else ""
        fields.add(DecodedField(off, length, name, cat, value, raw, note))
        if (value != null && cat != "frame" && cat != "unknown") parsed[name] = value
        return this
    }

    fun region(start: Int, end: Int, name: String, cat: String, note: String? = null) {
        if (start < end && end <= frame.size) {
            add(start, end - start, name, cat, ParsedValue.StringV("[${end - start} bytes]"), note)
        }
    }
}

typealias PostHook = (FieldBuilder, UByteArray, Int?, Schema) -> Unit

internal val postHooks: MutableMap<String, PostHook> = mutableMapOf()

fun parseFrame(frame: UByteArray): ParsedFrame {
    val rawHex = hex(frame, 0, frame.size)
    if (frame.size < 8 || frame[0] != 0xAAu.toUByte()) {
        return ParsedFrame(
            ok = false, typeName = "INVALID/FRAGMENT", seq = null, cmdName = null,
            crcOK = null, lenBytes = frame.size, rawHex = rawHex,
            fields = emptyList(), parsed = emptyMap(),
        )
    }

    val schema = loadSchema()
    val check = verifyFrame(frame)
    val length = check.length

    val t = frame[4].toInt()
    val typeName = schema.typeName(t)
    val seq = frame[5].toInt()

    val fb = FieldBuilder(frame)
    fb.add(0, 1, "SOF", "frame", ParsedValue.StringV("0xAA"))
    fb.add(1, 2, "length", "frame", length?.let { ParsedValue.IntV(it) })
    fb.add(3, 1, "crc8", "frame", ParsedValue.StringV("0x%02X".format(frame[3].toInt())))
    fb.add(4, 1, "packet_type", "frame", ParsedValue.StringV(typeName))
    fb.add(5, 1, "seq", "frame", ParsedValue.IntV(seq))

    val spec = schema.packet(t)
    if (spec == null) {
        fb.add(6, 1, "cmd", "cmd", if (frame.size > 6) ParsedValue.IntV(frame[6].toInt()) else null)
        if (length != null) fb.region(7, length, "payload", "unknown")
    } else {
        for (fld in spec.fields) {
            val dtype = fld.dtype ?: continue
            val v = readDType(frame, fld.off, dtype) ?: continue
            val value = fld.enumRef?.let { ParsedValue.StringV(schema.enumName(it, v.toInt())) }
                ?: intOrDoubleV(v)
            fb.add(fld.off, fld.len, fld.name, fld.cat, value, fld.note)
        }
        spec.post?.let { postHooks[it] }?.invoke(fb, frame, length, schema)
    }

    if (length != null && length + 4 <= frame.size) {
        val crcVal = u32le(frame, length)
        fb.add(
            length, 4, "crc32", "frame",
            ParsedValue.StringV("0x%08X".format(crcVal.toLong())),
            note = if (check.crc32OK == true) "OK" else "MISMATCH",
        )
    }

    val cmdName = if (t == 35 || t == 36) {
        schema.enumName("CommandNumber", if (frame.size > 6) frame[6].toInt() else 0)
    } else null

    return ParsedFrame(
        ok = true, typeName = typeName, seq = seq, cmdName = cmdName,
        crcOK = check.crc32OK, lenBytes = frame.size, rawHex = rawHex,
        fields = fb.fields, parsed = fb.parsed,
    )
}
