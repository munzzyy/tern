package io.github.munzzyy.tern.ui.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalOnline
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.StatusLine
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.confirmInstall
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.rememberHaptics
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.icons.AppIcon
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.StarFilled
import io.github.munzzyy.tern.ui.theme.categoryColor
import io.github.munzzyy.tern.ui.theme.status
import io.github.munzzyy.tern.ui.text.RowAction
import io.github.munzzyy.tern.ui.text.canSkip
import io.github.munzzyy.tern.ui.text.inlineAction
import io.github.munzzyy.tern.ui.text.isWaitingForUser
import io.github.munzzyy.tern.ui.text.statusLabel
import io.github.munzzyy.tern.ui.text.versionChange
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.heavier

private const val LARGE_FONT_SCALE = 1.5f

/**
 * One app: its icon, its name, one line about its status, and the one thing to do about it now.
 * Everything else is behind the row, on the detail screen, and in TalkBack's list of actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRowItem(
    row: AppRow,
    selected: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    checked: Boolean = false,
    onSelect: () -> Unit = {},
    onRemove: () -> Unit = {},
    actionPlace: ActionPlace = ActionPlace.BESIDE,
) {
    val engine = LocalEngine.current
    val actions = rememberActions()
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    var hasFocus by remember { mutableStateOf(false) }
    val action = inlineAction(row)
    val noLauncher = stringResource(R.string.open_no_launcher)

    val runAction: (RowAction) -> Unit = { a ->
        when (a) {
            RowAction.UPDATE, RowAction.INSTALL -> engine.install(row.id)
            RowAction.CONFIRM -> confirmInstall(engine, row.id, actions)
            else -> Unit
        }
    }
    val openApp: () -> Unit = {
        if (!engine.open(row.id)) actions.say(noLauncher)
    }
    val checkNow: () -> Unit = { actions.run { engine.check(row.id) } }
    val skip: () -> Unit = { actions.run { engine.dismissRelease(row.id) } }

    val labelDetails = stringResource(R.string.action_details)
    val labelOpen = stringResource(R.string.action_open)
    val labelCancel = stringResource(R.string.action_cancel)
    val labelCheck = stringResource(R.string.action_check_now)
    val labelSkip = stringResource(R.string.action_skip_version)
    val labelRemove = stringResource(R.string.action_remove)
    val labelSelect = stringResource(R.string.action_select)
    val labelToggle = stringResource(if (checked) R.string.action_deselect else R.string.action_select)
    val pickedState = stringResource(if (checked) R.string.state_selected else R.string.state_not_selected)
    val labelAction = action?.let { stringResource(it.text) }
    val description = rowDescription(row)

    val rowSemantics = if (selecting) {
        Modifier.clearAndSetSemantics {
            contentDescription = description
            focused = hasFocus
            this.selected = checked
            stateDescription = pickedState
            onClick(labelToggle) { onSelect(); true }
        }
    } else {
        Modifier.clearAndSetSemantics {
            contentDescription = description
            focused = hasFocus
            this.selected = selected
            onClick(labelDetails) { onOpen(); true }
            customActions = buildList {
                if (action != null && labelAction != null) add(CustomAccessibilityAction(labelAction) { runAction(action); true })
                if (isWaitingForUser(row)) add(CustomAccessibilityAction(labelCancel) { engine.cancel(row.id); true })
                if (row.installed != null) add(CustomAccessibilityAction(labelOpen) { openApp(); true })
                add(CustomAccessibilityAction(labelDetails) { onOpen(); true })
                add(CustomAccessibilityAction(labelCheck) { checkNow(); true })
                if (canSkip(row)) add(CustomAccessibilityAction(labelSkip) { skip(); true })
                add(CustomAccessibilityAction(labelSelect) { onSelect(); true })
                add(CustomAccessibilityAction(labelRemove) { onRemove(); true })
            }
        }
    }
    val highlighted = selected || (selecting && checked)
    val button: @Composable (Modifier) -> Unit = { place ->
        if (action != null && labelAction != null) {
            // On a row that is picked out the button would lie on its own colour and be lost.
            val colors = if (highlighted) scheme.copy(secondaryContainer = scheme.surface, onSecondaryContainer = scheme.onSurface) else scheme
            MaterialTheme(colorScheme = colors) { TonalButton(labelAction, onClick = { runAction(action) }, modifier = place) }
        }
    }
    val shape = MaterialTheme.shapes.large
    val inside = look.rowPaddingHorizontal - look.focusRoom

    val haptics = rememberHaptics()
    val categoryColors = LocalEngine.current.settings.collectAsStateWithLifecycle().value.categoryColors
    val stripe = remember(row.config.categories, categoryColors) { row.config.categories.map { categoryColor(it, categoryColors) } }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = look.focusRoom, vertical = look.focusRoom / 2)
            .background(if (highlighted) scheme.secondaryContainer else Color.Transparent, shape)
            .categoryStripe(stripe)
            .onFocusChanged { hasFocus = it.hasFocus }
            .then(rowSemantics),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(look.gap),
                modifier = Modifier
                    .weight(1f)
                    .focusLook(shape)
                    .clip(shape)
                    .combinedClickable(
                        onClick = if (selecting) onSelect else onOpen,
                        onLongClick = {
                            haptics.longPress()
                            onSelect()
                        },
                        onLongClickLabel = labelToggle,
                    )
                    .heightIn(min = look.rowHeight - look.focusRoom)
                    .padding(horizontal = inside, vertical = look.rowPaddingVertical),
            ) {
                if (selecting) Checkbox(checked = checked, onCheckedChange = null)
                if (!look.minimal) {
                    // A double tap on the icon of an installed app opens it, as in Obtainium; one tap still opens the page.
                    val opensApp = row.installed != null && !selecting && !LocalNoTouch.current
                    Box(if (opensApp) Modifier.combinedClickable(onClick = onOpen, onDoubleClick = openApp, onLongClick = onSelect) else Modifier) {
                        AppIcon(row)
                    }
                }
                RowText(row, highlighted, LocalDensity.current.fontScale >= LARGE_FONT_SCALE, Modifier.weight(1f))
            }
            if (!selecting && actionPlace == ActionPlace.BESIDE) button(Modifier.padding(start = look.focusRoom * 2, end = inside))
        }
        if (!selecting && actionPlace == ActionPlace.UNDER && action != null) {
            button(Modifier.padding(start = inside + look.iconList + look.gap, bottom = look.gapSmall))
        }
    }
}

@Composable
private fun RowText(row: AppRow, highlighted: Boolean, large: Boolean, modifier: Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
            Text(
                row.config.shownName,
                style = MaterialTheme.typography.titleMedium.heavier(),
                color = if (highlighted) scheme.onSecondaryContainer else scheme.onSurface,
                maxLines = if (large) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.config.favorite) {
                Icon(Glyphs.StarFilled, contentDescription = null, tint = MaterialTheme.status.caution.color, modifier = Modifier.size(look.glyphSmall))
            }
        }
        StatusLine(
            statusLabel(row, LocalOnline.current),
            detail = row.progress?.let { progressText(it) } ?: versionText(versionChange(row)),
            ink = if (highlighted) scheme.onSecondaryContainer else Color.Unspecified,
        )
        row.progress?.let { p ->
            val fraction = p.fraction
            val bar = Modifier.fillMaxWidth().padding(top = look.gapSmall / 4)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = bar)
            } else if (p.phase != Phase.WAITING_FOR_USER) {
                LinearProgressIndicator(modifier = bar)
            }
        }
    }
}

/** Thin bands at the start of a row, one in the colour of each of the app's categories, top to bottom. */
private fun Modifier.categoryStripe(colors: List<Color>): Modifier = if (colors.isEmpty()) this else drawBehind {
    val width = 4.dp.toPx()
    val inset = 12.dp.toPx()
    val height = size.height - inset * 2
    if (height <= 0f) return@drawBehind
    val band = height / colors.size
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    colors.forEachIndexed { i, color ->
        drawRoundRect(
            color = color,
            topLeft = Offset(x, inset + band * i),
            size = Size(width, band),
            cornerRadius = CornerRadius(width / 2, width / 2),
        )
    }
}
