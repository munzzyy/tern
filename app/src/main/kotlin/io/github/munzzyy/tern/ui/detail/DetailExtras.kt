package io.github.munzzyy.tern.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.apps.MAX_CATEGORY
import io.github.munzzyy.tern.ui.apps.cleanCategory
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ChipLines
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.notes.NotesView
import io.github.munzzyy.tern.ui.settings.allCategories
import io.github.munzzyy.tern.ui.settings.withoutCategory
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.categoryColor

/** The most categories one app is filed under. */
const val MAX_APP_CATEGORIES = 20

/** The longest note an app may carry, as an Obtainium export may. */
const val MAX_NOTE = 4000

/** [categories] with [name] added when [on], taken out otherwise; never more than [MAX_APP_CATEGORIES]. */
fun toggledCategory(categories: List<String>, name: String, on: Boolean): List<String> = when {
    !on -> withoutCategory(categories, name)
    categories.any { it.trim().equals(name, ignoreCase = true) } -> categories
    categories.size >= MAX_APP_CATEGORIES -> categories
    else -> categories + name
}

/**
 * Every category there is, the app's own ones picked: a tap files the app under one or takes it
 * out, and a new one can be named here. Each carries its colour.
 */
@Composable
fun CategoriesCard(row: AppRow) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val actions = rememberActions()
    val rows by engine.apps.collectAsStateWithLifecycle()
    val settings by engine.settings.collectAsStateWithLifecycle()
    val names = remember(rows, settings.categoryColors) { allCategories(rows, settings.categoryColors) }
    var naming by rememberSaveable(row.id) { mutableStateOf(false) }
    val toggle: (String, Boolean) -> Unit = { name, on ->
        actions.run { engine.configure(row.id) { it.copy(categories = toggledCategory(it.categories, name, on)) } }
    }
    DetailCard(stringResource(R.string.setting_categories)) {
        // The last chip, null, names a new category.
        ChipLines(
            names + listOf<String?>(null),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = look.cardPadding - look.focusRoom, vertical = look.gapSmall / 2),
        ) { _, name, stop ->
            if (name == null) {
                ChoiceChip(stringResource(R.string.categories_new_short), selected = false, onClick = { naming = true }, modifier = stop, role = Role.Button)
            } else {
                val on = row.config.categories.any { it.trim().equals(name, ignoreCase = true) }
                ChoiceChip(name, selected = on, onClick = { toggle(name, !on) }, modifier = stop, role = Role.Checkbox, dot = categoryColor(name, settings.categoryColors))
            }
        }
    }
    if (naming) {
        NewCategoryDialog(onDismiss = { naming = false }, onName = { toggle(it, true) })
    }
}

@Composable
private fun NewCategoryDialog(onDismiss: () -> Unit, onName: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val clean = cleanCategory(text)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_new)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_CATEGORY) },
                singleLine = true,
                label = { Text(stringResource(R.string.category_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onName); onDismiss() }, enabled = clean != null) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** What the person wrote about the app, or what an Obtainium export called its "about", shown as formatted text and edited as plain. */
@Composable
fun NotesCard(vm: DetailViewModel, row: AppRow) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val notes = row.config.notes?.takeIf { it.isNotBlank() }
    var editing by rememberSaveable(row.id) { mutableStateOf(false) }
    var text by rememberSaveable(row.id, editing) { mutableStateOf(notes.orEmpty()) }
    DetailCard(stringResource(R.string.app_notes_title)) {
        when {
            editing -> Column(
                verticalArrangement = Arrangement.spacedBy(look.gapSmall),
                modifier = Modifier.fillMaxWidth().padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_NOTE) },
                    label = { Text(stringResource(R.string.app_notes_title)) },
                    supportingText = { Text(stringResource(R.string.app_notes_help)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().textFieldKeys(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    QuietButton(stringResource(R.string.action_cancel), onClick = { editing = false })
                    TonalButton(
                        stringResource(R.string.action_save),
                        onClick = {
                            val next = text.trim().takeIf { it.isNotEmpty() }
                            vm.save({ it.copy(notes = next) }) { actions.say(failed) }
                            editing = false
                        },
                    )
                }
            }
            notes == null -> ActionRow(title = stringResource(R.string.app_notes_add), summary = stringResource(R.string.app_notes_add_effect), onClick = { editing = true })
            else -> {
                val blocks = remember(notes) { engine.renderNotes(notes) }
                NotesView(blocks, Modifier.fillMaxWidth().heightIn(min = look.touchTarget).padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2))
                ActionRow(title = stringResource(R.string.app_notes_edit), onClick = { editing = true })
            }
        }
    }
}

/** Where the project page stands: not asked for yet, on its way, there, or not to be had now. */
sealed interface PageState {
    data object Closed : PageState

    data object Loading : PageState

    data class Shown(val blocks: List<io.github.munzzyy.tern.engine.NoteBlock>) : PageState

    data class Failed(val message: String?) : PageState
}

/**
 * The project's own page, its README, read from the forge only when asked for and shown as text.
 * It stands where Obtainium can show the source's web page, without a web view or its scripts.
 */
@Composable
fun ProjectPageCard(row: AppRow) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val online = io.github.munzzyy.tern.ui.LocalOnline.current
    var state by remember(row.id) { mutableStateOf<PageState>(PageState.Closed) }
    var asked by remember(row.id) { mutableStateOf(0) }
    LaunchedEffect(row.id, asked) {
        if (asked == 0) return@LaunchedEffect
        state = PageState.Loading
        state = try {
            PageState.Shown(engine.projectPage(row.id))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: io.github.munzzyy.tern.engine.ProblemException) {
            PageState.Failed(e.problem.message)
        } catch (e: Exception) {
            PageState.Failed(null)
        }
    }
    DetailCard(stringResource(R.string.project_page)) {
        when (val s = state) {
            PageState.Closed -> ActionRow(
                title = stringResource(R.string.project_page_read),
                summary = stringResource(R.string.project_page_effect),
                onClick = { asked++ },
            )
            PageState.Loading -> Text(
                stringResource(R.string.project_page_loading),
                modifier = Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall),
            )
            is PageState.Shown -> if (s.blocks.isEmpty()) {
                Text(stringResource(R.string.project_page_none), modifier = Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall))
            } else {
                NotesView(s.blocks, Modifier.fillMaxWidth().padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2))
            }
            is PageState.Failed -> ActionRow(
                title = stringResource(R.string.project_page_failed),
                summary = s.message,
                onClick = { if (online) asked++ },
            )
        }
    }
}
