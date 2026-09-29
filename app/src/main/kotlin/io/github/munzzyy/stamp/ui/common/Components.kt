package io.github.munzzyy.stamp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.ui.icons.Glyphs
import io.github.munzzyy.stamp.ui.text.StatusLabel
import io.github.munzzyy.stamp.ui.text.Tone
import io.github.munzzyy.stamp.ui.text.sourceParts
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.LocalOutlines
import io.github.munzzyy.stamp.ui.theme.status

@Composable
fun sourceText(spec: SourceSpec): String {
    val (name, address) = sourceParts(spec)
    return if (name == null) address else stringResource(R.string.source_line, name, address)
}

/** What a chip means. The colour never says it alone: the glyph and the word do. */
enum class ChipTone { NEUTRAL, ACCENT, NOTICE, VERIFIED, CAUTION, REFUSED }

@Composable
private fun chipColors(tone: ChipTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.status
    return when (tone) {
        ChipTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ChipTone.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipTone.NOTICE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ChipTone.VERIFIED -> status.verified.container to status.verified.onContainer
        ChipTone.CAUTION -> status.caution.container to status.caution.onContainer
        ChipTone.REFUSED -> status.refused.container to status.refused.onContainer
    }
}

/** A status in a glyph and a word on a tinted ground. It is not pressed and takes no focus. */
@Composable
fun StatusChip(glyph: ImageVector, text: String, modifier: Modifier = Modifier, tone: ChipTone = ChipTone.NEUTRAL) {
    val look = LocalLook.current
    val (ground, ink) = chipColors(tone)
    Surface(color = ground, contentColor = ink, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
            modifier = Modifier
                .heightIn(min = look.chipHeight)
                .padding(start = look.gapSmall, end = look.gapSmall + look.gapSmall / 4),
        ) {
            Icon(glyph, contentDescription = null, modifier = Modifier.size(look.glyphSmall))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun StatusLabel.glyph(): ImageVector = when (this) {
    StatusLabel.UNKNOWN -> Glyphs.Unknown
    StatusLabel.UP_TO_DATE -> Glyphs.Check
    StatusLabel.UPDATE, StatusLabel.UPDATE_LIKELY, StatusLabel.NEW_RELEASE -> Glyphs.Update
    StatusLabel.NOT_INSTALLED -> Glyphs.Install
    StatusLabel.BLOCKED -> Glyphs.Refused
    StatusLabel.ERROR, StatusLabel.INSTALL_FAILED -> Glyphs.Failed
    StatusLabel.RATE_LIMITED, StatusLabel.WAITING -> Glyphs.Waiting
    StatusLabel.OFFLINE -> Glyphs.Offline
    StatusLabel.CHECKING -> Glyphs.Busy
    StatusLabel.QUEUED -> Glyphs.Queued
    StatusLabel.DOWNLOADING -> Glyphs.Download
    StatusLabel.VERIFYING -> Glyphs.Seal
    StatusLabel.INSTALLING -> Glyphs.Install
}

private fun StatusLabel.chipTone(): ChipTone = when {
    this == StatusLabel.WAITING -> ChipTone.CAUTION
    tone == Tone.ATTENTION -> ChipTone.NOTICE
    tone == Tone.PROBLEM -> ChipTone.REFUSED
    tone == Tone.BUSY -> ChipTone.ACCENT
    else -> ChipTone.NEUTRAL
}

@Composable
fun StatusPill(label: StatusLabel, modifier: Modifier = Modifier) {
    StatusChip(label.glyph(), stringResource(label.text), modifier, label.chipTone())
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = look.rowPaddingHorizontal, end = look.rowPaddingHorizontal, top = look.gapSection - look.gapSmall / 2, bottom = look.gapSmall / 2)
            .semantics { heading() },
    )
}

/** What belongs together, on one ground without an outline or a shadow. [padded] is for text and buttons; rows bring their own padding. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    padded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalLook.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = look.gapSmall)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2)
                        .semantics { heading() },
                )
            }
            Column(if (padded) Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2) else Modifier, content = content)
        }
    }
}

/** The frame of every row that is pressed: room for focus around it, the least height, the padding inside. */
@Composable
private fun PressedRow(modifier: Modifier, press: Modifier, content: @Composable RowScope.() -> Unit) {
    val look = LocalLook.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .then(press)
            .heightIn(min = look.settingHeight - look.focusRoom)
            .padding(horizontal = look.rowPaddingHorizontal - look.focusRoom, vertical = look.rowPaddingVertical),
        content = content,
    )
}

