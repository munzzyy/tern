package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject

/** The colours of the categories as they are stored and exported: a name to an ARGB colour. */
object CategoryColors {
    const val MAX_NAME = 40
    const val MAX_CATEGORIES = 200

    fun encode(colors: Map<String, Int>): JsonObject =
        JsonObject(colors.entries.take(MAX_CATEGORIES).associateTo(LinkedHashMap()) { (name, argb) -> name to JsonNumber(argb.toString()) })

    /** What [obj] names that is a usable name and a colour; an ARGB above the range of an Int, as Obtainium writes it, is read as the same bits. */
    fun decode(obj: JsonObject): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for ((raw, value) in obj.fields) {
            val name = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none(Char::isISOControl) } ?: continue
            val argb = (value as? JsonNumber)?.toLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL } ?: continue
            out[name] = argb.toInt()
            if (out.size >= MAX_CATEGORIES) break
        }
        return out
    }
}
