package io.github.munzzyy.tern.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.verticalKeysLeave
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.Minus
import io.github.munzzyy.tern.ui.icons.Plus
import io.github.munzzyy.tern.ui.text.intervalStops
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlin.math.roundToInt

/**
 * How often the background check runs, on a slider from off through a quarter of an hour to a
 * month. A remote moves it one stop per press; without a touch screen there are buttons as well.
 * The setting changes when the slider is let go, not on every stop it passes.
 */
@Composable
fun IntervalRow(minutes: Int, onChange: (Int) -> Unit) {
    val look = LocalLook.current
    val stops = remember(minutes) { intervalStops(minutes) }
    var at by remember(minutes) { mutableIntStateOf(stops.indexOf(minutes).coerceAtLeast(0)) }
    val title = stringResource(R.string.settings_interval)
    val shown = intervalLabel(stops[at])
    fun settle(index: Int) {
        at = index.coerceIn(0, stops.lastIndex)
        if (stops[at] != minutes) onChange(stops[at])
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = look.rowPaddingHorizontal, vertical = look.rowPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(look.gapSmall / 4),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            val buttons = LocalNoTouch.current
            if (buttons) {
                IconButton(onClick = { settle(at - 1) }, enabled = at > 0, modifier = Modifier.focusLook(CircleShape)) {
                    Icon(Glyphs.Minus, contentDescription = stringResource(minusLabel(stops, at)), modifier = Modifier.size(look.glyph))
                }
            }
            Slider(
                value = at.toFloat(),
                onValueChange = { at = it.roundToInt().coerceIn(0, stops.lastIndex) },
                onValueChangeFinished = { settle(at) },
                valueRange = 0f..stops.lastIndex.toFloat(),
                steps = (stops.size - 2).coerceAtLeast(0),
                modifier = Modifier
                    .weight(1f)
                    // The slider takes all four arrows; without touch the buttons step it, and up and down have to leave.
                    .focusProperties { canFocus = !buttons }
                    .verticalKeysLeave()
                    .focusLook(CircleShape)
                    .semantics {
                        contentDescription = title
                        stateDescription = shown
                    },
            )
            if (buttons) {
                IconButton(onClick = { settle(at + 1) }, enabled = at < stops.lastIndex, modifier = Modifier.focusLook(CircleShape)) {
                    Icon(Glyphs.Plus, contentDescription = stringResource(plusLabel(stops, at)), modifier = Modifier.size(look.glyph))
                }
            }
        }
    }
}

/** What the minus button does from stop [at]: it checks more often, or turns the checks off where the stop below is off. */
@StringRes
fun minusLabel(stops: List<Int>, at: Int): Int = if (stops.getOrNull(at - 1) == 0) R.string.interval_turn_off else R.string.interval_more_often

/** What the plus button does from stop [at]: it checks less often, or turns the checks on where they are off. */
@StringRes
fun plusLabel(stops: List<Int>, at: Int): Int = if (stops.getOrNull(at) == 0) R.string.interval_turn_on else R.string.interval_less_often
