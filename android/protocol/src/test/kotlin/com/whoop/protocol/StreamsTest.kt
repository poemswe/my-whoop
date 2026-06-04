package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamsTest {

    @Test fun emptyInputProducesEmptyStreams() {
        val s = extractStreams(emptyList(), deviceClockRef = 0, wallClockRef = 0)
        assertEquals(Streams(), s)
    }

    @Test fun realtimeDataHrIsWallClockShifted() {
        val payload = u32LE(1_000_500) + u16LE(0) + ubyteArrayOf(85u, 0u)
        val frame = parseFrame(buildFrame(type = 40u, seq = 0u, payload = payload))
        val s = extractStreams(listOf(frame), deviceClockRef = 1_000_000, wallClockRef = 1_700_000_000)
        assertEquals(listOf(HRSample(ts = 1_700_000_500, bpm = 85)), s.hr)
    }

    @Test fun realtimeDataRrIntervalsAreStampedSameAsHr() {
        val payload = u32LE(1_000_000) + u16LE(0) + ubyteArrayOf(70u, 2u) + u16LE(880) + u16LE(900)
        val frame = parseFrame(buildFrame(type = 40u, seq = 0u, payload = payload))
        val s = extractStreams(listOf(frame), deviceClockRef = 1_000_000, wallClockRef = 1_700_000_000)
        assertEquals(2, s.rr.size)
        assertEquals(RRInterval(ts = 1_700_000_000, rrMs = 880), s.rr[0])
        assertEquals(RRInterval(ts = 1_700_000_000, rrMs = 900), s.rr[1])
    }

    @Test fun nonOkFramesAreSkipped() {
        val bad = parseFrame(ubyteArrayOf(0x00u, 0x00u, 0x00u))
        val s = extractStreams(listOf(bad), deviceClockRef = 0, wallClockRef = 0)
        assertTrue(s.hr.isEmpty())
        assertTrue(s.events.isEmpty())
    }

    @Test fun eventBatteryLevelEmitsBatteryAndEvent() {
        val payload = payloadAt(
            size = 22,
            6 to ubyteArrayOf(3u),
            8 to u32LE(1_700_000_555),
            17 to u16LE(900),
            21 to u16LE(4000),
            26 to ubyteArrayOf(1u),
        )
        val frame = parseFrame(buildFrame(type = 48u, seq = 0u, payload = payload))
        val s = extractStreams(listOf(frame), deviceClockRef = 0, wallClockRef = 0)
        assertEquals(
            listOf(BatterySample(ts = 1_700_000_555, soc = 90.0, mv = 4000, charging = true)),
            s.battery,
        )
        assertEquals(1, s.events.size)
        val e = s.events.single()
        assertEquals(1_700_000_555, e.ts)
        assertTrue(e.kind.startsWith("BATTERY_LEVEL"))
        assertTrue("event" !in e.payload)
        assertTrue("event_timestamp" !in e.payload)
    }

    @Test fun commandResponseBatteryStampedAtWallClockRef() {
        val payload = ubyteArrayOf(26u, 0u, 0u) + u16LE(845)
        val frame = parseFrame(buildFrame(type = 36u, seq = 0u, payload = payload))
        val s = extractStreams(listOf(frame), deviceClockRef = 0, wallClockRef = 1_700_000_000)
        assertEquals(
            listOf(BatterySample(ts = 1_700_000_000, soc = 84.5, mv = null, charging = null)),
            s.battery,
        )
    }

    @Test fun historicalDataYieldsHrRrAndDspSamples() {
        val payload = payloadAt(
            size = 80,
            11 to u32LE(1_700_000_000),
            21 to ubyteArrayOf(60u),
            22 to ubyteArrayOf(1u),
            23 to u16LE(950),
            68 to u16LE(2200),
            70 to u16LE(2100),
            72 to u16LE(1850),
            80 to u16LE(1234),
        )
        val frame = parseFrame(buildFrame(type = 47u, seq = 24u, payload = payload))
        val s = extractHistoricalStreams(listOf(frame), deviceClockRef = 0, wallClockRef = 0)
        assertEquals(listOf(HRSample(ts = 1_700_000_000, bpm = 60)), s.hr)
        assertEquals(listOf(RRInterval(ts = 1_700_000_000, rrMs = 950)), s.rr)
        assertEquals(listOf(SpO2Sample(ts = 1_700_000_000, red = 2200, ir = 2100)), s.spo2)
        assertEquals(listOf(SkinTempSample(ts = 1_700_000_000, raw = 1850)), s.skinTemp)
        assertEquals(listOf(RespSample(ts = 1_700_000_000, raw = 1234)), s.resp)
    }

    @Test fun historicalDataSkipsHrZero() {
        val payload = payloadAt(
            size = 40,
            11 to u32LE(1_700_000_000),
            21 to ubyteArrayOf(0u),
        )
        val frame = parseFrame(buildFrame(type = 47u, seq = 24u, payload = payload))
        val s = extractHistoricalStreams(listOf(frame), deviceClockRef = 0, wallClockRef = 0)
        assertTrue(s.hr.isEmpty())
    }
}
