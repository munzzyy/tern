package io.github.munzzyy.tern.ui.detail

import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.munzzyy.tern.install.VerifiedApps
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.text.formatFingerprint

/**
 * Verified Apps, which was AppVerifier, is an app of its own that checks the signing certificate
 * of an installed app against a hash it is given and against its own list of known apps. It reads
 * the package name on the first line and the hashes on the lines after it, written the way it
 * writes them itself.
 */
object AppVerifier {
    fun text(packageName: String, signers: List<String>): String =
        (listOf(packageName) + signers.map(::formatFingerprint)).joinToString("\n")
}

/** What goes to the verifier on this device, and the name it goes by there. */
class VerifierSend(val intent: Intent, val name: String)

/**
 * What to send for [row] to the first of [VerifiedApps.PACKAGES] on this device that carries its
 * own certificate, or null when the app is not installed or no genuine verifier is there.
 */
@Composable
fun rememberAppVerifier(row: AppRow): VerifierSend? {
    val context = LocalContext.current
    val packageName = row.installed?.packageName
    val signers = row.config.pinnedSigners.ifEmpty { row.installed?.signers.orEmpty() }
    return remember(packageName, signers) {
        if (packageName == null || signers.isEmpty()) return@remember null
        val pm = context.packageManager
        val send = { verifier: String ->
            Intent(Intent.ACTION_SEND)
                .setPackage(verifier)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, AppVerifier.text(packageName, signers))
        }
        val verifier = VerifiedApps.first({ VerifiedApps.signers(pm, it) }) { send(it).resolveActivity(pm) != null } ?: return@remember null
        val name = try {
            pm.getApplicationLabel(pm.getApplicationInfo(verifier, 0)).toString().take(60)
        } catch (_: PackageManager.NameNotFoundException) {
            return@remember null
        }
        VerifierSend(send(verifier), name)
    }
}

const val APPVERIFIER_TAG = "detail_appverifier"

@Composable
fun AppVerifierCard(send: VerifierSend) {
    val context = LocalContext.current
    DetailCard(stringResource(R.string.appverifier_title), padded = true) {
        Text(stringResource(R.string.files_verifier_card_effect, send.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TonalButton(
            stringResource(R.string.files_verifier_card_action, send.name),
            onClick = { runCatching { context.startActivity(send.intent) } },
            modifier = Modifier.testTag(APPVERIFIER_TAG),
        )
    }
}
