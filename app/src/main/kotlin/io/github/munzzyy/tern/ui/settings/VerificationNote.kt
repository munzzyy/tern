package io.github.munzzyy.tern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.theme.LocalLook

const val VERIFICATION_URL = "https://developer.android.com/developer-verification"

/**
 * Google's plan that certified Android devices install only apps from developers registered with
 * it. It is shown once, on the first start after the first run, and stays readable under About.
 */
@Composable
fun VerificationNote(onDismiss: () -> Unit) {
    var link by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.verification_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LocalLook.current.gapSmall)) {
                Text(stringResource(R.string.verification_text))
                TextButton(onClick = { link = VERIFICATION_URL }) { Text(stringResource(R.string.verification_more)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}
