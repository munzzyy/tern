package io.github.munzzyy.jackdaw.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.engine.AppStatus
import io.github.munzzyy.jackdaw.engine.Phase
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalOnline
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import io.github.munzzyy.jackdaw.ui.apps.progressText
import io.github.munzzyy.jackdaw.ui.apps.versionText
import io.github.munzzyy.jackdaw.ui.common.LinkDialog
import io.github.munzzyy.jackdaw.ui.common.LinkText
import io.github.munzzyy.jackdaw.ui.common.ProblemBox
import io.github.munzzyy.jackdaw.ui.common.StatusPill
import io.github.munzzyy.jackdaw.ui.common.sourceText
import io.github.munzzyy.jackdaw.ui.common.VerificationPanel
import io.github.munzzyy.jackdaw.ui.common.confirmInstall
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.icons.AppIcon
import io.github.munzzyy.jackdaw.ui.text.RowAction
import io.github.munzzyy.jackdaw.ui.text.canSkip
import io.github.munzzyy.jackdaw.ui.text.formatTime
import io.github.munzzyy.jackdaw.ui.text.isWaitingForUser
import io.github.munzzyy.jackdaw.ui.text.minutesUntil
import io.github.munzzyy.jackdaw.ui.text.primaryAction
import io.github.munzzyy.jackdaw.ui.text.problemAdvice
import io.github.munzzyy.jackdaw.ui.text.statusLabel
import io.github.munzzyy.jackdaw.ui.text.versionChange
import kotlinx.coroutines.delay

const val DETAIL_PRIMARY_TAG = "detail_primary"
const val DETAIL_LIST_TAG = "detail_list"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(appId: String, onBack: (() -> Unit)?, onRemoved: () -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "detail:$appId") { DetailViewModel(engine, appId) }
    val row by vm.row.collectAsStateWithLifecycle()
    val move by vm.move.collectAsStateWithLifecycle()
    val current = row
    val listState = rememberLazyListState()
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (headerGone) Text(current?.config?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        if (current == null) {
            Text(
                stringResource(R.string.detail_gone),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(DETAIL_LIST_TAG),
        ) {
            item(key = "header") { Header(current) }
            item(key = "action") { ActionArea(current) }
            val moveState = move
            if (current.movedTo != null || moveState is MoveState.Refused || moveState == MoveState.Followed) {
                item(key = "moved") { MovedBox(current.movedTo, moveState, vm) }
            }
            current.verification?.let { v ->
                item(key = "checks") {
                    Section { VerificationPanel(v) }
                }
            }
            history(vm, current)
            settings(vm, current, onRemoved)
        }
    }
}

@Composable
fun Section(content: @Composable () -> Unit) {
    Column {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 840.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Header(row: AppRow) {
    var link by remember { mutableStateOf<String?>(null) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AppIcon(row, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(row.config.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            row.config.author?.let {
                Text(stringResource(R.string.by_author, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinkText(
                sourceText(row.config.source),
                onClick = { link = row.config.source.url },
            )
        }
    }
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionArea(row: AppRow) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val noLauncher = stringResource(R.string.open_no_launcher)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .widthIn(max = 840.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            StatusPill(statusLabel(row, LocalOnline.current))
            versionText(versionChange(row))?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        row.problem?.let { p ->
            val body = buildString {
                append(stringResource(problemAdvice(p.kind, installed = row.installed != null)))
                if (p.kind == ProblemKind.RATE_LIMITED) p.retryAtMs?.let { append(" ").append(retryText(it)) }
            }
            ProblemBox(title = p.message, body = body)
        }
        row.progress?.let { progress ->
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                val fraction = progress.fraction
                when {
                    progress.phase == Phase.WAITING_FOR_USER -> Unit
                    progress.phase == Phase.DOWNLOADING && fraction != null ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    progressText(progress) ?: stringResource(phaseExplanation(progress.phase)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        row.silentUpdate?.takeIf { row.progress == null && row.status == AppStatus.UPDATE_AVAILABLE && !row.config.trackOnly }?.let { silent ->
            Text(
                stringResource(if (silent) R.string.silent_yes else R.string.silent_no),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            primaryAction(row)?.let { action ->
                Button(
                    onClick = {
                        when (action) {
                            RowAction.UPDATE, RowAction.INSTALL -> engine.install(row.id)
                            RowAction.CANCEL -> engine.cancel(row.id)
                            RowAction.CONFIRM -> confirmInstall(engine, row.id, actions)
                            RowAction.OPEN -> if (!engine.open(row.id)) actions.say(noLauncher)
                            RowAction.MARK_SEEN -> actions.run { engine.dismissRelease(row.id) }
                            RowAction.CHECK -> actions.run { engine.check(row.id) }
                        }
                    },
                    modifier = Modifier.testTag(DETAIL_PRIMARY_TAG),
                ) { Text(stringResource(action.text)) }
            }
            if (isWaitingForUser(row)) {
                OutlinedButton(onClick = { engine.cancel(row.id) }) { Text(stringResource(R.string.action_cancel)) }
            }
            if (row.installed != null && primaryAction(row) != RowAction.OPEN && row.progress == null) {
                OutlinedButton(onClick = { if (!engine.open(row.id)) actions.say(noLauncher) }) {
                    Text(stringResource(R.string.action_open))
                }
            }
            if (canSkip(row) && row.progress == null && !row.config.trackOnly) {
                TextButton(onClick = { actions.run { engine.dismissRelease(row.id) } }) {
                    Text(stringResource(R.string.action_skip_version))
                }
            }
        }
    }
}

@Composable
private fun retryText(atMs: Long): String {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(atMs) {
        while (now < atMs) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val minutes = minutesUntil(atMs, now)
    return if (minutes <= 0) {
        stringResource(R.string.retry_now)
    } else {
        pluralStringResource(R.plurals.retry_at, minutes.toInt().coerceAtMost(9999), formatTime(atMs), minutes)
    }
}

private fun phaseExplanation(phase: Phase): Int = when (phase) {
    Phase.QUEUED -> R.string.phase_queued
    Phase.DOWNLOADING -> R.string.phase_downloading
    Phase.VERIFYING -> R.string.phase_verifying
    Phase.INSTALLING -> R.string.phase_installing
    Phase.WAITING_FOR_USER -> R.string.phase_waiting
}
