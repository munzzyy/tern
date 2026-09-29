package io.github.munzzyy.tern.ui.detail

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.text.formatFingerprint

/**
 * AppVerifier is an app of its own that checks the signing certificate of an installed app against
 * a hash it is given and against its own list of known apps. It reads the package name on the first
 * line and the hashes on the lines after it, written the way AppVerifier writes them itself.
 */
object AppVerifier {
    const val PACKAGE = "dev.soupslurpr.appverifier"

    fun text(packageName: String, signers: List<String>): String =
        (listOf(packageName) + signers.map(::formatFingerprint)).joinToString("\n")
}

/** What to send AppVerifier for [row], or null when the app is not installed or AppVerifier is not on this device. */
@Composable
fun rememberAppVerifier(row: AppRow): Intent? {
    val context = LocalContext.current
    val packageName = row.installed?.packageName
    val signers = row.config.pinnedSigners.ifEmpty { row.installed?.signers.orEmpty() }
    return remember(packageName, signers) {
        if (packageName == null || signers.isEmpty()) return@remember null
        Intent(Intent.ACTION_SEND)
            .setPackage(AppVerifier.PACKAGE)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, AppVerifier.text(packageName, signers))
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }
}

const val APPVERIFIER_TAG = "detail_appverifier"

@Composable
fun AppVerifierCard(intent: Intent) {
    val context = LocalContext.current
    DetailCard(stringResource(R.string.appverifier_title), padded = true) {
        Text(stringResource(R.string.appverifier_effect), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TonalButton(
            stringResource(R.string.appverifier_action),
            onClick = { runCatching { context.startActivity(intent) } },
            modifier = Modifier.testTag(APPVERIFIER_TAG),
        )
    }
}
