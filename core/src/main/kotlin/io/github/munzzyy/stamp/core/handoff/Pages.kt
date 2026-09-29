package io.github.munzzyy.stamp.core.handoff

/** What the page says about the last thing that was sent. Every sentence is fixed; none holds a word that arrived. */
internal enum class Notice(val status: Int, val reason: String) {
    LINKS_SENT(200, "OK"),
    FILE_SENT(200, "OK"),
    NO_LINKS(400, "Bad Request"),
    LINKS_UNREADABLE(400, "Bad Request"),
    LINK_UNREADABLE(400, "Bad Request"),
    TOO_MANY_LINKS(413, "Content Too Large"),
    LINK_TOO_LONG(413, "Content Too Large"),
    LINKS_TOO_LARGE(413, "Content Too Large"),
    NO_FILE(400, "Bad Request"),
    FILE_UNREADABLE(400, "Bad Request"),
    FILE_TOO_LARGE(413, "Content Too Large"),
    TOO_MUCH_WAITS(429, "Too Many Requests"),
    WRONG_PIN(403, "Forbidden"),
    ;

    val good: Boolean get() = status == 200

    fun text(limits: HandoffLimits): String = when (this) {
        LINKS_SENT -> "The links have arrived. Look at them on the other device."
        FILE_SENT -> "The file has arrived. Look at it on the other device."
        NO_LINKS -> "There is no link to take. Put one link on each line."
        LINKS_UNREADABLE -> "What arrived was not the form of this page, so nothing was taken."
        LINK_UNREADABLE -> "One of the links holds characters that cannot be shown, so none of them was taken."
        TOO_MANY_LINKS -> "Those are more than ${limits.links} links, so none of them was taken. Send them in lots of ${limits.links} or fewer."
        LINK_TOO_LONG -> "One of the links is longer than ${limits.linkLength} characters, so none of them was taken."
        LINKS_TOO_LARGE -> "That is more text than ${limits.links} links take, so nothing was taken."
        NO_FILE -> "No file was picked."
        FILE_UNREADABLE -> "What arrived was not one file from the form of this page, so nothing was taken."
        FILE_TOO_LARGE -> "The file is larger than ${Pages.size(limits.fileBytes)}, which is more than a list of apps takes, so it was not taken."
        TOO_MUCH_WAITS -> "Too much is waiting on the other device already. Look at it there first, then send this again."
        WRONG_PIN -> "That is not the PIN the other device shows."
    }
}

internal class Answer(val status: Int, val reason: String, html: String, val location: String? = null) {
    val body: ByteArray = html.toByteArray(Charsets.UTF_8)

    fun bytes(): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        if (location != null) head.append("Location: ").append(location).append("\r\n")
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

/** Every page the handoff can answer with. They are put together from fixed text, the limits and the secret, and from nothing else. */
internal object Pages {
    const val POLICY = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
    const val LINKS_FIELD = "links"
    const val FILE_FIELD = "file"
    const val PIN_FIELD = "pin"
    private const val TITLE = "Send to Stamp"
    private const val MIB = 1024 * 1024

    fun notFound() = bare(404, "Not Found", "There is nothing at this address.")

    fun badRequest() = bare(400, "Bad Request", "That request could not be read.")

    fun headTooLong() = bare(431, "Request Header Fields Too Large", "That request is longer than this page takes.")

    fun tooSlow() = bare(408, "Request Timeout", "That request took too long to arrive.")

    fun lengthRequired() = bare(411, "Length Required", "A request that sends something has to say how long it is, and send it in one piece.")

    fun busy() = bare(503, "Service Unavailable", "Too many connections are open at once. Try again in a moment.")

    fun usedUp() = bare(429, "Too Many Requests", "This handoff has answered as many requests as it will, so it is closed. Open a new one on the other device.")

    fun closed() = bare(410, "Gone", "The handoff is closed. Open a new one on the other device.")

    fun pinClosed() = bare(403, "Forbidden", "Too many wrong PINs were typed, so the handoff is closed. Open a new one on the other device.")

    fun pinRight(secret: String) = Answer(303, "See Other", document("<p>The PIN is right. Your browser goes on by itself.</p>"), "/$secret")

    fun pinPage(notice: Notice?, limits: HandoffLimits) = Answer(
        notice?.status ?: 200,
        notice?.reason ?: "OK",
        document(
            notice(notice, limits) +
                "<form method=\"post\" action=\"/pin\">" +
                "<label for=\"pin\">Type the six digits that the other device shows next to this address.</label>" +
                "<input type=\"text\" id=\"pin\" name=\"$PIN_FIELD\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" maxlength=\"6\" autocomplete=\"off\" required>" +
                "<button type=\"submit\">Go on</button>" +
                "</form>",
        ),
    )

