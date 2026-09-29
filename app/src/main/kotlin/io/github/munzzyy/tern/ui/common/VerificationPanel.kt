package io.github.munzzyy.tern.ui.common

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.SignerState
import io.github.munzzyy.tern.engine.Verification
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.icons.Info
import io.github.munzzyy.tern.ui.text.Trust
import io.github.munzzyy.tern.ui.text.breakableFingerprint
import io.github.munzzyy.tern.ui.text.checksumExplanation
import io.github.munzzyy.tern.ui.text.checksumLine
import io.github.munzzyy.tern.ui.text.formatFingerprint
import io.github.munzzyy.tern.ui.text.shortPermission
import io.github.munzzyy.tern.ui.text.signerExplanation
import io.github.munzzyy.tern.ui.text.signerLine
import io.github.munzzyy.tern.ui.text.virusTotalUrl
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.fingerprint
import io.github.munzzyy.tern.ui.theme.status

const val VIRUSTOTAL_TAG = "checks_virustotal"
const val EXPLAIN_PACKAGE_TAG = "explain_package"
const val EXPLAIN_SIGNER_TAG = "explain_signer"
const val EXPLAIN_CHECKSUM_TAG = "explain_checksum"
const val EXPLAIN_PERMISSIONS_TAG = "explain_permissions"
const val EXPLAIN_VIRUSTOTAL_TAG = "explain_virustotal"
const val EXPLAIN_TEXT_TAG = "explain_text"
const val EXPLAIN_TITLE_TAG = "explain_title"

/**
 * What Tern found out about a file, one check to a line, each with a way to ask what it means.
 * [installed] says whether the app is on this device; left out, the state of the signer decides
 * which explanation fits. [title] is false where a card around the panel already carries it.
 */
@Composable
fun VerificationPanel(v: Verification, modifier: Modifier = Modifier, installed: Boolean? = null, title: Boolean = true) {
    val look = LocalLook.current
    val compared = installed ?: (v.signerState != SignerState.FIRST_SEEN && v.signerState != SignerState.UNKNOWN)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(look.gapSmall)) {
        if (title) {
            Text(
                stringResource(R.string.checks_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Explained(stringResource(R.string.checks_package), stringResource(R.string.explain_package), EXPLAIN_PACKAGE_TAG) {
            Labeled(stringResource(R.string.checks_package)) {
                Text(v.packageName ?: stringResource(R.string.checks_package_unknown), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val signer = signerLine(v)
        Explained(stringResource(R.string.explain_signer_title), stringResource(signerExplanation(v, compared)), EXPLAIN_SIGNER_TAG) {
            TrustLine(signer.trust, stringResource(signer.text))
        }
        Fingerprints(v.signers, v.signersVerified)
        val checksum = checksumLine(v)
        Explained(stringResource(R.string.explain_checksum_title), stringResource(checksumExplanation(v)), EXPLAIN_CHECKSUM_TAG) {
            TrustLine(
                checksum.trust,
                stringResource(checksum.text),
                detail = v.checksumSource?.let { stringResource(R.string.checksum_source, it) },
            )
        }
        virusTotalUrl(v.fileSha256)?.let { VirusTotalLine(it) }
        Explained(stringResource(R.string.explain_permissions_title), stringResource(R.string.explain_permissions), EXPLAIN_PERMISSIONS_TAG) {
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
}

/** Lets a row reach [by] past both sides of its column, so that what stands inside it lines up with what stands outside. */
fun Modifier.outdent(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val wider = constraints.copy(
        minWidth = if (constraints.hasBoundedWidth) constraints.minWidth + 2 * extra else constraints.minWidth,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
    )
    val placed = measurable.measure(wider)
    layout((placed.width - 2 * extra).coerceAtLeast(0), placed.height) { placed.place(-extra, 0) }
}

/**
 * A line that says what it means when it is pressed. The whole line is pressed and takes focus,
 * so a remote walks from line to line and TalkBack reads each as one.
 */
@Composable
fun Explained(name: String, explanation: String, tag: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalLook.current
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = modifier
            .fillMaxWidth()
            .outdent(look.gapSmall)
            .testTag(tag)
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.explain_action), role = Role.Button) { open = true }
            .heightIn(min = look.touchTarget)
            .padding(horizontal = look.gapSmall, vertical = look.gapSmall / 2),
    ) {
        Column(Modifier.weight(1f)) { content() }
        Icon(Glyphs.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(look.glyph))
    }
    if (open) ExplainDialog(name, explanation, onDismiss = { open = false })
}

/** A glyph button at the end of a line, moved so that its glyph ends where the glyphs of the lines around it end. */
@Composable
private fun EndButton(glyph: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    GlyphButton(
        glyph,
        label,
        onClick = onClick,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.offset(x = (look.touchTarget - look.glyph) / 2),
    )
}

@Composable
fun ExplainDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.testTag(EXPLAIN_TITLE_TAG)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(EXPLAIN_TEXT_TAG))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.explain_close)) }
        },
    )
}

/** The link is pressed by itself, so what it means is asked at the button next to it. */
@Composable
private fun VirusTotalLine(url: String) {
    val look = LocalLook.current
    var open by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val name = stringResource(R.string.explain_virustotal_title)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
        LinkText(
            stringResource(R.string.checks_virustotal),
            onClick = { open = true },
            modifier = Modifier.weight(1f).testTag(VIRUSTOTAL_TAG).focusLook(),
        )
        EndButton(Glyphs.Info, stringResource(R.string.explain_button, name), Modifier.testTag(EXPLAIN_VIRUSTOTAL_TAG)) { asked = true }
    }
    if (open) LinkDialog(url, onDismiss = { open = false }, note = stringResource(R.string.checks_virustotal_note))
    if (asked) ExplainDialog(name, stringResource(R.string.explain_virustotal), onDismiss = { asked = false })
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** One finding in a glyph and a sentence. The glyph and the words carry it; the colour only goes along. */
@Composable
fun TrustLine(trust: Trust, text: String, detail: String? = null) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val (glyph, tint) = when (trust) {
        Trust.GOOD -> Glyphs.Check to MaterialTheme.status.verified.color
        Trust.NOTE -> Glyphs.Info to scheme.onSurfaceVariant
        Trust.BAD -> Glyphs.Caution to MaterialTheme.status.refused.color
    }
    Row(horizontalArrangement = Arrangement.spacedBy(look.gapSmall + look.gapSmall / 2)) {
        Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.padding(top = look.gapSmall / 4).size(look.glyphSmall))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (trust == Trust.BAD) tint else scheme.onSurface)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Fingerprints(signers: List<String>, verified: Boolean) {
    val look = LocalLook.current
    val clipboard = LocalClipboard.current
    val actions = rememberActions()
    val copied = stringResource(R.string.fingerprint_copied)
    val label = stringResource(if (verified) R.string.checks_certificate else R.string.checks_certificate_claimed)
    for (hex in signers) {
        val formatted = formatFingerprint(hex)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(breakableFingerprint(formatted), style = MaterialTheme.typography.bodySmall.fingerprint(), modifier = Modifier.fillMaxWidth())
                }
            }
            EndButton(Glyphs.Copy, stringResource(R.string.action_copy_fingerprint)) {
                actions.run {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, formatted)))
                    actions.say(copied)
                }
            }
        }
    }
}
