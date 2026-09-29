package io.github.munzzyy.tern.core.qr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures are what qrencode makes of the same texts; tools/make-qr-fixtures.sh writes them. */
class QrEncoderTest {
    private class Fixture(val name: String, val text: String, val rows: List<String>)

    private val fixtures: List<Fixture> = File(javaClass.getResource("/fixtures/qr")!!.toURI()).listFiles()!!
        .filter { it.name.endsWith(".txt") }
        .sortedBy { it.name }
        .map { file ->
            val lines = file.readLines(Charsets.UTF_8)
            Fixture(file.name, lines.first(), lines.drop(1))
        }

    private fun rows(code: QrMatrix): List<String> =
        (0 until code.size).map { y -> String(CharArray(code.size) { x -> if (code.isDark(x, y)) '#' else '.' }) }

    @Test
    fun everyFixtureComesOutSquareForSquare() {
        assertTrue("only ${fixtures.size} fixtures", fixtures.size >= 12)
        for (fixture in fixtures) {
            assertEquals(fixture.name, fixture.rows.joinToString("\n"), rows(QrEncoder.encode(fixture.text)).joinToString("\n"))
        }
    }

    @Test
    fun theFixturesReachEveryVersionAndSeveralMasks() {
        val codes = fixtures.map { QrEncoder.encode(it.text) }
        assertEquals((1..10).toList(), codes.map { it.version }.distinct().sorted())
        assertTrue("masks ${codes.map { it.mask }}", codes.map { it.mask }.distinct().size >= 4)
    }

    @Test
    fun aCodeIsAsSmallAsItsTextAllows() {
        val fullAt = listOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
        for ((index, bytes) in fullAt.withIndex()) {
            assertEquals(index + 1, QrEncoder.encode("a".repeat(bytes)).version)
            if (index + 2 <= 10) assertEquals(index + 2, QrEncoder.encode("a".repeat(bytes + 1)).version)
        }
        assertEquals(21, QrEncoder.encode("").size)
        assertEquals(57, QrEncoder.encode("a".repeat(213)).size)
    }

    @Test
    fun theLengthIsCountedInBytesAndNotInLetters() {
        assertEquals(1, QrEncoder.encode("\u00fc".repeat(7)).version)
        assertEquals(2, QrEncoder.encode("\u00fc".repeat(8)).version)
    }

    @Test
    fun whatDoesNotFitIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { QrEncoder.encode("a".repeat(214)) }
        assertThrows(IllegalArgumentException::class.java) { QrEncoder.encode("\u6771".repeat(72)) }
    }

    @Test
    fun theLongestAddressOfAHandoffFits() {
        val code = QrEncoder.encode("http://192.168.100.200:65535/#" + "a".repeat(20))
        assertEquals(4, code.version)
        assertEquals(3, QrEncoder.encode("http://10.0.0.2:1024/#" + "a".repeat(20)).version)
    }

    @Test
    fun theSquaresHandedOutAreACopy() {
        val code = QrEncoder.encode("A")
        val before = rows(code)
        code.squares().fill(true)
        assertEquals(before, rows(code))
        assertEquals(code.size * code.size, code.squares().size)
    }
}
