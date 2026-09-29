package io.github.munzzyy.jackdaw.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.NoteBlock
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.notes.NotesView
import io.github.munzzyy.jackdaw.ui.text.canPickInstall
import io.github.munzzyy.jackdaw.ui.text.formatDate
import io.github.munzzyy.jackdaw.ui.text.isInstalledRelease
import io.github.munzzyy.jackdaw.ui.text.isolate
import io.github.munzzyy.jackdaw.ui.text.knownVersion

private const val FOLDED_COUNT = 5

fun LazyListScope.history(vm: DetailViewModel, row: AppRow) {
    row.latest?.let { latest ->
        item(key = "notes") {
            Section {
                SectionTitle(knownVersion(latest.version)?.let { stringResource(R.string.notes_title, isolate(it)) } ?: stringResource(R.string.notes_title_unknown))
                LaunchedEffect(latest.id) { vm.loadNotes(latest) }
                val notes by vm.notes.collectAsStateWithLifecycle()
                NotesState(notes[latest.id])
            }
        }
    }
    item(key = "versions-title") {
        Section { SectionTitle(stringResource(R.string.versions_title)) }
    }
    item(key = "versions") { Versions(vm, row) }
}

@Composable
private fun NotesState(state: Loadable<List<NoteBlock>>?) {
    when (state) {
        null, Loadable.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
        Loadable.Failed -> Text(stringResource(R.string.notes_failed), style = MaterialTheme.typography.bodyMedium)
        is Loadable.Ready -> if (state.value.isEmpty()) {
            Text(stringResource(R.string.notes_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            NotesView(state.value)
        }
    }
}

@Composable
private fun Versions(vm: DetailViewModel, row: AppRow) {
    val releases by vm.releases.collectAsStateWithLifecycle()
    var all by rememberSaveable(row.id) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 840.dp)) {
        when (val r = releases) {
            Loadable.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            Loadable.Failed -> Text(stringResource(R.string.versions_failed), style = MaterialTheme.typography.bodyMedium)
            is Loadable.Ready -> {
                if (r.value.isEmpty()) {
                    Text(stringResource(R.string.versions_none), style = MaterialTheme.typography.bodyMedium)
                }
                val shown = if (all) r.value else r.value.take(FOLDED_COUNT)
                for (release in shown) ReleaseEntry(vm, row, release)
                if (r.value.size > FOLDED_COUNT) {
                    TextButton(onClick = { all = !all }) {
                        Text(if (all) stringResource(R.string.versions_fewer) else stringResource(R.string.versions_all, r.value.size))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseEntry(vm: DetailViewModel, row: AppRow, release: Release) {
    val engine = LocalEngine.current
    var open by rememberSaveable(release.id) { mutableStateOf(false) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val version = knownVersion(release.version)
        Text(version ?: stringResource(R.string.version_unknown_short), style = MaterialTheme.typography.titleSmall)
        val meta = listOfNotNull(
            release.publishedAtMs?.let { isolate(formatDate(it)) },
            if (release.prerelease) stringResource(R.string.prerelease) else null,
            if (release.id == row.latest?.id) stringResource(R.string.version_offered) else null,
            if (isInstalledRelease(release, row.installed)) stringResource(R.string.version_installed) else null,
        )
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val isInstalled = isInstalledRelease(release, row.installed)
            val installable = canPickInstall(row) && !isInstalled && release.installable.isNotEmpty()
            if (installable) {
                val spoken = version?.let { stringResource(R.string.action_install_version_spoken, it) } ?: stringResource(R.string.action_install_version)
                OutlinedButton(
                    onClick = { engine.install(row.id, releaseId = release.id) },
                    modifier = Modifier.semantics { contentDescription = spoken },
                ) {
                    Text(stringResource(R.string.action_install_version))
                }
            }
            TextButton(onClick = {
                open = !open
                if (open) vm.loadNotes(release)
            }) {
                Text(stringResource(if (open) R.string.notes_hide else R.string.notes_show))
            }
        }
        if (open) NotesState(notes[release.id])
    }
}
