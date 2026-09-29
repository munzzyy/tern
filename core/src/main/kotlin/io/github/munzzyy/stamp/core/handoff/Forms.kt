package io.github.munzzyy.stamp.core.handoff

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reads what a plain form sends. Anything that is not the one expected shape comes back as null. */
internal object Forms {
    const val TYPE = "application/x-www-form-urlencoded"

    fun typeOf(contentType: String?): String = contentType.orEmpty().substringBefore(';').trim().lowercase()

    /** The value of a form that has the single field [name]. */
    fun field(body: ByteArray, name: String): String? {
        val start = "$name=".toByteArray(Charsets.US_ASCII)
        if (body.size < start.size) return null
        for (i in start.indices) if (body[i] != start[i]) return null
        val out = ByteArrayOutputStream(body.size)
        var i = start.size
        while (i < body.size) {
            val b = body[i].toInt() and 0xFF
            when {
                b == '&'.code -> return null
                b == '+'.code -> out.write(' '.code)
                b == '%'.code -> {
                    if (i + 2 >= body.size) return null
                    val high = hex(body[i + 1])
                    val low = hex(body[i + 2])
                    if (high < 0 || low < 0) return null
                    out.write(high * 16 + low)
                    i += 2
                }
                b <= 0x20 || b >= 0x7F -> return null
                else -> out.write(b)
            }
            i++
        }
        return utf8(out.toByteArray())
    }

    fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun hex(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    sealed interface Links {
        class Taken(val links: List<String>) : Links

        class Refused(val notice: Notice) : Links
    }

    /** One link on each line. One that cannot be taken refuses them all, so that what arrives is what was sent. */
    fun links(text: String, limits: HandoffLimits): Links {
        val lines = text.split("\r\n", "\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            lines.isEmpty() -> Links.Refused(Notice.NO_LINKS)
            lines.size > limits.links -> Links.Refused(Notice.TOO_MANY_LINKS)
            lines.any { it.length > limits.linkLength } -> Links.Refused(Notice.LINK_TOO_LONG)
            lines.any { !printable(it) } -> Links.Refused(Notice.LINK_UNREADABLE)
            else -> Links.Taken(lines)
        }
    }

    /** False for text with characters that are not drawn, which could make a link look like another one. */
    fun printable(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val point = text.codePointAt(i)
            when (Character.getType(point).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                -> return false
            }
            i += Character.charCount(point)
        }
        return true
    }
}
