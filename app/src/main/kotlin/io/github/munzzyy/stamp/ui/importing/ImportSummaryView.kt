package io.github.munzzyy.stamp.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.ui.common.TrustLine
import io.github.munzzyy.stamp.ui.text.Trust

/** Groups of this size or smaller show their names at once; larger ones fold them behind a button. */
private const val OPEN_GROUP = 3

/** What an import did: counts first, then the skipped ones grouped by reason with their names folded. */
@Composable
fun ImportSummaryView(added: Int, present: Int, skipped: List<Pair<String, String>>, extra: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(R.string.import_done), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        TrustLine(Trust.GOOD, pluralStringResource(R.plurals.import_added, added, added))
        TrustLine(Trust.NOTE, pluralStringResource(R.plurals.import_present, present, present))
        extra()
        if (skipped.isNotEmpty()) {
            TrustLine(Trust.BAD, pluralStringResource(R.plurals.import_skipped, skipped.size, skipped.size))
            for ((reason, names) in groupSkipped(skipped)) {
                SkippedGroup(reason, names)
            }
        }
    }
}

@Composable
private fun SkippedGroup(reason: String, names: List<String>) {
    var open by rememberSaveable(reason) { mutableStateOf(names.size <= OPEN_GROUP) }
    Column(Modifier.padding(start = 32.dp)) {
        Text(reason, style = MaterialTheme.typography.bodyMedium)
        if (open) {
            Text(
                names.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (names.size > OPEN_GROUP) {
            TextButton(onClick = { open = !open }) {
                Text(if (open) stringResource(R.string.import_hide_names) else pluralStringResource(R.plurals.import_show_names, names.size, names.size))
            }
        }
    }
}
