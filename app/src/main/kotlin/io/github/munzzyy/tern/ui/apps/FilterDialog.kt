package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.ChipLines
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.categoryColor

const val FILTER_DIALOG_TAG = "filter_dialog"

/** The longest text a field of the filter takes. */
private const val MAX_WORDS = 100

/**
 * Every filter at once: words to find in the name, the developer and the package, the kinds of
 * apps to show, and the categories and sources to keep. Each change applies at once, as in the
 * dialog that arranges the list.
 */
@Composable
fun FilterDialog(state: AppsState, onChange: (ListFilter) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val filter = state.filter
    val colors = LocalEngine.current.settings.collectAsStateWithLifecycle().value.categoryColors
    AlertDialog(
        modifier = Modifier.focusHighlight().testTag(FILTER_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_open)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Words(stringResource(R.string.filter_name), filter.name) { onChange(filter.copy(name = it)) }
                Words(stringResource(R.string.filter_author), filter.author) { onChange(filter.copy(author = it)) }
                Words(stringResource(R.string.filter_package), filter.packageName) { onChange(filter.copy(packageName = it)) }
                SwitchRow(
                    title = stringResource(R.string.filter_show_up_to_date),
                    checked = !filter.hideUpToDate,
                    onChange = { onChange(filter.copy(hideUpToDate = !it)) },
                )
                SwitchRow(
                    title = stringResource(R.string.filter_show_not_installed),
                    checked = filter.installed != true,
                    onChange = { onChange(filter.copy(installed = if (it) null else true)) },
                )
                SwitchRow(
                    title = stringResource(R.string.filter_show_tracked),
                    checked = filter.tracked != false,
                    onChange = { onChange(filter.copy(tracked = if (it) null else false)) },
                )
                if (state.categories.isNotEmpty()) {
                    Chips(stringResource(R.string.filter_categories), state.categories.map { it to AppFilter.Category(it) }, filter, onChange) { categoryColor(it, colors) }
                }
                if (state.sources.size > 1) {
                    Chips(stringResource(R.string.filter_sources), state.sources.map { sourceLabel(it) to AppFilter.Source(it) }, filter, onChange)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = onClear, enabled = filter.isOn) { Text(stringResource(R.string.filter_clear)) } },
    )
}

@Composable
private fun Words(label: String, value: String, onValue: (String) -> Unit) {
    val look = LocalLook.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(MAX_WORDS)) },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.gapSmall / 2)
            .textFieldKeys(),
    )
}

/** Chips of one kind, any number of them on; the list keeps an app that is under any of them. */
@Composable
private fun Chips(
    title: String,
    chips: List<Pair<String, AppFilter>>,
    filter: ListFilter,
    onChange: (ListFilter) -> Unit,
    dot: (String) -> Color? = { null },
) {
    val look = LocalLook.current
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        ChipLines(chips) { _, (label, chip), stop ->
            ChoiceChip(
                label,
                selected = filter.has(chip),
                onClick = { onChange(filter.toggled(chip)) },
                modifier = stop,
                role = Role.Checkbox,
                dot = (chip as? AppFilter.Category)?.let { dot(it.name) },
            )
        }
    }
}
