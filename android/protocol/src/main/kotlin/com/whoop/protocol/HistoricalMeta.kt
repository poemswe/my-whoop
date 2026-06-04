package com.whoop.protocol

sealed interface HistoricalMeta {
    data object Start : HistoricalMeta
    data class End(val unix: UInt, val trim: UInt) : HistoricalMeta
    data object Complete : HistoricalMeta
    data object Other : HistoricalMeta
}

fun classifyHistoricalMeta(p: ParsedFrame): HistoricalMeta {
    if (p.typeName != "METADATA") return HistoricalMeta.Other
    val metaName = p.parsed["meta_type"]?.stringValue ?: return HistoricalMeta.Other
    return when {
        metaName.startsWith("HISTORY_START") -> HistoricalMeta.Start
        metaName.startsWith("HISTORY_COMPLETE") -> HistoricalMeta.Complete
        metaName.startsWith("HISTORY_END") -> {
            val unix = p.parsed["unix"]?.intValue ?: return HistoricalMeta.Other
            val trim = p.parsed["trim_cursor"]?.intValue ?: return HistoricalMeta.Other
            HistoricalMeta.End(unix = unix.toUInt(), trim = trim.toUInt())
        }
        else -> HistoricalMeta.Other
    }
}
