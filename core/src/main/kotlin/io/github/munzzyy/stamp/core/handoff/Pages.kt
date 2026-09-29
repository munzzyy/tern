package io.github.munzzyy.stamp.core.handoff

import java.security.MessageDigest
import java.util.Base64

/** What the page says about the last thing that was sent. Every sentence is fixed; none holds a word that arrived. */
internal enum class Notice(val status: Int, val reason: String) {
    LINKS_SENT(200, "OK"),
    FILE_SENT(200, "OK"),

    /** The only answer with this status, which is how the page knows to ask for the code again. */
    DID_NOT_OPEN(403, "Forbidden"),
    UNREADABLE(400, "Bad Request"),
    TOO_LARGE(413, "Content Too Large"),
    NO_LINKS(400, "Bad Request"),
    LINK_UNREADABLE(400, "Bad Request"),
    TOO_MANY_LINKS(413, "Content Too Large"),
    LINK_TOO_LONG(413, "Content Too Large"),
    LINKS_TOO_LARGE(413, "Content Too Large"),
    NO_FILE(400, "Bad Request"),
    FILE_TOO_LARGE(413, "Content Too Large"),
    TOO_MUCH_WAITS(429, "Too Many Requests"),
    ;

    val good: Boolean get() = status == 200

    fun text(limits: HandoffLimits): String = when (this) {
        LINKS_SENT -> "The links have arrived. Look at them on the other device."
        FILE_SENT -> "The file has arrived. Look at it on the other device."
        DID_NOT_OPEN -> "That did not open with the code the other device shows. Type the code again."
        UNREADABLE -> "What arrived was not what this page sends, so nothing was taken."
        TOO_LARGE -> "That is more than this page takes, so nothing was taken."
        NO_LINKS -> "There is no link to take. Put one link on each line."
        LINK_UNREADABLE -> "One of the links holds characters that cannot be shown, so none of them was taken."
        TOO_MANY_LINKS -> "Those are more than ${limits.links} links, so none of them was taken. Send them in lots of ${limits.links} or fewer."
        LINK_TOO_LONG -> "One of the links is longer than ${limits.linkLength} characters, so none of them was taken."
        LINKS_TOO_LARGE -> "That is more text than ${limits.links} links take, so nothing was taken."
        NO_FILE -> "No file was picked, or the file is empty."
        FILE_TOO_LARGE -> "The file is larger than ${Pages.size(limits.fileBytes)}, which is more than a list of apps takes, so it was not taken."
        TOO_MUCH_WAITS -> "Too much is waiting on the other device already. Look at it there first, then send this again."
    }
}

internal class Answer(val status: Int, val reason: String, html: String) {
    val body: ByteArray = html.toByteArray(Charsets.UTF_8)

    fun bytes(): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        head.append("Content-Type: text/html; charset=utf-8\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Content-Security-Policy: ").append(Pages.POLICY).append("\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        head.append("Referrer-Policy: no-referrer\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: close\r\n\r\n")
        return head.toString().toByteArray(Charsets.US_ASCII) + body
    }
}

/** Every page the handoff can answer with. They are put together from fixed text and the limits, and from nothing else. */
internal object Pages {
    private const val TITLE = "Send to Stamp"
    private const val MIB = 1024 * 1024

    private const val STYLE =
        ":root{color-scheme:light dark}" +
            "body{margin:0;font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:34rem;margin:0 auto;padding:1rem}" +
            "h1{font-size:1.5rem;margin:.5rem 0 1rem}" +
            "h2{font-size:1.1rem;margin:0 0 .25rem}" +
            "form,#code-part{margin:1rem 0;padding:1rem;border:1px solid #8888;border-radius:.75rem}" +
            "label{display:block;margin-bottom:.5rem}" +
            "textarea,input[type=text]{box-sizing:border-box;width:100%;font:inherit;padding:.5rem}" +
            "#code{font-family:monospace}" +
            "input[type=file]{display:block;max-width:100%;font:inherit}" +
            "button{font:inherit;margin-top:.75rem;padding:.6rem 1.2rem}" +
            ".notice{padding:.75rem 1rem;border:2px solid;border-radius:.5rem}" +
            ".good{border-color:#2e7d32}" +
            ".bad{border-color:#c62828}" +
            ".small{font-size:.9rem}" +
            "[hidden]{display:none!important}" +
            "@media(prefers-color-scheme:dark){.good{border-color:#81c784}.bad{border-color:#ff8a80}}"

    /** The script and the style are allowed by the hash of their very bytes, and nothing else runs or is fetched. */
    val POLICY: String = "default-src 'none'; script-src 'sha256-${hash(PageScript.TEXT)}'; style-src 'sha256-${hash(STYLE)}'; " +
        "connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

    fun notFound() = bare(404, "Not Found", "There is nothing at this address.")

    fun badRequest() = bare(400, "Bad Request", "That request could not be read.")

    fun headTooLong() = bare(431, "Request Header Fields Too Large", "That request is longer than this page takes.")

