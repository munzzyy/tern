package io.github.munzzyy.stamp.ui.handoff

import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.HandoffEnd
import io.github.munzzyy.stamp.engine.ImportSummary
import io.github.munzzyy.stamp.engine.QrCode
import io.github.munzzyy.stamp.engine.Received
import io.github.munzzyy.stamp.ui.fromHandoff

/** Something a phone sent, as the screen lists it. Nothing here is added to anything until the user says so. */
sealed interface Arrival {
    val id: Long

    data class Link(override val id: Long, val text: String, val looked: Boolean = false) : Arrival

    data class File(override val id: Long, val name: String, val sizeBytes: Int, val state: FileState = FileState.Waiting) : Arrival
}

sealed interface FileState {
    data object Waiting : FileState

    data object Working : FileState

    data class Done(val summary: ImportSummary) : FileState

    /** [message] is the engine's own sentence, or null when it gave none. */
    data class Failed(val message: String?) : FileState
}

const val MAX_ARRIVALS = 40
const val MAX_FILE_NAME = 80
private const val NAMELESS = "export.json"

/** What was taken from the engine, made safe to draw. A link that holds nothing to draw is dropped. */
fun arrivalOf(id: Long, item: Received): Arrival? = when (item) {
    is Received.Link -> fromHandoff(item.text)?.let { Arrival.Link(id, it) }
    is Received.ExportFile -> Arrival.File(id, fromHandoff(item.name)?.take(MAX_FILE_NAME) ?: NAMELESS, item.bytes.size)
}

/** True for what needs the user no more: a link that was looked at, a file that was imported. */
fun Arrival.dealtWith(): Boolean = when (this) {
    is Arrival.Link -> looked
    is Arrival.File -> state is FileState.Done
}

private fun Arrival.busy(): Boolean = this is Arrival.File && state == FileState.Working

/**
 * What is listed after [arrived] has come in. A full list makes room by letting go of its oldest
 * entries: first those that were dealt with, then those that were not. A file that is being read stays.
 */
fun listed(before: List<Arrival>, arrived: List<Arrival>, limit: Int = MAX_ARRIVALS): List<Arrival> {
    val all = (before + arrived).toMutableList()
    while (all.size > limit) {
        val goes = all.firstOrNull { it.dealtWith() } ?: all.firstOrNull { !it.busy() } ?: break
        all.remove(goes)
    }
    return all
}

const val QUIET_SQUARES = 4
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

fun qrSquares(qr: QrCode, quiet: Int = QUIET_SQUARES): Int = qr.size + 2 * quiet

/** One pixel for every square of the code and of the quiet border around it: white, and pure black for a dark square. */
fun qrPixels(qr: QrCode, quiet: Int = QUIET_SQUARES): IntArray {
    val side = qrSquares(qr, quiet)
    val pixels = IntArray(side * side) { WHITE }
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) pixels[(y + quiet) * side + x + quiet] = BLACK
        }
    }
    return pixels
}

/** How many pixels a square gets so that the code is at least [leastPx] wide. A whole number, so that every square is as wide as the next. */
fun squarePx(leastPx: Int, squares: Int): Int {
    if (squares <= 0) return 1
    return ((leastPx + squares - 1) / squares).coerceAtLeast(1)
}

private const val MAX_GROUPS = 10
private const val MAX_GROUP = 8

/** The code in the groups it is shown and typed in. */
fun codeGroups(code: String): List<String> =
    code.trim().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.take(MAX_GROUPS).map { it.take(MAX_GROUP) }

/** The code for TalkBack: every character by itself, and a pause between two groups. */
fun spokenCode(code: String): String = codeGroups(code).joinToString(", ") { group -> group.toList().joinToString(" ") }

private const val MAX_LINK_SHOWN = 200

/** A link as long as a row can carry. The Add screen shows all of it. */
fun shownLink(text: String, limit: Int = MAX_LINK_SHOWN): String = if (text.length <= limit) text else text.take(limit) + "\u2026"

/** Nothing is said about a handoff that was closed from this device. */
fun endSentence(why: HandoffEnd): Int? = when (why) {
    HandoffEnd.EXPIRED -> R.string.handoff_end_expired
    HandoffEnd.LEFT_SCREEN -> R.string.handoff_end_left_screen
    HandoffEnd.USED_UP -> R.string.handoff_end_used_up
    HandoffEnd.CLOSED -> null
}
