package io.github.munzzyy.tern.core.handoff

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesTest {
    private val limits = HandoffLimits()

    private fun everyAnswer(): List<Answer> = listOf(
        Pages.notFound(), Pages.badRequest(), Pages.headTooLong(), Pages.tooSlow(), Pages.lengthRequired(), Pages.busy(),
        Pages.usedUp(), Pages.closed(), Pages.page(null, limits),
    ) + Notice.entries.map { Pages.page(it, limits) }

    private fun head(answer: Answer): List<String> = String(answer.bytes(), Charsets.UTF_8).substringBefore("\r\n\r\n").split("\r\n")

    private fun html(answer: Answer): String = String(answer.body, Charsets.UTF_8)

    private fun between(html: String, open: String, close: String): String {
        assertEquals(open, 1, Regex(Regex.escape(open)).findAll(html).count())
        return html.substringAfter(open).substringBefore(close)
    }

    private fun hash(text: String): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    @Test
    fun everyAnswerCarriesTheHeadersThatKeepThePageToItself() {
        for (answer in everyAnswer()) {
            val lines = head(answer)
            assertEquals("HTTP/1.1 ${answer.status} ${answer.reason}", lines[0])
            val expected = listOf(
                "Content-Type: text/html; charset=utf-8",
                "Content-Length: ${answer.body.size}",
                "Content-Security-Policy: ${Pages.POLICY}",
                "X-Content-Type-Options: nosniff",
                "Referrer-Policy: no-referrer",
                "Cache-Control: no-store",
                "Connection: close",
            )
            assertEquals(lines[0], expected, lines.drop(1))
        }
    }

    @Test
    fun thePolicyAllowsTheScriptAndTheStyleThatAreServedAndNothingElse() {
        val html = html(Pages.page(null, limits))
        val script = between(html, "<script>", "</script>")
        val style = between(html, "<style>", "</style>")
        assertEquals(
            "default-src 'none'; script-src 'sha256-${hash(script)}'; style-src 'sha256-${hash(style)}'; " +
                "connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
            Pages.POLICY,
        )
        assertFalse(Pages.POLICY.contains("unsafe"))
        assertEquals(44, hash(script).length)
        for (answer in everyAnswer()) {
            val other = html(answer)
            assertEquals(style, between(other, "<style>", "</style>"))
            if (other.contains("<script")) assertEquals(script, between(other, "<script>", "</script>"))
        }
    }

    @Test
    fun theScriptAndTheStyleAreTheSameForEveryHandoff() {
        val one = html(Pages.page(null, limits))
        val other = html(Pages.page(Notice.FILE_TOO_LARGE, HandoffLimits(links = 3, linkLength = 50, fileBytes = 4096, lifeMs = 60_000)))
        assertEquals(between(one, "<script>", "</script>"), between(other, "<script>", "</script>"))
        assertEquals(between(one, "<style>", "</style>"), between(other, "<style>", "</style>"))
    }

    @Test
    fun aPageIsOneDocumentThatFetchesNothing() {
        for (answer in everyAnswer()) {
            val html = html(answer)
            assertTrue(html.startsWith("<!doctype html>"))
            for (fetching in listOf("http:", "https:", "//", "src=", "url(", "@import", "<img", "<iframe", "<object", "<embed", "stylesheet", "srcset")) {
                assertFalse("${answer.status} holds $fetching", html.contains(fetching, ignoreCase = true))
            }
            assertFalse("${answer.status} holds a link", html.contains("href", ignoreCase = true) || html.contains("<link", ignoreCase = true))
        }
    }

    @Test
    fun nothingInThePageRunsOrIsStyledFromAnAttribute() {
        for (answer in everyAnswer()) {
            val html = html(answer).replace(Regex("(?s)<script>.*</script>"), "")
            assertFalse(Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
            assertFalse(html.contains("style=", ignoreCase = true))
            assertFalse(html.contains("javascript:", ignoreCase = true))
        }
    }

    @Test
    fun thePageCannotSendWhatIsTypedIntoItAsAPlainForm() {
        val html = html(Pages.page(null, limits)).substringAfter("<body>").replace(Regex("(?s)<script>.*</script>"), "")
        assertFalse(html.contains(" name=", ignoreCase = true))
        assertFalse(html.contains("action=", ignoreCase = true))
        assertFalse(html.contains("method=", ignoreCase = true))
        assertTrue(html.contains("<form id=\"links-form\" "))
        assertTrue(html.contains("<form id=\"file-form\" "))
        assertTrue(html.contains("<textarea id=\"links\" "))
        assertTrue(html.contains("<input type=\"file\" id=\"file\" "))
        assertTrue(html.contains("<input type=\"text\" id=\"code\" "))
        assertEquals(2, Regex("<form ").findAll(html).count())
        assertEquals(2, Regex("<button type=\"submit\">").findAll(html).count())
    }

    @Test
    fun withoutItsScriptThePageOffersNothingToSend() {
        val html = html(Pages.page(null, limits))
        assertTrue(html.contains("<div id=\"sender\" hidden "))
        assertTrue(html.contains("<p class=\"notice bad\" id=\"cannot\" hidden>"))
        assertTrue(html.contains("[hidden]{display:none!important}"))
        val without = between(html, "<noscript>", "</noscript>")
        assertEquals("<p class=\"notice bad\">This page needs scripts to seal what you send, so with scripts turned off it offers nothing to send.</p>", without)
        assertTrue(html.indexOf("<form") > html.indexOf("<div id=\"sender\" hidden "))
        assertTrue(html.indexOf("</form>\n</div>") > html.lastIndexOf("<form"))
        assertFalse(html.contains("class=\"notice good\""))
        assertFalse(html.contains("id=\"notice\""))
    }

    @Test
    fun theScriptIsPlainTextThatEndsNowhereButAtItsEnd() {
        val script = PageScript.TEXT
        assertTrue(script.all { it == '\n' || it in ' '..'~' })
        for (not in listOf("</", "<!--", "crypto.subtle", "BigInt", "TextEncoder", "eval", "innerHTML", "document.write", "Function(", "import", "localStorage", "sessionStorage", "cookie")) {
            assertFalse(not, script.contains(not))
        }
        assertTrue(script.contains("if (typeof document !== 'undefined') start();"))
        assertTrue(script.contains("history.replaceState(null, '', location.pathname);"))
        assertTrue(script.contains("crypto.getRandomValues(nonce);"))
    }

    @Test
    fun everyNoticeIsSaidOnThePageInItsOwnSentence() {
        val sentences = Notice.entries.map { it.text(limits) }
        assertEquals(sentences.size, sentences.distinct().size)
        for (notice in Notice.entries) {
            val answer = Pages.page(notice, limits)
            val kind = if (notice.status == 200) "good" else "bad"
            assertTrue(notice.name, html(answer).contains("<p class=\"notice $kind\" id=\"notice\" role=\"status\">${notice.text(limits)}</p>"))
            assertEquals(1, Regex("id=\"notice\"").findAll(html(answer)).count())
            assertEquals(notice.status, answer.status)
        }
        assertEquals(setOf(Notice.LINKS_SENT, Notice.FILE_SENT), Notice.entries.filter { it.good }.toSet())
        assertEquals(listOf(Notice.DID_NOT_OPEN), Notice.entries.filter { it.status == 403 })
        assertEquals("That did not open with the code the other device shows. Type the code again.", Notice.DID_NOT_OPEN.text(limits))
    }

    @Test
    fun everyAnswerWithoutThePageSaysItsSentenceWhereThePageLooksForIt() {
        for (answer in listOf(Pages.notFound(), Pages.badRequest(), Pages.headTooLong(), Pages.tooSlow(), Pages.lengthRequired(), Pages.busy(), Pages.usedUp(), Pages.closed())) {
            val html = html(answer)
            assertEquals(1, Regex("<p class=\"notice bad\" id=\"notice\" role=\"status\">[^<]+</p>").findAll(html).count())
            assertFalse(html.contains("<script"))
            assertFalse(html.contains("<form"))
            assertFalse(answer.status == 403)
        }
    }

    @Test
    fun theLimitsOnThePageAreTheLimitsOfTheHandoff() {
        val html = html(Pages.page(null, limits))
        assertTrue(html.contains("One link on each line, 20 at most."))
        assertTrue(html.contains("2 MiB at most."))
        assertTrue(html.contains("10 minutes after it was opened"))
        assertTrue(html.contains(" data-most=\"20\" data-longest=\"2000\" "))
        assertTrue(html.contains(" data-most=\"2097152\" data-name=\"80\" "))
        assertTrue(html.contains(" data-too-many=\"${Notice.TOO_MANY_LINKS.text(limits)}\""))
        assertTrue(html.contains(" data-wrong-length=\"The code has 20 letters and digits. Type all of them.\""))
        val few = HandoffLimits(links = 3, linkLength = 50, fileBytes = 4096, lifeMs = 60_000)
        val small = html(Pages.page(Notice.FILE_TOO_LARGE, few))
        assertTrue(small.contains("One link on each line, 3 at most."))
        assertTrue(small.contains("The file is larger than 4 KiB"))
        assertTrue(small.contains("1 minute after it was opened"))
        assertTrue(small.contains(" data-most=\"3\" data-longest=\"50\" "))
        assertTrue(small.contains(" data-most=\"4096\" data-name=\"80\" "))
        assertTrue(small.contains(" data-too-large=\"${Notice.FILE_TOO_LARGE.text(few)}\""))
    }

    @Test
    fun theLongestBodyIsTheLargestFileSealedAndWrittenAsText() {
        assertEquals("sealed=".length + Seal.text(ByteArray(1 + 12 + 1 + 80 + 2 * 1024 * 1024 + 32)).length, limits.sendBytes)
        assertEquals(2_796_378, limits.sendBytes)
        val links = HandoffLimits(fileBytes = 4096, waitingBytes = 4096)
        assertEquals("sealed=".length + Seal.text(ByteArray(1 + 12 + 128 * 1024 + 32)).length, links.sendBytes)
    }

    @Test
    fun sizesAreSaidInTheLargestUnitThatIsExact() {
        assertEquals("2 MiB", Pages.size(2 * 1024 * 1024))
        assertEquals("512 KiB", Pages.size(512 * 1024))
        assertEquals("1000 bytes", Pages.size(1000))
    }
}
