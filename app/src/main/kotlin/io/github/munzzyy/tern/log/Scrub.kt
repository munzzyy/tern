package io.github.munzzyy.tern.log

/**
 * Text made fit to keep in the log, which may be shared: nothing in it may open an account or
 * name a person. Every address loses its query, its fragment and any name and password in front
 * of its host, also an address inside another. What looks like a token becomes [GONE]: GitHub's
 * and GitLab's by their prefix, a JSON web token, what follows Bearer or an Authorization or
 * Cookie header, a code after a word such as key, token, secret or session, and anything set as
 * a password, however short. So do an email address and the device's own address in a
 * connection that failed.
 *
 * It leaves out too much rather than too little: a fingerprint after the word key goes too.
 */
object Scrub {
    /** What stands where something was taken out. */
    const val GONE = "\u2026"

    /** A code after a word of [NAMED] is taken for a secret from this length when it has a digit, a sign or a capital inside it. */
    private const val SHORT = 8

    /** A code after such a word is taken for a secret from this length, whatever it has. */
    private const val LONG = 20

    private val USER_INFO = Regex("""(://)[^\s/?#]*@""")

    /** The same, in an address that is itself the escaped part of another, as in obtainium://add/https%3A%2F%2Fme%40example.org */
    private val ESCAPED_USER_INFO = Regex("""(?i)(%3A%2F%2F)(?:(?!%2F|%3F|%23)[^\s/?#])*%40""")

    private val ADDRESS = Regex("""(?<![A-Za-z0-9+.\-])[A-Za-z][A-Za-z0-9+.\-]*+://[^\s"'<>`]*+""")

    /** Marks that end a sentence around an address rather than belong to it. */
    private const val CLOSING = ".,;:!)]}"

    private val KNOWN_TOKEN = Regex("""gh[pousr]_[A-Za-z0-9_]++|github_pat_[A-Za-z0-9_]++|gl(?:pat|dt|rt|ptt|ft|imt|agent|soat|cbt|oas)-[A-Za-z0-9_\-]++""")

    private val JSON_WEB_TOKEN = Regex("""eyJ[A-Za-z0-9_\-]++\.[A-Za-z0-9_\-]++(?:\.[A-Za-z0-9_\-]++)?""")

    private val AUTHORIZATION = Regex("""(?i)(authorization["']?\s?[:=]\s?["']?)(?:(bearer|basic|token|digest)(\s++))?[^\s"',;]++""")

    private val COOKIE = Regex("""(?i)((?:set-)?cookie["']?\s?:\s?["']?)[^\r\n"']++""")

    private val BEARER = Regex("""(?i)\b(bearer\s++)[A-Za-z0-9._~+/=%\-]++""")

    private val BASIC = Regex("""\b(Basic\s++)([A-Za-z0-9+/]++={0,2})""")

    private val NAMED = Regex("""(?i)(token|secret|passw(?:or)?d|pwd|passphrase|credentials?|key|auth|session(?:[_-]?id)?|(?<![a-z])sid|cookie)(\s?["']?\s?[:=]\s?["']?\s?|\s)([A-Za-z0-9._~+/=%\-]++)""")

    /** Words whose value goes at any length once it is set with = or :, since a password or a cookie can be short. */
    private val ALWAYS_SECRET = Regex("""(?i)passw(?:or)?d|pwd|passphrase|cookie""")

    /** The device's own address, as Android names it when a connection fails: "from /192.168.1.23 (port 43210)". */
    private val OWN_ADDRESS = Regex("""(?i)(\bfrom )[\w.\-]*/[0-9a-f:.]++(?:%[\w.\-]++)?(?= \(port \d)""")

    private val EMAIL = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]++@[A-Za-z0-9\-]++(?:\.[A-Za-z0-9\-]++)*\.[A-Za-z]{2,}+""")

    fun text(text: String): String {
        var s = USER_INFO.replace(text, "$1")
        s = ESCAPED_USER_INFO.replace(s, "$1")
        s = ADDRESS.replace(s) { withoutQuery(it.value) }
        s = KNOWN_TOKEN.replace(s, GONE)
        s = JSON_WEB_TOKEN.replace(s, GONE)
        s = AUTHORIZATION.replace(s) { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] + GONE }
        s = COOKIE.replace(s) { it.groupValues[1] + GONE }
        s = BEARER.replace(s) { it.groupValues[1] + GONE }
        s = BASIC.replace(s) { if (isBase64(it.groupValues[2])) it.groupValues[1] + GONE else it.value }
        s = named(s)
        s = OWN_ADDRESS.replace(s) { it.groupValues[1] + GONE }
        return EMAIL.replace(s, GONE)
    }

    /** [url] up to its query or fragment, with the marks after it that close the sentence. */
    private fun withoutQuery(url: String): String {
        val end = url.length - url.takeLastWhile { it in CLOSING }.length
        val cut = url.indexOfAny(charArrayOf('?', '#')).takeIf { it in 0 until end } ?: end
        return url.substring(0, cut) + url.substring(end)
    }

    /**
     * Every code that follows a word of [NAMED] and looks secret, taken out. A word that is not
     * followed by one is passed over by its own length only, so the code in "key token a1b2c3d4"
     * is still found after "token".
     */
    private fun named(text: String): String {
        val out = StringBuilder(text.length)
        var copied = 0
        var from = 0
        while (true) {
            val found = NAMED.find(text, from) ?: break
            val value = found.groups[3]!!
            val set = found.groupValues[2].any { it == ':' || it == '=' }
            if (looksSecret(value.value) || set && ALWAYS_SECRET.matches(found.groupValues[1])) {
                out.append(text, copied, value.range.first).append(GONE)
                copied = value.range.last + 1
                from = copied
            } else {
                from = found.groups[1]!!.range.last + 1
            }
        }
        return out.append(text, copied, text.length).toString()
    }

    private fun looksSecret(value: String): Boolean =
        value.length >= LONG || value.length >= SHORT && (value.any { it.isDigit() || it in "_+/=%~" } || mixedCase(value))

    /** A capital after a small letter inside one word, which random letters have and the words of a sentence seldom do. */
    private fun mixedCase(value: String): Boolean = value.zipWithNext().any { (a, b) -> a.isLowerCase() && b.isUpperCase() }

    /** What "user:password" looks like in base64: at least eight characters, in blocks of four. */
    private fun isBase64(value: String): Boolean = value.length >= SHORT && value.length % 4 == 0
}