@Composable
private fun RowScope.RowWords(title: String, summary: String?, tint: Color = Color.Unspecified) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LocalLook.current.gapSmall / 4)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
        if (!summary.isNullOrEmpty()) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    PressedRow(modifier, Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        RowWords(title, summary, if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    PressedRow(modifier, Modifier.selectable(selected = false, role = Role.Button, onClick = onClick)) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint,
                modifier = Modifier.size(LocalLook.current.glyph),
            )
        }
        RowWords(title, summary, tint)
    }
}

/** A setting with a few fixed values: shows the current one and opens a dialog of radio buttons. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    summary: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    PressedRow(Modifier, Modifier.selectable(selected = false, role = Role.Button, onClick = { open = true })) {
        RowWords(title, listOfNotNull(label(selected), summary).joinToString("\n"))
    }
    if (open) {
        ChoiceDialog(title, options, selected, label, onDismiss = { open = false }) {
            onSelect(it)
            open = false
        }
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    val look = LocalLook.current
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (option in options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(look.gap),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
                            .then(if (option == selected) Modifier.focusWhenShown() else Modifier)
                            .focusLook()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .heightIn(min = look.touchTarget)
                            .padding(horizontal = look.gapSmall),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Only before something that cannot be undone. What can be undone gets a snackbar with Undo instead. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Something to read. Without a touch screen it takes focus, because a remote scrolls by moving focus. */
@Composable
fun InfoRow(title: String, value: String, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val reach = if (LocalNoTouch.current) Modifier.focusLook().clip(MaterialTheme.shapes.medium).focusable() else Modifier
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .then(reach)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = look.settingHeight - look.focusRoom)
            .padding(horizontal = look.rowPaddingHorizontal - look.focusRoom, vertical = look.rowPaddingVertical)
            .wrapContentSize(Alignment.CenterStart),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Something waits for the user: one sentence and the one thing to do about it. The whole row is pressed. */
@Composable
fun BannerRow(
    text: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: ImageVector = Glyphs.Waiting,
    tone: ChipTone = ChipTone.CAUTION,
) {
    val look = LocalLook.current
    val (ground, ink) = chipColors(tone)
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val frame = Modifier
        .fillMaxWidth()
        .focusLook(MaterialTheme.shapes.large)
        .clip(MaterialTheme.shapes.large)
        .background(ground)
        .clickable(onClickLabel = action, role = Role.Button, onClick = onAction)
        .heightIn(min = look.settingHeight)
        .padding(horizontal = look.cardPadding, vertical = look.rowPaddingVertical)
    val sentence: @Composable (Modifier) -> Unit = { place ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gap), modifier = place) {
            Icon(glyph, contentDescription = null, modifier = Modifier.size(look.glyph))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
    Box(modifier.fillMaxWidth().padding(horizontal = look.screenPadding, vertical = look.gapSmall / 2)) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            if (stacked) {
                Column(frame, verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                    sentence(Modifier.fillMaxWidth())
                    Text(action, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.End))
                }
            } else {
                Row(frame, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gap)) {
                    sentence(Modifier.weight(1f))
                    Text(action, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** From this text size on, what sits side by side is put one under the other. */
private const val STACK_FONT_SCALE = 1.5f

/** An empty screen says what to do and offers the quickest ways to do it, two at most. */
@Composable
fun EmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    secondAction: String? = null,
    onSecondAction: () -> Unit = {},
    actionModifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.screenPadding + look.gap, vertical = look.gapSection),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = look.contentMaxWidth / 2),
        )
        if (action != null) PrimaryButton(action, onAction, actionModifier.padding(top = look.gap))
        if (secondAction != null) QuietButton(secondAction, onSecondAction)
    }
}

enum class ButtonKind { PRIMARY, TONAL, QUIET }

/** The one filled button of a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: ImageVector? = null, enabled: Boolean = true) =
    StampButton(ButtonKind.PRIMARY, text, onClick, modifier, glyph, enabled)

/** A second action next to the primary one, or the action of a row. */
@Composable
fun TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: ImageVector? = null, enabled: Boolean = true) =
    StampButton(ButtonKind.TONAL, text, onClick, modifier, glyph, enabled)

