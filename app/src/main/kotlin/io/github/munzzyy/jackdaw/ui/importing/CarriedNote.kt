package io.github.munzzyy.jackdaw.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.ImportSummary
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.common.LinkText
import io.github.munzzyy.jackdaw.ui.common.TrustLine
import io.github.munzzyy.jackdaw.ui.text.Trust

/**
 * Pairs each name with a different app of that name, preferring one that [fits], in list order.
 * The id is null when no such app is in the list any more.
 */
fun linkNames(names: List<String>, rows: List<AppRow>, fits: (AppRow) -> Boolean): List<Pair<String, String?>> {
    val used = HashSet<String>()
    return names.map { name ->
        val free = rows.filter { it.config.name == name && it.id !in used }
        val row = free.firstOrNull(fits) ?: free.firstOrNull()
        row?.let { used += it.id }
        name to row?.id
    }
}

private fun AppRow.hasFilters(): Boolean = listOf(
    config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
    config.releases.versionExtract, config.assets.include, config.assets.exclude,
).any { !it.isNullOrBlank() }

/** Names the imported apps whose pins or filters were chosen by whoever made the file, each linked to its page. */
@Composable
fun CarriedNote(summary: ImportSummary, onOpenApp: (String) -> Unit) {
    if (summary.withPins.isEmpty() && summary.withFilters.isEmpty()) return
    val rows by LocalEngine.current.apps.collectAsStateWithLifecycle()
    val pins = remember(summary, rows) { linkNames(summary.withPins, rows) { it.config.pinnedSigners.isNotEmpty() } }
    val filters = remember(summary, rows) { linkNames(summary.withFilters, rows) { it.hasFilters() } }
    TrustLine(Trust.NOTE, stringResource(R.string.import_carried))
    Group(stringResource(R.string.import_carried_pins), pins, onOpenApp)
    Group(stringResource(R.string.import_carried_filters), filters, onOpenApp)
}

@Composable
private fun Group(title: String, names: List<Pair<String, String?>>, onOpenApp: (String) -> Unit) {
    if (names.isEmpty()) return
    Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        for ((name, id) in names) {
            if (id == null) {
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LinkText(name, onClick = { onOpenApp(id) })
            }
        }
    }
}
