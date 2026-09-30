package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.source.SourceOptions

/**
 * One page a web page source goes through before the last, as [SourceOptions.STEPS] stores it.
 * On that page the links that match [filter] are put in order and the last one is followed.
 */
data class HtmlStep(
    /** The pattern the link to follow must match: its decoded address, or with [byText] what it says. */
    val filter: String,
    /** Match [filter] against what a link says instead of its address. */
    val byText: Boolean = false,
    /** Prefer links that name this device's processor, then links that name none. */
    val arch: Boolean = false,
    /** Keep the links in the order of the page instead of natural order. */
    val pageOrder: Boolean = false,
    /** Turn the order around, so the first link is the one followed. */
    val firstLink: Boolean = false,
    /** Put the links in order by the last segment of their address. */
    val lastSegment: Boolean = false,
    /** Also look for addresses outside link tags. */
    val anyText: Boolean = false,
) : java.io.Serializable {
    /** The pattern alone when no flag is set, else an object that holds the flags that are. */
    fun toJson(): JsonValue {
        val flags = listOf(TEXT to byText, ARCH to arch, PAGE_ORDER to pageOrder, FIRST_LINK to firstLink, LAST_SEGMENT to lastSegment, ANY_TEXT to anyText)
            .filter { it.second }
        if (flags.isEmpty()) return JsonString(filter)
        return Json.obj(FILTER to filter, *flags.toTypedArray())
    }

    companion object {
        /** Obtainium follows at most this many pages before the last, and so does Tern. */
        const val MAX = 10

        private const val FILTER = "filter"
        private const val TEXT = "text"
        private const val ARCH = "arch"
        private const val PAGE_ORDER = "pageOrder"
        private const val FIRST_LINK = "firstLink"
        private const val LAST_SEGMENT = "lastSegment"
        private const val ANY_TEXT = "anyText"

        /**
         * The steps [raw] holds, in order: a pattern alone, or an object with a pattern and flags.
         * An object without a pattern gives an empty one. Other entries are left out. Null when [raw]
         * is not a JSON array; an empty list when there is no option.
         */
        fun parse(raw: String?): List<HtmlStep>? {
            if (raw.isNullOrBlank()) return emptyList()
            val array = try {
                Json.parseArray(raw)
            } catch (_: JsonException) {
                return null
            }
            return array.items.mapNotNull(::of)
        }

        private fun of(value: JsonValue): HtmlStep? = when (value) {
            is JsonString -> HtmlStep(value.value)
            is JsonObject -> HtmlStep(
                filter = value.string(FILTER).orEmpty(),
                byText = value.bool(TEXT) == true,
                arch = value.bool(ARCH) == true,
                pageOrder = value.bool(PAGE_ORDER) == true,
                firstLink = value.bool(FIRST_LINK) == true,
                lastSegment = value.bool(LAST_SEGMENT) == true,
                anyText = value.bool(ANY_TEXT) == true,
            )
            else -> null
        }

        /** [steps] as the option stores them, or null for none. */
        fun write(steps: List<HtmlStep>): String? = if (steps.isEmpty()) null else Json.write(Json.of(steps.map { it.toJson() }))
    }
}