/** An action that needs no weight: text in the accent colour. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: ImageVector? = null, enabled: Boolean = true) =
    StampButton(ButtonKind.QUIET, text, onClick, modifier, glyph, enabled)

@Composable
private fun StampButton(kind: ButtonKind, text: String, onClick: () -> Unit, modifier: Modifier, glyph: ImageVector?, enabled: Boolean) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val shape = LocalOutlines.current.button
    val (ground, ink) = when {
        !enabled && kind == ButtonKind.QUIET -> Color.Transparent to scheme.onSurface.copy(alpha = 0.38f)
        !enabled -> scheme.onSurface.copy(alpha = 0.12f) to scheme.onSurface.copy(alpha = 0.38f)
        kind == ButtonKind.PRIMARY -> scheme.primary to scheme.onPrimary
        kind == ButtonKind.TONAL -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> Color.Transparent to scheme.primary
    }
    val side = if (kind == ButtonKind.QUIET) look.gap * 3 / 4 else look.gap * 3 / 2
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall, Alignment.CenterHorizontally),
        modifier = modifier
            .defaultMinSize(minWidth = look.touchTarget, minHeight = look.touchTarget)
            .wrapContentSize(Alignment.Center)
            .focusLook(shape)
            .clip(shape)
            .background(ground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = look.buttonHeight)
            .padding(horizontal = side, vertical = look.gapSmall / 2),
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            if (glyph != null) Icon(glyph, contentDescription = null, modifier = Modifier.size(look.glyphSmall))
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The top of a screen: the way back, the title, the actions. Comfortable gives the title a line
 * of its own, Compact puts all of it on one. Nothing here has a fixed height, so large text
 * grows the bar instead of being cut.
 */
@Composable
fun ScreenTop(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val look = LocalLook.current
    val back: @Composable () -> Unit = {
        if (onBack != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(look.touchTarget)
                    .focusLook(MaterialTheme.shapes.extraLarge)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .clickable(role = Role.Button, onClick = onBack),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), modifier = Modifier.size(look.glyph))
            }
        }
    }
    val words: @Composable (Modifier) -> Unit = { place ->
        Text(
            title,
            style = if (look.topTakesTwoLines) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = place.semantics { heading() },
        )
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = look.focusRoom, vertical = look.gapSmall / 2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = look.touchTarget + look.focusRoom)) {
                back()
                if (look.topTakesTwoLines) Box(Modifier.weight(1f)) else words(Modifier.weight(1f).padding(horizontal = look.rowPaddingHorizontal - look.focusRoom))
                actions()
            }
            if (look.topTakesTwoLines) {
                words(Modifier.padding(start = look.rowPaddingHorizontal - look.focusRoom, end = look.rowPaddingHorizontal - look.focusRoom, bottom = look.gapSmall))
            }
        }
    }
}

/**
 * A few choices shown side by side, one of them taken. Left and right move between them in their
 * order, also from the end of one line to the start of the next when large text has wrapped
 * them. Up and down leave them, so a remote never has to walk through every line of one choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val stops = remember(options.size) { List(options.size) { FocusRequester() } }
    var inside by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2),
            verticalArrangement = Arrangement.spacedBy(look.focusRoom + (look.touchTarget - look.choiceHeight)),
            modifier = Modifier
                .selectableGroup()
                .onFocusChanged { inside = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    var lines = options.size
                    while (focus.moveFocus(direction) && inside && --lines > 0) Unit
                    true
                },
        ) {
            options.forEachIndexed { index, option ->
                val taken = index == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
                    modifier = Modifier
                        .focusRequester(stops[index])
                        .focusProperties {
                            if (index > 0) start = stops[index - 1]
                            if (index < stops.lastIndex) end = stops[index + 1]
                        }
                        .focusLook(LocalOutlines.current.button)
                        .clip(LocalOutlines.current.button)
                        .background(if (taken) scheme.secondaryContainer else scheme.surfaceContainerHighest)
                        .selectable(selected = taken, role = Role.RadioButton, onClick = { onSelect(index) })
                        .heightIn(min = look.choiceHeight)
                        .padding(horizontal = look.gap),
                ) {
                    val ink = if (taken) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                    if (taken) Icon(Glyphs.Check, contentDescription = null, tint = ink, modifier = Modifier.size(look.glyphSmall))
                    Text(option, style = MaterialTheme.typography.labelLarge, color = ink)
                }
            }
        }
        if (summary != null) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}
