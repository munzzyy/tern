package io.github.munzzyy.tern.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.install.InstallerChoices
import io.github.munzzyy.tern.install.VerifiedApps
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.InfoRow
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook
import kotlinx.coroutines.CancellationException

/**
 * The installer app, picked from those on the device that take an APK, each with its icon. An
 * app with several ways in lists each of them, and the one picked is the one the file goes to.
 */
@Composable
internal fun OtherInstallerPicker(packageName: String?, activity: String?, onPick: (InstallerChoice) -> Unit) {
    val engine = LocalEngine.current
    val choices = remember(engine) { engine.installerChoices() }
    if (choices.isEmpty()) {
        InfoRow(stringResource(R.string.installer_pick_app), stringResource(R.string.installer_no_apps))
        return
    }
    var open by remember { mutableStateOf(false) }
    val picked = InstallerChoices.picked(choices, packageName, activity)
    ActionRow(
        title = stringResource(R.string.installer_pick_app),
        summary = picked?.let { wayName(it, exact = it.activity == activity) } ?: stringResource(R.string.installer_pick_app_none),
        onClick = { open = true },
    )
    if (open) {
        InstallerDialog(choices, picked?.takeIf { it.activity == activity }, onDismiss = { open = false }) { choice ->
            onPick(choice)
            open = false
        }
    }
}

/** The app's name, and the name of the way in where one is picked. */
private fun wayName(choice: InstallerChoice, exact: Boolean): String =
    if (exact && choice.activity != null) listOfNotNull(choice.label, choice.activityLabel?.takeIf { it != choice.label }).joinToString(" · ") else choice.label

@Composable
private fun InstallerDialog(choices: List<InstallerChoice>, selected: InstallerChoice?, onDismiss: () -> Unit, onPick: (InstallerChoice) -> Unit) {
    val look = LocalLook.current
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.installer_pick_app)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (ways in choices.groupBy { it.packageName }.values) {
                    val app = ways.first()
                    if (ways.size == 1) {
                        WayLine(app, app.label, null, selected == app) { onPick(app) }
                        continue
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(look.gap),
                        modifier = Modifier.padding(horizontal = look.focusRoom + look.gapSmall, vertical = look.gapSmall),
                    ) {
                        InstallerIcon(app)
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                    }
                    for (way in ways) {
                        // The name of the class tells apart two ways that the app gives one name.
                        WayLine(way, way.activityLabel ?: app.label, way.activity?.substringAfterLast('.'), selected == way, indented = true) { onPick(way) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun WayLine(choice: InstallerChoice, name: String, detail: String?, selected: Boolean, indented: Boolean = false, onClick: () -> Unit) {
    val look = LocalLook.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(look.gap),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) look.gap * 2 else look.focusRoom, end = look.focusRoom, top = look.focusRoom / 2, bottom = look.focusRoom / 2)
            .then(if (selected) Modifier.focusWhenShown() else Modifier)
            .focusLook()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = look.touchTarget)
            .padding(horizontal = look.gapSmall),
    ) {
        RadioButton(selected = selected, onClick = null)
        if (!indented) InstallerIcon(choice)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun InstallerIcon(choice: InstallerChoice) {
    val engine = LocalEngine.current
    val size = LocalLook.current.glyph * 3 / 2
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<Bitmap?>(null, engine, choice, px) {
        value = try {
            engine.installerIcon(choice, px)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size).clearAndSetSemantics { })
    } else {
        Box(Modifier.size(size))
    }
}

/**
 * Obtainium's hand-off to Verified Apps: before an app's first install, its checked file goes
 * there to be looked at, and the install goes on once the person is back in Tern.
 */
@Composable
internal fun VerifierRows(on: Boolean, onChange: (Boolean) -> Unit) {
    var about by remember { mutableStateOf(false) }
    SwitchRow(
        title = stringResource(R.string.files_setting_verifier),
        summary = stringResource(R.string.files_setting_verifier_effect),
        checked = on,
        onChange = onChange,
    )
    ActionRow(
        title = stringResource(R.string.files_verifier_about),
        trailing = Glyphs.OpenInNew,
        onClick = { about = true },
    )
    if (about) LinkDialog(VerifiedApps.ABOUT, onDismiss = { about = false })
}
