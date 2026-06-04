package com.whoop.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParityTest {

    @Serializable
    private data class FrameEntry(val hex: String)

    @Serializable
    private data class GoldenEntry(
        @SerialName("type_name") val typeName: String,
        val seq: Int? = null,
        @SerialName("crc_ok") val crcOK: Boolean? = null,
        @SerialName("cmd_name") val cmdName: String? = null,
        val parsed: Map<String, ParsedValue> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(name: String): String =
        ParityTest::class.java.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().use { it.readText() }

    private fun hexToBytes(s: String): UByteArray {
        val out = UByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toUByte()
        }
        return out
    }

    @Test fun kotlinMatchesPythonGoldenForAllFrames() {
        val frames = json.decodeFromString<List<FrameEntry>>(resource("frames.json"))
        val golden = json.decodeFromString<List<GoldenEntry>>(resource("golden.json"))
        assertEquals(frames.size, golden.size, "frames.json / golden.json length mismatch")
        assertTrue(frames.isNotEmpty(), "no parity frames loaded")

        for (i in frames.indices) {
            val g = golden[i]
            val out = parseFrame(hexToBytes(frames[i].hex))
            assertEquals(g.typeName, out.typeName, "type_name @ #$i")
            assertEquals(g.seq, out.seq, "seq @ #$i (${g.typeName})")
            assertEquals(g.crcOK, out.crcOK, "crc_ok @ #$i (${g.typeName})")
            assertEquals(g.cmdName, out.cmdName, "cmd_name @ #$i (${g.typeName})")
            assertEquals(g.parsed, out.parsed,
                "parsed @ #$i (${g.typeName})\n  kotlin: ${out.parsed}\n  python: ${g.parsed}")
        }
    }

    @Test fun goldenCoversCorePacketTypes() {
        val golden = json.decodeFromString<List<GoldenEntry>>(resource("golden.json"))
        val types = golden.map { it.typeName }.toSet()
        for (expected in listOf(
            "REALTIME_DATA", "COMMAND_RESPONSE", "EVENT", "METADATA",
            "CONSOLE_LOGS", "REALTIME_RAW_DATA",
        )) {
            assertTrue(expected in types, "parity fixture missing $expected")
        }
    }
}
