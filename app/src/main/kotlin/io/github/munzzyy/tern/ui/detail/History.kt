package io.github.munzzyy.tern.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.NoteBlock
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.notes.NotesView
import io.github.munzzyy.tern.ui.text.canPickInstall
import io.github.munzzyy.tern.ui.text.formatDate
import io.github.munzzyy.tern.ui.text.isInstalledRelease
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.knownVersion
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.figures

private const val FOLDED_COUNT = 5

/** How far the words of a quiet button stand in from its side. A quiet button that starts a line is moved back by this, so its words line up with the text above. */
@Composable
fun quietInset(): Dp = LocalLook.current.gap * 3 / 4

fun LazyListScope.history(vm: DetailViewModel, row: AppRow) {
    row.latest?.let { latest ->
        item(key = "notes") {
            val title = knownVersion(latest.version)?.let { stringResource(R.string.notes_title, isolate(it)) } ?: stringResource(R.string.notes_title_unknown)
            DetailCard(title, padded = true) {
                LaunchedEffect(latest.id) { vm.loadNotes(latest) }
                val notes by vm.notes.collectAsStateWithLifecycle()
                NotesState(notes[latest.id])
            }
        }
    }
    item(key = "versions") {
        DetailCard(stringResource(R.string.versions_title), padded = true) { Versions(vm, row) }
    }
}

@Composable
private fun NotesState(state: Loadable<List<NoteBlock>>?) {
    val look = LocalLook.current
    when (state) {
        null, Loadable.Loading -> CircularProgressIndicator(Modifier.padding(look.gapSmall).size(look.glyph))
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
    val look = LocalLook.current
    val releases by vm.releases.collectAsStateWithLifecycle()
    var all by rememberSaveable(row.id) { mutableStateOf(false) }
    when (val r = releases) {
        Loadable.Loading -> CircularProgressIndicator(Modifier.padding(look.gapSmall).size(look.glyph))
        Loadable.Failed -> Text(stringResource(R.string.versions_failed), style = MaterialTheme.typography.bodyMedium)
        is Loadable.Ready -> {
            if (r.value.isEmpty()) {
                Text(stringResource(R.string.versions_none), style = MaterialTheme.typography.bodyMedium)
            }
            val shown = if (all) r.value else r.value.take(FOLDED_COUNT)
            shown.forEachIndexed { index, release ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReleaseEntry(vm, row, release)
            }
            if (r.value.size > FOLDED_COUNT) {
                QuietButton(
                    if (all) stringResource(R.string.versions_fewer) else stringResource(R.string.versions_all, r.value.size),
                    onClick = { all = !all },
                    modifier = Modifier.offset(x = -quietInset()),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseEntry(vm: DetailViewModel, row: AppRow, release: Release) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    var open by rememberSaveable(release.id) { mutableStateOf(false) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(vertical = look.gapSmall), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
        val version = knownVersion(release.version)
        Text(version ?: stringResource(R.string.version_unknown_short), style = MaterialTheme.typography.titleSmall.figures())
        val meta = listOfNotNull(
            release.publishedAtMs?.let { isolate(formatDate(it)) },
            if (release.prerelease) stringResource(R.string.prerelease) else null,
            if (release.id == row.latest?.id) stringResource(R.string.version_offered) else null,
            if (isInstalledRelease(release, row.installed)) stringResource(R.string.version_installed) else null,
        )
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2),
            verticalArrangement = Arrangement.spacedBy(look.focusRoom),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val isInstalled = isInstalledRelease(release, row.installed)
            val installable = canPickInstall(row) && !isInstalled && release.installable.isNotEmpty()
            if (installable) {
                val spoken = version?.let { stringResource(R.string.action_install_version_spoken, it) } ?: stringResource(R.string.action_install_version)
                TonalButton(
                    stringResource(R.string.action_install_version),
                    onClick = { engine.install(row.id, releaseId = release.id) },
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
            QuietButton(
                stringResource(if (open) R.string.notes_hide else R.string.notes_show),
                onClick = {
                    open = !open
                    if (open) vm.loadNotes(release)
                },
                modifier = if (installable) Modifier else Modifier.offset(x = -quietInset()),
            )
        }
        if (open) NotesState(notes[release.id])
    }
}
