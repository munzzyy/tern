package io.github.munzzyy.tern.core.json

sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonNumber(val raw: String) : JsonValue {
    fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.let { d ->
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) d.toLong() else null
    }

    fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}

data class JsonArray(val items: List<JsonValue>) : JsonValue, List<JsonValue> by items {
    fun objects(): List<JsonObject> = items.filterIsInstance<JsonObject>()

    fun strings(): List<String> = items.mapNotNull { (it as? JsonString)?.value }
}

data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]

    fun string(key: String): String? = (fields[key] as? JsonString)?.value

    fun long(key: String): Long? = when (val v = fields[key]) {
        is JsonNumber -> v.toLongOrNull()
        is JsonString -> v.value.toLongOrNull()
        else -> null
    }

    fun double(key: String): Double? = (fields[key] as? JsonNumber)?.toDoubleOrNull()

    fun bool(key: String): Boolean? = (fields[key] as? JsonBool)?.value

    fun obj(key: String): JsonObject? = fields[key] as? JsonObject

    fun array(key: String): JsonArray? = fields[key] as? JsonArray
}
