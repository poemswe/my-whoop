package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FramingTest {

    @Test fun crc8EmptyIsZero() {
        assertEquals(0.toUByte(), crc8(ubyteArrayOf()))
    }

    @Test fun crc8StandardVector() {
        assertEquals(0xF4.toUByte(), crc8("123456789".toByteArray().toUByteArray()))
    }

    @Test fun crc32EmptyIsZero() {
        assertEquals(0u, crc32(ubyteArrayOf()))
    }

    @Test fun crc32StandardVector() {
        assertEquals(0xCBF43926u, crc32("123456789".toByteArray().toUByteArray()))
    }

    @Test fun frameFromPayloadRoundTripsCrc32ButNotCrc8() {
        val frame = frameFromPayload(ubyteArrayOf(0x01u, 0x02u, 0x03u, 0x04u), type = 40u, seq = 7u)
        val check = verifyFrame(frame)
        assertFalse(check.ok)
        assertEquals(false, check.crc8OK)
        assertEquals(true, check.crc32OK)
        assertEquals(11, check.length)
    }

    @Test fun verifyFrameWithCorrectCrc8IsOk() {
        val frame = frameFromPayload(ubyteArrayOf(0xDEu, 0xADu, 0xBEu, 0xEFu), type = 47u, seq = 1u)
        frame[3] = crc8(ubyteArrayOf(frame[1], frame[2]))
        val check = verifyFrame(frame)
        assertTrue(check.ok, "frame should validate: $check")
        assertEquals(true, check.crc8OK)
        assertEquals(true, check.crc32OK)
    }

    @Test fun verifyFrameRejectsBadSof() {
        val frame = ubyteArrayOf(0xBBu, 0x07u, 0x00u, 0x00u, 0x28u, 0x00u, 0x00u, 0x00u)
        assertFalse(verifyFrame(frame).ok)
    }

    @Test fun verifyFrameRejectsTooShort() {
        assertFalse(verifyFrame(ubyteArrayOf(0xAAu, 0x07u, 0x00u)).ok)
    }

    @Test fun reassemblerEmitsCompleteFrameInOneFeed() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(0x01u, 0x02u))
        val out = Reassembler().feed(frame)
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
    }

    @Test fun reassemblerEmitsBothWhenTwoFramesConcatenated() {
        val a = buildFrame(type = 40u, seq = 1u, payload = ubyteArrayOf(0xAAu))
        val b = buildFrame(type = 47u, seq = 2u, payload = ubyteArrayOf(0xBBu, 0xCCu))
        val out = Reassembler().feed(a + b)
        assertEquals(2, out.size)
        assertContentEquals(a, out[0])
        assertContentEquals(b, out[1])
    }

    @Test fun reassemblerStitchesFragmentsAcrossFeeds() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(0x10u, 0x20u, 0x30u, 0x40u))
        val r = Reassembler()
        assertEquals(0, r.feed(frame.sliceArray(0 until 5)).size)
        val out = r.feed(frame.sliceArray(5 until frame.size))
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
    }

    @Test fun reassemblerDropsGarbageBeforeSof() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(0x99u))
        val noise = ubyteArrayOf(0x00u, 0x11u, 0x22u, 0x33u)
        val out = Reassembler().feed(noise + frame)
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
    }

    @Test fun reassemblerSlidesPastFalseSofWithBadCrc8() {
        val noise = ubyteArrayOf(0xAAu, 0xFFu, 0xFFu, 0x00u, 0xAAu, 0x00u)
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(0x07u))
        val out = Reassembler().feed(noise + frame)
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
    }

    @Test fun reassemblerHandlesSofInSecondFragment() {
        val frame = buildFrame(type = 40u, seq = 0u, payload = ubyteArrayOf(0x01u, 0x02u, 0x03u))
        val noise = ubyteArrayOf(0xDEu, 0xADu, 0xBEu, 0xEFu)
        val r = Reassembler()
        assertEquals(0, r.feed(noise).size)
        val out = r.feed(frame)
        assertEquals(1, out.size)
        assertContentEquals(frame, out[0])
    }

    private fun assertContentEquals(expected: UByteArray, actual: UByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
