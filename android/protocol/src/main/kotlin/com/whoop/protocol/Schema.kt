package com.whoop.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class FieldSpec(
    val off: Int,
    val len: Int,
    val name: String,
    val cat: String,
    val dtype: String? = null,
    val enumRef: String? = null,
    val note: String? = null,
)

data class AxisSpec(val name: String, val off: Int, val cat: String)

data class VariantSpec(
    val kind: String,
    val note: String,
    val hrOff: Int? = null,
    val rrCountOff: Int? = null,
    val rrFirstOff: Int? = null,
    val samples: Int? = null,
    val axes: List<AxisSpec> = emptyList(),
    val tailFrom: Int? = null,
    val ppgOff: Int? = null,
    val ppgStride: Int? = null,
    val ppgSamples: Int? = null,
    val configFrom: Int? = null,
    val configTo: Int? = null,
)

data class VersionSpec(
    val kind: String?,
    val fields: List<FieldSpec>,
    val rrFirstOff: Int?,
    val ref: String?,
)

data class PacketSpec(
    val name: String,
    val type: Int,
    val aliases: List<Int>,
    val post: String?,
    val fields: List<FieldSpec>,
    val variants: Map<String, VariantSpec>,
    val versions: Map<String, VersionSpec>,
)

class Schema internal constructor(
    val enums: Map<String, Map<String, String>>,
    val envelope: List<FieldSpec>,
    val packets: Map<String, PacketSpec>,
) {
    private val byType: Map<Int, PacketSpec> = buildMap {
        for (spec in packets.values) {
            put(spec.type, spec)
            for (alias in spec.aliases) put(alias, spec)
        }
    }

    fun typeName(v: Int): String = enums["PacketType"]?.get(v.toString()) ?: "type$v"

    fun enumName(enumName: String, v: Int): String =
        enums[enumName]?.get(v.toString())?.let { "$it($v)" }
            ?: "0x%02X(%d)".format(v, v)

    fun packet(type: Int): PacketSpec? = byType[type]

    fun resolveVersion(versions: Map<String, VersionSpec>, version: Int): VersionSpec? {
        var entry = versions[version.toString()] ?: return null
        val seen = mutableSetOf<String>()
        while (true) {
            val ref = entry.ref ?: break
            if (!seen.add(ref)) break
            val base = versions[ref] ?: break
            entry = VersionSpec(
                kind = entry.kind ?: base.kind,
                fields = if (entry.fields.isEmpty()) base.fields else entry.fields,
                rrFirstOff = entry.rrFirstOff ?: base.rrFirstOff,
                ref = null,
            )
        }
        return entry
    }
}

private val schemaLazy: Schema by lazy {
    val s = parseSchema()
    registerPostHooks()
    s
}

fun loadSchema(): Schema = schemaLazy

private fun parseSchema(): Schema {
    val stream = Schema::class.java.classLoader?.getResourceAsStream("whoop_protocol.json")
        ?: error("whoop_protocol.json missing from resources")
    val text = stream.bufferedReader().use { it.readText() }
    val root = Json.parseToJsonElement(text).jsonObject

    val enums = root.getValue("enums").jsonObject.mapValues { (_, v) ->
        v.jsonObject.mapValues { (_, vv) -> vv.jsonPrimitive.content }
    }
    val envelope = root.getValue("envelope").jsonArray.map { it.toFieldSpec() }
    val packets = root.getValue("packets").jsonObject.mapValues { (name, v) ->
        v.jsonObject.toPacketSpec(name)
    }
    return Schema(enums = enums, envelope = envelope, packets = packets)
}

private fun JsonElement.toFieldSpec(): FieldSpec {
    val o = jsonObject
    return FieldSpec(
        off = o.getValue("off").jsonPrimitive.int,
        len = o.getValue("len").jsonPrimitive.int,
        name = o.getValue("name").jsonPrimitive.content,
        cat = o.getValue("cat").jsonPrimitive.content,
        dtype = o["dtype"]?.jsonPrimitive?.contentOrNull,
        enumRef = o["enum"]?.jsonPrimitive?.contentOrNull,
        note = o["note"]?.jsonPrimitive?.contentOrNull,
    )
}

private fun JsonObject.toPacketSpec(name: String): PacketSpec = PacketSpec(
    name = name,
    type = getValue("type").jsonPrimitive.int,
    aliases = this["aliases"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
    post = this["post"]?.jsonPrimitive?.contentOrNull,
    fields = this["fields"]?.jsonArray?.map { it.toFieldSpec() } ?: emptyList(),
    variants = this["variants"]?.jsonObject?.mapValues { (_, v) -> v.jsonObject.toVariantSpec() } ?: emptyMap(),
    versions = this["versions"]?.jsonObject?.mapValues { (_, v) -> v.jsonObject.toVersionSpec() } ?: emptyMap(),
)

private fun JsonObject.toVariantSpec(): VariantSpec = VariantSpec(
    kind = getValue("kind").jsonPrimitive.content,
    note = this["note"]?.jsonPrimitive?.contentOrNull ?: "",
    hrOff = this["hr_off"]?.jsonPrimitive?.intOrNull,
    rrCountOff = this["rr_count_off"]?.jsonPrimitive?.intOrNull,
    rrFirstOff = this["rr_first_off"]?.jsonPrimitive?.intOrNull,
    samples = this["samples"]?.jsonPrimitive?.intOrNull,
    axes = (this["axes"] as? JsonArray)?.map { it.toAxisSpec() } ?: emptyList(),
    tailFrom = this["tail_from"]?.jsonPrimitive?.intOrNull,
    ppgOff = this["ppg_off"]?.jsonPrimitive?.intOrNull,
    ppgStride = this["ppg_stride"]?.jsonPrimitive?.intOrNull,
    ppgSamples = this["ppg_samples"]?.jsonPrimitive?.intOrNull,
    configFrom = this["config_from"]?.jsonPrimitive?.intOrNull,
    configTo = this["config_to"]?.jsonPrimitive?.intOrNull,
)

private fun JsonElement.toAxisSpec(): AxisSpec {
    val a = jsonArray
    return AxisSpec(
        name = a[0].jsonPrimitive.content,
        off = a[1].jsonPrimitive.int,
        cat = a[2].jsonPrimitive.content,
    )
}

private fun JsonObject.toVersionSpec(): VersionSpec = VersionSpec(
    kind = this["kind"]?.jsonPrimitive?.contentOrNull,
    fields = this["fields"]?.jsonArray?.map { it.toFieldSpec() } ?: emptyList(),
    rrFirstOff = this["rr_first_off"]?.jsonPrimitive?.intOrNull,
    ref = this["ref"]?.jsonPrimitive?.contentOrNull,
)
