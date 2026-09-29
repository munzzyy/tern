package io.github.munzzyy.tern.core.handoff

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
    fun thePageIsAtTheBareAddressAndWhatIsSentSealedWaitsToBeTaken() {
        val phone = handoffs.open()
        assertEquals("http://127.0.0.1:${phone.port}", phone.server.address)
        val page = phone.get("/")!!
        assertEquals(200, page.status)
        assertTrue(page.body.contains("<form id=\"links-form\""))
        assertTrue(page.body.contains("<form id=\"file-form\""))

        val links = phone.links("https://github.com/example/wren", "codeberg.org/example/dunnock")
        assertEquals(200, links!!.status)
        assertTrue(links.toString(), said(links, Notice.LINKS_SENT))
        assertTrue(links.body.contains("<form id=\"links-form\""))
        assertEquals(2, phone.server.waiting())

        val export = "{\"format\":\"tern-export\",\"schema\":1,\"apps\":[]}\r\n".toByteArray()
        val file = phone.file("tern-apps-2026-09-29.json", export)
        assertEquals(200, file!!.status)
        assertTrue(file.toString(), said(file, Notice.FILE_SENT))
        assertEquals(3, phone.server.waiting())
        assertEquals(2, handoffs.changes.get())

        val taken = phone.server.take()
        assertEquals(listOf(HandoffItem.Link("https://github.com/example/wren"), HandoffItem.Link("codeberg.org/example/dunnock")), taken.take(2))
        val received = taken[2] as HandoffItem.ExportFile
        assertEquals("tern-apps-2026-09-29.json", received.name)
        assertArrayEquals(export, received.bytes)
        assertEquals(0, phone.server.waiting())
        assertEquals(emptyList<HandoffItem>(), phone.server.take())
    }

    @Test
    fun theCodeIsShownInGroupsAndNoAnswerEverHoldsIt() {
        val phone = handoffs.open()
        assertTrue(phone.server.code, Regex("[a-z2-7]{4}( [a-z2-7]{4}){4}").matches(phone.server.code))
        assertEquals("http://127.0.0.1:${phone.port}/#${phone.code}", phone.server.addressWithCode)
        val answers = listOfNotNull(
            phone.get("/"),
            phone.get("/nothing"),
            phone.links("https://example.org/app"),
            phone.file("apps.json", "{}".toByteArray()),
            phone.send(Seal(if (phone.code == "a".repeat(20)) "b".repeat(20) else "a".repeat(20)).seal(Seal.LINKS, ByteArray(12), "example.org".toByteArray())),
            phone.form("/send", "sealed=AAAA"),
        )
        assertEquals(6, answers.size)
        for (answer in answers) {
            assertFalse(answer.toString(), answer.text.contains(phone.code, ignoreCase = true))
            assertFalse(answer.toString(), answer.text.contains(phone.server.code, ignoreCase = true))
            assertFalse(answer.toString(), answer.text.contains(phone.code.take(8), ignoreCase = true))
            assertFalse(answer.toString(), answer.text.contains(phone.code.takeLast(8), ignoreCase = true))
        }
    }

    @Test
    fun whatWasSealedWithAnotherCodeDoesNotOpenAndDoesNotCloseTheHandoff() {
        val phone = handoffs.open()
        val last = if (phone.code.last() == 'a') 'b' else 'a'
        val other = Seal(phone.code.dropLast(1) + last)
        repeat(25) {
            val reply = phone.send(other.seal(Seal.LINKS, ByteArray(12) { n -> (it + n).toByte() }, "https://example.org/app".toByteArray()))!!
            assertEquals(403, reply.status)
            assertTrue(reply.toString(), said(reply, Notice.DID_NOT_OPEN))
        }
        assertTrue(phone.server.isOpen)
        assertEquals(0, phone.server.waiting())
        assertEquals(0, handoffs.changes.get())
        assertTrue(said(phone.links("https://example.org/app"), Notice.LINKS_SENT))
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
    fun twentyLinksOfTwoThousandCharactersThatAreNotAsciiAreTaken() {
        val phone = handoffs.open()
        val twenty = Array(20) { "https://example.org/$it/".padEnd(2000, '\u6771') }
        assertTrue(twenty.joinToString("\n").toByteArray().size in 100_000..limits.linksBytes)
        assertTrue(said(phone.links(*twenty), Notice.LINKS_SENT))
        assertEquals(twenty.map { HandoffItem.Link(it) }, phone.server.take())
    }

    @Test
    fun linksThatAreNoLinksAreRefusedInASentence() {
        val phone = handoffs.open()
        assertTrue(said(phone.links(), Notice.NO_LINKS))
        assertTrue(said(phone.links(" ", "", " "), Notice.NO_LINKS))
        assertTrue(said(phone.links("https://example.org/\u202egnp.exe"), Notice.LINK_UNREADABLE))
        assertTrue(said(phone.links("a\u0000b"), Notice.LINK_UNREADABLE))
        assertTrue(said(phone.plain(Seal.LINKS, byteArrayOf(0x4B, 0xF6.toByte(), 0x6C, 0x6E)), Notice.UNREADABLE))
        assertTrue(said(phone.plain(Seal.LINKS, ByteArray(limits.linksBytes + 1) { 'a'.code.toByte() }), Notice.LINKS_TOO_LARGE))
        assertEquals(0, phone.server.waiting())
        assertEquals(0, handoffs.changes.get())
    }

    @Test
    fun aFileOfTwoMebibytesIsTakenAndOneByteMoreIsNot() {
        val phone = handoffs.open()
        assertEquals(2 * 1024 * 1024, limits.fileBytes)
        val full = ByteArray(limits.fileBytes) { (it * 31).toByte() }
        assertTrue(said(phone.file("n".repeat(80), full), Notice.FILE_SENT))
        assertArrayEquals(full, (phone.server.take().single() as HandoffItem.ExportFile).bytes)

        val over = phone.file("over.json", full + byteArrayOf(1))!!
        assertEquals(413, over.status)
        assertTrue(over.toString(), said(over, Notice.FILE_TOO_LARGE))
        val farOver = phone.file("far-over.json", ByteArray(3 * limits.fileBytes))!!
        assertEquals(413, farOver.status)
        assertTrue(farOver.toString(), said(farOver, Notice.TOO_LARGE))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aFileWithNothingInItIsToldSo() {
        val phone = handoffs.open()
        val empty = phone.file("apps.json", ByteArray(0))!!
        assertEquals(400, empty.status)
        assertTrue(empty.toString(), said(empty, Notice.NO_FILE))
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aFileThatIsNotWrittenAsThePageWritesItIsToldSo() {
        val phone = handoffs.open()
        for (plain in listOf(ByteArray(0), byteArrayOf(9, 'a'.code.toByte()), byteArrayOf(81) + ByteArray(100), byteArrayOf(1, 0xC3.toByte(), 1, 2))) {
            val reply = phone.plain(Seal.FILE, plain)!!
            assertEquals(400, reply.status)
            assertTrue(reply.toString(), said(reply, Notice.UNREADABLE))
        }
        assertEquals(0, phone.server.waiting())
    }

    @Test
    fun aKindThatThePageDoesNotSendIsNotTaken() {
        val phone = handoffs.open()
        for (kind in listOf(0, 3, 255)) {
            val reply = phone.plain(kind, "https://example.org/app".toByteArray())!!
            assertEquals(403, reply.status)
            assertTrue(reply.toString(), said(reply, Notice.DID_NOT_OPEN))
        }
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
    fun openingAgainMakesANewCodeAndTheOldOneOpensNothing() {
        val first = handoffs.open()
        val sealedForFirst = first.sealed(Seal.LINKS, "https://example.org/app".toByteArray())
        first.server.close()
        val second = handoffs.open()
        assertNotEquals(first.code, second.code)
        assertEquals(20, second.code.length)
        val reply = second.send(sealedForFirst)!!
        assertEquals(403, reply.status)
        assertTrue(reply.toString(), said(reply, Notice.DID_NOT_OPEN))
        assertEquals(0, second.server.waiting())
    }
}
