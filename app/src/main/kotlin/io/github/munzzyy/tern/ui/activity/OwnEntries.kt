package io.github.munzzyy.tern.ui.activity

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.Info
import io.github.munzzyy.tern.ui.theme.status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The first line of one of Tern's own messages, which says what happened. */
fun ownHeadline(message: String): String = message.substringBefore('\n')

/** The lines after it, an error's class and message and where in Tern it happened; null when there are none. */
fun ownDetail(message: String): String? = message.substringAfter('\n', "").takeIf { it.isNotBlank() }

/** Marks an entry as Tern's own, with how much it weighs. */
@Composable
fun ownMark(kind: EventKind): String = stringResource(
    when (kind) {
        EventKind.OWN_WARNING -> R.string.journal_mark_warning
        EventKind.OWN_ERROR -> R.string.journal_mark_error
        else -> R.string.journal_mark_note
    },
)

/** [ownMark] of each kind of Tern's own messages, for the text that is shared. */
@Composable
fun ownMarks(): Map<EventKind, String> = listOf(EventKind.OWN_NOTE, EventKind.OWN_WARNING, EventKind.OWN_ERROR).associateWith { ownMark(it) }

/** The glyph and colour of one of Tern's own messages: an error as a failure, a warning as a caution, a note as a note. */
@Composable
fun ownLook(kind: EventKind): Pair<ImageVector, Color> {
    val status = MaterialTheme.status
    return when (kind) {
        EventKind.OWN_ERROR -> Glyphs.Failed to status.refused.color
        EventKind.OWN_WARNING -> Glyphs.Caution to status.caution.color
        else -> Glyphs.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The time of one of Tern's own messages, to the second: several can come in one minute. */
fun formatSecond(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))
