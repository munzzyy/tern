package io.github.munzzyy.stamp.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.ui.common.TrustLine
import io.github.munzzyy.stamp.ui.detail.updateModeLabel
import io.github.munzzyy.stamp.ui.text.Trust
import io.github.munzzyy.stamp.ui.text.breakableFingerprint
import io.github.munzzyy.stamp.ui.text.formatFingerprint
import io.github.munzzyy.stamp.ui.text.isolate
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.fingerprint

const val CARRIED_TAG = "add_carried"

@Composable
fun CarriedSection(settings: List<CarriedSetting>) {
    if (settings.isEmpty()) return
    val look = LocalLook.current
    Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall), modifier = Modifier.testTag(CARRIED_TAG)) {
        Text(
            stringResource(R.string.carried_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        for (setting in settings.filterNot { it is CarriedSetting.Pin }) {
            Row {
                Text("\u2022", modifier = Modifier.width(look.gap), style = MaterialTheme.typography.bodyMedium)
                Text(sentence(setting), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val pins = settings.filterIsInstance<CarriedSetting.Pin>()
        if (pins.isNotEmpty()) {
            TrustLine(Trust.BAD, pluralStringResource(R.plurals.carried_pins_warning, pins.size))
            for (pin in pins) {
                Text(breakableFingerprint(formatFingerprint(pin.sha256)), style = MaterialTheme.typography.bodyMedium.fingerprint())
            }
        }
    }
}

private fun pattern(p: String?): String = isolate("“$p”")

@Composable
private fun sentence(s: CarriedSetting): String = when (s) {
    is CarriedSetting.Mode -> stringResource(R.string.carried_mode, updateModeLabel(s.mode))
    is CarriedSetting.Prereleases -> stringResource(if (s.on) R.string.setting_prereleases_on else R.string.setting_prereleases_off)
    is CarriedSetting.MinAge -> if (s.days == 0) {
        stringResource(R.string.carried_min_age_none)
    } else {
        pluralStringResource(R.plurals.carried_min_age, s.days, s.days)
    }
    is CarriedSetting.Include -> s.pattern?.let { stringResource(R.string.carried_include, pattern(it)) } ?: stringResource(R.string.carried_include_none)
    is CarriedSetting.Exclude -> s.pattern?.let { stringResource(R.string.carried_exclude, pattern(it)) } ?: stringResource(R.string.carried_exclude_none)
    is CarriedSetting.TagFilter -> s.pattern?.let { stringResource(R.string.carried_tag, pattern(it)) } ?: stringResource(R.string.carried_tag_none)
    is CarriedSetting.TitleFilter -> s.pattern?.let { stringResource(R.string.carried_title_filter, pattern(it)) } ?: stringResource(R.string.carried_title_filter_none)
    is CarriedSetting.NotesFilter -> s.pattern?.let { stringResource(R.string.carried_notes, pattern(it)) } ?: stringResource(R.string.carried_notes_none)
    is CarriedSetting.VersionPattern -> s.pattern?.let { stringResource(R.string.carried_version, pattern(it)) } ?: stringResource(R.string.carried_version_none)
    is CarriedSetting.MatchDevice -> stringResource(if (s.on) R.string.carried_match_device_on else R.string.carried_match_device_off)
    is CarriedSetting.FallBack -> stringResource(if (s.on) R.string.carried_fallback_on else R.string.carried_fallback_off)
    is CarriedSetting.TrackOnly -> stringResource(if (s.on) R.string.carried_track_on else R.string.carried_track_off)
    is CarriedSetting.Pin -> formatFingerprint(s.sha256)
}
