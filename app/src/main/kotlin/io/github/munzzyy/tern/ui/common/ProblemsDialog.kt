package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Engine
import io.github.munzzyy.tern.ui.theme.LocalLook

/**
 * What went wrong for [appIds], as Tern holds it now. The notification of problems opens Tern on
 * this, and only the ids come with it, so nothing but Tern's own words is ever shown here. A press
 * on an app opens its page.
 */
@Composable
fun ProblemsDialog(engine: Engine, appIds: List<String>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val look = LocalLook.current
    val rows by engine.apps.collectAsStateWithLifecycle()
    val shown = problemsOf(rows, appIds)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_problems_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                if (shown.isEmpty()) Text(stringResource(R.string.files_problems_none), style = MaterialTheme.typography.bodyMedium)
                for ((row, reason) in shown) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusLook()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) {
                                onOpen(row.id)
                                onDismiss()
                            }
                            .heightIn(min = look.touchTarget)
                            .padding(look.gapSmall),
                    ) {
                        Text(row.config.shownName, style = MaterialTheme.typography.titleSmall)
                        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_close)) } },
    )
}

/** The apps of [appIds] that have a problem now, in the order given, each with the words its row shows. */
fun problemsOf(rows: List<AppRow>, appIds: List<String>): List<Pair<AppRow, String>> =
    appIds.distinct().mapNotNull { id ->
        val row = rows.firstOrNull { it.id == id } ?: return@mapNotNull null
        row.problem?.message?.takeIf { it.isNotBlank() }?.let { row to it }
    }
