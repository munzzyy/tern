package io.github.munzzyy.stamp.core.handoff

/**
 * Reads the one shape a plain form with a single file field sends: one part, named as expected,
 * with a file name. A body of any other shape comes back as null.
 */
internal object Multipart {
    const val TYPE = "multipart/form-data"

    /** The most a form adds around the file: two boundaries and the headers of the part. */
    const val MAX_WRAPPING = 4 * 1024
    private const val MAX_HEADERS = 2 * 1024
    private const val MAX_BOUNDARY = 70
    private const val MAX_NAME = 80
    private const val FALLBACK_NAME = "export.json"
    private const val BOUNDARY_MARKS = "'()+_,-./:=?"
    private val CRLF = byteArrayOf(13, 10)
    private val HEADERS_END = byteArrayOf(13, 10, 13, 10)

    class Part(val fileName: String, val content: ByteArray)

    fun boundary(contentType: String?): String? {
        val header = contentType ?: return null
        if (Forms.typeOf(header) != TYPE) return null
        val parameters = header.substringAfter(';', "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val named = parameters.filter { it.substringBefore('=').trim().equals("boundary", ignoreCase = true) }
        if (named.size != 1 || parameters.size != 1) return null
        var value = named[0].substringAfter('=', "").trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.length - 1)
        if (value.isEmpty() || value.length > MAX_BOUNDARY) return null
        if (value.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in BOUNDARY_MARKS) }) return null
        return value
    }

    fun single(body: ByteArray, boundary: String, field: String): Part? {
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        if (!startsWith(body, 0, delimiter) || !startsWith(body, delimiter.size, CRLF)) return null
        val headersStart = delimiter.size + CRLF.size
        val headersEnd = indexOf(body, HEADERS_END, headersStart, minOf(body.size, headersStart + MAX_HEADERS))
        if (headersEnd < 0) return null
        val fileName = fileName(body.copyOfRange(headersStart, headersEnd), field) ?: return null

        val contentStart = headersEnd + HEADERS_END.size
        val closing = CRLF + delimiter
        val contentEnd = indexOf(body, closing, contentStart, body.size)
        if (contentEnd < 0) return null
        val after = contentEnd + closing.size
        val rest = body.size - after
        val dashes = rest >= 2 && body[after] == '-'.code.toByte() && body[after + 1] == '-'.code.toByte()
        val ended = dashes && (rest == 2 || (rest == 4 && startsWith(body, after + 2, CRLF)))
        if (!ended) return null
        return Part(tidy(fileName), body.copyOfRange(contentStart, contentEnd))
    }

    private fun fileName(headers: ByteArray, field: String): String? {
        val lines = (Forms.utf8(headers) ?: return null).split("\r\n")
        var disposition: String? = null
        for (line in lines) {
            val name = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (name) {
                "content-disposition" -> if (disposition == null) disposition = value else return null
                "content-type" -> Unit
                else -> return null
            }
        }
        val parameters = parameters(disposition ?: return null) ?: return null
        if (parameters.size != 2 || parameters["name"] != field) return null
        return parameters["filename"]
    }

    /** The parameters of `form-data; name="file"; filename="apps.json"`, each of them once and in quotes. */
    private fun parameters(disposition: String): Map<String, String>? {
        if (!disposition.startsWith("form-data;")) return null
        val out = HashMap<String, String>()
        var rest = disposition.removePrefix("form-data;")
        while (true) {
            rest = rest.trimStart()
            val equals = rest.indexOf("=\"")
            if (equals <= 0) return null
            val name = rest.substring(0, equals)
            val close = rest.indexOf('"', equals + 2)
            if (close < 0 || name.any { !it.isLetter() }) return null
            if (out.put(name, rest.substring(equals + 2, close)) != null) return null
            rest = rest.substring(close + 1).trimStart()
            if (rest.isEmpty()) return out
            if (!rest.startsWith(';')) return null
            rest = rest.substring(1)
        }
    }

    /** A name is only ever shown, never used as a path. It is still cut down to what a name needs. */
    fun tidy(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in " ._-()" }
        .trim().trimStart('.').trim()
        .take(MAX_NAME)
        .ifEmpty { FALLBACK_NAME }

    private fun startsWith(body: ByteArray, at: Int, what: ByteArray): Boolean {
        if (at < 0 || at + what.size > body.size) return false
        for (i in what.indices) if (body[at + i] != what[i]) return false
        return true
    }

    private fun indexOf(body: ByteArray, what: ByteArray, from: Int, until: Int): Int {
        var at = from
        while (at + what.size <= until) {
            if (body[at] == what[0] && startsWith(body, at, what)) return at
            at++
        }
        return -1
    }
}
