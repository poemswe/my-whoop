package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaTest {

    @Test fun loadsAndCachesAcrossCalls() {
        assertSame(loadSchema(), loadSchema())
    }

    @Test fun envelopeMatchesFrameLayout() {
        val env = loadSchema().envelope
        assertEquals(5, env.size)
        assertEquals(listOf("SOF", "length", "crc8", "packet_type", "seq"), env.map { it.name })
        assertEquals(listOf(0, 1, 3, 4, 5), env.map { it.off })
        assertEquals("PacketType", env[3].enumRef)
    }

    @Test fun typeNameKnownAndFallback() {
        val s = loadSchema()
        assertEquals("REALTIME_DATA", s.typeName(40))
        assertEquals("HISTORICAL_DATA", s.typeName(47))
        assertEquals("type999", s.typeName(999))
    }

    @Test fun enumNameKnownAndHexFallback() {
        val s = loadSchema()
        assertEquals("REALTIME_DATA(40)", s.enumName("PacketType", 40))
        assertEquals("WRIST_ON(9)", s.enumName("EventNumber", 9))
        assertEquals("0xAB(171)", s.enumName("PacketType", 0xAB))
        assertEquals("0x07(7)", s.enumName("NoSuchEnum", 7))
    }

    @Test fun packetByTypeExposesFieldSpecs() {
        val rt = loadSchema().packet(40)
        assertNotNull(rt)
        assertEquals("REALTIME_DATA", rt.name)
        assertEquals("realtime_data", rt.post)
        assertEquals(4, rt.fields.size)
        val hr = rt.fields.single { it.name == "heart_rate" }
        assertEquals(12, hr.off)
        assertEquals(1, hr.len)
        assertEquals("u8", hr.dtype)
        assertEquals("hr", hr.cat)
        assertEquals("bpm", hr.note)
    }

    @Test fun packetByTypeUnknownReturnsNull() {
        assertNull(loadSchema().packet(999))
    }

    @Test fun realtimeRawHasImuVariantWithAxes() {
        val raw = loadSchema().packet(43)
        assertNotNull(raw)
        val imu = raw.variants["1917"]
        assertNotNull(imu)
        assertEquals("imu", imu.kind)
        assertEquals(100, imu.samples)
        assertEquals(21, imu.hrOff)
        assertEquals(22, imu.rrCountOff)
        assertEquals(23, imu.rrFirstOff)
        assertEquals(1292, imu.tailFrom)
        assertEquals(6, imu.axes.size)
        val accelX = imu.axes[0]
        assertEquals("accelX", accelX.name)
        assertEquals(89, accelX.off)
        assertEquals("accel", accelX.cat)
    }

    @Test fun realtimeRawHasOpticalVariant() {
        val raw = loadSchema().packet(43)!!
        val opt = raw.variants["1921"]
        assertNotNull(opt)
        assertEquals("optical", opt.kind)
        assertEquals(42, opt.ppgOff)
        assertEquals(4, opt.ppgStride)
        assertEquals(419, opt.ppgSamples)
        assertEquals(15, opt.configFrom)
        assertEquals(42, opt.configTo)
        assertTrue(opt.axes.isEmpty())
    }

    @Test fun historicalDataResolvesV24Directly() {
        val s = loadSchema()
        val hist = s.packet(47)!!
        val v24 = s.resolveVersion(hist.versions, 24)
        assertNotNull(v24)
        assertEquals("biometric", v24.kind)
        assertEquals(20, v24.fields.size)
        assertEquals(23, v24.rrFirstOff)
    }

    @Test fun historicalDataV12FollowsRefToV24() {
        val s = loadSchema()
        val hist = s.packet(47)!!
        val v24 = s.resolveVersion(hist.versions, 24)!!
        val v12 = s.resolveVersion(hist.versions, 12)
        assertNotNull(v12)
        assertEquals("biometric", v12.kind)
        assertEquals(v24.fields.size, v12.fields.size)
        assertEquals(23, v12.rrFirstOff)
    }

    @Test fun historicalDataV7FollowsRefToV5() {
        val s = loadSchema()
        val hist = s.packet(47)!!
        val v7 = s.resolveVersion(hist.versions, 7)
        assertNotNull(v7)
        assertEquals("generic", v7.kind)
        assertEquals(3, v7.fields.size)
        assertEquals(23, v7.rrFirstOff)
    }

    @Test fun resolveVersionUnknownReturnsNull() {
        val s = loadSchema()
        val hist = s.packet(47)!!
        assertNull(s.resolveVersion(hist.versions, 99))
    }

    @Test fun consoleLogsPacketHasNoFields() {
        val cl = loadSchema().packet(50)
        assertNotNull(cl)
        assertEquals("CONSOLE_LOGS", cl.name)
        assertEquals("console_logs", cl.post)
        assertTrue(cl.fields.isEmpty())
    }
}
