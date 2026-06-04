package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterpreterTest {

    private fun buildFrame(type: UByte, seq: UByte, payload: UByteArray): UByteArray {
        val inner = ubyteArrayOf(type, seq) + payload
        val length = inner.size + 4
        val c = crc32(inner)
        val frame = UByteArray(length + 4)
        frame[0] = 0xAAu
        frame[1] = (length and 0xFF).toUByte()
        frame[2] = ((length shr 8) and 0xFF).toUByte()
        frame[3] = crc8(ubyteArrayOf(frame[1], frame[2]))
        inner.copyInto(frame, 4)
        frame[length]     = (c and 0xFFu).toUByte()
        frame[length + 1] = ((c shr 8) and 0xFFu).toUByte()
        frame[length + 2] = ((c shr 16) and 0xFFu).toUByte()
        frame[length + 3] = ((c shr 24) and 0xFFu).toUByte()
        return frame
    }

    @Test fun parseRejectsFrameTooShort() {
        val f = parseFrame(ubyteArrayOf(0xAAu, 0x01u, 0x02u))
        assertFalse(f.ok)
        assertEquals("INVALID/FRAGMENT", f.typeName)
        assertTrue(f.fields.isEmpty())
        assertTrue(f.parsed.isEmpty())
    }

    @Test fun parseRejectsBadSof() {
        val f = parseFrame(UByteArray(16) { 0u })
        assertFalse(f.ok)
        assertEquals("INVALID/FRAGMENT", f.typeName)
    }

    @Test fun parseRealtimeDataExposesTimestampHrRr() {
        val payload = ubyteArrayOf(
            0x78u, 0x56u, 0x34u, 0x12u,  // timestamp u32 LE = 0x12345678
            0x02u, 0x01u,                // subseconds u16 LE = 0x0102
            72u,                         // heart_rate
            3u,                          // rr_count
        )
        val frame = buildFrame(type = 40u, seq = 7u, payload = payload)
        val f = parseFrame(frame)

        assertTrue(f.ok)
        assertEquals("REALTIME_DATA", f.typeName)
        assertEquals(7, f.seq)
        assertEquals(true, f.crcOK)
        assertEquals(frame.size, f.lenBytes)

        assertEquals(ParsedValue.IntV(0x12345678), f.parsed["timestamp"])
        assertEquals(ParsedValue.IntV(0x0102), f.parsed["subseconds"])
        assertEquals(ParsedValue.IntV(72), f.parsed["heart_rate"])
        assertEquals(ParsedValue.IntV(3), f.parsed["rr_count"])

        val envelope = f.fields.take(5).map { it.name }
        assertEquals(listOf("SOF", "length", "crc8", "packet_type", "seq"), envelope)
        f.fields.take(5).forEach { assertEquals("frame", it.cat) }
        assertFalse(f.parsed.containsKey("SOF"))
    }

    @Test fun parseRealtimeDataAppendsCrc32Trailer() {
        val payload = ubyteArrayOf(0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u)
        val frame = buildFrame(type = 40u, seq = 0u, payload = payload)
        val crc = parseFrame(frame).fields.single { it.name == "crc32" }
        assertEquals("frame", crc.cat)
        assertEquals(4, crc.len)
        assertEquals("OK", crc.note)
    }

    @Test fun parseEventDecodesEnum() {
        val payload = ubyteArrayOf(
            9u,                              // event = 9 (WRIST_ON)
            0u,                              // gap byte at off 7
            0xD2u, 0x02u, 0x96u, 0x49u,      // event_timestamp u32 LE = 0x499602D2 = 1234567890
        )
        val frame = buildFrame(type = 48u, seq = 0u, payload = payload)
        val f = parseFrame(frame)

        assertTrue(f.ok)
        assertEquals("EVENT", f.typeName)
        assertEquals(ParsedValue.StringV("WRIST_ON(9)"), f.parsed["event"])
        assertEquals(ParsedValue.IntV(0x499602D2.toInt()), f.parsed["event_timestamp"])
    }

    @Test fun parseUnknownTypeAddsCmdAndPayloadRegion() {
        val payload = ubyteArrayOf(0x42u, 0x01u, 0x02u, 0x03u)
        val frame = buildFrame(type = 99u, seq = 0u, payload = payload)
        val f = parseFrame(frame)

        assertEquals("type99", f.typeName)
        val cmd = f.fields.single { it.name == "cmd" }
        assertEquals(ParsedValue.IntV(0x42), cmd.value)
        val region = f.fields.single { it.name == "payload" }
        assertEquals("unknown", region.cat)
    }

    @Test fun parseCommandResponseSetsCmdName() {
        val payload = ubyteArrayOf(26u)
        val frame = buildFrame(type = 36u, seq = 0u, payload = payload)
        val f = parseFrame(frame)
        assertEquals("COMMAND_RESPONSE", f.typeName)
        assertEquals("GET_BATTERY_LEVEL(26)", f.cmdName)
    }

    @Test fun parseRealtimeDataDoesNotSetCmdName() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = UByteArray(8) { 0u })
        assertNull(parseFrame(frame).cmdName)
    }

    @Test fun parseTrimsOutOfRangeFieldsSilently() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(1u, 2u))
        val f = parseFrame(frame)
        assertTrue(f.ok)
        assertFalse(f.parsed.containsKey("heart_rate"))
        assertFalse(f.parsed.containsKey("rr_count"))
    }

    @Test fun parseRawHexIsLowercase() {
        val f = parseFrame(ubyteArrayOf(0xAAu, 0xBBu, 0xCCu))
        assertEquals("aabbcc", f.rawHex)
    }
}
