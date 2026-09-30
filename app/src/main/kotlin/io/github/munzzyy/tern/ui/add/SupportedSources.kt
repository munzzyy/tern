package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.theme.LocalLook

/** Where the app configurations that Obtainium's users share are listed. Their links open in Tern when it takes Obtainium's links. */
const val SHARED_CONFIGS_URL = "https://apps.obtainium.imranr.dev/"

/** The kinds of place Tern reads, in the order the list shows them. */
enum class SourceKind { FORGES, REPOSITORIES, STORES, MIRRORS, OTHER }

/** Every kind with the sources of it, each named as people know it. */
fun sourcesByKind(): List<Pair<SourceKind, List<String>>> {
    val kinds = linkedMapOf<SourceKind, MutableList<String>>()
    for (type in SourceTypes.ALL) {
        val kind = when (type) {
            SourceTypes.GITHUB, SourceTypes.GITHUB_ACTIONS, SourceTypes.GITLAB, SourceTypes.FORGEJO -> SourceKind.FORGES
            SourceTypes.FDROID, SourceTypes.FDROID_REPO -> SourceKind.REPOSITORIES
            in SourceTypes.REPUBLISHING -> SourceKind.MIRRORS
            in SourceTypes.THIRD_PARTY_STORES -> SourceKind.STORES
            else -> SourceKind.OTHER
        }
        kinds.getOrPut(kind) { mutableListOf() } += type
    }
    return SourceKind.entries.mapNotNull { kind -> kinds[kind]?.let { kind to it } }
}

/** A way to see every source Tern reads, and the configurations Obtainium's users share. */
@Composable
fun SourcesCard(modifier: Modifier = Modifier) {
    var listing by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf(false) }
    SectionCard(modifier = modifier) {
        ActionRow(
            title = stringResource(R.string.sources_title),
            summary = stringResource(R.string.sources_effect),
            onClick = { listing = true },
        )
        ActionRow(
            title = stringResource(R.string.shared_configs_title),
            summary = stringResource(R.string.shared_configs_effect),
            onClick = { link = true },
        )
    }
    if (listing) SourcesDialog(onDismiss = { listing = false })
    if (link) LinkDialog(SHARED_CONFIGS_URL, onDismiss = { link = false })
}

@Composable
private fun SourcesDialog(onDismiss: () -> Unit) {
    val look = LocalLook.current
    val engine = LocalEngine.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    val searchable = remember(engine, settings.thirdPartyStores) { engine.searchOrigins.toSet() }
    val offWord = stringResource(R.string.stores_off_tag)
    val searchWord = stringResource(R.string.sources_searchable)
    val trackWord = stringResource(R.string.sources_track_only)
    val general = mapOf(
        SourceTypes.HTML to stringResource(R.string.sources_web_page),
        SourceTypes.DIRECT to stringResource(R.string.sources_direct),
    )
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
                for ((kind, types) in sourcesByKind()) {
                    Text(kindName(kind), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                    Text(
                        types.joinToString("\n") { type ->
                            val name = SourceTypes.displayName(type) ?: general[type] ?: type
                            val tags = listOfNotNull(
                                searchWord.takeIf { name in searchable || (type == SourceTypes.FORGEJO && "Codeberg" in searchable) },
                                trackWord.takeIf { type in SourceTypes.TRACK_ONLY },
                                offWord.takeIf { type in SourceTypes.THIRD_PARTY_STORES && !settings.thirdPartyStores },
                            )
                            if (tags.isEmpty()) name else "$name (${tags.joinToString()})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.sources_self_hosted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun kindName(kind: SourceKind): String = stringResource(
    when (kind) {
        SourceKind.FORGES -> R.string.sources_forges
        SourceKind.REPOSITORIES -> R.string.sources_repositories
        SourceKind.STORES -> R.string.sources_stores
        SourceKind.MIRRORS -> R.string.sources_mirrors
        SourceKind.OTHER -> R.string.sources_other
    },
)
