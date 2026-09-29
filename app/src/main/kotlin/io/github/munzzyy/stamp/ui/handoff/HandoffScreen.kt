package io.github.munzzyy.stamp.ui.handoff

import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Handoff
import io.github.munzzyy.stamp.engine.QrCode
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.common.ChipTone
import io.github.munzzyy.stamp.ui.common.PressRow
import io.github.munzzyy.stamp.ui.common.PrimaryButton
import io.github.munzzyy.stamp.ui.common.ProblemBox
import io.github.munzzyy.stamp.ui.common.ReadBlock
import io.github.munzzyy.stamp.ui.common.ScreenTop
import io.github.munzzyy.stamp.ui.common.SectionCard
import io.github.munzzyy.stamp.ui.common.StatusChip
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.focusWhenShown
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import io.github.munzzyy.stamp.ui.icons.Glyphs
import io.github.munzzyy.stamp.ui.importing.CarriedNote
import io.github.munzzyy.stamp.ui.importing.ImportSummaryView
import io.github.munzzyy.stamp.ui.text.formatBytes
import io.github.munzzyy.stamp.ui.text.isolate
import io.github.munzzyy.stamp.ui.text.ltr
import io.github.munzzyy.stamp.ui.text.minutesUntil
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.figures
import io.github.munzzyy.stamp.ui.theme.fingerprint
import kotlinx.coroutines.delay

const val HANDOFF_QR_TAG = "handoff_qr"
const val HANDOFF_ADDRESS_TAG = "handoff_address"
const val HANDOFF_CODE_TAG = "handoff_code"
const val HANDOFF_ARRIVALS_TAG = "handoff_arrivals"
const val HANDOFF_OPEN_TAG = "handoff_open"
const val HANDOFF_TIME_TAG = "handoff_time"

private val QR_SIDE_PHONE = 240.dp
private val QR_SIDE_TELEVISION = 320.dp
private const val SIDE_BY_SIDE = 0.8f
private const val WIDEST = 1.25f
private const val MINUTE_MS = 60_000L

/** From this text size on, the time left stands under the title and not beside the way back. */
private const val STACK_FONT_SCALE = 1.5f

/**
 * The open door for a phone. It opens when the screen is entered and closes when the screen is
 * left, and nothing that arrives is added to anything: a link is looked at on the Add screen, a
 * file is imported when the user says so.
 */
