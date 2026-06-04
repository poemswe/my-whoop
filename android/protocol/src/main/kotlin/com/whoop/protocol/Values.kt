package com.whoop.protocol

sealed interface ParsedValue {
    data class IntV(val v: Int) : ParsedValue
    data class DoubleV(val v: Double) : ParsedValue
    data class StringV(val v: String) : ParsedValue
    data class IntArrayV(val v: List<Int>) : ParsedValue
    data class BoolV(val v: Boolean) : ParsedValue
    data object NullV : ParsedValue
}

val ParsedValue.intValue: Int?
    get() = (this as? ParsedValue.IntV)?.v

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
