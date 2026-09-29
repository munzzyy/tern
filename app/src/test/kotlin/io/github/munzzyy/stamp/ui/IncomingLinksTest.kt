package io.github.munzzyy.stamp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingLinksTest {
    @Test
    fun sharedTextYieldsTheFirstHttpsUrl() {
        val text = "Look at this http://plain.example.org and https://github.com/example/app. Also https://other.example.org"
        assertEquals("https://github.com/example/app", incomingAddInput(ACTION_SEND, null, text))
    }

    @Test
    fun trailingPunctuationIsDroppedButBalancedBracketsStay() {
        assertEquals("https://example.org/a", firstHttpsUrl("(see https://example.org/a)."))
        assertEquals("https://en.example.org/wiki/App_(software)", firstHttpsUrl("https://en.example.org/wiki/App_(software)"))
        assertEquals("https://example.org/x", firstHttpsUrl("\"https://example.org/x\""))
    }

    @Test
    fun sharedTextWithoutUrlBecomesASearchOnOneLine() {
        assertEquals("feed reader", incomingAddInput(ACTION_SEND, null, "  feed\n\treader \u0000 "))
        assertNull(incomingAddInput(ACTION_SEND, null, " \n "))
        assertNull(firstHttpsUrl("https://"))
        assertEquals("https://", fromSharedText("https://"))
    }

    @Test
    fun sharedTextIsCapped() {
        val huge = "a".repeat(20_000) + " https://example.org/late"
        val result = fromSharedText(huge)
        assertTrue(result!!.length <= MAX_INCOMING_CHARS)
        assertEquals("https://example.org/early", fromSharedText("https://example.org/early " + "b".repeat(50_000)))
    }

    @Test
    fun stampAddLinkDecodesTheUrl() {
        assertEquals(
            "https://codeberg.org/example/app",
            incomingAddInput(ACTION_VIEW, "stamp://add?url=https%3A%2F%2Fcodeberg.org%2Fexample%2Fapp", null),
        )
        assertEquals("https://a.example.org/x", fromLink("stamp://add?foo=1&url=https%3A%2F%2Fa.example.org%2Fx#frag"))
    }

    @Test
    fun stampLinkRefusesAnythingButHttps() {
        assertNull(fromLink("stamp://add?url=http%3A%2F%2Fexample.org"))
        assertNull(fromLink("stamp://add?url=javascript%3Aalert(1)"))
        assertNull(fromLink("stamp://add?url=%zz"))
        assertNull(fromLink("stamp://remove?url=https%3A%2F%2Fexample.org"))
        assertNull(fromLink("stamp://add"))
        assertNull(fromLink("stamp://add?url=https%3A%2F%2Fexample.org%0Aevil"))
    }

    @Test
    fun obtainiumAddTakesRawOrEncodedUrls() {
        assertEquals("https://github.com/example/app", fromLink("obtainium://add/https://github.com/example/app"))
        assertEquals("https://github.com/example/app", fromLink("obtainium://add/https%3A%2F%2Fgithub.com%2Fexample%2Fapp"))
        assertNull(fromLink("obtainium://add/http://github.com/example/app"))
        assertNull(fromLink("obtainium://add/"))
    }

    @Test
    fun obtainiumAppLinksPassThroughWhole() {
        val raw = "obtainium://app/%7B%22id%22%3A%22x%22%7D"
        assertEquals(raw, fromLink(raw))
        assertEquals("obtainium://apps/[]", fromLink("obtainium://apps/[]"))
        assertNull(fromLink("obtainium://settings/whatever"))
    }

    @Test
    fun overlongLinksAreRefusedNotTruncated() {
        assertNull(fromLink("obtainium://app/" + "x".repeat(MAX_INCOMING_CHARS)))
    }

    @Test
    fun whatAPhoneSentIsKeptAsItIsWhenAllOfItIsDrawn() {
        assertEquals("https://github.com/example/app", fromHandoff("  https://github.com/example/app \n"))
        assertEquals("https://example.org/\u00FCber/\u6F22\u5B57", fromHandoff("https://example.org/\u00FCber/\u6F22\u5B57"))
        assertEquals("a \uD83D\uDE00 b", fromHandoff("a \uD83D\uDE00 b"))
    }

    @Test
    fun whatAPhoneSentLosesWhatIsNotDrawn() {
        assertEquals("https://example.org/gpj.exe", fromHandoff("https://example.org/\u202Egpj.exe"))
        assertEquals("https://example.org/ab", fromHandoff("https://example.org/a\u200B\u200D\u2060\uFEFFb"))
        assertEquals("ab", fromHandoff("a\u0000\u0007\u001B\r\n\tb"))
        assertEquals("ab", fromHandoff("a\u2028\u2029b"))
        assertEquals("ab", fromHandoff("a\uE000\uDC00b"))
        assertEquals("ab", fromHandoff("a\u2066\u2067\u2068\u2069b"))
        assertNull(fromHandoff("\u200B\u202E \n"))
        assertNull(fromHandoff(""))
    }

    @Test
    fun whatAPhoneSentIsCapped() {
        assertEquals(MAX_INCOMING_CHARS, fromHandoff("a".repeat(50_000))!!.length)
        val cutInsideAPair = "a".repeat(MAX_INCOMING_CHARS - 1) + "\uD83D\uDE00"
        assertEquals("a".repeat(MAX_INCOMING_CHARS - 1), fromHandoff(cutInsideAPair))
    }

    @Test
    fun otherActionsAndSchemesAreIgnored() {
        assertNull(incomingAddInput("android.intent.action.MAIN", "stamp://add?url=https%3A%2F%2Fa.example.org", null))
        assertNull(incomingAddInput(ACTION_VIEW, "https://github.com/example/app", null))
        assertNull(incomingAddInput(ACTION_VIEW, null, null))
    }
}
