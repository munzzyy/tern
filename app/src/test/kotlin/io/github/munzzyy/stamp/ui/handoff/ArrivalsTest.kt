package io.github.munzzyy.stamp.ui.handoff

import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.HandoffEnd
import io.github.munzzyy.stamp.engine.ImportSummary
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.ProblemKind
import io.github.munzzyy.stamp.engine.QrCode
import io.github.munzzyy.stamp.engine.Received
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalsTest {
    private fun link(id: Long, looked: Boolean = false) = Arrival.Link(id, "https://example.org/$id", looked)

    private fun file(id: Long, state: FileState = FileState.Waiting) = Arrival.File(id, "export-$id.json", 10, state)

    private val done = FileState.Done(ImportSummary(1, 0, emptyList()))

    @Test
    fun whatArrivesIsListedAfterWhatWasThere() {
        assertEquals(listOf(1L, 2L, 3L), listed(listOf(link(1), file(2)), listOf(link(3))).map { it.id })
    }

    @Test
    fun aFullListLetsGoOfWhatWasDealtWithFirst() {
        val before = listOf(link(1), link(2, looked = true), file(3, done), link(4))
        val after = listed(before, listOf(link(5), link(6)), limit = 4)
        assertEquals(listOf(1L, 4L, 5L, 6L), after.map { it.id })
    }

    @Test
    fun whenNothingWasDealtWithTheOldestGoes() {
        val after = listed(listOf(link(1), file(2), link(3)), listOf(link(4)), limit = 3)
        assertEquals(listOf(2L, 3L, 4L), after.map { it.id })
    }

    @Test
    fun aFileThatIsBeingReadStays() {
        val after = listed(listOf(file(1, FileState.Working), link(2)), listOf(link(3)), limit = 2)
        assertEquals(listOf(1L, 3L), after.map { it.id })
        val onlyBusy = listed(listOf(file(1, FileState.Working)), listOf(file(2, FileState.Working)), limit = 1)
        assertEquals(listOf(1L, 2L), onlyBusy.map { it.id })
    }

    @Test
    fun theListNeverGrowsPastItsLimit() {
        var all = emptyList<Arrival>()
        for (id in 1L..500L) all = listed(all, listOf(link(id)))
        assertEquals(MAX_ARRIVALS, all.size)
        assertEquals(500L, all.last().id)
    }

    @Test
    fun aLinkIsListedWithoutWhatIsNotDrawn() {
        val hidden = "https://example.org/\u202Eppa\u200B\u0000/x"
        assertEquals(Arrival.Link(7, "https://example.org/ppa/x"), arrivalOf(7, Received.Link(hidden)))
        assertNull(arrivalOf(7, Received.Link("\u200B\u202E \n")))
    }

    @Test
    fun aFileIsListedWithItsSizeAndANameThatCanBeDrawn() {
        val bytes = ByteArray(1234)
        assertEquals(Arrival.File(3, "apps.json", 1234), arrivalOf(3, Received.ExportFile("apps\u202E.json", bytes)))
        assertEquals(Arrival.File(3, "export.json", 1234), arrivalOf(3, Received.ExportFile("\u0000", bytes)))
        val long = arrivalOf(3, Received.ExportFile("a".repeat(500), bytes)) as Arrival.File
        assertEquals(MAX_FILE_NAME, long.name.length)
    }

    @Test
    fun theCodeIsDrawnBlackOnWhiteWithAQuietBorderOfFourSquares() {
        val dark = BooleanArray(9) { it % 2 == 0 }
        val qr = QrCode(3, dark)
        val pixels = qrPixels(qr)
        assertEquals(11, qrSquares(qr))
        assertEquals(11 * 11, pixels.size)
        for (y in 0 until 11) {
            for (x in 0 until 11) {
                val inside = x in 4..6 && y in 4..6
                val wanted = if (inside && qr.isDark(x - 4, y - 4)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                assertEquals("square $x, $y", wanted, pixels[y * 11 + x])
            }
        }
        assertEquals(5, pixels.count { it == 0xFF000000.toInt() })
    }

    @Test
    fun everySquareIsAWholeNumberOfPixelsAndTheCodeIsNeverSmallerThanAsked() {
        assertEquals(16, squarePx(630, 41))
        assertEquals(16, squarePx(640, 41))
        assertEquals(10, squarePx(410, 41))
        assertEquals(11, squarePx(411, 41))
        for (least in listOf(1, 240, 630, 640, 841)) {
            for (squares in listOf(29, 33, 41, 185)) {
                val side = squarePx(least, squares) * squares
                assertTrue("$least over $squares gives $side", side >= least && side < least + squares)
            }
        }
        assertEquals(1, squarePx(0, 41))
        assertEquals(1, squarePx(100, 0))
    }

    @Test
    fun theCodeIsShownInTheGroupsItIsTypedIn() {
        assertEquals(listOf("k4mz", "q7wd", "x2np", "h5tc", "r9vb"), codeGroups(" k4mz q7wd  x2np h5tc r9vb\n"))
        assertEquals(listOf("482913"), codeGroups("482913"))
        assertEquals(emptyList<String>(), codeGroups("   "))
        assertEquals(10, codeGroups((1..50).joinToString(" ") { "g$it" }).size)
        assertEquals(listOf("abcdefgh"), codeGroups("abcdefghijklmnop"))
    }

    @Test
    fun theCodeIsSpokenOneCharacterAtATime() {
        assertEquals("k 4 m z, q 7 w d", spokenCode("k4mz q7wd"))
        assertEquals("", spokenCode(""))
    }

    @Test
    fun everyEndHasItsSentenceAndClosingFromThisDeviceHasNone() {
        assertEquals(R.string.handoff_end_expired, endSentence(HandoffEnd.EXPIRED))
        assertEquals(R.string.handoff_end_left_screen, endSentence(HandoffEnd.LEFT_SCREEN))
        assertEquals(R.string.handoff_end_used_up, endSentence(HandoffEnd.USED_UP))
        assertNull(endSentence(HandoffEnd.CLOSED))
    }

    @Test
    fun aFailedOpeningIsSaidBeforeAnEarlierEnd() {
        val problem = Problem(ProblemKind.NETWORK, "This device is on no local network.")
        assertEquals(Closed.Failed(problem), closedFor(Failure(problem), null))
        assertEquals(Closed.Failed(problem), closedFor(Failure(problem), HandoffEnd.EXPIRED))
        assertEquals(Closed.Failed(null), closedFor(Failure(null), HandoffEnd.CLOSED))
    }

    @Test
    fun anEndIsSaidUnlessItWasClosedFromThisDevice() {
        assertEquals(Closed.Ended(HandoffEnd.EXPIRED), closedFor(null, HandoffEnd.EXPIRED))
        assertEquals(Closed.Ended(HandoffEnd.LEFT_SCREEN), closedFor(null, HandoffEnd.LEFT_SCREEN))
        assertEquals(Closed.Ended(HandoffEnd.USED_UP), closedFor(null, HandoffEnd.USED_UP))
        assertEquals(Closed.Quiet, closedFor(null, HandoffEnd.CLOSED))
        assertEquals(Closed.Quiet, closedFor(null, null))
    }

    @Test
    fun aLongLinkIsCutInTheListAndSaysSo() {
        assertEquals("https://example.org/a", shownLink("https://example.org/a"))
        val long = "https://example.org/" + "a".repeat(2000)
        assertEquals(long.take(200) + "\u2026", shownLink(long))
        assertEquals("abc\u2026", shownLink("abcdef", limit = 3))
    }
}
