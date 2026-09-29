package io.github.munzzyy.stamp.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownTest {
    private fun points(vararg codes: Int): String = StringBuilder().apply { codes.forEach { appendCodePoint(it) } }.toString()

    private val overrides = intArrayOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C)
    private val noWidth = intArrayOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x180E)

    @Test
    fun plainTextComesThroughAsItIs() {
        assertEquals("Organic Maps", Shown.line("Organic Maps", 200))
        assertEquals("K-9 Mail (beta) 1.0_rc2", Shown.line("K-9 Mail (beta) 1.0_rc2", 200))
    }

    @Test
    fun otherScriptsAndPicturesComeThroughAsTheyAre() {
        val names = listOf("Nachtwächter", "Телеграм", "地图", "مسنجر", "नक्शा", "café", "a" + points(0x1F600) + "b")
        for (name in names) assertEquals(name, Shown.line(name, 200))
    }

    @Test
    fun everyMarkThatTurnsTheDirectionOfWritingIsLeftOut() {
        for (code in overrides) {
            val text = "Bank" + points(code) + "knaB"
            assertEquals("U+%04X".format(code), "BankknaB", Shown.line(text, 200))
        }
    }

    @Test
    fun everyCharacterOfNoWidthIsLeftOut() {
        for (code in noWidth) {
            val text = "Sig" + points(code) + "nal"
            assertEquals("U+%04X".format(code), "Signal", Shown.line(text, 200))
        }
    }

    @Test
    fun aNameMadeToLookLikeAnotherIsShownAsWhatItHolds() {
        assertEquals("Signalgpj.apk", Shown.line("Signal" + points(0x202E) + "gpj.apk", 200))
        assertEquals("Signal", Shown.line(points(0x200B) + "Sig" + points(0x200D) + "nal" + points(0xFEFF), 200))
    }

    @Test
    fun controlCharactersAreLeftOut() {
        assertEquals("ab", Shown.line("a" + points(0x00) + points(0x07) + points(0x1B) + points(0x7F) + points(0x9B) + "b", 200))
    }

    @Test
    fun tagCharactersAndPrivateUseAndUnassignedAreLeftOut() {
        assertEquals("ab", Shown.line("a" + points(0xE0041) + points(0xE007F) + "b", 200))
        assertEquals("ab", Shown.line("a" + points(0xE000) + points(0xF8FF) + points(0x10FFFD) + "b", 200))
        assertEquals("ab", Shown.line("a" + points(0x0378) + points(0xFFFF) + "b", 200))
    }

    @Test
    fun halfASurrogatePairIsLeftOutAndAWholeOneStays() {
        assertEquals("ab", Shown.line("a\uD83Db", 200))
        assertEquals("ab", Shown.line("a\uDE00b", 200))
        assertEquals("a😀b", Shown.line("a😀b", 200))
    }

    @Test
    fun everyRunOfWhiteSpaceBecomesOneSpace() {
        assertEquals("a b", Shown.line("a \t\r\n  b", 200))
        assertEquals("a b", Shown.line("a" + points(0x2028) + points(0x2029) + "b", 200))
        assertEquals("a b", Shown.line("a" + points(0x00A0) + points(0x2003) + points(0x3000) + points(0x202F) + "b", 200))
        assertEquals("a b", Shown.line("a" + points(0x0B) + points(0x0C) + points(0x1C) + "b", 200))
        assertEquals("a b", Shown.line("a" + points(0x85) + "b", 200))
    }

    @Test
    fun aRunOfWhiteSpaceAroundACharacterThatIsLeftOutIsStillOneSpace() {
        assertEquals("a b", Shown.line("a " + points(0x200B) + " b", 200))
        assertEquals("a b", Shown.line("a " + points(0x202E) + points(0x2066) + "\n b", 200))
    }

    @Test
    fun thereIsNoWhiteSpaceAtEitherEnd() {
        assertEquals("a", Shown.line("  \n a \t ", 200))
        assertEquals("a", Shown.line(points(0x200B) + " a " + points(0x202E), 200))
        assertEquals("", Shown.line(" \n\t ", 200))
    }

    @Test
    fun theCutIsAtTheLengthAndLeavesNoSpaceAtTheEnd() {
        assertEquals("abcde", Shown.line("abcdefgh", 5))
        assertEquals("abc", Shown.line("abc defgh", 4))
        assertEquals("abc d", Shown.line("abc defgh", 5))
        assertEquals("", Shown.line("abc", 0))
    }

    @Test
    fun theCutNeverDividesASurrogatePair() {
        val face = points(0x1F600)
        assertEquals("ab", Shown.line("ab$face", 3))
        assertEquals("ab$face", Shown.line("ab$face", 4))
        for (max in 0..12) {
            val cut = Shown.line("a$face b$face$face", max)
            assertTrue("$max gave ${cut.length}", cut.length <= max)
            assertTrue(cut.isEmpty() || !Character.isHighSurrogate(cut.last()))
        }
    }

    @Test
    fun whatIsLeftOutDoesNotCountTowardsTheLength() {
        val hidden = points(0x200B).repeat(500)
        assertEquals("abc", Shown.line(hidden + "a" + hidden + "b" + hidden + "c", 3))
    }

    @Test
    fun cleaningTwiceChangesNothing() {
        val texts = listOf(
            "  Bank" + points(0x202E) + "knaB \n of" + points(0x200B) + "  Names ",
            "a" + points(0x2028) + "b",
            "x".repeat(300),
        )
        for (text in texts) {
            val once = Shown.line(text, 200)
            assertEquals(once, Shown.line(once, 200))
        }
    }

    @Test
    fun nothingLeftIsNull() {
        assertNull(Shown.lineOrNull(null, 200))
        assertNull(Shown.lineOrNull("", 200))
        assertNull(Shown.lineOrNull(" " + points(0x202E) + points(0x200B) + "\n", 200))
        assertEquals("a", Shown.lineOrNull(" a ", 200))
    }

    @Test
    fun noCharacterOfAKindThatIsNotDrawnIsEverInALine() {
        val all = StringBuilder()
        for (code in 0..0x10FFFF) all.appendCodePoint(code)
        val cleaned = Shown.line(all.toString(), Int.MAX_VALUE)
        var i = 0
        var count = 0
        while (i < cleaned.length) {
            val point = cleaned.codePointAt(i)
            i += Character.charCount(point)
            count++
            val type = Character.getType(point).toByte()
            val refused = type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE ||
                type == Character.PRIVATE_USE || type == Character.UNASSIGNED ||
                type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
            assertTrue("U+%04X came through".format(point), !refused)
            assertTrue("U+%04X is white space other than a space".format(point), point == ' '.code || !Character.isWhitespace(point))
        }
        assertTrue("only $count characters came through", count > 100_000)
    }

    @Test
    fun severalLinesKeepTheirLineBreaksAndTabs() {
        assertEquals("# Title\n\n\tcode\r\nnext", Shown.prose("# Title\n\n\tcode\r\nnext", 200))
        assertEquals("a  b", Shown.prose("a  b", 200))
    }

    @Test
    fun severalLinesLoseEveryMarkThatTurnsTheDirectionOfWriting() {
        for (code in overrides) {
            assertEquals("U+%04X".format(code), "sha256: abcd", Shown.prose("sha256: " + points(code) + "abcd", 200))
        }
    }

    @Test
    fun severalLinesLoseControlCharactersAndWhatHasNoWidthButKeepTheTwoJoiners() {
        assertEquals("ab", Shown.prose("a" + points(0x00) + points(0x1B) + points(0x200B) + points(0xFEFF) + points(0x2060) + "b", 200))
        assertEquals("a" + points(0x200C) + "b" + points(0x200D) + "c", Shown.prose("a" + points(0x200C) + "b" + points(0x200D) + "c", 200))
    }

    @Test
    fun aSeparatorInSeveralLinesIsALineBreak() {
        assertEquals("a\nb\nc\nd", Shown.prose("a" + points(0x2028) + "b" + points(0x2029) + "c" + points(0x85) + "d", 200))
    }

    @Test
    fun severalLinesAreCutWithoutDividingASurrogatePair() {
        val face = points(0x1F600)
        assertEquals("ab", Shown.prose("ab$face", 3))
        assertEquals("ab$face", Shown.prose("ab$face", 4))
        assertEquals("", Shown.prose("abc", 0))
    }
}