@Composable
fun HandoffScreen(onBack: () -> Unit, onLook: (String) -> Unit, onOpenApp: (String) -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "handoff") { HandoffViewModel(engine) }
    val handoff by vm.handoff.collectAsStateWithLifecycle()
    val arrivals by vm.arrivals.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val failure by vm.failure.collectAsStateWithLifecycle()
    val closed = closedFor(failure, end)
    val activity = LocalActivity.current
    val look = LocalLook.current
    val screen = rememberScreenFocus()
    val hadNone = remember { arrivals.isEmpty() }
    val largeText = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    var arrivalsHoldFocus by remember { mutableStateOf(false) }
    val next = arrivals.firstOrNull { !it.dealtWith() } ?: arrivals.firstOrNull()

    DisposableEffect(vm) {
        vm.enter()
        onDispose { if (activity?.isChangingConfigurations != true) vm.leave() }
    }
    KeepScreenOn(handoff != null)

    Scaffold(
        topBar = {
            ScreenTop(stringResource(R.string.handoff_title), onBack = onBack) {
                if (!largeText) handoff?.let { TimeLeft(it.closesAtMs, Modifier.padding(horizontal = look.rowPaddingHorizontal - look.focusRoom)) }
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val sideBySide = maxWidth >= look.contentMaxWidth * SIDE_BY_SIDE
            Column(
                verticalArrangement = Arrangement.spacedBy(look.gap),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = look.screenPadding)
                    .padding(bottom = look.gapSection)
                    .widthIn(max = look.contentMaxWidth * WIDEST),
            ) {
                val open = handoff
                val lead = if (next == null) Modifier.firstFocus(screen) else Modifier
                if (largeText && open != null) TimeLeft(open.closesAtMs, Modifier.padding(horizontal = look.cardPadding))
                when {
                    open != null -> Unit
                    opening -> Opening()
                    else -> ClosedDoor(closed, vm::open, lead, takesFocus = !arrivalsHoldFocus)
                }
                if (arrivals.isNotEmpty()) {
                    SectionCard(
                        title = stringResource(R.string.handoff_arrived),
                        modifier = Modifier
                            .testTag(HANDOFF_ARRIVALS_TAG)
                            .onFocusChanged { arrivalsHoldFocus = it.hasFocus },
                    ) {
                        for (arrival in arrivals) {
                            key(arrival.id) {
                                val focus = when {
                                    arrival.id != next?.id -> Modifier
                                    hadNone && arrival.id == arrivals.first().id -> Modifier.firstFocus(screen).focusWhenShown()
                                    else -> Modifier.firstFocus(screen)
                                }
                                when (arrival) {
                                    is Arrival.Link -> LinkRow(arrival, focus, below = !sideBySide) {
                                        vm.looked(arrival.id)
                                        onLook(arrival.text)
                                    }
                                    is Arrival.File -> FileRow(arrival, focus, below = !sideBySide, onImport = { vm.import(arrival.id) }, onOpenApp = onOpenApp)
                                }
                            }
                        }
                    }
                }
                if (open != null) OpenDoor(open, sideBySide, lead)
                ReadBlock {
                    Text(stringResource(R.string.handoff_footer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A television starts its screen saver after a few minutes without a key, and that would end the handoff. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun OpenDoor(handoff: Handoff, sideBySide: Boolean, lead: Modifier) {
    val look = LocalLook.current
    val side = if (look.television) QR_SIDE_TELEVISION else QR_SIDE_PHONE
    if (sideBySide) {
        Row(horizontalArrangement = Arrangement.spacedBy(look.gap), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            QrCard(handoff.qr, side)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) { Words(handoff, lead) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(look.gap), modifier = Modifier.fillMaxWidth()) {
            QrCard(handoff.qr, side, Modifier.align(Alignment.CenterHorizontally))
            Words(handoff, lead)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(handoff: Handoff, lead: Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    ReadBlock(lead) {
        for (step in listOf(R.string.handoff_step_1, R.string.handoff_step_2, R.string.handoff_step_3)) {
            Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
        }
    }
    ReadBlock {
        val address = ltr(handoff.address)
        val sentence = stringResource(R.string.handoff_by_hand, address)
        val at = sentence.indexOf(address)
        Text(
            buildAnnotatedString {
                if (at < 0) {
                    append(sentence)
                } else {
                    append(sentence.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = scheme.onSurface)) { append(address) }
                    append(sentence.substring(at + address.length))
                }
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(HANDOFF_ADDRESS_TAG),
        )
        val spoken = stringResource(R.string.handoff_code_spoken, spokenCode(handoff.code))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(look.gap),
                verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
                modifier = Modifier
                    .testTag(HANDOFF_CODE_TAG)
                    .semantics(mergeDescendants = true) { contentDescription = spoken },
            ) {
                for (group in codeGroups(handoff.code)) {
                    Text(group, style = MaterialTheme.typography.headlineMedium.fingerprint(), color = scheme.primary)
                }
            }
        }
        Text(stringResource(R.string.handoff_code_note), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeLeft(closesAtMs: Long, modifier: Modifier = Modifier) {
    val minutes = minutesLeft(closesAtMs)
    StatusChip(Glyphs.Waiting, pluralStringResource(R.plurals.handoff_time_left, minutes, minutes), modifier.testTag(HANDOFF_TIME_TAG))
}

@Composable
private fun minutesLeft(closesAtMs: Long): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(closesAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            val left = closesAtMs - now
            if (left <= 0) break
            delay(left % MINUTE_MS + TICK_SLACK_MS)
        }
    }
    return minutesUntil(closesAtMs, now).coerceIn(1, MAX_MINUTES).toInt()
}

private const val TICK_SLACK_MS = 50L
private const val MAX_MINUTES = 24 * 60L

/** Dark squares are pure black on white whatever the theme is, because that is what a camera reads best. */
@Composable
private fun QrCard(qr: QrCode, side: Dp, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    Surface(
        color = Color.White,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(look.focusOutline / 3, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Box(Modifier.padding(look.gapSmall / 2)) { QrView(qr, side) }
    }
}

@Composable
fun QrView(qr: QrCode, leastSide: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val squares = qrSquares(qr)
    val sidePx = squarePx(with(density) { leastSide.roundToPx() }, squares) * squares
    val image = remember(qr) { Bitmap.createBitmap(qrPixels(qr), squares, squares, Bitmap.Config.ARGB_8888).asImageBitmap() }
    val spoken = stringResource(R.string.handoff_qr_spoken)
    Canvas(
        modifier
            .size(with(density) { sidePx.toDp() })
            .testTag(HANDOFF_QR_TAG)
            .semantics {
                contentDescription = spoken
                role = Role.Image
            },
    ) {
        drawImage(image, dstSize = IntSize(sidePx, sidePx), filterQuality = FilterQuality.None)
    }
}

@Composable
private fun Opening() {
    val look = LocalLook.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(look.cardPadding)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(look.glyph + look.gapSmall))
        Text(stringResource(R.string.handoff_opening), style = MaterialTheme.typography.bodyLarge)
    }
}

/** What had focus while the handoff was open is gone when it closes. Unless focus is on something that arrived, the way to open it again takes it. */
@Composable
private fun ClosedDoor(closed: Closed, onOpen: () -> Unit, lead: Modifier, takesFocus: Boolean) {
    val look = LocalLook.current
    val follow = if (remember { takesFocus }) Modifier.focusWhenShown() else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(look.gap), modifier = Modifier.fillMaxWidth()) {
        when (closed) {
            is Closed.Failed -> ProblemBox(title = closed.problem?.message ?: stringResource(R.string.handoff_failed), body = null)
            is Closed.Ended -> endSentence(closed.why)?.let { sentence ->
                Text(
                    stringResource(sentence),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = look.cardPadding)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Closed.Quiet -> Text(
                stringResource(R.string.handoff_effect),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = look.cardPadding),
            )
        }
        PrimaryButton(
            stringResource(if (closed is Closed.Failed) R.string.action_try_again else R.string.handoff_open_again),
            onClick = onOpen,
            modifier = lead
                .then(follow)
                .padding(horizontal = look.cardPadding)
                .testTag(HANDOFF_OPEN_TAG),
            glyph = HandoffGlyphs.Phone,
        )
    }
}

@Composable
private fun LinkRow(link: Arrival.Link, focus: Modifier, below: Boolean, onLook: () -> Unit) {
    val look = LocalLook.current
    PressRow(
        action = stringResource(R.string.action_look_at),
        onClick = onLook,
        modifier = focus,
        below = below,
        leading = { Icon(HandoffGlyphs.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(look.glyph)) },
    ) {
        Text(ltr(shownLink(link.text)), style = MaterialTheme.typography.bodyLarge)
        if (link.looked) StatusChip(Glyphs.Check, stringResource(R.string.handoff_looked), tone = ChipTone.NEUTRAL)
    }
}

@Composable
private fun FileRow(file: Arrival.File, focus: Modifier, below: Boolean, onImport: () -> Unit, onOpenApp: (String) -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val name = ltr(file.name)
    val size = isolate(formatBytes(file.sizeBytes.toLong()))
    // The row that was pressed goes away when the import starts, and focus with it. What takes its place takes focus too.
    var started by remember { mutableStateOf(false) }
    val follow = if (started) Modifier.focusWhenShown() else Modifier
    val start = {
        started = true
        onImport()
    }
    when (val state = file.state) {
        FileState.Waiting -> PressRow(
            action = stringResource(R.string.action_import),
            onClick = start,
            modifier = focus,
            below = below,
            leading = { Icon(HandoffGlyphs.File, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(look.glyph)) },
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(size, style = MaterialTheme.typography.bodyMedium.figures(), color = scheme.onSurfaceVariant)
        }
        FileState.Working -> ReadBlock(follow.semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gap)) {
                CircularProgressIndicator(Modifier.size(look.glyph))
                Text(stringResource(R.string.import_working), style = MaterialTheme.typography.bodyLarge)
            }
        }
        is FileState.Done -> ReadBlock(focus.then(follow)) {
            Text(stringResource(R.string.handoff_file_line, name, size), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            ImportSummaryView(state.summary.added, state.summary.alreadyPresent, state.summary.skipped) { CarriedNote(state.summary, onOpenApp) }
        }
        is FileState.Failed -> Column(
            verticalArrangement = Arrangement.spacedBy(look.gapSmall),
            modifier = Modifier.padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
        ) {
            Text(stringResource(R.string.handoff_file_line, name, size), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            ProblemBox(
                title = state.message ?: stringResource(R.string.import_failed),
                body = if (state.message == null) stringResource(R.string.import_failed_help) else null,
                action = stringResource(R.string.action_try_again),
                onAction = start,
                modifier = focus.then(follow),
            )
        }
    }
}