    fun tooSlow() = bare(408, "Request Timeout", "That request took too long to arrive.")

    fun lengthRequired() = bare(411, "Length Required", "A request that sends something has to say how long it is, and send it in one piece.")

    fun busy() = bare(503, "Service Unavailable", "The other device has too much to do at once. Try again in a moment.")

    fun usedUp() = bare(429, "Too Many Requests", "This handoff has answered as many requests as it will, so it is closed. Open a new one on the other device.")

    fun closed() = bare(410, "Gone", "The handoff is closed. Open a new one on the other device.")

    fun page(notice: Notice?, limits: HandoffLimits) = Answer(
        notice?.status ?: 200,
        notice?.reason ?: "OK",
        document(
            (if (notice == null) "" else said(notice.text(limits), notice.good) + "\n") +
                "<noscript><p class=\"notice bad\">This page needs scripts to seal what you send, so with scripts turned off it offers nothing to send.</p></noscript>\n" +
                "<p class=\"notice bad\" id=\"cannot\" hidden>This browser cannot seal what you send. Open this page in another browser.</p>\n" +
                "<div id=\"sender\" hidden data-no-answer=\"The other device did not answer. Look there whether the handoff is still open.\">\n" +
                "<p>What you send from here is sealed with the code that the other device shows, and arrives in Stamp on that device. " +
                "Nothing is added there until someone has looked at it on that device and said yes.</p>\n" +
                "<div id=\"code-part\"" +
                " data-wrong-character=\"The code has letters and the digits 2 to 7 and nothing else. Look at the other device and type it again.\"" +
                " data-wrong-length=\"The code has ${Secrets.CODE_LENGTH} letters and digits. Type all of them.\">\n" +
                "<label for=\"code\">Type the code that the other device shows.</label>\n" +
                "<input type=\"text\" id=\"code\" autocomplete=\"off\" autocapitalize=\"none\" autocorrect=\"off\" spellcheck=\"false\">\n" +
                "<p id=\"code-said\" role=\"status\" hidden></p>\n" +
                "</div>\n" +
                "<form id=\"links-form\" data-most=\"${limits.links}\" data-longest=\"${limits.linkLength}\"" +
                " data-none=\"${escape(Notice.NO_LINKS.text(limits))}\"" +
                " data-too-many=\"${escape(Notice.TOO_MANY_LINKS.text(limits))}\"" +
                " data-too-long=\"${escape(Notice.LINK_TOO_LONG.text(limits))}\">\n" +
                "<h2>Links</h2>\n" +
                "<label for=\"links\">One link on each line, ${limits.links} at most.</label>\n" +
                "<textarea id=\"links\" rows=\"6\" autocomplete=\"off\" autocapitalize=\"none\" autocorrect=\"off\" spellcheck=\"false\"></textarea>\n" +
                "<p id=\"links-said\" role=\"status\" hidden></p>\n" +
                "<button type=\"submit\">Send the links</button>\n" +
                "</form>\n" +
                "<form id=\"file-form\" data-most=\"${limits.fileBytes}\" data-name=\"${Forms.MAX_NAME}\"" +
                " data-none=\"${escape(Notice.NO_FILE.text(limits))}\"" +
                " data-too-large=\"${escape(Notice.FILE_TOO_LARGE.text(limits))}\"" +
                " data-unread=\"The browser could not read that file, so nothing was sent.\">\n" +
                "<h2>A list of apps</h2>\n" +
                "<label for=\"file\">A file exported from Stamp or Obtainium, ${size(limits.fileBytes)} at most.</label>\n" +
                "<input type=\"file\" id=\"file\" accept=\".json,application/json\">\n" +
                "<p id=\"file-said\" role=\"status\" hidden></p>\n" +
                "<button type=\"submit\">Send the file</button>\n" +
                "</form>\n" +
                "</div>\n" +
                "<p class=\"small\">This page works until the handoff is closed on the other device. It closes by itself ${minutes(limits.lifeMs)} after it was opened.</p>\n" +
                "<script>${PageScript.TEXT}</script>",
        ),
    )

    fun size(bytes: Int): String = when {
        bytes % MIB == 0 -> "${bytes / MIB} MiB"
        bytes % 1024 == 0 -> "${bytes / 1024} KiB"
        else -> "$bytes bytes"
    }

    private fun minutes(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes == 1L) "1 minute" else "$minutes minutes"
    }

    private fun bare(status: Int, reason: String, sentence: String) = Answer(status, reason, document(said(sentence, false)))

    /** The one place in an answer where the page looks for what the other device said. */
    private fun said(sentence: String, good: Boolean): String =
        "<p class=\"notice ${if (good) "good" else "bad"}\" id=\"notice\" role=\"status\">${escape(sentence)}</p>"

    private fun document(inside: String): String =
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>$TITLE</title>\n<style>$STYLE</style>\n</head>\n" +
            "<body>\n<main>\n<h1>$TITLE</h1>\n$inside\n</main>\n</body>\n</html>\n"

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun hash(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
