package com.whoop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor

@Serializable(with = ParsedValueSerializer::class)
sealed interface ParsedValue {
    data class IntV(val v: Int) : ParsedValue
    data class DoubleV(val v: Double) : ParsedValue
    data class StringV(val v: String) : ParsedValue
    data class IntArrayV(val v: List<Int>) : ParsedValue
    data class BoolV(val v: Boolean) : ParsedValue
    data object NullV : ParsedValue
}

object ParsedValueSerializer : KSerializer<ParsedValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ParsedValue", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ParsedValue {
        require(decoder is JsonDecoder) { "ParsedValue requires JsonDecoder" }
        return fromJson(decoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: ParsedValue) {
        require(encoder is JsonEncoder) { "ParsedValue requires JsonEncoder" }
        encoder.encodeJsonElement(toJson(value))
    }

    private fun fromJson(e: JsonElement): ParsedValue = when (e) {
        is JsonNull -> ParsedValue.NullV
        is JsonArray -> ParsedValue.IntArrayV(e.map { it.jsonPrimitive.toInt() })
        is JsonPrimitive -> when {
            e.booleanOrNull != null -> ParsedValue.BoolV(e.boolean)
            e.isString -> ParsedValue.StringV(e.content)
            else -> {
                val long = e.longOrNull
                if (long != null && long in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    ParsedValue.IntV(long.toInt())
                } else {
                    val d = e.doubleOrNull ?: error("Unsupported ParsedValue JSON: $e")
                    if (!d.isNaN() && !d.isInfinite() && d == floor(d)
                        && d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()
                    ) ParsedValue.IntV(d.toInt())
                    else ParsedValue.DoubleV(d)
                }
            }
        }
        else -> error("Unsupported ParsedValue JSON: $e")
    }

    private fun JsonPrimitive.toInt(): Int {
        val long = longOrNull
        if (long != null) return long.toInt()
        val d = doubleOrNull ?: error("Not a number: $this")
        return d.toInt()
    }

    private fun toJson(v: ParsedValue): JsonElement = when (v) {
        is ParsedValue.IntV -> JsonPrimitive(v.v)
        is ParsedValue.DoubleV -> JsonPrimitive(v.v)
        is ParsedValue.StringV -> JsonPrimitive(v.v)
        is ParsedValue.BoolV -> JsonPrimitive(v.v)
        is ParsedValue.IntArrayV -> JsonArray(v.v.map { JsonPrimitive(it) })
        ParsedValue.NullV -> JsonNull
    }
}

internal fun intOrDoubleV(value: Long): ParsedValue =
    if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) ParsedValue.IntV(value.toInt())
    else ParsedValue.DoubleV(value.toDouble())

val ParsedValue.intValue: Int?
    get() = (this as? ParsedValue.IntV)?.v

val ParsedValue.longValue: Long?
    get() = when (this) {
        is ParsedValue.IntV -> v.toLong()
        is ParsedValue.DoubleV -> if (v.isFinite()) v.toLong() else null
        else -> null
    }

val ParsedValue.doubleValue: Double?
    get() = when (this) {
        is ParsedValue.DoubleV -> v
        is ParsedValue.IntV -> v.toDouble()
        else -> null
    }

val ParsedValue.stringValue: String?
    get() = (this as? ParsedValue.StringV)?.v

val ParsedValue.intArrayValue: List<Int>?
    get() = (this as? ParsedValue.IntArrayV)?.v
