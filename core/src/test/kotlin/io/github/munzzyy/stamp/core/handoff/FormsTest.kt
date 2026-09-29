package io.github.munzzyy.stamp.core.handoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FormsTest {
    private val limits = HandoffLimits()

    private fun sealed(body: String) = Forms.sealed(body.toByteArray(Charsets.ISO_8859_1))

    private fun taken(text: String): List<String> = (Forms.links(text, limits) as Forms.Links.Taken).links

    private fun refused(text: String): Notice = (Forms.links(text, limits) as Forms.Links.Refused).notice

    @Test
    fun theFieldIsReadTheWayThePageWritesIt() {
        assertArrayEquals(byteArrayOf(-5, -1), sealed("sealed=-_8"))
        assertArrayEquals(byteArrayOf(0), sealed("sealed=AA"))
        assertArrayEquals(ByteArray(0), sealed("sealed="))
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, sealed(Phone.field(all)))
        assertArrayEquals(all + all, sealed(Phone.field(all + all)))
    }

    @Test
    fun anythingButTheOneFieldIsNoForm() {
        val not = listOf(
            "", "sealed", "seale=AA", "Sealed=AA", "links=AA", " sealed=AA", "sealed=AA&sealed=AA", "sealed=AA&more=AA", "more=AA&sealed=AA",
            "sealed=AA==", "sealed=AA=", "sealed=A", "sealed=AAAAA", "sealed=+/8", "sealed=-_8 ", "sealed=-_8\n", "sealed=-_8\r\n", "sealed= -_8",
            "sealed=%41%41", "sealed=A%41", "sealed=A.A", "sealed=A\u0000A", "sealed=A\u00e9", "sealed=AA;", "sealed=\"AA\"",
        )
        for (body in not) assertNull(body, sealed(body))
    }

    @Test
    fun linksComeOneOnALineWithTheEmptyLinesLeftOut() {
        assertEquals(listOf("https://example.org/a", "example.org/b", "c"), taken("  https://example.org/a \r\n\r\nexample.org/b\n\n\rc\r"))
        assertEquals(List(20) { "l$it" }, taken((0 until 20).joinToString("\n") { "l$it" }))
        assertEquals(listOf("a".repeat(2000)), taken("a".repeat(2000)))
        assertEquals(listOf("https://example.org/\u6771\u4eac?q=\u00fc"), taken("https://example.org/\u6771\u4eac?q=\u00fc"))
    }

    @Test
    fun oneLinkThatCannotBeTakenRefusesThemAll() {
        assertEquals(Notice.NO_LINKS, refused(""))
        assertEquals(Notice.NO_LINKS, refused(" \r\n \n"))
        assertEquals(Notice.TOO_MANY_LINKS, refused((0 until 21).joinToString("\n") { "l$it" }))
        assertEquals(Notice.LINK_TOO_LONG, refused("short\n" + "a".repeat(2001)))
        for (hidden in listOf("\u0000", "\u0007", "\t", "\u001b", "\u007f", "\u0085", "\u200b", "\u200e", "\u202e", "\u2066", "\u2028", "\ufeff", "\ue000", "\ud800")) {
            assertEquals("U+%04X".format(hidden[0].code), Notice.LINK_UNREADABLE, refused("https://example.org/a${hidden}b"))
        }
    }

    @Test
    fun theTypeOfABodyIsReadWithoutItsParameters() {
        assertEquals(Forms.TYPE, Forms.typeOf("application/x-www-form-urlencoded"))
        assertEquals(Forms.TYPE, Forms.typeOf("Application/X-WWW-Form-Urlencoded; charset=UTF-8"))
        assertEquals("text/plain", Forms.typeOf("text/plain"))
        assertEquals("", Forms.typeOf(null))
    }

    @Test
    fun textThatIsNotUtf8IsNoText() {
        assertNotNull(Forms.utf8("K\u00f6ln".toByteArray()))
        assertNull(Forms.utf8(byteArrayOf(0x4B, 0xF6.toByte(), 0x6C, 0x6E)))
        assertArrayEquals("a".toByteArray(), Forms.utf8(byteArrayOf(0x61))!!.toByteArray())
    }

    @Test
    fun aFileComesWithItsNameInFront() {
        val content = ByteArray(512) { it.toByte() }
        val read = Forms.file(Phone.named("stamp-apps-2026-09-29.json", content))!!
        assertEquals("stamp-apps-2026-09-29.json", read.name)
        assertArrayEquals(content, read.content)
        assertArrayEquals(ByteArray(0), Forms.file(Phone.named("apps.json", ByteArray(0)))!!.content)
        assertEquals("export.json", Forms.file(byteArrayOf(0, 1, 2))!!.name)
        assertArrayEquals(byteArrayOf(1, 2), Forms.file(byteArrayOf(0, 1, 2))!!.content)
        assertEquals("n".repeat(80), Forms.file(Phone.named("n".repeat(80), content))!!.name)
        assertEquals("../../etc/passwd".substringAfterLast('/'), Forms.file(Phone.named("../../etc/passwd", content))!!.name)
    }

    @Test
    fun aFileWithoutAWholeNameIsNoFile() {
        assertNull(Forms.file(ByteArray(0)))
        assertNull(Forms.file(byteArrayOf(1)))
        assertNull(Forms.file(byteArrayOf(5, 'a'.code.toByte(), 'b'.code.toByte())))
        assertNull(Forms.file(byteArrayOf(81) + ByteArray(200) { 'n'.code.toByte() }))
        assertNull(Forms.file(byteArrayOf(-1) + ByteArray(300) { 'n'.code.toByte() }))
        assertNull(Forms.file(byteArrayOf(2, 0xC3.toByte(), 0x28, 1, 2, 3)))
        assertNull(Forms.file(byteArrayOf(1, 0xC3.toByte(), 0xB6.toByte(), 1, 2, 3)))
    }

    @Test
    fun aNameIsCutDownToWhatANameNeeds() {
        assertEquals("stamp-apps-2026-09-29.json", Forms.tidy("stamp-apps-2026-09-29.json"))
        assertEquals("apps (2).json", Forms.tidy("C:\\Users\\someone\\Downloads\\apps (2).json"))
        assertEquals("passwd", Forms.tidy("../../etc/passwd"))
        assertEquals("export.json", Forms.tidy(""))
        assertEquals("export.json", Forms.tidy("../.."))
        assertEquals("export.json", Forms.tidy(" ... "))
        assertEquals("hidden", Forms.tidy(".hidden"))
        assertEquals("ab.json", Forms.tidy("a\u202eb\u0000<>:\"|?*\r\n.json"))
        assertEquals("\u6771\u4eac.json", Forms.tidy("\u6771\u4eac.json"))
        assertEquals("n".repeat(80), Forms.tidy("n".repeat(500)))
    }
}
