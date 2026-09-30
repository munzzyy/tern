package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.detail.Loadable
import io.github.munzzyy.tern.ui.notes.NotesView
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.knownVersion
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.CancellationException

const val CHANGES_DIALOG_TAG = "changes_dialog"

/** Whether [release] has notes or a page to show as its changes. */
fun hasChanges(release: Release?): Boolean = release != null && (!release.notes.isNullOrBlank() || release.pageUrl != null)

/** The notes of [release], read when asked for. Its page opens in the browser after its whole address has been shown. */
@Composable
fun ChangesDialog(release: Release, onDismiss: () -> Unit) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val notes by produceState<Loadable<List<NoteBlock>>>(Loadable.Loading, release.id) {
        value = try {
            Loadable.Ready(engine.notes(release))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Loadable.Failed
        }
    }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    val title = knownVersion(release.version)?.let { stringResource(R.string.notes_title, isolate(it)) } ?: stringResource(R.string.notes_title_unknown)
    AlertDialog(
        modifier = Modifier.focusHighlight().testTag(CHANGES_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                when (val state = notes) {
                    Loadable.Loading -> CircularProgressIndicator(Modifier.padding(look.gapSmall).size(look.glyph))
                    Loadable.Failed -> Text(stringResource(R.string.notes_failed), style = MaterialTheme.typography.bodyMedium)
                    is Loadable.Ready -> if (state.value.isEmpty()) {
                        Text(stringResource(R.string.notes_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        NotesView(state.value)
                    }
                }
                release.pageUrl?.let { page -> QuietButton(stringResource(R.string.action_release_page), onClick = { link = page }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}
