package io.github.munzzyy.tern.core.handoff

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Reads what the page sends. Anything that is not the one expected shape comes back as null. */
internal object Forms {
    const val TYPE = "application/x-www-form-urlencoded"
    const val FIELD = "sealed"
    const val MAX_NAME = 80
    private const val FALLBACK_NAME = "export.json"

    class Named(val name: String, val content: ByteArray)

    fun typeOf(contentType: String?): String = contentType.orEmpty().substringBefore(';').trim().lowercase()

    /** The bytes in the single field of the form, which holds nothing but the letters of base64 for addresses. */
    fun sealed(body: ByteArray): ByteArray? {
        val start = "$FIELD=".toByteArray(Charsets.US_ASCII)
        if (body.size < start.size) return null
        for (i in start.indices) if (body[i] != start[i]) return null
        for (i in start.size until body.size) {
            val c = body[i].toInt().toChar()
            if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_')) return null
        }
        return try {
            Base64.getUrlDecoder().decode(body.copyOfRange(start.size, body.size))
        } catch (_: IllegalArgumentException) {
            null
        }
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

    /** A file the way the page writes it: one byte that says how long the name is, the name, then the file. */
    fun file(plain: ByteArray): Named? {
        if (plain.isEmpty()) return null
        val length = plain[0].toInt() and 0xFF
        if (length > MAX_NAME || 1 + length > plain.size) return null
        val name = utf8(plain.copyOfRange(1, 1 + length)) ?: return null
        return Named(tidy(name), plain.copyOfRange(1 + length, plain.size))
    }

    /** A name is only ever shown, never used as a path. It is still cut down to what a name needs. */
    fun tidy(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in " ._-()" }
        .trim().trimStart('.').trim()
        .take(MAX_NAME)
        .ifEmpty { FALLBACK_NAME }
}
