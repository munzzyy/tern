package io.github.munzzyy.jackdaw.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.SearchHit
import io.github.munzzyy.jackdaw.ui.common.ProblemBox
import io.github.munzzyy.jackdaw.ui.common.SectionHeader
import io.github.munzzyy.jackdaw.ui.common.focusRing
import io.github.munzzyy.jackdaw.ui.common.verticalKeysLeave

const val STARS_USER_TAG = "stars_user"
const val STARS_SHOW_TAG = "stars_show"
const val STARS_ADD_TAG = "stars_add"

fun LazyListScope.starsSection(state: StarsState, vm: StarsViewModel) {
    item(key = "stars-heading") { SectionHeader(stringResource(R.string.import_stars_heading)) }
    item(key = "stars-explain") {
        Padded { Text(stringResource(R.string.import_stars_explain), style = MaterialTheme.typography.bodyLarge) }
    }
    when (state) {
        StarsState.Idle -> item(key = "stars-ask") { AskUser(vm::look, problem = null) }
        is StarsState.Failed -> item(key = "stars-ask") { AskUser(vm::look, problem = state) }
        StarsState.Loading -> item(key = "stars-loading") {
            Padded { Working(stringResource(R.string.import_stars_loading), stringResource(R.string.action_cancel), vm::reset) }
        }
        is StarsState.Listed -> {
            item(key = "stars-count") {
                Padded {
                    val n = state.hits.size
                    Text(
                        if (n == 0) stringResource(R.string.import_stars_none, state.user) else pluralStringResource(R.plurals.import_stars_count, n, n, state.user),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = vm::reset) { Text(stringResource(R.string.import_stars_other_user)) }
                }
            }
            items(state.hits, key = { "star:" + it.url }, contentType = { "star" }) { hit ->
                StarRow(hit, picked = hit.url in state.picked, onToggle = { vm.toggle(hit.url) })
            }
        }
        is StarsState.Adding -> item(key = "stars-adding") {
            Padded {
                Working(pluralStringResource(R.plurals.import_stars_adding, state.total, state.done, state.total), stringResource(R.string.import_stars_stop), vm::stop)
                LinearProgressIndicator(progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            }
        }
        is StarsState.Done -> item(key = "stars-done") {
            Padded {
                val o = state.outcome
                ImportSummaryView(o.added, o.present, o.skipped.map { (name, reason) -> name to skipText(reason) })
                TextButton(onClick = vm::reset) { Text(stringResource(R.string.import_stars_other_user)) }
            }
        }
    }
}

@Composable
private fun AskUser(onLook: (String) -> Unit, problem: StarsState.Failed?) {
    var user by rememberSaveable { mutableStateOf("") }
    val ready = user.isNotBlank()
    Padded {
        problem?.let {
            ProblemBox(
                title = it.problem?.message ?: stringResource(R.string.import_stars_failed),
                body = if (it.problem == null) stringResource(R.string.import_stars_failed_help) else null,
            )
        }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it.take(60) },
            singleLine = true,
            label = { Text(stringResource(R.string.import_stars_user)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { if (ready) onLook(user) }),
            modifier = Modifier
                .fillMaxWidth()
                .verticalKeysLeave()
                .testTag(STARS_USER_TAG),
        )
        Button(onClick = { onLook(user) }, enabled = ready, modifier = Modifier.testTag(STARS_SHOW_TAG)) {
            Text(stringResource(R.string.import_stars_show))
        }
    }
}

@Composable
private fun StarRow(hit: SearchHit, picked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape)
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 720.dp),
    ) {
        Checkbox(checked = picked, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.owner?.let { "$it/${hit.name}" } ?: hit.name, style = MaterialTheme.typography.titleSmall)
            hit.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            hit.stars?.let {
                Text(pluralStringResource(R.plurals.search_stars, it, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun skipText(reason: SkipReason): String = stringResource(
    when (reason) {
        SkipReason.NO_FILE -> R.string.import_skip_no_file
        SkipReason.NO_RELEASES -> R.string.import_skip_no_releases
        SkipReason.NOT_FOUND -> R.string.import_skip_not_found
        SkipReason.RATE_LIMITED -> R.string.import_skip_rate_limited
        SkipReason.NETWORK -> R.string.import_skip_network
        SkipReason.OTHER -> R.string.import_skip_other
    },
)
