package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.text.PatternException
import io.github.munzzyy.tern.core.text.SafePattern
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.real.RealEngine
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.PressRow
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.StatusChip
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.TrustLine
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.LetterAvatar
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.text.hostOf
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.heavier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val RESULTS_TAG = "add_results"

/** As many as the engine hands out for one repository. A longer list is cut here, and says so. */
const val MAX_RESULTS = 200

/** A list that came from an address is a repository's own list of apps. One that came from words is what a search found. */
fun isRepository(results: Detection.Results): Boolean = results.query.trim().startsWith("https://", ignoreCase = true)

/** A list of apps that came in one link, each with the settings the link gave it. */
fun isCarriedList(results: Detection.Results): Boolean = results.query == Detection.Results.CARRIED

/** The repository's own name, which the engine puts on every app it lists, or else its host. */
fun repositoryName(results: Detection.Results): String =
    results.hits.firstOrNull()?.origin?.trim()?.takeIf { it.isNotEmpty() } ?: hostOf(results.query)

private val reader by lazy { SourceRegistry.standard() }

/**
 * The source [hit] leads to, read from its address alone as the Add screen reads it: as the kind it
 * names, else as whichever source knows the address. Nothing is asked of the network.
 */
fun sourceOf(hit: SearchHit): SourceSpec? {
    val type = hit.type ?: return reader.match(hit.url)
    val address = Urls.normalize(hit.url) ?: return null
    return reader.get(type)?.match(address) ?: SourceSpec(type, address)
}

/** The addresses of the [hits] whose app is followed from one of [sources] already, matched as the engine matches a source to an app it has. */
fun followedHits(hits: List<SearchHit>, sources: List<SourceSpec>): Set<String> = hits.filter { hit ->
    val spec = sourceOf(hit) ?: return@filter false
    sources.any { RealEngine.sameSource(it, spec) }
}.mapTo(HashSet()) { it.url }

/** A description as long as a row can carry: cut between two words, with three dots where it was cut. */
fun brief(text: String, limit: Int = MAX_DESCRIPTION): String {
    val plain = text.trim().split(Regex("\\s+")).joinToString(" ")
    if (plain.length <= limit) return plain
    val cut = plain.take(limit)
    val end = cut.lastIndexOf(' ').takeIf { it > limit / 2 } ?: limit
    return cut.take(end).trimEnd() + "\u2026"
}

const val MAX_DESCRIPTION = 280

/** True when the list on screen is not all there is: the engine cut it, or the screen did. */
fun isCut(results: Detection.Results): Boolean = results.more || results.hits.size > MAX_RESULTS

/**
 * The hits that [filter] leaves: as a pattern, the way Obtainium's filter takes one, else as the
 * words themselves, in either case whatever the case, in the name, the owner, the description, the
 * place or the address. A pattern that takes too long to match leaves nothing.
 */
fun filterHits(hits: List<SearchHit>, filter: String): List<SearchHit> {
    val text = filter.trim()
    if (text.isEmpty()) return hits
    val fields = { hit: SearchHit -> listOfNotNull(hit.name, hit.owner, hit.description, hit.origin, hit.url) }
    val pattern = try {
        SafePattern.compile(text)
    } catch (_: PatternException) {
        return hits.filter { hit -> fields(hit).any { it.contains(text, ignoreCase = true) } }
    }
    return try {
        SafePattern.watched(text) { hits.filter { hit -> fields(hit).any(pattern::matches) } }
    } catch (_: PatternException) {
        emptyList()
    }
}

@Composable
fun ResultsList(results: Detection.Results, onPick: (SearchHit) -> Unit, modifier: Modifier = Modifier, onSearchRepository: (String) -> Unit = {}) {
    val look = LocalLook.current
    val repository = isRepository(results)
    var filter by rememberSaveable(results.query, results.within) { mutableStateOf("") }
    val kept by produceState(results.hits, results, filter) {
        value = withContext(Dispatchers.Default) { filterHits(results.hits, filter) }
    }
    val hits = kept.take(MAX_RESULTS)
    val rows by LocalEngine.current.apps.collectAsStateWithLifecycle()
    val followed = remember(rows) { rows.map { it.config.source } }
    val inList by produceState(emptySet<String>(), hits, followed) {
        value = withContext(Dispatchers.Default) { followedHits(hits, followed) }
    }
    Column(modifier.testTag(RESULTS_TAG), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
        Text(
            when {
                isCarriedList(results) -> pluralStringResource(R.plurals.link_apps_heading, hits.size, hits.size)
                results.hits.isEmpty() && results.within == null -> stringResource(R.string.search_none, results.query)
                repository && results.within != null -> stringResource(R.string.repo_heading_within, isolate(repositoryName(results)), isolate(results.within))
                repository -> stringResource(R.string.repo_heading, isolate(repositoryName(results)))
                else -> pluralStringResource(R.plurals.search_count, results.hits.size, results.hits.size, results.query)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = look.cardPadding)
                .semantics { heading() },
        )
        if (results.missed.isNotEmpty()) {
            Column(Modifier.padding(horizontal = look.cardPadding), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
                for (miss in results.missed) TrustLine(Trust.NOTE, miss.reason)
            }
        }
        if (results.hits.size > 1 || filter.isNotEmpty() || repository) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it.take(SafePattern.MAX_PATTERN) },
                label = { Text(stringResource(R.string.results_filter)) },
                supportingText = { Text(stringResource(R.string.results_filter_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { if (repository && filter.isNotBlank()) onSearchRepository(filter.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .textFieldKeys()
                    .testTag(RESULTS_FILTER_TAG),
            )
        }
        // The list of a large repository is only its first apps; the filter can only narrow those, a search reads them all.
        if (repository && filter.isNotBlank() && filter.trim() != results.within && (results.more || results.within != null)) {
            TonalButton(stringResource(R.string.repo_search_all, filter.trim()), onClick = { onSearchRepository(filter.trim()) })
        }
        if (repository && results.within != null) QuietButton(stringResource(R.string.repo_show_all), onClick = { onSearchRepository("") })
        if (hits.isNotEmpty()) {
            SectionCard {
                for (hit in hits) ResultRow(hit, repository, inList = hit.url in inList, onPick = { onPick(hit) })
            }
        } else if (filter.isNotBlank()) {
            ReadBlock { Text(stringResource(R.string.results_filter_none, filter.trim()), style = MaterialTheme.typography.bodyMedium) }
        }
        if (isCut(results) || kept.size > MAX_RESULTS) {
            ReadBlock {
                Text(
                    stringResource(if (repository) R.string.repo_more else R.string.search_more),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

const val RESULTS_FILTER_TAG = "add_results_filter"

@Composable
private fun ResultRow(hit: SearchHit, repository: Boolean, inList: Boolean, onPick: () -> Unit) {
    val look = LocalLook.current
    val stars = hit.stars?.let { pluralStringResource(R.plurals.search_stars, it, it) }
    val source = listOfNotNull(hit.owner, hit.origin.takeUnless { repository }, stars).joinToString(" \u00B7 ")
    PressRow(
        action = stringResource(R.string.action_look_at),
        onClick = onPick,
        leading = { LetterAvatar(hit.url, hit.name, look.iconList) },
    ) {
        Text(hit.name, style = MaterialTheme.typography.titleMedium.heavier())
        if (source.isNotEmpty()) {
            Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        hit.description?.takeIf { it.isNotBlank() }?.let {
            Text(brief(it), style = MaterialTheme.typography.bodyMedium)
        }
        if (inList) StatusChip(Glyphs.Check, stringResource(R.string.starter_in_list))
    }
}
