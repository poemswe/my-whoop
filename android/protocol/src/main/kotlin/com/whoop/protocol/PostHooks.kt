package com.whoop.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private fun s24(f: UByteArray, off: Int): Int? {
    if (off + 3 > f.size) return null
    val v = f[off].toInt() or (f[off + 1].toInt() shl 8) or (f[off + 2].toInt() shl 16)
    return if ((v and 0x800000) != 0) v - 0x1000000 else v
}

private fun f32(f: UByteArray, off: Int): Double? {
    val bits = readU32(f, off) ?: return null
    return Float.fromBits(bits.toInt()).toDouble()
}

private fun readHistInt(f: UByteArray, off: Int, dtype: String): Long? = when (dtype) {
    "u8" -> readU8(f, off)?.toLong()
    "u16" -> readU16(f, off)?.toLong()
    "u32" -> readU32(f, off)
    else -> null
}

private fun i16Block(frame: UByteArray, off: Int, count: Int): IntArray {
    val available = if (off + count * 2 > frame.size) maxOf(0, (frame.size - off) / 2) else count
    if (available <= 0) return IntArray(0)
    val out = IntArray(available)
    for (i in 0 until available) {
        val p = off + i * 2
        val raw = frame[p].toInt() or (frame[p + 1].toInt() shl 8)
        out[i] = raw.toShort().toInt()
    }
    return out
}

private fun twoProduct(a: Double, b: Double): Pair<Double, Double> {
    val p = a * b
    val split = 134217729.0
    val ca = split * a; val ah = ca - (ca - a); val al = a - ah
    val cb = split * b; val bh = cb - (cb - b); val bl = b - bh
    val err = ((ah * bh - p) + ah * bl + al * bh) + al * bl
    return p to err
}

internal fun round1(x: Double): Double {
    val y = x * 10.0
    val fl = floor(y)
    val frac = y - fl
    if (abs(frac - 0.5) >= 1e-14) {
        return Math.rint(y) / 10.0
    }
    val (_, err) = twoProduct(x, 10.0)
    return when {
        err > 0 -> kotlin.math.ceil(y) / 10.0
        err < 0 -> fl / 10.0
        else -> {
            val z = fl.toLong()
            (if (z % 2L == 0L) fl else fl + 1.0) / 10.0
        }
    }
}

internal fun formatMean(x: Double): String = "%.1f".format(x)

private val utcRangeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

private fun integralOrDoubleV(mean: Double): ParsedValue =
    if (!mean.isNaN() && mean == floor(mean)) ParsedValue.IntV(mean.toInt())
    else ParsedValue.DoubleV(mean)

