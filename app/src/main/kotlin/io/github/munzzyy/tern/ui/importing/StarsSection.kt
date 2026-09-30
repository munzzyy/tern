package io.github.munzzyy.tern.ui.importing

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.ui.add.brief
import io.github.munzzyy.tern.ui.common.ProblemBox
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.theme.LocalLook

const val STARS_USER_TAG = "stars_user"
const val STARS_SHOW_TAG = "stars_show"
const val STARS_ADD_TAG = "stars_add"

private const val MAX_STAR_DESCRIPTION = 160

fun LazyListScope.starsSection(state: StarsState, vm: StarsViewModel) {
    item(key = "stars") {
        val look = LocalLook.current
        SectionCard(
            title = stringResource(R.string.import_stars_heading),
            modifier = Modifier
                .padding(top = look.gap)
                .widthIn(max = look.contentMaxWidth),
        ) {
            // What a list has not laid out yet gets focus only through something that holds its place, which the stop in front of a field does not.
            ReadBlock { Text(stringResource(R.string.import_stars_explain), style = MaterialTheme.typography.bodyLarge) }
            val inside = Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2)
            when (state) {
                StarsState.Idle -> AskUser(vm::look, problem = null)
                is StarsState.Failed -> AskUser(vm::look, problem = state)
                StarsState.Loading -> Working(stringResource(R.string.import_stars_loading), stringResource(R.string.action_cancel), vm::reset, inside)
                is StarsState.Listed -> {
                    val n = state.hits.size
                    Words(if (n == 0) stringResource(R.string.import_stars_none, state.user) else pluralStringResource(R.plurals.import_stars_count, n, n, state.user))
                    QuietButton(stringResource(R.string.import_stars_other_user), onClick = vm::reset, modifier = Modifier.padding(horizontal = look.focusRoom))
                }
                is StarsState.Adding -> {
                    Working(pluralStringResource(R.plurals.import_stars_adding, state.total, state.done, state.total), stringResource(R.string.import_stars_stop), vm::stop, inside)
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = inside.fillMaxWidth(),
                    )
                }
                is StarsState.Done -> {
                    val o = state.outcome
                    Column(inside) { ImportSummaryView(o.added, o.present, o.skipped.map { (name, reason) -> name to skipText(reason) }) }
                    QuietButton(stringResource(R.string.import_stars_other_user), onClick = vm::reset, modifier = Modifier.padding(horizontal = look.focusRoom))
                }
            }
        }
    }
    if (state is StarsState.Listed) {
        items(state.hits, key = { "star:" + it.url }, contentType = { "star" }) { hit ->
            StarRow(hit, picked = hit.url in state.picked, onToggle = { vm.toggle(hit.url) })
        }
    }
}

@Composable
private fun AskUser(onLook: (String) -> Unit, problem: StarsState.Failed?) {
    val look = LocalLook.current
    var user by rememberSaveable { mutableStateOf("") }
    val ready = user.isNotBlank()
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
    ) {
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
                .textFieldKeys()
                .testTag(STARS_USER_TAG),
        )
        TonalButton(stringResource(R.string.import_stars_show), onClick = { onLook(user) }, enabled = ready, modifier = Modifier.testTag(STARS_SHOW_TAG))
    }
}

@Composable
internal fun StarRow(hit: SearchHit, picked: Boolean, onToggle: () -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .widthIn(max = look.contentMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = look.settingHeight - look.focusRoom)
            .padding(horizontal = look.rowPaddingHorizontal - look.focusRoom, vertical = look.rowPaddingVertical),
    ) {
        Checkbox(checked = picked, onCheckedChange = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4)) {
            Text(hit.owner?.let { "$it/${hit.name}" } ?: hit.name, style = MaterialTheme.typography.titleMedium)
            hit.description?.takeIf { it.isNotBlank() }?.let {
                Text(brief(it, MAX_STAR_DESCRIPTION), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            hit.stars?.let {
                Text(pluralStringResource(R.plurals.search_stars, it, it), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
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
