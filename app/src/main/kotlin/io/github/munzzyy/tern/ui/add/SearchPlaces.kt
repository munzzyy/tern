package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook

/** The places a search looks in after [picked] was switched: at least one always stays. */
fun toggledPlaces(current: Set<String>, picked: String): Set<String> = when {
    picked !in current -> current + picked
    current.size > 1 -> current - picked
    else -> current
}

/**
 * Where a search by name looks: the forges, F-Droid and the stores that can be searched. What is
 * picked is kept for the next search, as Obtainium keeps it.
 */
@Composable
fun SearchPlaces(modifier: Modifier = Modifier) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    val actions = rememberActions()
    val settings by engine.settings.collectAsStateWithLifecycle()
    val places = remember(engine) { engine.searchOrigins }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
        Text(
            stringResource(R.string.search_in),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = look.focusRoom).semantics { heading() },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
            verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2),
            modifier = Modifier.padding(horizontal = look.focusRoom),
        ) {
            for (place in places) {
                val on = place in settings.searchIn
                FilterChip(
                    selected = on,
                    onClick = {
                        val next = settings.copy(searchIn = toggledPlaces(settings.searchIn, place))
                        actions.run { engine.saveSettings(next) }
                    },
                    label = { Text(place) },
                    leadingIcon = if (on) {
                        { Icon(Glyphs.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
