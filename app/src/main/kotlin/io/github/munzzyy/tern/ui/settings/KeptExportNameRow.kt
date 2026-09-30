package io.github.munzzyy.tern.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.data.KeptExportName
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.text.ltr

/** The name of the kept export's file, as in Obtainium's automatic export; empty gives the usual name. */
@Composable
fun KeptExportNameRow(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val usual = KeptExportName.usual(s.keptExportFormat)
    ActionRow(
        title = stringResource(R.string.kept_export_name),
        summary = ltr(KeptExportName.of(s.keptExportName, s.keptExportFormat)),
        onClick = { editing = true },
    )
    if (editing) {
        var text by rememberSaveable { mutableStateOf(s.keptExportName.orEmpty()) }
        AlertDialog(
            modifier = Modifier.focusHighlight(),
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.kept_export_name)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(KeptExportName.MAX_LENGTH) },
                    singleLine = true,
                    placeholder = { Text(ltr(usual)) },
                    supportingText = { Text(stringResource(R.string.kept_export_name_usual, ltr(usual))) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().textFieldKeys(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = KeptExportName.clean(text)
                    update { it.copy(keptExportName = name) }
                    editing = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
