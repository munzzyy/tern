package io.github.munzzyy.tern.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook

const val OFFLINE_BANNER_TAG = "offline_banner"

@Composable
fun OfflineBanner(online: Boolean, modifier: Modifier = Modifier) {
    if (online) return
    val look = LocalLook.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = look.screenPadding, vertical = look.gapSmall / 2)
            .testTag(OFFLINE_BANNER_TAG)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(look.gap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = look.touchTarget)
                .padding(horizontal = look.cardPadding, vertical = look.gapSmall),
        ) {
            Icon(Glyphs.Offline, contentDescription = null, modifier = Modifier.size(look.glyph))
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
