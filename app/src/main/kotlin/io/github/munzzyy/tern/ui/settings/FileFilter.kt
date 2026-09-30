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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.data.SettingsStore
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.text.ltr

/** The filter every app without one of its own picks its file by, as in Obtainium's global APK filter. */
@Composable
fun FileFilterRow(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    ActionRow(
        title = stringResource(R.string.settings_file_filter),
        summary = s.globalFileFilter?.let(::ltr) ?: stringResource(R.string.settings_file_filter_effect),
        onClick = { editing = true },
    )
    if (editing) {
        FileFilterDialog(
            current = s.globalFileFilter.orEmpty(),
            onDismiss = { editing = false },
            onSave = { pattern -> update { it.copy(globalFileFilter = pattern) } },
        )
    }
}

@Composable
private fun FileFilterDialog(current: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val blank = text.isBlank()
    val clean = SettingsStore.cleanFilter(text)
    val invalid = !blank && clean == null
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_file_filter)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(SettingsStore.MAX_FILTER) },
                singleLine = true,
                isError = invalid,
                label = { Text(stringResource(R.string.settings_file_filter)) },
                placeholder = { Text(stringResource(R.string.settings_file_filter_none)) },
                supportingText = { Text(stringResource(if (invalid) R.string.settings_file_filter_invalid else R.string.settings_file_filter_effect)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(clean); onDismiss() }, enabled = !invalid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}
