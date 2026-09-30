package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.data.SettingsStore
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.engine.real.Search
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    val places = remember(engine, settings.thirdPartyStores) { engine.searchOrigins }
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
                    label = { Text(if (place == Search.CODEBERG_PLACE) Search.forgejoOrigin(settings.searchForgejo) else place) },
                    leadingIcon = if (on) {
                        { Icon(Glyphs.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        val forgejo = Search.CODEBERG_PLACE in settings.searchIn
        if (forgejo || "GitHub" in settings.searchIn) SearchScope(forgejo)
    }
}

/**
 * The Forgejo or Gitea a search looks in and the fewest stars a project may have, as Obtainium
 * asks before each search. Kept a moment after typing stops.
 */
@Composable
private fun SearchScope(forgejo: Boolean) {
    val engine = LocalEngine.current
    val look = LocalLook.current
    var host by rememberSaveable { mutableStateOf(engine.settings.value.searchForgejo) }
    var stars by rememberSaveable { mutableStateOf(engine.settings.value.searchMinStars.takeIf { it > 0 }?.toString().orEmpty()) }
    val badHost = host.isNotBlank() && Urls.normalize("https://" + host.trim().substringAfter("://")) == null
    LaunchedEffect(host, stars) {
        delay(KEEP_AFTER_MS)
        val current = engine.settings.value
        val next = current.copy(
            searchForgejo = if (badHost) current.searchForgejo else SettingsStore.cleanHost(host),
            searchMinStars = stars.toIntOrNull()?.coerceIn(0, SettingsStore.MAX_STARS) ?: 0,
        )
        if (next != current) {
            try {
                engine.saveSettings(next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2), modifier = Modifier.padding(horizontal = look.focusRoom)) {
        if (forgejo) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.take(MAX_HOST).filterNot(Char::isWhitespace) },
                label = { Text(stringResource(R.string.search_forgejo_label)) },
                placeholder = { Text(Settings.DEFAULT_FORGEJO) },
                supportingText = { Text(stringResource(if (badHost) R.string.search_forgejo_invalid else R.string.search_forgejo_help)) },
                isError = badHost,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
        }
        OutlinedTextField(
            value = stars,
            onValueChange = { stars = it.filter(Char::isDigit).take(MAX_STAR_DIGITS) },
            label = { Text(stringResource(R.string.search_min_stars_label)) },
            placeholder = { Text("0") },
            supportingText = { Text(stringResource(R.string.search_min_stars_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().textFieldKeys(),
        )
    }
}

private const val KEEP_AFTER_MS = 600L
private const val MAX_HOST = 253
private const val MAX_STAR_DIGITS = 7
