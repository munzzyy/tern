package io.github.munzzyy.tern.ui.suggest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.StarterHere
import io.github.munzzyy.tern.engine.Suggestion
import io.github.munzzyy.tern.ui.common.ChipTone
import io.github.munzzyy.tern.ui.common.PressRow
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.StatusChip
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.LetterAvatar
import io.github.munzzyy.tern.ui.text.ltr
import io.github.munzzyy.tern.ui.text.shortUrl
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.heavier

const val STARTERS_TAG = "starters"
const val STARTER_ROW_TAG = "starter_row"

/** More than the built-in list holds, so that a longer one could never fill the screen without end. */
const val MAX_STARTERS = 60

enum class StarterHeading { TELEVISION, ANY_DEVICE }

data class StarterGroup(val heading: StarterHeading?, val apps: List<Suggestion>)

/** On a television the apps made for one come first, under a heading of their own. Anywhere else the list is one. */
fun starterGroups(all: List<Suggestion>, television: Boolean, limit: Int = MAX_STARTERS): List<StarterGroup> {
    val shown = all.take(limit)
    val groups = if (television) {
        val (made, rest) = shown.partition { it.forTelevision }
        listOf(StarterGroup(StarterHeading.TELEVISION, made), StarterGroup(StarterHeading.ANY_DEVICE, rest))
    } else {
        listOf(StarterGroup(null, shown))
    }
    return groups.filter { it.apps.isNotEmpty() }
}

/** Well known apps to start from. [rowFocus] gives each row what it needs for focus to come back to it. */
@Composable
fun Starters(
    suggestions: List<Suggestion>,
    onLook: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
    rowFocus: @Composable (Suggestion) -> Modifier = { Modifier },
) {
    val look = LocalLook.current
    val groups = remember(suggestions, look.television) { starterGroups(suggestions, look.television) }
    if (groups.isEmpty()) return
    Column(modifier.testTag(STARTERS_TAG), verticalArrangement = Arrangement.spacedBy(look.gap)) {
        Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2), modifier = Modifier.padding(horizontal = look.cardPadding)) {
            Text(stringResource(R.string.starter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.starter_explain_sources), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (group in groups) {
            val title = when (group.heading) {
                StarterHeading.TELEVISION -> stringResource(R.string.starter_for_television)
                StarterHeading.ANY_DEVICE -> stringResource(R.string.starter_for_any_device)
                null -> null
            }
            SectionCard(title = title) {
                for (app in group.apps) StarterRow(app, onLook = { onLook(app) }, modifier = rowFocus(app))
            }
        }
    }
}

@Composable
private fun StarterRow(app: Suggestion, onLook: () -> Unit, modifier: Modifier) {
    val look = LocalLook.current
    PressRow(
        action = stringResource(R.string.action_look_at),
        onClick = onLook,
        modifier = modifier.testTag(STARTER_ROW_TAG),
        leading = { LetterAvatar(app.url, app.name, look.iconList) },
    ) {
        Text(app.name, style = MaterialTheme.typography.titleMedium.heavier())
        Text(app.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ltr(shortUrl(app.url)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (app.pinned) StatusChip(Glyphs.Seal, stringResource(R.string.starter_pinned), tone = ChipTone.VERIFIED)
        when (app.here) {
            StarterHere.NONE -> Unit
            StarterHere.IN_LIST -> StatusChip(Glyphs.Check, stringResource(R.string.starter_in_list))
            StarterHere.ON_PHONE -> StatusChip(Glyphs.Check, stringResource(R.string.starter_on_phone))
            StarterHere.ON_PHONE_OTHER_SIGNER -> {
                StatusChip(Glyphs.Caution, stringResource(R.string.starter_on_phone_other), tone = ChipTone.CAUTION)
                Text(stringResource(R.string.starter_on_phone_other_explain), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
