package com.whoop.protocol

internal fun buildFrame(type: UByte, seq: UByte, payload: UByteArray): UByteArray {
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

internal fun payloadAt(size: Int, vararg writes: Pair<Int, UByteArray>): UByteArray {
    val out = UByteArray(size)
    for ((offFromSix, bytes) in writes) bytes.copyInto(out, offFromSix - 6)
    return out
}

internal fun u16LE(v: Int): UByteArray =
    ubyteArrayOf((v and 0xFF).toUByte(), ((v shr 8) and 0xFF).toUByte())

internal fun u32LE(v: Int): UByteArray = ubyteArrayOf(
    (v and 0xFF).toUByte(),
    ((v shr 8) and 0xFF).toUByte(),
    ((v shr 16) and 0xFF).toUByte(),
    ((v shr 24) and 0xFF).toUByte(),
)
