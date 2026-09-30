package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.status

/** A problem said plainly, what to do about it, and at most one way out. */
@Composable
fun ProblemBox(
    title: String,
    body: String?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    val refused = MaterialTheme.status.refused
    Surface(
        color = refused.container,
        contentColor = refused.onContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(look.cardPadding), verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            Row(horizontalArrangement = Arrangement.spacedBy(look.gap)) {
                Icon(Glyphs.Caution, contentDescription = null, modifier = Modifier.size(look.glyph))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(look.gapSmall / 2)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    // What went wrong can be selected and copied, to look it up or to report it.
                    body?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            if (action != null && onAction != null) {
                QuietButton(action, onAction, ink = refused.onContainer, modifier = Modifier.padding(start = look.glyph + look.gap - look.gap * 3 / 4))
            }
        }
    }
}
