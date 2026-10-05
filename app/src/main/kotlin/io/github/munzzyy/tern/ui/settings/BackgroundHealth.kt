package io.github.munzzyy.tern.ui.settings

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.BackgroundFacts
import io.github.munzzyy.tern.engine.RunStop
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.PressRow
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.openAppInfo
import io.github.munzzyy.tern.ui.common.openNotificationSettings
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.status

/** Why the last background run reached nothing, what keeps the check from running, or what it finds from being heard. */
enum class BackgroundNote { PROXY_SILENT, OFFLINE, RESTRICTED, QUIET, NOT_SET, STALE }

object BackgroundHealth {
    /** A check that has not run for this many of its intervals is said to be held back. */
    const val STALE_INTERVALS = 3
    private const val MINUTE_MS = 60_000L

    /**
     * The notes for the check as [s] sets it, none while it is off, the reason the last run reached
     * nothing first. A television shows no
     * notifications, so it is told nothing about them. A check that waits for Wi-Fi or a charger
     * may not have run for long because of that alone, so it is not said to be held back.
     */
    fun notes(s: Settings, facts: BackgroundFacts, nowMs: Long, television: Boolean): List<BackgroundNote> {
        if (s.checkEveryMinutes <= 0) return emptyList()
        val waits = s.checkOnlyOnUnmetered || s.checkOnlyWhileCharging
        return buildList {
            when (facts.lastRunStopped) {
                RunStop.PROXY_SILENT -> add(BackgroundNote.PROXY_SILENT)
                RunStop.OFFLINE -> add(BackgroundNote.OFFLINE)
                null -> Unit
            }
            if (facts.restricted) add(BackgroundNote.RESTRICTED)
            if (!television && s.notifyUpdates && (!facts.notificationsOn || !facts.updatesChannelOn)) add(BackgroundNote.QUIET)
            if (!facts.scheduled) {
                add(BackgroundNote.NOT_SET)
            } else if (!facts.restricted && !waits && stale(s.checkEveryMinutes, facts, nowMs)) {
                add(BackgroundNote.STALE)
            }
        }
    }

    private fun stale(minutes: Int, facts: BackgroundFacts, nowMs: Long): Boolean {
        val from = listOfNotNull(facts.lastRunMs, facts.sinceMs).maxOrNull() ?: return false
        return nowMs - from > STALE_INTERVALS * minutes.toLong() * MINUTE_MS
    }
}

/** Under how often: when the background check last ran, and a note for each thing that holds it back. */
@Composable
fun BackgroundHealthRows(s: Settings) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    var facts by remember(engine) { mutableStateOf(engine.background()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(engine, s.checkEveryMinutes, s.checkOnlyOnUnmetered, s.checkOnlyWhileCharging) {
        facts = engine.background()
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }
    if (s.checkEveryMinutes <= 0) return
    val last = facts.lastRunMs
    Text(
        if (last != null) stringResource(R.string.background_last_run, relativeTime(last, now)) else stringResource(R.string.background_not_run_yet),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = look.rowPaddingHorizontal, end = look.rowPaddingHorizontal, bottom = look.gapSmall),
    )
    for (note in BackgroundHealth.notes(s, facts, now, look.television)) NoteRow(note, facts.canOpenAppInfo)
}

@Composable
private fun NoteRow(note: BackgroundNote, canOpenAppInfo: Boolean) {
    val context = LocalContext.current
    val look = LocalLook.current
    val actions = rememberActions()
    val failed = stringResource(R.string.action_failed)
    val sentence = stringResource(noteText(note))
    val glyph: @Composable () -> Unit = {
        Icon(Glyphs.Caution, contentDescription = null, tint = MaterialTheme.status.caution.color, modifier = Modifier.size(look.glyph))
    }
    val toNotifications = note == BackgroundNote.QUIET
    val opens = toNotifications || canOpenAppInfo && (note == BackgroundNote.RESTRICTED || note == BackgroundNote.STALE)
    if (!opens) {
        ReadBlock {
            Row(horizontalArrangement = Arrangement.spacedBy(look.gap), verticalAlignment = Alignment.CenterVertically) {
                glyph()
                Text(sentence, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        return
    }
    PressRow(
        action = stringResource(if (toNotifications) R.string.settings_system_notifications else R.string.action_app_info),
        onClick = { if (!(if (toNotifications) openNotificationSettings(context) else openAppInfo(context))) actions.say(failed) },
        leading = glyph,
    ) {
        Text(sentence, style = MaterialTheme.typography.bodyMedium)
    }
}

@StringRes
private fun noteText(note: BackgroundNote): Int = when (note) {
    BackgroundNote.PROXY_SILENT -> R.string.engine_proxy_silent
    BackgroundNote.OFFLINE -> R.string.background_stopped_offline
    BackgroundNote.RESTRICTED -> R.string.background_restricted
    BackgroundNote.QUIET -> R.string.background_quiet
    BackgroundNote.NOT_SET -> R.string.background_not_set
    BackgroundNote.STALE -> R.string.background_stale
}

/** "2 hours ago", as Android says it in the person's language. */
private fun relativeTime(ms: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS).toString()
