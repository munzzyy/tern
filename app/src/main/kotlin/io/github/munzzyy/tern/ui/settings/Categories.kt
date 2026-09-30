package io.github.munzzyy.tern.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.apps.MAX_CATEGORY
import io.github.munzzyy.tern.ui.apps.cleanCategory
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ColorDot
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.CATEGORY_SWATCHES
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.categoryArgb
import io.github.munzzyy.tern.ui.theme.categoryColor
import java.text.Collator
import java.util.Locale

/** Every category named anywhere, on an app or only given a colour, once each and in reading order. */
fun allCategories(rows: List<AppRow>, colors: Map<String, Int>, locale: Locale = Locale.getDefault()): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (name in rows.flatMap { it.config.categories } + colors.keys) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) seen.putIfAbsent(trimmed.lowercase(locale), trimmed)
    }
    val collator = Collator.getInstance(locale)
    return seen.values.sortedWith { a, b -> collator.compare(a, b) }
}

/** How many apps are filed under [name], in any case. */
fun appsIn(rows: List<AppRow>, name: String): Int = rows.count { row -> row.config.categories.any { it.trim().equals(name, ignoreCase = true) } }

/** [categories] with [from] called [to]; an app already under [to] keeps it once. */
fun renamedCategories(categories: List<String>, from: String, to: String): List<String> =
    categories.map { if (it.trim().equals(from, ignoreCase = true)) to else it }.distinctBy { it.trim().lowercase() }

fun withoutCategory(categories: List<String>, name: String): List<String> = categories.filterNot { it.trim().equals(name, ignoreCase = true) }

/** The colours with the one of [from] moved to [to], set to [argb]. */
fun recolored(colors: Map<String, Int>, from: String?, to: String, argb: Int): Map<String, Int> {
    val kept = if (from == null) colors else colors.filterKeys { !it.equals(from, ignoreCase = true) }
    return kept.filterKeys { !it.equals(to, ignoreCase = true) } + (to to argb)
}

/** The first colour no category has yet, for a new one; the first of all when every one is taken. */
fun unusedSwatch(names: List<String>, colors: Map<String, Int>): Int {
    val used = names.mapTo(HashSet()) { categoryArgb(it, colors) }
    return CATEGORY_SWATCHES.firstOrNull { it !in used } ?: CATEGORY_SWATCHES.first()
}

/** Whether [name] is taken by a category other than the one being edited, [original]. */
fun categoryTaken(name: String, original: String?, existing: List<String>): Boolean =
    existing.any { it.equals(name, ignoreCase = true) && !it.equals(original, ignoreCase = true) }

@Composable
fun CategoriesRow(s: Settings, update: ((Settings) -> Settings) -> Unit) {
    val rows by LocalEngine.current.apps.collectAsStateWithLifecycle()
    val names = remember(rows, s.categoryColors) { allCategories(rows, s.categoryColors) }
    var open by rememberSaveable { mutableStateOf(false) }
    ActionRow(
        title = stringResource(R.string.settings_categories),
        summary = if (names.isEmpty()) stringResource(R.string.settings_categories_effect) else pluralStringResource(R.plurals.settings_categories_count, names.size, names.size),
        onClick = { open = true },
    )
    if (open) CategoriesDialog(s, rows, names, update, onDismiss = { open = false })
}

@Composable
private fun CategoriesDialog(s: Settings, rows: List<AppRow>, names: List<String>, update: ((Settings) -> Settings) -> Unit, onDismiss: () -> Unit) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val look = LocalLook.current
    // Null when nothing is edited, "" for a new category, else the name of the one edited.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_categories)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (names.isEmpty()) Text(stringResource(R.string.categories_none), style = MaterialTheme.typography.bodyMedium)
                for (name in names) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(look.gap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = look.focusRoom / 2)
                            .focusLook(MaterialTheme.shapes.medium)
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(selected = false, role = Role.Button, onClick = { editing = name })
                            .heightIn(min = look.touchTarget)
                            .padding(horizontal = look.gapSmall),
                    ) {
                        ColorDot(categoryColor(name, s.categoryColors))
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val count = appsIn(rows, name)
                        Text(pluralStringResource(R.plurals.category_apps, count, count), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_close)) } },
        dismissButton = { TextButton(onClick = { editing = "" }) { Text(stringResource(R.string.categories_new)) } },
    )
    editing?.let { edited ->
        val original = edited.takeIf { it.isNotEmpty() }
        CategoryEditor(
            original = original,
            existing = names,
            argb = original?.let { categoryArgb(it, s.categoryColors) } ?: unusedSwatch(names, s.categoryColors),
            onDismiss = { editing = null },
            onSave = { name, argb ->
                update { it.copy(categoryColors = recolored(it.categoryColors, original, name, argb)) }
                if (original != null && original != name) {
                    actions.run {
                        for (row in rows) {
                            if (row.config.categories.any { it.trim().equals(original, ignoreCase = true) }) {
                                engine.configure(row.id) { it.copy(categories = renamedCategories(it.categories, original, name)) }
                            }
                        }
                    }
                }
            },
            onDelete = original?.let { name ->
                {
                    update { it.copy(categoryColors = it.categoryColors.filterKeys { key -> !key.equals(name, ignoreCase = true) }) }
                    actions.run {
                        for (row in rows) {
                            if (row.config.categories.any { it.trim().equals(name, ignoreCase = true) }) {
                                engine.configure(row.id) { it.copy(categories = withoutCategory(it.categories, name)) }
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun CategoryEditor(
    original: String?,
    existing: List<String>,
    argb: Int,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val look = LocalLook.current
    var text by rememberSaveable { mutableStateOf(original.orEmpty()) }
    var picked by rememberSaveable { mutableIntStateOf(argb) }
    val clean = cleanCategory(text)
    val taken = clean != null && categoryTaken(clean, original, existing)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) R.string.categories_new else R.string.category_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gap)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_CATEGORY) },
                    singleLine = true,
                    isError = taken,
                    label = { Text(stringResource(R.string.category_name)) },
                    supportingText = if (taken) {
                        { Text(stringResource(R.string.category_exists)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().textFieldKeys(),
                )
                Text(stringResource(R.string.category_color), style = MaterialTheme.typography.labelLarge)
                Swatches(picked, onPick = { picked = it })
                if (onDelete != null) {
                    Text(stringResource(R.string.category_delete_effect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onDelete(); onDismiss() }) {
                        Text(stringResource(R.string.category_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clean?.let { onSave(it, picked) }; onDismiss() }, enabled = clean != null && !taken) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Swatches(picked: Int, onPick: (Int) -> Unit) {
    val look = LocalLook.current
    val ring = MaterialTheme.colorScheme.onSurface
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier.selectableGroup(),
    ) {
        CATEGORY_SWATCHES.forEachIndexed { index, argb ->
            val selected = argb == picked
            val spoken = stringResource(R.string.category_color_spoken, index + 1, CATEGORY_SWATCHES.size)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(look.touchTarget)
                    .focusLook(CircleShape)
                    .clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(argb) })
                    .semantics { contentDescription = spoken },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(look.touchTarget - 12.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .then(if (selected) Modifier.border(2.dp, ring, CircleShape) else Modifier),
                ) {
                    if (selected) Icon(Glyphs.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(look.glyphSmall))
                }
            }
        }
    }
}
