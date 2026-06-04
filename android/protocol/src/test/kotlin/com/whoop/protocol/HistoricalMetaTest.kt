package com.whoop.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoricalMetaTest {

    private fun metaFrame(metaType: UByte, payload: UByteArray = UByteArray(0)): ParsedFrame =
        parseFrame(buildFrame(type = 49u, seq = 0u, payload = ubyteArrayOf(metaType) + payload))

    @Test fun startMetaTypeIsClassifiedAsStart() {
        assertEquals(HistoricalMeta.Start, classifyHistoricalMeta(metaFrame(1u)))
    }

    @Test fun completeMetaTypeIsClassifiedAsComplete() {
        assertEquals(HistoricalMeta.Complete, classifyHistoricalMeta(metaFrame(3u)))
    }

    @Test fun endMetaTypeCarriesUnixAndTrim() {
        val payload = u32LE(1_700_000_000) + u16LE(0) +
            u32LE(0) + u32LE(0xCAFEBABE.toInt())
        val result = classifyHistoricalMeta(metaFrame(2u, payload))
        assertEquals(HistoricalMeta.End(unix = 1_700_000_000u, trim = 0xCAFEBABEu), result)
    }

    @Test fun endMetaTypeSurvivesPostY2038Unix() {
        val payload = u32LE(0xFFEEDDCC.toInt()) + u16LE(0) +
            u32LE(0) + u32LE(0x01020304)
        val result = classifyHistoricalMeta(metaFrame(2u, payload))
        assertEquals(HistoricalMeta.End(unix = 0xFFEEDDCCu, trim = 0x01020304u), result)
    }

    @Test fun endWithoutPayloadFallsBackToOther() {
        assertEquals(HistoricalMeta.Other, classifyHistoricalMeta(metaFrame(2u)))
    }

    @Test fun nonMetadataFrameIsOther() {
        val f = parseFrame(buildFrame(type = 40u, seq = 0u, payload = UByteArray(8) { 0u }))
        assertEquals(HistoricalMeta.Other, classifyHistoricalMeta(f))
    }
}
