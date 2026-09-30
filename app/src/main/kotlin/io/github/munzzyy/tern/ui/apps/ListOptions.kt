package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppGrouping
import io.github.munzzyy.tern.engine.AppSort
import io.github.munzzyy.tern.ui.common.ChoiceChips
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.focusHighlight

const val LIST_OPTIONS_TAG = "list_options"

fun sortLabel(sort: AppSort): Int = when (sort) {
    AppSort.NAME -> R.string.sort_name
    AppSort.AUTHOR -> R.string.sort_author
    AppSort.ADDED -> R.string.sort_added
    AppSort.RELEASED -> R.string.sort_released
    AppSort.RECENTLY_CHECKED -> R.string.sort_recently_checked
    AppSort.SOURCE -> R.string.sort_source
}

private fun groupingLabel(grouping: AppGrouping): Int = when (grouping) {
    AppGrouping.NONE -> R.string.group_none
    AppGrouping.CATEGORY -> R.string.group_category
    AppGrouping.SOURCE -> R.string.group_source
}

/** Order, direction, groups and what goes on top or below. Every choice is kept and applies at once. */
@Composable
fun ListOptionsDialog(query: ListQuery, vm: AppsViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.focusHighlight().testTag(LIST_OPTIONS_TAG),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_options_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val sorts = AppSort.entries
                ChoiceChips(
                    title = stringResource(R.string.sort_title),
                    options = sorts.map { stringResource(sortLabel(it)) },
                    selected = sorts.indexOf(query.sort),
                    onSelect = { vm.setSort(sorts[it]) },
                )
                SwitchRow(
                    title = stringResource(R.string.list_reverse),
                    checked = query.descending,
                    onChange = vm::setDescending,
                )
                val groupings = AppGrouping.entries
                ChoiceChips(
                    title = stringResource(R.string.group_title),
                    options = groupings.map { stringResource(groupingLabel(it)) },
                    selected = groupings.indexOf(query.grouping),
                    onSelect = { vm.setGrouping(groupings[it]) },
                    summary = if (query.grouping == AppGrouping.CATEGORY) stringResource(R.string.group_category_effect) else null,
                )
                SwitchRow(
                    title = stringResource(R.string.list_updates_first),
                    checked = query.updatesFirst,
                    onChange = vm::setUpdatesFirst,
                )
                SwitchRow(
                    title = stringResource(R.string.list_bury_not_installed),
                    checked = query.buryNotInstalled,
                    onChange = vm::setBuryNotInstalled,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
