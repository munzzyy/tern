package io.github.munzzyy.tern.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.common.focusHighlight

/** Android 9 gets no security fixes any more, so a person on it is told once what Tern cannot cover. */
fun showsAndroid9Note(sdk: Int, firstRunDone: Boolean, noted: Boolean): Boolean = sdk == 28 && firstRunDone && !noted

@Composable
fun Android9Note(onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.android9_title)) },
        text = { Text(stringResource(R.string.android9_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
