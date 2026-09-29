package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.ui.common.PressRow
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.icons.LetterAvatar
import io.github.munzzyy.tern.ui.text.hostOf
import io.github.munzzyy.tern.ui.text.isolate
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.heavier

const val RESULTS_TAG = "add_results"

/** As many as the engine hands out for one repository. A longer list is cut here, and says so. */
const val MAX_RESULTS = 200

/** A list that came from an address is a repository's own list of apps. One that came from words is what a search found. */
fun isRepository(results: Detection.Results): Boolean = results.query.trim().startsWith("https://", ignoreCase = true)

/** The repository's own name, which the engine puts on every app it lists, or else its host. */
fun repositoryName(results: Detection.Results): String =
    results.hits.firstOrNull()?.origin?.trim()?.takeIf { it.isNotEmpty() } ?: hostOf(results.query)

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

@Composable
fun ResultsList(results: Detection.Results, onPick: (SearchHit) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val repository = isRepository(results)
    val hits = results.hits.take(MAX_RESULTS)
    Column(modifier.testTag(RESULTS_TAG), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
        Text(
            when {
                hits.isEmpty() -> stringResource(R.string.search_none, results.query)
                repository -> stringResource(R.string.repo_heading, isolate(repositoryName(results)))
                else -> pluralStringResource(R.plurals.search_count, hits.size, hits.size, results.query)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = look.cardPadding)
                .semantics { heading() },
        )
        if (hits.isNotEmpty()) {
            SectionCard {
                for (hit in hits) ResultRow(hit, repository, onPick = { onPick(hit) })
            }
        }
        if (repository && isCut(results)) {
            ReadBlock {
                Text(stringResource(R.string.repo_more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultRow(hit: SearchHit, repository: Boolean, onPick: () -> Unit) {
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
    }
}
