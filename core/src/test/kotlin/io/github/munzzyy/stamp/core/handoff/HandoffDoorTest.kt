package io.github.munzzyy.stamp.core.handoff

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a phone that means well can do, and where that ends. Real sockets on the loopback address. */
class HandoffDoorTest {
    private val handoffs = Handoffs()
    private val limits = Handoffs.QUICK

    @After
    fun closeAll() = handoffs.closeAll()

    private fun said(reply: Reply?, notice: Notice): Boolean = reply!!.body.contains(">${notice.text(limits)}</p>")

    @Test
    fun theCodeLeadsToThePageAndWhatIsSentWaitsToBeTaken() {
        val phone = handoffs.open()
        assertEquals("http://127.0.0.1:${phone.port}/${phone.secret}", phone.server.secretAddress)
        val page = phone.get("/${phone.secret}")!!
        assertEquals(200, page.status)
        assertTrue(page.body.contains("action=\"/${phone.secret}/links\""))
        assertTrue(page.body.contains("action=\"/${phone.secret}/file\""))

        val links = phone.links("https://github.com/example/wren", "codeberg.org/example/dunnock")
        assertEquals(200, links!!.status)
        assertTrue(links.toString(), said(links, Notice.LINKS_SENT))
        assertTrue(links.body.contains("action=\"/${phone.secret}/links\""))
        assertEquals(2, phone.server.waiting())

        val export = "{\"format\":\"stamp-export\",\"schema\":1,\"apps\":[]}\r\n".toByteArray()
        val file = phone.file("stamp-apps-2026-09-29.json", export)
        assertEquals(200, file!!.status)
        assertTrue(file.toString(), said(file, Notice.FILE_SENT))
        assertEquals(3, phone.server.waiting())
        assertEquals(2, handoffs.changes.get())

        val taken = phone.server.take()
        assertEquals(listOf(HandoffItem.Link("https://github.com/example/wren"), HandoffItem.Link("codeberg.org/example/dunnock")), taken.take(2))
        val received = taken[2] as HandoffItem.ExportFile
        assertEquals("stamp-apps-2026-09-29.json", received.name)
        assertArrayEquals(export, received.bytes)
        assertEquals(0, phone.server.waiting())
        assertEquals(emptyList<HandoffItem>(), phone.server.take())
    }

    @Test
    fun thePinLeadsToThePageForAPhoneThatCannotScan() {
        val phone = handoffs.open()
        assertEquals("http://127.0.0.1:${phone.port}", phone.server.address)
        assertTrue(phone.server.pin, Regex("[0-9]{6}").matches(phone.server.pin))
        val asks = phone.get("/")!!
        assertEquals(200, asks.status)
        assertTrue(asks.body.contains("name=\"pin\""))
        assertFalse(asks.text.contains(phone.secret))

        val right = phone.pin(phone.server.pin)!!
        assertEquals(303, right.status)
        assertEquals("/${phone.secret}", right.header("Location"))
        assertEquals(200, phone.get(right.header("Location")!!)!!.status)
        assertEquals(303, phone.form("/pin", "pin=+${phone.server.pin}+")!!.status)
    }

    @Test
    fun aWrongPinIsToldSoAndFourOfThemLeaveTheHandoffOpen() {
        val phone = handoffs.open()
        val wrong = if (phone.server.pin == "000000") "000001" else "000000"
        for (guess in listOf(wrong, "", "12345", "${phone.server.pin}0")) {
            val reply = phone.pin(guess)!!
            assertEquals(guess, 403, reply.status)
            assertTrue(reply.toString(), said(reply, Notice.WRONG_PIN))
            assertTrue(reply.body.contains("name=\"pin\""))
            assertFalse(reply.text.contains(phone.secret))
            assertEquals(null, reply.header("Location"))
        }
        assertTrue(phone.server.isOpen)
        assertEquals(303, phone.pin(phone.server.pin)!!.status)
    }

