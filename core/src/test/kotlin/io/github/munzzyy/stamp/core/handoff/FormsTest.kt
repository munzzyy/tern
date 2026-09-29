package io.github.munzzyy.stamp.core.handoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FormsTest {
    private val limits = HandoffLimits()

    private fun field(body: String, name: String = "links") = Forms.field(body.toByteArray(Charsets.ISO_8859_1), name)

    private fun taken(text: String): List<String> = (Forms.links(text, limits) as Forms.Links.Taken).links

    private fun refused(text: String): Notice = (Forms.links(text, limits) as Forms.Links.Refused).notice

    @Test
    fun aFieldIsReadTheWayAFormWritesIt() {
        assertEquals("https://example.org/a b?c=d&e", field("links=https%3A%2F%2Fexample.org%2Fa+b%3Fc%3Dd%26e"))
        assertEquals("one\r\ntwo", field("links=one%0D%0Atwo"))
        assertEquals("https://example.org/?a=b", field("links=https://example.org/?a=b"))
        assertEquals("K\u00f6ln \u6771", field("links=K%C3%B6ln+%E6%9D%B1"))
        assertEquals("", field("links="))
        assertEquals("402917", field("pin=402917", "pin"))
    }

    @Test
    fun anythingButTheOneFieldIsNoForm() {
        val not = listOf(
            "", "links", "link=a", "Links=a", "pin=1", " links=a", "links=a&links=b", "links=a&more=b", "more=b&links=a",
            "links=%", "links=%4", "links=%zz", "links=a%", "links=a b", "links=a\nb", "links=a\u0000", "links=\u00e9",
            "links=%C3", "links=%FF%FE", "links=%ED%A0%80",
        )
        for (body in not) assertNull(body, field(body))
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
}