internal fun registerPostHooks() {
    postHooks["realtime_data"] = hook@ { fb, frame, _, _ ->
        val rrn = readU8(frame, 13) ?: 0
        val rrs = mutableListOf<Int>()
        for (i in 0 until rrn) {
            val off = 14 + i * 2
            val v = readU16(frame, off) ?: continue
            fb.add(off, 2, "rr[$i]", "rr", ParsedValue.IntV(v), note = "ms")
            rrs.add(v)
        }
        fb.parsed["rr_intervals"] = ParsedValue.IntArrayV(rrs)
    }

    postHooks["event"] = hook@ { fb, frame, length, schema ->
        if (length == null) return@hook
        val evVal = if (frame.size > 6) frame[6].toInt() else null
        val evName = evVal?.let { schema.enums["EventNumber"]?.get(it.toString()) }
        when (evName) {
            "BATTERY_LEVEL" -> {
                fb.region(7, length, "BATTERY_LEVEL payload", "battery",
                    note = "soc@17(/10) mv@21 charge@26")
                readU16(frame, 17)?.takeIf { it <= 1100 }?.let {
                    fb.parsed["battery_pct"] = ParsedValue.DoubleV(it / 10.0)
                }
                readU16(frame, 21)?.takeIf { it in 3000..4300 }?.let {
                    fb.parsed["battery_mV"] = ParsedValue.IntV(it)
                }
                readU8(frame, 26)?.takeIf { it <= 1 }?.let {
                    fb.parsed["battery_charging"] = ParsedValue.IntV(it and 1)
                }
            }
            "EXTENDED_BATTERY_INFORMATION" -> {
                val payEnd = minOf(length, frame.size)
                if (7 >= payEnd) return@hook
                val pay = frame.sliceArray(7 until payEnd)
                fb.region(7, length, "EXTENDED_BATTERY_INFORMATION payload", "battery",
                    note = "mV (heuristic scan)")
                if (pay.size >= 2) {
                    for (o in 0 until pay.size - 1) {
                        val v = pay[o].toInt() or (pay[o + 1].toInt() shl 8)
                        if (v in 3000..4300) {
                            fb.parsed["battery_mV?"] = ParsedValue.IntV(v)
                            break
                        }
                    }
                }
            }
        }
    }

    postHooks["command_response"] = hook@ { fb, frame, length, schema ->
        if (length == null) return@hook
        val payEnd = minOf(length, frame.size)
        if (7 > payEnd) return@hook
        val pay = frame.sliceArray(7 until payEnd)
        fb.region(7, length, "response payload", "cmd")
        val cmd = if (frame.size > 6) frame[6].toInt() else null
        val name = cmd?.let { schema.enums["CommandNumber"]?.get(it.toString()) }
        when (name) {
            "GET_BATTERY_LEVEL" -> if (pay.size >= 4) {
                val v = pay[2].toInt() or (pay[3].toInt() shl 8)
                fb.parsed["battery_pct"] = ParsedValue.DoubleV(v / 10.0)
            }
            "GET_CLOCK" -> if (pay.size >= 6) {
                val v = (pay[2].toLong() or (pay[3].toLong() shl 8) or
                    (pay[4].toLong() shl 16) or (pay[5].toLong() shl 24)) and 0xFFFFFFFFL
                fb.parsed["clock"] = intOrDoubleV(v)
            }
            "GET_EXTENDED_BATTERY_INFO" -> if (pay.size >= 9) {
                val v = pay[7].toInt() or (pay[8].toInt() shl 8)
                fb.parsed["battery_mV"] = ParsedValue.IntV(v)
            }
            "REPORT_VERSION_INFO" -> if (pay.size >= 31) {
                val buf = UByteArray(35)
                pay.sliceArray(0 until minOf(35, pay.size)).copyInto(buf)
                fun le32(at: Int): Long =
                    (buf[at].toLong() or (buf[at + 1].toLong() shl 8) or
                        (buf[at + 2].toLong() shl 16) or (buf[at + 3].toLong() shl 24)) and 0xFFFFFFFFL
                fb.parsed["fw_harvard"] =
                    ParsedValue.StringV("${le32(3)}.${le32(7)}.${le32(11)}.${le32(15)}")
                fb.parsed["fw_boylston"] =
                    ParsedValue.StringV("${le32(19)}.${le32(23)}.${le32(27)}.${le32(31)}")
            }
            "GET_DATA_RANGE" -> {
                val uniq = mutableListOf<Long>()
                var o = 3
                while (o < pay.size - 3) {
                    val v = (pay[o].toLong() or (pay[o + 1].toLong() shl 8) or
                        (pay[o + 2].toLong() shl 16) or (pay[o + 3].toLong() shl 24)) and 0xFFFFFFFFL
                    if (v in 1_600_000_000L..1_800_000_000L && v !in uniq) uniq.add(v)
                    o += 1
                }
                if (uniq.isNotEmpty()) {
                    val lo = uniq.min()
                    val hi = uniq.max()
                    fb.parsed["history_oldest"] =
                        ParsedValue.StringV(utcRangeFormatter.format(Instant.ofEpochSecond(lo)))
                    fb.parsed["history_newest"] =
                        ParsedValue.StringV(utcRangeFormatter.format(Instant.ofEpochSecond(hi)))
                }
            }
        }
    }

    postHooks["raw_data"] = hook@ { fb, frame, length, schema ->
        if (length == null) return@hook
        val spec = schema.packet(frame[4].toInt())
        val dataLen = length - 7
        val variant = spec?.variants?.get(dataLen.toString())
        if (variant == null) {
            fb.region(21, length, "sensor payload (short/alt subtype)", "unknown")
            return@hook
        }
        when (variant.kind) {
            "imu" -> {
                val hrOff = variant.hrOff ?: return@hook
                val rrCountOff = variant.rrCountOff ?: return@hook
                val rrFirstOff = variant.rrFirstOff ?: return@hook
                val samples = variant.samples ?: return@hook
                val tailFrom = variant.tailFrom ?: return@hook
                val hr = readU8(frame, hrOff)
                val rrn = readU8(frame, rrCountOff) ?: 0
                fb.add(hrOff, 1, "heart_rate", "hr", hr?.let { ParsedValue.IntV(it) }, note = "bpm")
                fb.add(rrCountOff, 1, "rr_count", "rr", ParsedValue.IntV(rrn))
                val rrVals = mutableListOf<Int>()
                for (i in 0 until minOf(rrn, 4)) {
                    val off = rrFirstOff + i * 2
                    val v = readU16(frame, off)
                    fb.add(off, 2, "rr[$i]", "rr", v?.let { ParsedValue.IntV(it) }, note = "ms")
                    if (v != null) rrVals.add(v)
                }
                if (hr != null) fb.parsed["heart_rate"] = ParsedValue.IntV(hr)
                else fb.parsed.remove("heart_rate")
                fb.parsed["rr_intervals"] = ParsedValue.IntArrayV(rrVals)
                for (axis in variant.axes) {
                    val vals = i16Block(frame, axis.off, samples)
                    val mean = if (vals.isEmpty()) null
                        else round1(vals.sum().toDouble() / vals.size)
                    val text = mean?.let {
                        ParsedValue.StringV("mean=${formatMean(it)} (${vals.size}xi16)")
                    }
                    fb.add(axis.off, samples * 2, axis.name, axis.cat, text, note = variant.note)
                    if (mean != null) fb.parsed["${axis.name}_mean"] = integralOrDoubleV(mean)
                }
                fb.region(tailFrom, length, "tail (optical? - not parsed by app)", "unknown")
            }
            "optical" -> {
                val ppgOff = variant.ppgOff ?: return@hook
                val ppgStride = variant.ppgStride ?: return@hook
                val ppgSamples = variant.ppgSamples ?: return@hook
                val configFrom = variant.configFrom ?: return@hook
                fb.region(configFrom, ppgOff, "optical config header (UNKNOWN)", "unknown",
                    note = variant.note)
                val vals = mutableListOf<Int>()
                for (i in 0 until ppgSamples) {
                    val v = s24(frame, ppgOff + i * ppgStride) ?: break
                    vals.add(v)
                }
                if (vals.isNotEmpty()) {
                    val mean = round1(vals.sum().toDouble() / vals.size)
                    fb.add(ppgOff, vals.size * ppgStride, "ppg_green_ac", "ppg",
                        ParsedValue.StringV("mean=${formatMean(mean)} (${vals.size}xs24)"),
                        note = variant.note)
                    fb.parsed["ppg_sample_count"] = ParsedValue.IntV(vals.size)
                    fb.parsed["ppg_mean"] = integralOrDoubleV(mean)
                }
            }
        }
    }

    postHooks["historical_data"] = hook@ { fb, frame, length, schema ->
        if (length == null) return@hook
        val spec = schema.packet(frame[4].toInt())
        val version = frame[5].toInt()
        fb.parsed["hist_version"] = ParsedValue.IntV(version)
        val entry = spec?.let { schema.resolveVersion(it.versions, version) }
        if (entry == null) {
            fb.region(7, length, "HISTORICAL_DATA v$version (unmapped layout)", "unknown")
            return@hook
        }
        for (fld in entry.fields) {
            val dtype = fld.dtype ?: continue
            val value: ParsedValue = when (dtype) {
                "u8", "u16", "u32" -> {
                    val v = readHistInt(frame, fld.off, dtype) ?: continue
                    fld.enumRef?.let { ParsedValue.StringV(schema.enumName(it, v.toInt())) }
                        ?: intOrDoubleV(v)
                }
                "f32" -> ParsedValue.DoubleV(f32(frame, fld.off) ?: continue)
                else -> continue
            }
            fb.add(fld.off, fld.len, fld.name, fld.cat, value, note = fld.note)
        }
        val rrVals = mutableListOf<Int>()
        entry.rrFirstOff?.let { rrFirst ->
            val rrn = fb.parsed["rr_count"]?.intValue ?: 0
            for (i in 0 until minOf(rrn, 4)) {
                val o = rrFirst + i * 2
                val v = readU16(frame, o) ?: continue
                if (v == 0) continue
                fb.add(o, 2, "rr[$i]", "rr", ParsedValue.IntV(v), note = "ms")
                rrVals.add(v)
            }
        }
        fb.parsed["rr_intervals"] = ParsedValue.IntArrayV(rrVals)
    }

    postHooks["metadata"] = hook@ { fb, frame, length, _ ->
        if (length == null) return@hook
        val payEnd = minOf(length, frame.size)
        if (7 >= payEnd) return@hook
        val pay = frame.sliceArray(7 until payEnd)
        if (pay.size >= 14) {
            val unix = (pay[0].toLong() or (pay[1].toLong() shl 8) or
                (pay[2].toLong() shl 16) or (pay[3].toLong() shl 24)) and 0xFFFFFFFFL
            val ss = pay[4].toInt() or (pay[5].toInt() shl 8)
            val unk0 = (pay[6].toLong() or (pay[7].toLong() shl 8) or
                (pay[8].toLong() shl 16) or (pay[9].toLong() shl 24)) and 0xFFFFFFFFL
            val trim = (pay[10].toLong() or (pay[11].toLong() shl 8) or
                (pay[12].toLong() shl 16) or (pay[13].toLong() shl 24)) and 0xFFFFFFFFL
            fb.add(7, 4, "unix", "time", intOrDoubleV(unix))
            fb.add(11, 2, "subsec", "time", ParsedValue.IntV(ss))
            fb.add(13, 4, "unk0", "meta", intOrDoubleV(unk0))
            fb.add(17, 4, "trim_cursor", "meta", intOrDoubleV(trim),
                note = "ack with this to advance")
        }
    }

    postHooks["console_logs"] = hook@ { fb, frame, length, _ ->
        if (length == null) return@hook
        val lo = 11
        val hi = length - 1
        val txt = if (lo < hi && hi <= frame.size)
            String(frame.sliceArray(lo until hi).toByteArray(), Charsets.UTF_8)
        else ""
        val head = txt.take(80)
        fb.region(7, length, "console log text", "text", note = head)
        fb.parsed["log"] = ParsedValue.StringV(txt)
    }
}
