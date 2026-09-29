package io.github.munzzyy.stamp.core.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesTest {
    private val limits = HandoffLimits()
    private val secret = "mfrggzdfmztwq2lknnwg23tpoa"

    private fun everyAnswer(): List<Answer> = listOf(
        Pages.notFound(), Pages.badRequest(), Pages.headTooLong(), Pages.tooSlow(), Pages.lengthRequired(), Pages.busy(),
        Pages.usedUp(), Pages.closed(), Pages.pinClosed(), Pages.pinRight(secret), Pages.pinPage(null, limits), Pages.page(secret, null, limits),
    ) + Notice.entries.map { Pages.page(secret, it, limits) } + Pages.pinPage(Notice.WRONG_PIN, limits)

    private fun head(answer: Answer): List<String> = String(answer.bytes(), Charsets.UTF_8).substringBefore("\r\n\r\n").split("\r\n")

    private fun html(answer: Answer): String = String(answer.body, Charsets.UTF_8)

    @Test
    fun everyAnswerCarriesTheHeadersThatKeepThePageToItself() {
        for (answer in everyAnswer()) {
            val lines = head(answer)
            assertEquals("HTTP/1.1 ${answer.status} ${answer.reason}", lines[0])
            val expected = listOf(
                "Content-Type: text/html; charset=utf-8",
                "Content-Length: ${answer.body.size}",
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
                    "form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
                "X-Content-Type-Options: nosniff",
                "Referrer-Policy: no-referrer",
                "Cache-Control: no-store",
                "Connection: close",
            )
            assertEquals(lines[0], expected, lines.drop(1).filterNot { it.startsWith("Location: ") })
        }
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
    fun thePageSendsItsTwoFormsToTheSecretAddressAsPlainForms() {
        val html = html(Pages.page(secret, null, limits))
        assertTrue(html.contains("<form method=\"post\" action=\"/$secret/links\" id=\"links-form\">"))
        assertTrue(html.contains("<textarea id=\"links\" name=\"links\""))
        assertTrue(html.contains("<form method=\"post\" action=\"/$secret/file\" enctype=\"multipart/form-data\" id=\"file-form\">"))
        assertTrue(html.contains("<input type=\"file\" id=\"file\" name=\"file\""))
        assertEquals(2, Regex("<form ").findAll(html).count())
        assertEquals(2, Regex("<button type=\"submit\">").findAll(html).count())
        assertFalse(html.contains("class=\"notice"))
    }

    @Test
    fun thePageForThePinKnowsNothingOfTheSecret() {
        for (answer in listOf(Pages.pinPage(null, limits), Pages.pinPage(Notice.WRONG_PIN, limits), Pages.pinClosed())) {
            assertFalse(String(answer.bytes(), Charsets.UTF_8).contains(secret))
        }
        val html = html(Pages.pinPage(null, limits))
        assertTrue(html.contains("<form method=\"post\" action=\"/pin\">"))
        assertTrue(html.contains("name=\"pin\""))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun theRightPinSendsTheBrowserOnToTheSecretAddress() {
        val answer = Pages.pinRight(secret)
        assertEquals(303, answer.status)
        assertTrue(head(answer).contains("Location: /$secret"))
        assertFalse(html(answer).contains(secret))
    }

    @Test
    fun everyNoticeIsSaidOnThePageInItsOwnSentence() {
        val sentences = Notice.entries.map { it.text(limits) }
        assertEquals(sentences.size, sentences.distinct().size)
        for (notice in Notice.entries) {
            val answer = Pages.page(secret, notice, limits)
            val kind = if (notice.status == 200) "good" else "bad"
            assertTrue(notice.name, html(answer).contains("<p class=\"notice $kind\" role=\"status\">${notice.text(limits)}</p>"))
            assertEquals(notice.status, answer.status)
        }
        assertEquals(setOf(Notice.LINKS_SENT, Notice.FILE_SENT), Notice.entries.filter { it.good }.toSet())
    }

    @Test
    fun theLimitsOnThePageAreTheLimitsOfTheHandoff() {
        val html = html(Pages.page(secret, null, limits))
        assertTrue(html.contains("One link on each line, 20 at most."))
        assertTrue(html.contains("2 MiB at most."))
        assertTrue(html.contains("10 minutes after it was opened"))
        assertTrue(html.contains("lines.length>20"))
        assertTrue(html.contains("l.length>2000"))
        assertTrue(html.contains("files[0].size>2097152"))
        val small = html(Pages.page(secret, Notice.FILE_TOO_LARGE, HandoffLimits(links = 3, linkLength = 50, fileBytes = 4096, lifeMs = 60_000)))
        assertTrue(small.contains("One link on each line, 3 at most."))
        assertTrue(small.contains("The file is larger than 4 KiB"))
        assertTrue(small.contains("1 minute after it was opened"))
        assertTrue(small.contains("files[0].size>4096"))
    }

    @Test
    fun sizesAreSaidInTheLargestUnitThatIsExact() {
        assertEquals("2 MiB", Pages.size(2 * 1024 * 1024))
        assertEquals("512 KiB", Pages.size(512 * 1024))
        assertEquals("1000 bytes", Pages.size(1000))
    }
}
