package io.github.munzzyy.tern.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import io.github.munzzyy.tern.ui.text.VersionChange
import io.github.munzzyy.tern.ui.text.formatBytes
import io.github.munzzyy.tern.ui.text.formatDate
import io.github.munzzyy.tern.ui.text.shortUrl
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.quietOffline
import io.github.munzzyy.tern.ui.text.percentOf
import io.github.munzzyy.tern.ui.text.statusLabel
import io.github.munzzyy.tern.ui.text.versionChange

@Composable
fun versionText(change: VersionChange?): String? = when (change) {
    null -> null
    is VersionChange.Same -> isolate(change.version)
    is VersionChange.Change -> stringResource(R.string.version_change, isolate(change.from), isolate(change.to))
    is VersionChange.NewFile -> stringResource(R.string.version_new_file, isolate(change.installed))
    VersionChange.Unknown -> stringResource(R.string.version_unknown)
}

@Composable
private fun spokenVersion(change: VersionChange): String = when (change) {
    is VersionChange.Same -> stringResource(R.string.version_single_spoken, change.version)
    is VersionChange.Change -> stringResource(R.string.version_change_spoken, change.from, change.to)
    is VersionChange.NewFile -> stringResource(R.string.version_new_file_spoken, change.installed)
    VersionChange.Unknown -> stringResource(R.string.version_unknown)
}

@Composable
fun progressText(progress: Progress): String? {
    if (progress.phase != Phase.DOWNLOADING) return null
    val total = progress.bytesTotal
    val fraction = progress.fraction
    return if (total != null && fraction != null) {
        stringResource(R.string.progress_bytes_percent, isolate(formatBytes(progress.bytesDone)), isolate(formatBytes(total)), percentOf(fraction))
    } else {
        stringResource(R.string.progress_bytes, isolate(formatBytes(progress.bytesDone)))
    }
}

/** One sentence that TalkBack reads for the whole row. */
@Composable
fun rowDescription(row: AppRow): String {
    val online = LocalOnline.current
    val parts = mutableListOf(row.config.shownName)
    if (row.config.favorite) parts += stringResource(R.string.state_favorite)
    parts += stringResource(statusLabel(row, online).text)
    versionChange(row)?.let { parts += spokenVersion(it) }
    row.movedTo?.let { parts += stringResource(R.string.row_moved, shortUrl(it)) }
    if (row.movedTo == null && row.progress == null) row.latest?.publishedAtMs?.let { parts += stringResource(R.string.row_released, formatDate(it)) }
    row.progress?.let { p -> progressText(p)?.let { parts += it } }
    row.problem?.takeUnless { quietOffline(row, online) }?.let { parts += it.message }
    return parts.joinToString(". ") { it.trimEnd('.') } + "."
}
