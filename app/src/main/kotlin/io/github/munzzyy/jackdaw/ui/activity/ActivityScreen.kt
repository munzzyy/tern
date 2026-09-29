package io.github.munzzyy.jackdaw.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.Event
import io.github.munzzyy.jackdaw.engine.EventKind
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import io.github.munzzyy.jackdaw.ui.common.ConfirmDialog
import io.github.munzzyy.jackdaw.ui.common.LocalNoTouch
import io.github.munzzyy.jackdaw.ui.common.firstFocus
import io.github.munzzyy.jackdaw.ui.common.rememberScreenFocus
import io.github.munzzyy.jackdaw.ui.common.returnFocus
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.text.formatDate
import io.github.munzzyy.jackdaw.ui.text.formatTime
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(onOpenApp: (String) -> Unit) {
    val engine = LocalEngine.current
    val events by engine.events.collectAsStateWithLifecycle()
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val actions = rememberActions()
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(events, problemsOnly) { groupByDay(events, zone, problemsOnly) }
    val today = LocalDate.now(zone)
    val screen = rememberScreenFocus()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_activity)) },
                actions = {
                    IconButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.activity_clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "filter") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).firstFocus(screen)) {
                    FilterChip(
                        selected = problemsOnly,
                        onClick = { problemsOnly = !problemsOnly },
                        label = { Text(stringResource(R.string.activity_problems_only)) },
                        leadingIcon = if (problemsOnly) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (problemsOnly) R.string.activity_no_problems else R.string.activity_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            for (day in days) {
                item(key = "d-${day.date}") {
                    Text(
                        dayTitle(dayName(day.date, today)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                items(day.events, key = { "e-${it.id}" }) { event -> EventRow(event, onOpenApp, Modifier.returnFocus(screen, "e-${event.id}")) }
            }
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.activity_clear_title),
            text = stringResource(R.string.activity_clear_text),
            confirm = stringResource(R.string.activity_clear),
            onConfirm = { actions.run { engine.clearEvents() } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun dayTitle(name: DayName): String = when (name) {
    DayName.Today -> stringResource(R.string.day_today)
    DayName.Yesterday -> stringResource(R.string.day_yesterday)
    is DayName.On -> formatDate(name.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}

@Composable
private fun EventRow(event: Event, onOpenApp: (String) -> Unit, focus: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val problem = isProblem(event)
    val (icon, tint) = when {
        problem -> Icons.Filled.Warning to scheme.error
        event.kind == EventKind.INSTALLED || event.kind == EventKind.VERIFIED -> Icons.Filled.CheckCircle to scheme.primary
        else -> Icons.Filled.Info to scheme.onSurfaceVariant
    }
    val time = formatTime(event.atMs)
    val sentence = listOfNotNull(time, event.appName, event.message).joinToString(". ") { it.trimEnd('.') } + "."
    val appId = event.appId
    val clickable = when {
        appId != null -> focus.selectable(selected = false, role = Role.Button, onClick = { onOpenApp(appId) })
        // A remote scrolls by moving focus, so an entry it cannot land on is one it cannot reach.
        LocalNoTouch.current -> focus.focusable()
        else -> Modifier
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(clickable)
            .clearAndSetSemantics { contentDescription = sentence }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            event.appName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(event.message, style = MaterialTheme.typography.bodyMedium, color = if (problem) scheme.error else scheme.onSurface)
            Text(time, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
