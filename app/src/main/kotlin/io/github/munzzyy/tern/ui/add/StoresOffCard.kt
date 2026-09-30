package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.theme.LocalLook

const val STORES_ON_TAG = "stores_turn_on"

/** Said for an address of a third-party store while those are off: what they are, and the one way to turn them on. Nothing was asked of the store. */
@Composable
fun StoresOffCard(type: String, onTurnOn: () -> Unit) {
    val look = LocalLook.current
    SectionCard(title = stringResource(R.string.stores_off_title, SourceTypes.displayName(type) ?: type), padded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            Text(stringResource(R.string.stores_off_body), style = MaterialTheme.typography.bodyMedium)
            TonalButton(stringResource(R.string.stores_turn_on), onClick = onTurnOn, modifier = Modifier.testTag(STORES_ON_TAG))
        }
    }
}
