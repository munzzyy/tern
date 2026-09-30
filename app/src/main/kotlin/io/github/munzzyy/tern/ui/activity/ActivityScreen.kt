package io.github.munzzyy.tern.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.apps.TernSnackbarHost
import io.github.munzzyy.tern.ui.common.ChoiceChip
import io.github.munzzyy.tern.ui.common.ConfirmDialog
import io.github.munzzyy.tern.ui.common.GlyphButton
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.RevealWithRoom
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.common.returnFocus
import io.github.munzzyy.tern.ui.common.shareText
import io.github.munzzyy.tern.ui.icons.Bin
import io.github.munzzyy.tern.ui.icons.Close
import io.github.munzzyy.tern.ui.icons.Collapse
import io.github.munzzyy.tern.ui.icons.Expand
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.Info
import io.github.munzzyy.tern.ui.icons.Plus
import io.github.munzzyy.tern.ui.icons.Share
import io.github.munzzyy.tern.ui.text.formatDate
import io.github.munzzyy.tern.ui.text.formatTime
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.figures
import io.github.munzzyy.tern.ui.theme.heavier
import io.github.munzzyy.tern.ui.theme.status
import java.time.LocalDate
import java.time.ZoneId

const val ACTIVITY_LIST_TAG = "activity_list"
const val ACTIVITY_STEPS_TAG = "activity_steps"

/** The tag of the control that shows and hides the steps of one entry. */
fun stepsToggleTag(entryId: Long): String = "activity_toggle_$entryId"

private const val STACK_FONT_SCALE = 1.5f

@Composable
fun ActivityScreen(onOpenApp: (String) -> Unit) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val events by engine.events.collectAsStateWithLifecycle()
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val actions = rememberActions()
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(events, problemsOnly) { entriesByDay(events, zone, problemsOnly) }
    val today = LocalDate.now(zone)
    val screen = rememberScreenFocus()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.activity_share)
    val noApp = stringResource(R.string.action_failed)

    Scaffold(
        topBar = {
            ScreenTop(stringResource(R.string.tab_activity)) {
                val shared = remember(events, problemsOnly) { activityText(events, zone, problemsOnly) }
                GlyphButton(
                    Glyphs.Share,
                    stringResource(R.string.activity_share),
                    onClick = { if (!shareText(context, shareTitle, shared)) actions.say(noApp) },
                    enabled = events.isNotEmpty(),
                )
                GlyphButton(Glyphs.Bin, stringResource(R.string.activity_clear), onClick = { confirmClear = true }, enabled = events.isNotEmpty())
            }
        },
        snackbarHost = { TernSnackbarHost() },
    ) { padding ->
        RevealWithRoom {
        LazyColumn(
            contentPadding = PaddingValues(bottom = look.gapSection),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(ACTIVITY_LIST_TAG),
        ) {
            item(key = "filter") {
                Row(Modifier.padding(horizontal = look.screenPadding, vertical = look.gapSmall / 2 + look.focusRoom / 2)) {
                    ChoiceChip(
                        stringResource(R.string.activity_problems_only),
                        selected = problemsOnly,
                        onClick = { problemsOnly = !problemsOnly },
                        modifier = Modifier.firstFocus(screen),
                        role = Role.Checkbox,
                    )
                }
            }
            if (days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (problemsOnly) R.string.activity_no_problems else R.string.activity_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = look.screenPadding, vertical = look.gapSection),
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
                            .padding(start = look.rowPaddingHorizontal, end = look.rowPaddingHorizontal, top = look.gap, bottom = look.gapSmall / 2)
                            .semantics { heading() },
                    )
                }
                items(day.entries, key = { "e-${it.id}" }) { entry -> EntryRow(entry, onOpenApp, Modifier.returnFocus(screen, "e-${entry.id}")) }
            }
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

/** [spoken] leaves out the marks that keep a version in its own order on the screen: TalkBack has no use for them. */
@Composable
private fun headlineText(entry: Entry, spoken: Boolean = false): String {
    val version = entry.version?.let { if (spoken) it else isolate(it) }
    fun words(named: Int, plain: Int): Int = if (version == null) plain else named
    val id = when (entry.headline) {
        Headline.INSTALLED -> words(R.string.activity_installed, R.string.activity_installed_plain)
        Headline.UPDATED -> words(R.string.activity_updated, R.string.activity_updated_plain)
        Headline.UPDATE_WAITS -> words(R.string.activity_update_waits, R.string.activity_update_waits_plain)
        Headline.INSTALL_WAITS -> words(R.string.activity_install_waits, R.string.activity_install_waits_plain)
        Headline.NOT_INSTALLED -> words(R.string.activity_not_installed, R.string.activity_not_installed_plain)
        Headline.CANCELLED -> words(R.string.activity_cancelled, R.string.activity_cancelled_plain)
        Headline.BLOCKED -> words(R.string.activity_blocked, R.string.activity_blocked_plain)
        Headline.ADDED -> R.string.activity_added
        Headline.REMOVED -> R.string.activity_removed
        Headline.CHECK_FAILED -> R.string.activity_check_failed
        Headline.PLAIN -> return entry.outcome.message
    }
    return if (version == null) stringResource(id) else stringResource(id, version)
}