    @Test
    fun twentyLinksAreTakenAndTwentyOneAreNot() {
        val phone = handoffs.open()
        val twenty = Array(20) { "https://example.org/app-$it" }
        assertTrue(said(phone.links(*twenty), Notice.LINKS_SENT))
        assertEquals(twenty.map { HandoffItem.Link(it) }, phone.server.take())

        val more = phone.links(*twenty, "https://example.org/app-20")!!
        assertEquals(413, more.status)
        assertTrue(more.toString(), said(more, Notice.TOO_MANY_LINKS))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aLinkOfTwoThousandCharactersIsTakenAndOneMoreIsNot() {
        val phone = handoffs.open()
        val longest = "https://example.org/" + "a".repeat(1980)
        assertTrue(said(phone.links(longest), Notice.LINKS_SENT))
        assertEquals(listOf(HandoffItem.Link(longest)), phone.server.take())

        val longer = phone.links("https://example.org/short", longest + "a")!!
        assertEquals(413, longer.status)
        assertTrue(longer.toString(), said(longer, Notice.LINK_TOO_LONG))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun linksThatAreNoLinksAreRefusedInASentence() {
        val phone = handoffs.open()
        assertTrue(said(phone.form("/${phone.secret}/links", "links="), Notice.NO_LINKS))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=+%0D%0A+"), Notice.NO_LINKS))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=https%3A%2F%2Fexample.org%2F%E2%80%AEgnp.exe"), Notice.LINK_UNREADABLE))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=a%00b"), Notice.LINK_UNREADABLE))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=a&links=b"), Notice.LINKS_UNREADABLE))
        assertTrue(said(phone.form("/${phone.secret}/links", "other=a"), Notice.LINKS_UNREADABLE))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=%FF"), Notice.LINKS_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/links", "text/plain", "links=a".toByteArray()), Notice.LINKS_UNREADABLE))
        assertTrue(said(phone.post("/${phone.secret}/links", "application/json", "[\"a\"]".toByteArray()), Notice.LINKS_UNREADABLE))
        assertTrue(said(phone.form("/${phone.secret}/links", "links=" + "a".repeat(limits.formBytes)), Notice.LINKS_TOO_LARGE))
        assertEquals(0, phone.server.waiting())
        assertEquals(0, handoffs.changes.get())
    }

    @Test
    fun aFileOfTwoMebibytesIsTakenAndOneByteMoreIsNot() {
        val phone = handoffs.open()
        assertEquals(2 * 1024 * 1024, limits.fileBytes)
        val full = ByteArray(limits.fileBytes) { (it * 31).toByte() }
        assertTrue(said(phone.file("full.json", full), Notice.FILE_SENT))
        assertArrayEquals(full, (phone.server.take().single() as HandoffItem.ExportFile).bytes)

        val over = phone.file("over.json", full + byteArrayOf(1))!!
        assertEquals(413, over.status)
        assertTrue(over.toString(), said(over, Notice.FILE_TOO_LARGE))
        val farOver = phone.file("far-over.json", ByteArray(3 * limits.fileBytes))!!
        assertTrue(farOver.toString(), said(farOver, Notice.FILE_TOO_LARGE))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aFormWithoutAFileIsToldSo() {
        val phone = handoffs.open()
        val empty = phone.file("", ByteArray(0))!!
        assertEquals(400, empty.status)
        assertTrue(empty.toString(), said(empty, Notice.NO_FILE))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun theNameOfAFileIsCutDownBeforeAnyoneSeesIt() {
        val phone = handoffs.open()
        assertTrue(said(phone.file("../../data/apps<script>\u202e.json", "{}".toByteArray()), Notice.FILE_SENT))
        assertEquals("appsscript.json", (phone.server.take().single() as HandoffItem.ExportFile).name)
    }

    @Test
    fun theFortyFirstThingIsRefusedUntilWhatWaitsIsTaken() {
        val phone = handoffs.open()
        val twenty = Array(20) { "https://example.org/app-$it" }
        assertTrue(said(phone.links(*twenty), Notice.LINKS_SENT))
        assertTrue(said(phone.links(*twenty.take(19).toTypedArray()), Notice.LINKS_SENT))
        assertTrue(said(phone.file("apps.json", "{}".toByteArray()), Notice.FILE_SENT))
        assertEquals(40, phone.server.waiting())

        val link = phone.links("https://example.org/one-more")!!
        assertEquals(429, link.status)
        assertTrue(link.toString(), said(link, Notice.TOO_MUCH_WAITS))
        assertTrue(said(phone.file("more.json", "{}".toByteArray()), Notice.TOO_MUCH_WAITS))
        assertEquals(40, phone.server.waiting())

        assertEquals(40, phone.server.take().size)
        assertTrue(said(phone.links("https://example.org/one-more"), Notice.LINKS_SENT))
        assertEquals(listOf(HandoffItem.Link("https://example.org/one-more")), phone.server.take())
    }

    @Test
    fun linksAreTakenAllOrNotAtAllWhenTheyDoNotAllFit() {
        val phone = handoffs.open()
        val twenty = Array(20) { "https://example.org/app-$it" }
        assertTrue(said(phone.links(*twenty), Notice.LINKS_SENT))
        assertTrue(said(phone.links(*twenty.take(15).toTypedArray()), Notice.LINKS_SENT))
        assertTrue(said(phone.links(*twenty.take(6).toTypedArray()), Notice.TOO_MUCH_WAITS))
        assertEquals(35, phone.server.waiting())
    }

    @Test
    fun filesThatWaitAreBoundedTogether() {
        val small = limits.copy(fileBytes = 4096, waitingBytes = 10_000)
        val phone = handoffs.open(small)
        assertTrue(phone.file("one.json", ByteArray(4096) { 'a'.code.toByte() })!!.body.contains(Notice.FILE_SENT.text(small)))
        assertTrue(phone.file("two.json", ByteArray(4096) { 'b'.code.toByte() })!!.body.contains(Notice.FILE_SENT.text(small)))
        val third = phone.file("three.json", ByteArray(4096) { 'c'.code.toByte() })!!
        assertEquals(429, third.status)
        assertTrue(third.toString(), third.body.contains(Notice.TOO_MUCH_WAITS.text(small)))
        assertEquals(listOf("one.json", "two.json"), phone.server.take().map { (it as HandoffItem.ExportFile).name })
        assertTrue(phone.file("three.json", ByteArray(4096))!!.body.contains(Notice.FILE_SENT.text(small)))
    }

    @Test
    fun openingAgainMakesANewSecretThatTheOldOneDoesNotOpen() {
        val first = handoffs.open()
        first.server.close()
        val second = handoffs.open()
        assertNotEquals(first.secret, second.secret)
        assertEquals(26, second.secret.length)
        assertEquals(404, second.get("/${first.secret}")!!.status)
        assertEquals(200, second.get("/${second.secret}")!!.status)
    }
}
