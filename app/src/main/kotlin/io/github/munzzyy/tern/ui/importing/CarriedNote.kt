package io.github.munzzyy.tern.ui.importing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.LinkText
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.TrustLine
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.theme.LocalLook

/**
 * Pairs each name with a different app of that name, preferring one that [fits], in list order.
 * The id is null when no such app is in the list any more.
 */
fun linkNames(names: List<String>, rows: List<AppRow>, fits: (AppRow) -> Boolean): List<Pair<String, String?>> {
    val used = HashSet<String>()
    return names.map { name ->
        val free = rows.filter { it.config.shownName == name && it.id !in used }
        val row = free.firstOrNull(fits) ?: free.firstOrNull()
        row?.let { used += it.id }
        name to row?.id
    }
}

private fun AppRow.hasFilters(): Boolean = listOf(
    config.releases.tagFilter, config.releases.titleFilter, config.releases.notesFilter,
    config.releases.versionExtract, config.assets.include, config.assets.exclude,
    config.releases.versionFilter, config.assets.innerFilter,
).any { !it.isNullOrBlank() }

/**
 * Names the imported apps whose pins or filters were chosen by whoever made the file, each linked
 * to its page, and those the file set to update by themselves, which Tern stored as "Tell me".
 * Says so first when the file's settings were taken too.
 */
@Composable
fun CarriedNote(summary: ImportSummary, onOpenApp: (String) -> Unit) {
    if (summary.settingsTaken) TrustLine(Trust.NOTE, stringResource(R.string.import_settings_taken))
    summary.offer?.let { ImportOffer(summary, it, onOpenApp) }
    CarriedGroups(summary, onOpenApp)
}

const val REPLACE_TAG = "import_replace"
const val TAKE_SETTINGS_TAG = "import_take_settings"

/**
 * What the file only offered: other settings for apps already in the list, and settings for Tern.
 * Nothing of it is done until the person asks, and then each is done once.
 */
@Composable
private fun ImportOffer(summary: ImportSummary, offer: String, onOpenApp: (String) -> Unit) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val look = LocalLook.current
    var done by remember(offer) { mutableStateOf<ImportSummary?>(null) }
    var busy by remember(offer) { mutableStateOf(false) }
    val now = done ?: summary
    val finish = { replace: Boolean, take: Boolean ->
        busy = true
        actions.run {
            try {
                done = engine.finishImport(offer, replace, take)
            } finally {
                busy = false
            }
        }
    }
    val waiting = now.replaceable
    if (waiting.isNotEmpty()) {
        TrustLine(Trust.NOTE, pluralStringResource(R.plurals.import_replaceable, waiting.size, waiting.size))
        Text(waiting.take(MAX_NAMES).joinToString("\n"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = look.gap * 2))
        TonalButton(
            pluralStringResource(R.plurals.import_replace, waiting.size, waiting.size),
            onClick = { finish(true, false) },
            enabled = !busy,
            modifier = Modifier.testTag(REPLACE_TAG),
        )
    }
    done?.let { result ->
        if (result.replaced > 0) TrustLine(Trust.GOOD, pluralStringResource(R.plurals.import_replaced, result.replaced, result.replaced))
        CarriedGroups(result, onOpenApp)
    }
    if (now.settingsOffered) {
        TrustLine(Trust.NOTE, stringResource(R.string.import_settings_offered))
        TonalButton(stringResource(R.string.import_take_settings), onClick = { finish(false, true) }, enabled = !busy, modifier = Modifier.testTag(TAKE_SETTINGS_TAG))
    } else if (done?.settingsTaken == true) {
        TrustLine(Trust.NOTE, stringResource(R.string.import_settings_taken))
    }
}

private const val MAX_NAMES = 20

@Composable
private fun CarriedGroups(summary: ImportSummary, onOpenApp: (String) -> Unit) {
    if (summary.withPins.isEmpty() && summary.withFilters.isEmpty() && summary.askedToInstallByThemselves.isEmpty()) return
    val rows by LocalEngine.current.apps.collectAsStateWithLifecycle()
    if (summary.withPins.isNotEmpty() || summary.withFilters.isNotEmpty()) {
        val pins = remember(summary, rows) { linkNames(summary.withPins, rows) { it.config.pinnedSigners.isNotEmpty() } }
        val filters = remember(summary, rows) { linkNames(summary.withFilters, rows) { it.hasFilters() } }
        TrustLine(Trust.NOTE, stringResource(R.string.import_carried))
        Group(stringResource(R.string.import_carried_pins), pins, onOpenApp)
        Group(stringResource(R.string.import_carried_filters), filters, onOpenApp)
    }
    AskedToUpdateByThemselves(summary.askedToInstallByThemselves, rows)
}

const val LET_AUTO_TAG = "import_let_auto"

@Composable
private fun AskedToUpdateByThemselves(names: List<String>, rows: List<AppRow>) {
    if (names.isEmpty()) return
    val engine = LocalEngine.current
    val actions = rememberActions()
    val look = LocalLook.current
    val linked = remember(names, rows) { linkNames(names, rows) { true } }
    TrustLine(Trust.NOTE, stringResource(R.string.import_asked_auto))
    Column(Modifier.padding(start = look.gap * 2)) {
        for ((name, id) in linked) {
            val row = rows.firstOrNull { it.id == id }
            Text(name, style = MaterialTheme.typography.bodyMedium)
            when {
                row == null -> Unit
                row.config.updates == UpdateMode.AUTO -> Text(
                    stringResource(R.string.import_auto_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> QuietButton(
                    stringResource(R.string.import_let_auto),
                    onClick = { actions.run { engine.configure(row.id) { it.copy(updates = UpdateMode.AUTO) } } },
                    modifier = Modifier.testTag(LET_AUTO_TAG),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, names: List<Pair<String, String?>>, onOpenApp: (String) -> Unit) {
    if (names.isEmpty()) return
    Column(Modifier.padding(start = LocalLook.current.gap * 2)) {
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