/** What the engine wrote, where it says more than the headline does: why something went wrong, or where an app came from. */
private fun detailOf(entry: Entry): String? = when (entry.headline) {
    Headline.NOT_INSTALLED, Headline.BLOCKED, Headline.CHECK_FAILED, Headline.ADDED -> entry.outcome.message.takeIf { it.isNotBlank() }
    else -> null
}

@Composable
private fun mark(entry: Entry): Pair<ImageVector, Color> {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.status
    return when (entry.headline) {
        Headline.INSTALLED, Headline.UPDATED -> Glyphs.Seal to status.verified.color
        Headline.UPDATE_WAITS, Headline.INSTALL_WAITS -> Glyphs.Update to scheme.tertiary
        Headline.NOT_INSTALLED -> Glyphs.Failed to status.refused.color
        Headline.CANCELLED -> Glyphs.Close to scheme.onSurfaceVariant
        Headline.BLOCKED -> Glyphs.Refused to status.refused.color
        Headline.CHECK_FAILED -> Glyphs.Caution to status.refused.color
        Headline.ADDED -> Glyphs.Plus to scheme.onSurfaceVariant
        Headline.REMOVED -> Glyphs.Bin to scheme.onSurfaceVariant
        Headline.PLAIN -> Glyphs.Info to scheme.onSurfaceVariant
    }
}

@Composable
private fun EntryRow(entry: Entry, onOpenApp: (String) -> Unit, focus: Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(entry.id) { mutableStateOf(false) }
    val headline = headlineText(entry)
    val detail = detailOf(entry)?.takeUnless { it.trimEnd('.') == headline.trimEnd('.') }
    val time = formatTime(entry.atMs)
    val name = entry.outcome.appName
    val sentence = listOfNotNull(time, name, headlineText(entry, spoken = true), detail).joinToString(". ") { it.trimEnd('.') } + "."
    val appId = entry.outcome.appId
    val (glyph, tint) = mark(entry)
    val shape = MaterialTheme.shapes.large
    val inside = look.rowPaddingHorizontal - look.focusRoom
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val press = when {
        appId != null -> Modifier.selectable(selected = false, role = Role.Button, onClick = { onOpenApp(appId) })
        // A remote scrolls by moving focus, so an entry it cannot land on is one it cannot reach.
        LocalNoTouch.current -> Modifier.focusable()
        else -> Modifier
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(look.gap),
            verticalAlignment = Alignment.Top,
            modifier = focus
                .fillMaxWidth()
                .focusLook(shape)
                .clip(shape)
                .then(press)
                .clearAndSetSemantics { contentDescription = sentence }
                .heightIn(min = look.settingHeight - look.focusRoom)
                .padding(horizontal = inside, vertical = look.rowPaddingVertical),
        ) {
            Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(look.glyph))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4)) {
                val clock: @Composable () -> Unit = {
                    Text(time, style = MaterialTheme.typography.bodySmall.figures(), color = scheme.onSurfaceVariant)
                }
                if (name == null) {
                    Text(headline, style = MaterialTheme.typography.bodyLarge)
                } else if (stacked) {
                    Text(name, style = MaterialTheme.typography.titleSmall.heavier())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(look.gapSmall), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleSmall.heavier(), modifier = Modifier.weight(1f))
                        clock()
                    }
                }
                if (name != null) Text(headline, style = MaterialTheme.typography.bodyLarge, color = if (entry.isProblem) tint else scheme.onSurface)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
                if (name == null || stacked) clock()
            }
        }
        if (entry.steps.size > 1) {
            val under = inside + look.glyph + look.gap
            StepsToggle(open, under, Modifier.testTag(stepsToggleTag(entry.id))) { open = !open }
            if (open) Steps(entry.steps, Modifier.padding(start = under - look.gapSmall, end = inside))
        }
    }
}

/** As wide as the entry it belongs to: a remote finds what lies under the middle of what has focus, not what hangs at its side. */
@Composable
private fun StepsToggle(open: Boolean, indent: Dp, modifier: Modifier, onToggle: () -> Unit) {
    val look = LocalLook.current
    val shape = MaterialTheme.shapes.large
    val ink = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .focusLook(shape)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = look.touchTarget)
            .padding(start = indent, end = look.rowPaddingHorizontal - look.focusRoom),
    ) {
        Icon(if (open) Glyphs.Collapse else Glyphs.Expand, contentDescription = null, tint = ink, modifier = Modifier.size(look.glyphSmall))
        Text(stringResource(if (open) R.string.activity_steps_hide else R.string.activity_steps_show), style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** Every step of an operation in the engine's own words, oldest first. Without a touch screen it takes focus, so a remote can bring it into view. */
@Composable
private fun Steps(steps: List<Event>, modifier: Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val reach = if (LocalNoTouch.current) Modifier.focusLook().clip(MaterialTheme.shapes.medium).focusable() else Modifier
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .testTag(ACTIVITY_STEPS_TAG)
            .then(reach)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = look.gapSmall, vertical = look.gapSmall),
    ) {
        for (step in steps) {
            Column {
                Text(step.message, style = MaterialTheme.typography.bodyMedium)
                Text(formatTime(step.atMs), style = MaterialTheme.typography.bodySmall.figures(), color = scheme.onSurfaceVariant)
            }
        }
    }
}
