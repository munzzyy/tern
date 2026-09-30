package io.github.munzzyy.tern.ui.detail

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.apps.TernSnackbarHost
import io.github.munzzyy.tern.ui.apps.progressText
import io.github.munzzyy.tern.ui.apps.rememberRemovals
import io.github.munzzyy.tern.ui.apps.versionText
import io.github.munzzyy.tern.ui.common.Explained
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.LinkText
import io.github.munzzyy.tern.ui.common.PrimaryButton
import io.github.munzzyy.tern.ui.common.ProblemBox
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.RevealWithRoom
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.StatusPill
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.VerificationPanel
import io.github.munzzyy.tern.ui.common.backupFocus
import io.github.munzzyy.tern.ui.common.confirmInstall
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.common.sourceText
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import io.github.munzzyy.tern.ui.icons.AppIcon
import io.github.munzzyy.tern.ui.icons.Seal
import io.github.munzzyy.tern.ui.text.PromptLine
import io.github.munzzyy.tern.ui.text.RowAction
import io.github.munzzyy.tern.ui.text.canSkip
import io.github.munzzyy.tern.ui.text.formatTime
import io.github.munzzyy.tern.ui.text.isWaitingForUser
import io.github.munzzyy.tern.ui.text.minutesUntil
import io.github.munzzyy.tern.ui.text.passedEveryCheck
import io.github.munzzyy.tern.ui.text.primaryAction
import io.github.munzzyy.tern.ui.text.problemAdvice
import io.github.munzzyy.tern.ui.text.promptLine
import io.github.munzzyy.tern.ui.text.statusLabel
import io.github.munzzyy.tern.ui.text.versionChange
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.figures
import io.github.munzzyy.tern.ui.theme.status
import kotlinx.coroutines.delay

const val DETAIL_PRIMARY_TAG = "detail_primary"
const val DETAIL_LIST_TAG = "detail_list"
const val DETAIL_SEAL_TAG = "detail_seal"
const val DETAIL_REMOVE_TAG = "detail_remove"
const val EXPLAIN_PROMPT_TAG = "explain_prompt"

private const val STACK_FONT_SCALE = 1.5f

@Composable
fun DetailScreen(appId: String, onBack: (() -> Unit)?, onRemoved: () -> Unit, focusAgain: Int = 0) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "detail:$appId") { DetailViewModel(engine, appId) }
    val row by vm.row.collectAsStateWithLifecycle()
    val move by vm.move.collectAsStateWithLifecycle()
    val hidden by rememberRemovals().hidden.collectAsStateWithLifecycle()
    val current = row?.takeUnless { it.id in hidden }
    val listState = rememberLazyListState()
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val screen = rememberScreenFocus(again = focusAgain)
    val look = LocalLook.current

    Scaffold(
        topBar = {
            if (onBack != null) ScreenTop(if (headerGone) current?.config?.name.orEmpty() else "", onBack = onBack, oneLine = true)
        },
        snackbarHost = { if (onBack != null) TernSnackbarHost() },
    ) { padding ->
        if (current == null) {
            Text(
                stringResource(R.string.detail_gone),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(look.screenPadding + look.gapSmall),
            )
            return@Scaffold
        }
        RevealWithRoom {
        val verifier = rememberAppVerifier(current)
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(look.gap),
            contentPadding = PaddingValues(top = if (onBack == null) look.gap else look.gapSmall / 2, bottom = look.gapSection),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(DETAIL_LIST_TAG),
        ) {
            item(key = "header") { Header(current, Modifier.backupFocus(screen)) }
            item(key = "action") { ActionArea(current, Modifier.firstFocus(screen)) }
            val moveState = move
            if (current.movedTo != null || moveState is MoveState.Refused || moveState == MoveState.Followed) {
                item(key = "moved") { MovedBox(current.movedTo, moveState, vm) }
            }
            current.verification?.let { v ->
                item(key = "checks") {
                    DetailCard(stringResource(R.string.checks_title), padded = true) {
                        Passed(current)
                        VerificationPanel(v, installed = current.installed != null, title = false)
                    }
                }
            }
            verifier?.let { intent -> item(key = "appverifier") { AppVerifierCard(intent) } }
            history(vm, current)
            settings(vm, current, onRemoved)
        }
        }
    }
}

/** A card of the detail screen: as wide as text reads well, with the side of the screen kept free. */
@Composable
fun DetailCard(title: String?, modifier: Modifier = Modifier, padded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalLook.current
    SectionCard(
        modifier = modifier
            .padding(horizontal = look.screenPadding)
            .widthIn(max = look.contentMaxWidth),
        title = title,
        padded = padded,
        content = content,
    )
}

