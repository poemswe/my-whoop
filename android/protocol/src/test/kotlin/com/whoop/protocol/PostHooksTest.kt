package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostHooksTest {

    @Test fun round1NonTieValues() {
        assertEquals(62.8, round1(62.84))
        assertEquals(62.9, round1(62.86))
        assertEquals(5.0, round1(5.0))
        assertEquals(-3.7, round1(-3.7))
        assertEquals(0.0, round1(0.0))
    }

    @Test fun formatMeanAlwaysKeepsOneDecimal() {
        assertEquals("62.9", formatMean(62.9))
        assertEquals("5.0", formatMean(5.0))
        assertEquals("3637.8", formatMean(3637.8))
        assertEquals("-3.7", formatMean(-3.7))
    }

    @Test fun realtimeDataHookEmitsRrIntervals() {
        val payload = ubyteArrayOf(
            0u, 0u, 0u, 0u,
            0u, 0u,
            80u,
            2u,
        ) + u16LE(420) + u16LE(415)
        val f = parseFrame(buildFrame(type = 40u, seq = 0u, payload = payload))
        assertTrue(f.ok)
        assertEquals(ParsedValue.IntV(80), f.parsed["heart_rate"])
        assertEquals(ParsedValue.IntV(2), f.parsed["rr_count"])
        assertEquals(ParsedValue.IntArrayV(listOf(420, 415)), f.parsed["rr_intervals"])
        assertNotNull(f.fields.singleOrNull { it.name == "rr[0]" })
        assertNotNull(f.fields.singleOrNull { it.name == "rr[1]" })
    }

    @Test fun eventBatteryLevelExtractsSocMvCharging() {
        val payload = payloadAt(
            size = 22,
            6 to ubyteArrayOf(3u),               // event = 3 (BATTERY_LEVEL)
            8 to u32LE(1_700_000_000),           // event_timestamp
            17 to u16LE(758),                    // soc *10 → 75.8%
            21 to u16LE(3700),                   // mV
            26 to ubyteArrayOf(1u),              // charging
        )
        val f = parseFrame(buildFrame(type = 48u, seq = 0u, payload = payload))
        assertTrue(f.ok)
        assertEquals(ParsedValue.DoubleV(75.8), f.parsed["battery_pct"])
        assertEquals(ParsedValue.IntV(3700), f.parsed["battery_mV"])
        assertEquals(ParsedValue.IntV(1), f.parsed["battery_charging"])
    }

    @Test fun rawDataUnknownDataLenEmitsRegionWithoutParsedFields() {
        val payload = UByteArray(40) { 0u }
        val frame = parseFrame(buildFrame(type = 43u, seq = 0u, payload = payload))
        assertTrue(frame.ok)
        assertEquals(null, frame.parsed["heart_rate"])
        assertEquals(null, frame.parsed["accelX_mean"])
        assertNotNull(frame.fields.singleOrNull { it.name.startsWith("sensor payload") })
    }

    @Test fun eventBatteryLevelMvBoundaries() {
        fun mv(value: Int): ParsedValue? {
            val payload = payloadAt(
                size = 22,
                6 to ubyteArrayOf(3u),
                21 to u16LE(value),
            )
            return parseFrame(buildFrame(type = 48u, seq = 0u, payload = payload)).parsed["battery_mV"]
        }
        assertEquals(null, mv(2999))
        assertEquals(ParsedValue.IntV(3000), mv(3000))
        assertEquals(ParsedValue.IntV(4300), mv(4300))
        assertEquals(null, mv(4301))
    }

    @Test fun eventBatteryLevelClampsSocOutOfRange() {
        fun soc(value: Int): ParsedValue? {
            val payload = payloadAt(
                size = 22,
                6 to ubyteArrayOf(3u),
                17 to u16LE(value),
            )
            return parseFrame(buildFrame(type = 48u, seq = 0u, payload = payload)).parsed["battery_pct"]
        }
        assertEquals(ParsedValue.DoubleV(110.0), soc(1100))
        assertEquals(null, soc(1101))
    }

    @Test fun commandResponseGetBatteryLevelDecodesPercent() {
        val payload = ubyteArrayOf(26u) + ubyteArrayOf(0u, 0u) + u16LE(845)
        val f = parseFrame(buildFrame(type = 36u, seq = 0u, payload = payload))
        assertEquals(ParsedValue.DoubleV(84.5), f.parsed["battery_pct"])
    }

    @Test fun commandResponseGetClockDecodesU32() {
        val payload = ubyteArrayOf(11u) + ubyteArrayOf(0u, 0u) + u32LE(1_700_000_123)
        val f = parseFrame(buildFrame(type = 36u, seq = 0u, payload = payload))
        assertEquals(ParsedValue.IntV(1_700_000_123), f.parsed["clock"])
    }

    @Test fun historicalDataV24DecodesHeartRateAndRr() {
        val payload = payloadAt(
            size = 80,
            11 to u32LE(1_700_000_000),          // unix
            21 to ubyteArrayOf(65u),             // heart_rate
            22 to ubyteArrayOf(1u),              // rr_count = 1
            23 to u16LE(880),                    // rr[0]
        )
        val f = parseFrame(buildFrame(type = 47u, seq = 24u, payload = payload))
        assertTrue(f.ok)
        assertEquals(ParsedValue.IntV(24), f.parsed["hist_version"])
        assertEquals(ParsedValue.IntV(1_700_000_000), f.parsed["unix"])
        assertEquals(ParsedValue.IntV(65), f.parsed["heart_rate"])
        assertEquals(ParsedValue.IntArrayV(listOf(880)), f.parsed["rr_intervals"])
    }

    @Test fun historicalDataUnmappedVersionEmitsRegion() {
        val payload = UByteArray(30) { 0u }
        val f = parseFrame(buildFrame(type = 47u, seq = 99u, payload = payload))
        assertEquals(ParsedValue.IntV(99), f.parsed["hist_version"])
        assertNotNull(f.fields.singleOrNull {
            it.name.startsWith("HISTORICAL_DATA v99")
        })
    }

    @Test fun metadataDecodesHistoryEndFields() {
        val payload = u32LE(1_700_000_000) + u16LE(123) +
            u32LE(0xDEADBEEF.toInt()) + u32LE(0x01020304)
        val f = parseFrame(buildFrame(type = 49u, seq = 0u, payload = ubyteArrayOf(2u) + payload))
        assertTrue(f.ok)
        assertEquals(ParsedValue.IntV(1_700_000_000), f.parsed["unix"])
        assertEquals(ParsedValue.IntV(123), f.parsed["subsec"])
        assertEquals(ParsedValue.IntV(0x01020304), f.parsed["trim_cursor"])
    }

    @Test fun consoleLogsDecodesAsciiText() {
        val text = "hello world"
        val padding = UByteArray(5) { 0u }
        val tail = ubyteArrayOf(0u)
        val payload = padding + text.toByteArray(Charsets.UTF_8).toUByteArray() + tail
        val f = parseFrame(buildFrame(type = 50u, seq = 0u, payload = payload))
        assertTrue(f.ok)
        assertEquals(ParsedValue.StringV(text), f.parsed["log"])
    }
}
