package io.github.munzzyy.jackdaw.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.ui.notes.isWebLink

/** Hands a web link to whatever the user picks. Returns false when nothing can open it. */
fun openLink(context: Context, url: String): Boolean {
    if (!isWebLink(url)) return false
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val chooser = Intent.createChooser(view, context.getString(R.string.link_open_with))
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Shows the whole address before anything leaves the app. */
@Composable
fun LinkDialog(url: String, onDismiss: () -> Unit, note: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val actions = rememberActions()
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(url, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openLink(context, url)
                onDismiss()
            }) { Text(stringResource(R.string.link_open)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    actions.run { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(url, url))) }
                    onDismiss()
                },
                modifier = Modifier.focusWhenShown(),
            ) { Text(stringResource(R.string.action_copy)) }
        },
    )
}

/** Text that opens a link: start aligned and free to wrap, with a 48dp tall target. */
@Composable
fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .wrapContentHeight()
            .padding(vertical = 4.dp),
    )
}
