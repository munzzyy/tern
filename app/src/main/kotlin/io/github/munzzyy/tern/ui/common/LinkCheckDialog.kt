package io.github.munzzyy.tern.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R

/**
 * Asked when a web page or another app sends a tern:// or obtainium:// link for a check: Tern
 * reaches the sources only once the person says so. [appName] is the one app the link names,
 * null for the whole list of [count] apps.
 */
@Composable
fun LinkCheckDialog(appName: String?, count: Int, onCheck: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_check_title)) },
        text = {
            Text(if (appName != null) stringResource(R.string.link_check_one, appName) else pluralStringResource(R.plurals.link_check_all, count, count))
        },
        confirmButton = { TextButton(onClick = onCheck) { Text(stringResource(R.string.link_check_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.link_check_not_now)) } },
    )
}
