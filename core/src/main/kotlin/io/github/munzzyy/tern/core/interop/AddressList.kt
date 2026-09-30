package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.net.Urls

/**
 * The web addresses in any text: a list typed or pasted one a line, an OPML file of feeds, a page
 * of notes. Every https address is taken, in the order it first appears, cleaned of the punctuation
 * that ends a sentence. Plain http is left out, since Tern never follows it.
 */
object AddressList {
    const val MAX_ADDRESSES = 500
    private val ADDRESS = Regex("""https://[^\s"'<>`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?"

    fun read(text: String, max: Int = MAX_ADDRESSES): List<String> {
        val found = LinkedHashSet<String>()
        for (match in ADDRESS.findAll(text.take(MAX_TEXT))) {
            val address = Urls.normalize(unescape(trim(match.value))) ?: continue
            if (!Urls.isHttps(address)) continue
            found += address
            if (found.size >= max) break
        }
        return found.toList()
    }

    /** Punctuation that ends a sentence, and a closing bracket that nothing in the address opened. */
    private fun trim(address: String): String {
        var end = address.length
        while (end > 0) {
            val last = address[end - 1]
            val unopened = when (last) {
                ')' -> address.substring(0, end).count { it == '(' } < address.substring(0, end).count { it == ')' }
                ']' -> address.substring(0, end).count { it == '[' } < address.substring(0, end).count { it == ']' }
                else -> false
            }
            if (last in TRAILING || unopened) end-- else break
        }
        return address.substring(0, end)
    }

    /** OPML and HTML write & as &amp; inside an attribute. */
    private fun unescape(address: String): String = address.replace("&amp;", "&")

    private const val MAX_TEXT = 2 * 1024 * 1024
}