    fun page(secret: String, notice: Notice?, limits: HandoffLimits) = Answer(
        notice?.status ?: 200,
        notice?.reason ?: "OK",
        document(
            notice(notice, limits) +
                "<p>What you send from here arrives in Stamp on the device that showed you this page. " +
                "Nothing is added there until someone has looked at it on that device and said yes.</p>" +
                "<form method=\"post\" action=\"/$secret/links\" id=\"links-form\">" +
                "<h2>Links</h2>" +
                "<label for=\"links\">One link on each line, ${limits.links} at most.</label>" +
                "<textarea id=\"links\" name=\"$LINKS_FIELD\" rows=\"6\" autocapitalize=\"off\" autocomplete=\"off\" spellcheck=\"false\" required></textarea>" +
                "<p class=\"problem\" id=\"links-problem\" hidden></p>" +
                "<button type=\"submit\">Send the links</button>" +
                "</form>" +
                "<form method=\"post\" action=\"/$secret/file\" enctype=\"multipart/form-data\" id=\"file-form\">" +
                "<h2>A list of apps</h2>" +
                "<label for=\"file\">A file exported from Stamp or Obtainium, ${size(limits.fileBytes)} at most.</label>" +
                "<input type=\"file\" id=\"file\" name=\"$FILE_FIELD\" accept=\".json,application/json\" required>" +
                "<p class=\"problem\" id=\"file-problem\" hidden></p>" +
                "<button type=\"submit\">Send the file</button>" +
                "</form>" +
                "<p class=\"small\">This page works until the handoff is closed on the other device. It closes by itself ${minutes(limits.lifeMs)} after it was opened.</p>" +
                script(limits),
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

    private fun bare(status: Int, reason: String, sentence: String) = Answer(status, reason, document("<p>${escape(sentence)}</p>"))

    private fun notice(notice: Notice?, limits: HandoffLimits): String {
        if (notice == null) return ""
        val kind = if (notice.good) "good" else "bad"
        return "<p class=\"notice $kind\" role=\"status\">${escape(notice.text(limits))}</p>"
    }

    private fun document(inside: String): String =
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>$TITLE</title>\n<style>$STYLE</style>\n</head>\n" +
            "<body>\n<main>\n<h1>$TITLE</h1>\n$inside\n</main>\n</body>\n</html>\n"

    private fun script(limits: HandoffLimits): String =
        "<script>(function(){" +
            "function say(id,text){var p=document.getElementById(id);p.textContent=text;p.hidden=!text;}" +
            "document.getElementById('links-form').addEventListener('submit',function(e){" +
            "var lines=document.getElementById('links').value.split(/\\r\\n|\\r|\\n/)" +
            ".map(function(l){return l.trim();}).filter(function(l){return l;});" +
            "var problem='';" +
            "if(!lines.length)problem=${quoted(Notice.NO_LINKS.text(limits))};" +
            "else if(lines.length>${limits.links})problem=${quoted(Notice.TOO_MANY_LINKS.text(limits))};" +
            "else if(lines.some(function(l){return l.length>${limits.linkLength};}))problem=${quoted(Notice.LINK_TOO_LONG.text(limits))};" +
            "say('links-problem',problem);if(problem)e.preventDefault();});" +
            "document.getElementById('file-form').addEventListener('submit',function(e){" +
            "var files=document.getElementById('file').files;var problem='';" +
            "if(!files||!files.length)problem=${quoted(Notice.NO_FILE.text(limits))};" +
            "else if(files[0].size>${limits.fileBytes})problem=${quoted(Notice.FILE_TOO_LARGE.text(limits))};" +
            "say('file-problem',problem);if(problem)e.preventDefault();});" +
            "})();</script>"

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun quoted(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c").replace("\n", "\\n").replace("\r", "\\r") + "'"

    private const val STYLE =
        ":root{color-scheme:light dark}" +
            "body{margin:0;font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:34rem;margin:0 auto;padding:1rem}" +
            "h1{font-size:1.5rem;margin:.5rem 0 1rem}" +
            "h2{font-size:1.1rem;margin:0 0 .25rem}" +
            "form{margin:1rem 0;padding:1rem;border:1px solid #8888;border-radius:.75rem}" +
            "label{display:block;margin-bottom:.5rem}" +
            "textarea,input[type=text]{box-sizing:border-box;width:100%;font:inherit;padding:.5rem}" +
            "input[type=file]{display:block;max-width:100%;font:inherit}" +
            "button{font:inherit;margin-top:.75rem;padding:.6rem 1.2rem}" +
            ".notice{padding:.75rem 1rem;border:2px solid;border-radius:.5rem}" +
            ".good{border-color:#2e7d32}" +
            ".bad{border-color:#c62828}" +
            ".problem{margin:.5rem 0 0;color:#c62828;font-weight:600}" +
            ".small{font-size:.9rem}" +
            "[hidden]{display:none!important}" +
            "@media(prefers-color-scheme:dark){.problem{color:#ff8a80}.good{border-color:#81c784}.bad{border-color:#ff8a80}}"
}
