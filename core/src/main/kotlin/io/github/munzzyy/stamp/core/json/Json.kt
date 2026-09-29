package io.github.munzzyy.stamp.core.json

import java.io.Reader
import java.io.StringReader

object Json {
    fun parse(text: String): JsonValue = parse(StringReader(text))

    fun parse(reader: Reader): JsonValue {
        val json = JsonReader(reader)
        val value = json.readValue()
        json.requireEndOfDocument()
        return value
    }

    fun parseObject(text: String): JsonObject =
        parse(text) as? JsonObject ?: throw JsonException("Expected a JSON object", 0)

    fun parseArray(text: String): JsonArray =
        parse(text) as? JsonArray ?: throw JsonException("Expected a JSON array", 0)

    fun write(value: JsonValue, indent: Boolean = false): String =
        StringBuilder().also { write(value, it, if (indent) 0 else -1) }.toString()

    fun obj(vararg fields: Pair<String, Any?>): JsonObject {
        val map = LinkedHashMap<String, JsonValue>()
        for ((k, v) in fields) map[k] = of(v)
        return JsonObject(map)
    }

    fun of(value: Any?): JsonValue = when (value) {
        null -> JsonNull
        is JsonValue -> value
        is String -> JsonString(value)
        is Boolean -> JsonBool(value)
        is Int, is Long -> JsonNumber(value.toString())
        is Double -> if (value.isFinite()) JsonNumber(value.toString()) else JsonNull
        is Map<*, *> -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> value.forEach { (k, v) -> m[k.toString()] = of(v) } })
        is Iterable<*> -> JsonArray(value.map { of(it) })
        else -> throw IllegalArgumentException("Cannot encode ${value::class.java.name} as JSON")
    }

    private fun write(value: JsonValue, out: StringBuilder, depth: Int) {
        when (value) {
            JsonNull -> out.append("null")
            is JsonBool -> out.append(value.value)
            is JsonNumber -> out.append(value.raw)
            is JsonString -> quote(value.value, out)
            is JsonArray -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    newline(out, depth, 1)
                    write(item, out, next(depth))
                }
                if (value.items.isNotEmpty()) newline(out, depth, 0)
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, depth, 1)
                    quote(k, out)
                    out.append(if (depth >= 0) ": " else ":")
                    write(v, out, next(depth))
                }
                if (value.fields.isNotEmpty()) newline(out, depth, 0)
                out.append('}')
            }
        }
    }

    private fun next(depth: Int) = if (depth >= 0) depth + 1 else depth

    private fun newline(out: StringBuilder, depth: Int, extra: Int) {
        if (depth < 0) return
        out.append('\n')
        repeat(depth + extra) { out.append("  ") }
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 || c == ' ' || c == ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
