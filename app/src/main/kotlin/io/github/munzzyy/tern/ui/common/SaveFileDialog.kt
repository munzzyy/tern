package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.ui.text.formatBytes
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.text.ltr
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.figures

/**
 * Every file of [release] to pick one to save, the archives of its source among them, as
 * Obtainium's picker offers them. The file [preselected] names, else the first, is picked at
 * first. [onSkip], where given, passes over this app to the next of several.
 */
@Composable
fun SaveFileDialog(
    title: String,
    release: Release,
    preselected: String?,
    onSave: (Asset) -> Unit,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val look = LocalLook.current
    val files = release.savable
    var picked by rememberSaveable(release.id) { mutableStateOf(startingPick(files, preselected)) }
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (file in files) {
                    val selected = file.url == picked
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(look.gap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
                            .then(if (selected) Modifier.focusWhenShown() else Modifier)
                            .focusLook()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { picked = file.url })
                            .heightIn(min = look.touchTarget)
                            .padding(horizontal = look.gapSmall),
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        FileWords(file, source = file in release.sourceArchives, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { files.firstOrNull { it.url == picked }?.let(onSave) }, enabled = picked != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip ?: onDismiss) { Text(stringResource(if (onSkip != null) R.string.files_skip else R.string.action_cancel)) }
        },
    )
}

/** A file's name, and below it its size where known and what it is where it is the source. */
@Composable
fun FileWords(file: Asset, source: Boolean, modifier: Modifier = Modifier, size: Long? = file.size) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LocalLook.current.gapSmall / 4)) {
        Text(ltr(file.name), style = MaterialTheme.typography.bodyLarge)
        val about = listOfNotNull(size?.let { isolate(formatBytes(it)) }, if (source) stringResource(R.string.files_source_code) else null)
        if (about.isNotEmpty()) Text(about.joinToString(" · "), style = MaterialTheme.typography.bodySmall.figures(), color = quiet)
    }
}

/** The address of the file a picker starts on: the one [preselected] names where the release has it, else the first. */
fun startingPick(files: List<Asset>, preselected: String?): String? =
    files.firstOrNull { it.url == preselected }?.url ?: files.firstOrNull()?.url
