package io.github.munzzyy.stamp.ui.common

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.Verification
import io.github.munzzyy.stamp.ui.text.Trust
import io.github.munzzyy.stamp.ui.text.breakableFingerprint
import io.github.munzzyy.stamp.ui.text.checksumLine
import io.github.munzzyy.stamp.ui.text.formatFingerprint
import io.github.munzzyy.stamp.ui.text.shortPermission
import io.github.munzzyy.stamp.ui.text.signerLine
import io.github.munzzyy.stamp.ui.text.virusTotalUrl

@Composable
fun VerificationPanel(v: Verification, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.checks_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Labeled(stringResource(R.string.checks_package)) {
            SelectionContainer {
                Text(v.packageName ?: stringResource(R.string.checks_package_unknown), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val signer = signerLine(v)
        TrustLine(signer.trust, stringResource(signer.text))
        Fingerprints(v.signers, v.signersVerified)
        val checksum = checksumLine(v)
        TrustLine(
            checksum.trust,
            stringResource(checksum.text),
            detail = v.checksumSource?.let { stringResource(R.string.checksum_source, it) },
        )
        virusTotalUrl(v.fileSha256)?.let { VirusTotalLink(it) }
        if (v.newPermissions.isEmpty()) {
            TrustLine(Trust.GOOD, stringResource(R.string.permissions_none_new))
        } else {
            TrustLine(
                Trust.NOTE,
                pluralStringResource(R.plurals.permissions_new, v.newPermissions.size, v.newPermissions.size),
                detail = v.newPermissions.joinToString("\n") { shortPermission(it) },
            )
        }
    }
}

const val VIRUSTOTAL_TAG = "checks_virustotal"

@Composable
private fun VirusTotalLink(url: String) {
    var open by remember { mutableStateOf(false) }
    LinkText(stringResource(R.string.checks_virustotal), onClick = { open = true }, modifier = Modifier.testTag(VIRUSTOTAL_TAG))
    if (open) LinkDialog(url, onDismiss = { open = false }, note = stringResource(R.string.checks_virustotal_note))
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun TrustLine(trust: Trust, text: String, detail: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (trust) {
        Trust.GOOD -> Icons.Filled.CheckCircle to scheme.primary
        Trust.NOTE -> Icons.Filled.Info to scheme.onSurfaceVariant
        Trust.BAD -> Icons.Filled.Warning to scheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (trust == Trust.BAD) scheme.error else scheme.onSurface)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Fingerprints(signers: List<String>, verified: Boolean) {
    val clipboard = LocalClipboard.current
    val actions = rememberActions()
    val copied = stringResource(R.string.fingerprint_copied)
    val label = stringResource(if (verified) R.string.checks_certificate else R.string.checks_certificate_claimed)
    if (signers.isEmpty()) return
    for (hex in signers) {
        val formatted = formatFingerprint(hex)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(breakableFingerprint(formatted), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = {
                actions.run {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, formatted)))
                    actions.say(copied)
                }
            }) {
                Icon(io.github.munzzyy.stamp.ui.icons.Glyphs.Copy, contentDescription = stringResource(R.string.action_copy_fingerprint))
            }
        }
    }
}
