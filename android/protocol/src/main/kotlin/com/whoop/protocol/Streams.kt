package com.whoop.protocol

data class HRSample(val ts: Int, val bpm: Int)

data class RRInterval(val ts: Int, val rrMs: Int)

data class WhoopEvent(val ts: Int, val kind: String, val payload: Map<String, ParsedValue>)

data class BatterySample(
    val ts: Int,
    val soc: Double?,
    val mv: Int?,
    val charging: Boolean? = null,
)

data class SpO2Sample(val ts: Int, val red: Int, val ir: Int, val unit: String = "raw_adc")

data class SkinTempSample(val ts: Int, val raw: Int, val unit: String = "raw_adc")

data class RespSample(val ts: Int, val raw: Int, val unit: String = "raw_adc")

data class GravitySample(
    val ts: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val unit: String = "g",
)

data class Streams(
    val hr: MutableList<HRSample> = mutableListOf(),
    val rr: MutableList<RRInterval> = mutableListOf(),
    val spo2: MutableList<SpO2Sample> = mutableListOf(),
    val skinTemp: MutableList<SkinTempSample> = mutableListOf(),
    val resp: MutableList<RespSample> = mutableListOf(),
    val gravity: MutableList<GravitySample> = mutableListOf(),
    val events: MutableList<WhoopEvent> = mutableListOf(),
    val battery: MutableList<BatterySample> = mutableListOf(),
)

private fun toWall(deviceTs: Int?, deviceClockRef: Int, wallClockRef: Int): Int? =
    deviceTs?.let { wallClockRef + (it - deviceClockRef) }

internal fun appendBattery(out: Streams, ts: Int, p: Map<String, ParsedValue>) {
    val soc = p["battery_pct"]?.doubleValue
    val mv = p["battery_mV"]?.intValue
    if (soc == null && mv == null) return
    val charging = p["battery_charging"]?.intValue?.let { it != 0 }
    out.battery.add(BatterySample(ts = ts, soc = soc, mv = mv, charging = charging))
}

fun extractStreams(
    parsed: List<ParsedFrame>,
    deviceClockRef: Int,
    wallClockRef: Int,
): Streams {
    val out = Streams()
    for (r in parsed) {
        if (!r.ok || r.crcOK == false) continue
        val p = r.parsed
        when (r.typeName) {
            "REALTIME_DATA" -> {
                val ts = toWall(p["timestamp"]?.intValue, deviceClockRef, wallClockRef)
                if (ts != null) {
                    p["heart_rate"]?.intValue?.let { out.hr.add(HRSample(ts, it)) }
                    p["rr_intervals"]?.intArrayValue?.forEach { out.rr.add(RRInterval(ts, it)) }
                }
            }
            "EVENT" -> {
                val ts = p["event_timestamp"]?.intValue ?: continue
                val kind = p["event"]?.stringValue ?: ""
                if (kind.startsWith("BATTERY_LEVEL")) appendBattery(out, ts, p)
                val payload = p.toMutableMap().apply {
                    remove("event"); remove("event_timestamp")
                }
                out.events.add(WhoopEvent(ts, kind, payload))
            }
            "COMMAND_RESPONSE" -> appendBattery(out, wallClockRef, p)
        }
    }
    return out
}

fun extractHistoricalStreams(
    parsed: List<ParsedFrame>,
    deviceClockRef: Int,
    wallClockRef: Int,
): Streams {
    val out = Streams()
    for (r in parsed) {
        if (!r.ok || r.crcOK == false) continue
        val p = r.parsed
        when (r.typeName) {
            "HISTORICAL_DATA" -> {
                val ts = p["unix"]?.intValue ?: continue
                p["heart_rate"]?.intValue?.takeIf { it != 0 }?.let {
                    out.hr.add(HRSample(ts, it))
                }
                p["rr_intervals"]?.intArrayValue?.forEach { out.rr.add(RRInterval(ts, it)) }
                p["spo2_red"]?.intValue?.let {
                    out.spo2.add(SpO2Sample(ts, red = it, ir = p["spo2_ir"]?.intValue ?: 0))
                }
                p["skin_temp_raw"]?.intValue?.let { out.skinTemp.add(SkinTempSample(ts, it)) }
                p["resp_rate_raw"]?.intValue?.let { out.resp.add(RespSample(ts, it)) }
                p["gravity_x"]?.doubleValue?.let { x ->
                    out.gravity.add(GravitySample(
                        ts = ts, x = x,
                        y = p["gravity_y"]?.doubleValue ?: 0.0,
                        z = p["gravity_z"]?.doubleValue ?: 0.0,
                    ))
                }
            }
            "REALTIME_RAW_DATA" -> {
                val ts = toWall(p["timestamp"]?.intValue, deviceClockRef, wallClockRef)
                if (ts != null) {
                    p["heart_rate"]?.intValue?.let { out.hr.add(HRSample(ts, it)) }
                    p["rr_intervals"]?.intArrayValue?.forEach { out.rr.add(RRInterval(ts, it)) }
                }
            }
            "EVENT" -> {
                val ts = p["event_timestamp"]?.intValue ?: continue
                val kind = p["event"]?.stringValue ?: ""
                if (kind.startsWith("BATTERY_LEVEL")) appendBattery(out, ts, p)
                val payload = p.toMutableMap().apply {
                    remove("event"); remove("event_timestamp")
                }
                out.events.add(WhoopEvent(ts, kind, payload))
            }
            "COMMAND_RESPONSE" -> appendBattery(out, wallClockRef, p)
        }
    }
    return out
}
