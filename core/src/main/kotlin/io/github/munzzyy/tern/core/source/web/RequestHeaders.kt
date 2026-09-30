package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceOptions

/**
 * Extra headers a person has a web page or a download address sent with, such as a Referer a
 * site asks for. They are stored in [SourceOptions.HEADERS] as a JSON object of names to values
 * and go with the page and the file alike. A header that carries credentials or changes what a
 * request means is refused, and so is any character a header cannot carry. So is User-Agent:
 * Tern says who it is to every site, and does not pass for a browser or another app.
 */
object RequestHeaders {
    const val MAX_HEADERS = 8

    /** For a name and its value together. */
    const val MAX_LENGTH = 500

    private val REFUSED = setOf(
        "authorization", "proxy-authorization", "cookie", "host", "content-length", "range", "user-agent",
        "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "expect", "accept-encoding",
    )
    private val REFUSED_PREFIXES = listOf("if-", "proxy-")

    /** Headers that say how to ask and never who asks. Any other can hold a key. */
    private val PLAIN = setOf("accept", "accept-language", "referer")
    private const val NAME_MARKS = "!#$%&'*+-.^_`|~"

    /** The headers [spec] asks for; a refused one fails the check as an unsupported option. */
    @Throws(SourceException::class)
    fun of(spec: SourceSpec): Map<String, String> = parse(spec.option(SourceOptions.HEADERS))

    /** The headers the option's text names, or none for blank text. */
    @Throws(SourceException::class)
    fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = try {
            Json.parseObject(raw)
        } catch (e: Exception) {
            throw refused("It is not a JSON object of names and values", e)
        }
        if (obj.fields.size > MAX_HEADERS) throw refused("It holds more than $MAX_HEADERS headers")
        val out = LinkedHashMap<String, String>()
        for ((name, value) in obj.fields) {
            val text = (value as? JsonString)?.value ?: throw refused("It holds a value that is not text")
            problem(name, text)?.let { throw refused(it) }
            if (out.keys.any { it.equals(name, ignoreCase = true) }) throw refused("It names the header $name twice")
            out[name] = text.trim()
        }
        return out
    }

    /** Why the header [name] with [value] may not be sent, or null when it may. */
    fun problem(name: String, value: String): String? {
        val lower = name.lowercase()
        return when {
            name.isEmpty() || name.any { !isNameChar(it) } -> "A header name may hold only letters, digits and $NAME_MARKS"
            name.length + value.length > MAX_LENGTH -> "The header $name is longer than $MAX_LENGTH characters"
            lower in REFUSED || REFUSED_PREFIXES.any { lower.startsWith(it) } -> "The header $name may not be set"
            value.any { it !in ' '..'~' } -> "The value of $name holds a character a header cannot carry"
            else -> null
        }
    }

    /**
     * [spec] with only the headers of [PLAIN] kept, for everything that leaves the phone: an
     * export, a shared file, a link. Headers that cannot be read go too.
     */
    fun plainOnly(spec: SourceSpec): SourceSpec {
        val raw = spec.option(SourceOptions.HEADERS) ?: return spec
        val plain = runCatching { parse(raw) }.getOrDefault(emptyMap()).filterKeys { it.lowercase() in PLAIN }
        val options = if (plain.isEmpty()) spec.options - SourceOptions.HEADERS else spec.options + (SourceOptions.HEADERS to write(plain))
        return spec.copy(options = options)
    }

    /** [headers] as the option stores them. */
    fun write(headers: Map<String, String>): String = Json.write(Json.of(headers))

    private fun isNameChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in NAME_MARKS

    private fun refused(why: String, cause: Throwable? = null) =
        SourceException(SourceErrorKind.UNSUPPORTED, "Option ${SourceOptions.HEADERS}: $why", cause = cause)
}
