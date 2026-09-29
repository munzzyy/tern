package io.github.munzzyy.jackdaw.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.Progress
import io.github.munzzyy.jackdaw.ui.text.VersionChange
import io.github.munzzyy.jackdaw.ui.text.formatBytes
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.text.isolate
import io.github.munzzyy.jackdaw.ui.text.quietOffline
import io.github.munzzyy.jackdaw.ui.text.percentOf
import io.github.munzzyy.jackdaw.ui.text.statusLabel
import io.github.munzzyy.jackdaw.ui.text.versionChange

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
    val parts = mutableListOf(row.config.name, stringResource(statusLabel(row, online).text))
    versionChange(row)?.let { parts += spokenVersion(it) }
    row.progress?.let { p -> progressText(p)?.let { parts += it } }
    row.problem?.takeUnless { quietOffline(row, online) }?.let { parts += it.message }
    return parts.joinToString(". ") { it.trimEnd('.') } + "."
}