/** The seal and what it stands for. It is pressed on when the checks pass while the screen is open; a file that had passed before simply carries it. */
@Composable
private fun Passed(row: AppRow) {
    val look = LocalLook.current
    val passed = passedEveryCheck(row)
    val passedBefore = rememberSaveable(row.id) { passed }
    if (!passed) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = look.gapSmall)
            .testTag(DETAIL_SEAL_TAG),
    ) {
        Seal(size = look.iconHeader, press = !passedBefore)
        Text(
            stringResource(R.string.checks_passed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.status.verified.color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Header(row: AppRow, focus: Modifier) {
    val look = LocalLook.current
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    val words: @Composable (Modifier) -> Unit = { place ->
        Column(place) {
            Text(row.config.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            row.config.author?.let {
                Text(stringResource(R.string.by_author, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinkText(sourceText(row.config.source), onClick = { link = row.config.source.url }, modifier = Modifier.focusLook())
            row.lastCheckedMs?.let { checked ->
                Text(
                    stringResource(R.string.detail_last_checked, relativeTime(checked)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val tools: @Composable () -> Unit = {
        Row {
            FavoriteButton(row)
            DetailMenu(row)
        }
    }
    val frame = focus
        .padding(horizontal = look.screenPadding + look.focusRoom)
        .widthIn(max = look.contentMaxWidth)
        .fillMaxWidth()
    Column(frame, verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
        if (LocalDensity.current.fontScale >= STACK_FONT_SCALE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row, size = look.iconHeader)
                Box(Modifier.weight(1f))
                tools()
            }
            words(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(look.gap), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row, size = look.iconHeader)
                words(Modifier.weight(1f))
                tools()
            }
        }
        row.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
    }
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}

/** What the source says the app is. Long ones open on a press, so the actions stay near the top. */
@Composable
private fun Description(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val long = text.length > DESCRIPTION_FOLD
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (open || !long) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = if (long) Modifier.focusLook().clickable { open = !open } else Modifier,
    )
}

private const val DESCRIPTION_FOLD = 160

/** "5 minutes ago", as Android says it in the person's language. */
@Composable
private fun relativeTime(ms: Long): String =
    DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionArea(row: AppRow, focus: Modifier) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val look = LocalLook.current
    val noLauncher = stringResource(R.string.open_no_launcher)
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall + look.gapSmall / 2),
        modifier = Modifier
            .padding(horizontal = look.screenPadding + look.focusRoom)
            .widthIn(max = look.contentMaxWidth)
            .fillMaxWidth(),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.gapSmall + look.gapSmall / 2),
            verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(statusLabel(row, LocalOnline.current))
            versionText(versionChange(row))?.let { Text(it, style = MaterialTheme.typography.bodyLarge.figures()) }
        }
        promptLine(row, Build.VERSION.SDK_INT)?.let { Prompt(it) }
        row.problem?.let { p ->
            val body = buildString {
                append(stringResource(problemAdvice(p.kind, installed = row.installed != null)))
                if (p.kind == ProblemKind.RATE_LIMITED) p.retryAtMs?.let { append(" ").append(retryText(it)) }
            }
            ProblemBox(title = p.message, body = body)
        }
        row.progress?.let { progress ->
            Column(
                verticalArrangement = Arrangement.spacedBy(look.gapSmall),
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
                    style = MaterialTheme.typography.bodyMedium.figures(),
                )
                if (progress.phase == Phase.WAITING_FOR_USER) {
                    Text(
                        stringResource(R.string.waiting_play_protect),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2),
            verticalArrangement = Arrangement.spacedBy(look.focusRoom),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = focus,
        ) {
            primaryAction(row)?.let { action ->
                val press = {
                    when (action) {
                        RowAction.UPDATE, RowAction.INSTALL -> engine.install(row.id)
                        RowAction.CANCEL -> engine.cancel(row.id)
                        RowAction.CONFIRM -> confirmInstall(engine, row.id, actions)
                        RowAction.OPEN -> if (!engine.open(row.id)) actions.say(noLauncher)
                        RowAction.MARK_SEEN -> actions.run { engine.dismissRelease(row.id) }
                        RowAction.CHECK -> actions.run { engine.check(row.id) }
                    }
                }
                if (action == RowAction.CANCEL) {
                    TonalButton(stringResource(action.text), press, Modifier.testTag(DETAIL_PRIMARY_TAG))
                } else {
                    PrimaryButton(stringResource(action.text), press, Modifier.testTag(DETAIL_PRIMARY_TAG))
                }
            }
            if (isWaitingForUser(row)) {
                TonalButton(stringResource(R.string.action_cancel), onClick = { engine.cancel(row.id) })
            }
            if (row.installed != null && primaryAction(row) != RowAction.OPEN && row.progress == null) {
                TonalButton(stringResource(R.string.action_open), onClick = { if (!engine.open(row.id)) actions.say(noLauncher) })
            }
            if (canSkip(row) && row.progress == null && !row.config.trackOnly) {
                QuietButton(stringResource(R.string.action_skip_version), onClick = { actions.run { engine.dismissRelease(row.id) } })
            }
        }
    }
}

@Composable
private fun Prompt(line: PromptLine) {
    val explanation = if (line == PromptLine.ALWAYS_ASKS) stringResource(line.explanation, Build.VERSION.RELEASE) else stringResource(line.explanation)
    Explained(stringResource(line.title), explanation, EXPLAIN_PROMPT_TAG) {
        Text(stringResource(line.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
