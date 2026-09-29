package io.github.munzzyy.jackdaw.core.json

/** RFC 7386: an object in the patch is merged key by key, null removes a key, anything else replaces. */
object JsonMergePatch {
    private const val MAX_DEPTH = 96

    fun apply(target: JsonValue?, patch: JsonValue): JsonValue = apply(target, patch, 0)

    private fun apply(target: JsonValue?, patch: JsonValue, depth: Int): JsonValue {
        if (patch !is JsonObject) return patch
        if (depth > MAX_DEPTH) throw JsonException("Patch nests deeper than $MAX_DEPTH", 0)
        val merged = LinkedHashMap((target as? JsonObject)?.fields ?: emptyMap())
        for ((key, value) in patch.fields) {
            if (value == JsonNull) merged.remove(key) else merged[key] = apply(merged[key], value, depth + 1)
        }
        return JsonObject(merged)
    }
}
