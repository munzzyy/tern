package io.github.munzzyy.tern.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.ui.add.isPackageName
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.fingerprint

/**
 * The package name of an app already in the list, set or put right by the person and saved with
 * its own button. Every file is then held to it, and the app installed under it is the one looked at.
 */
@Composable
fun PackageNameSetting(config: AppConfig, save: ((AppConfig) -> AppConfig) -> Unit) {
    val look = LocalLook.current
    val saved = config.packageName.orEmpty()
    var entry by rememberSaveable(config.id, saved) { mutableStateOf(saved) }
    val invalid = !isPackageName(entry)
    OutlinedTextField(
        value = entry,
        onValueChange = { entry = packageEntry(it) },
        label = { Text(stringResource(R.string.add_package_label)) },
        supportingText = { Text(stringResource(if (invalid) R.string.add_package_invalid else R.string.setting_package_effect)) },
        isError = invalid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.fingerprint(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().textFieldKeys(),
    )
    if (entry == saved) return
    Row(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End), modifier = Modifier.fillMaxWidth()) {
        QuietButton(stringResource(R.string.action_discard), onClick = { entry = saved })
        TonalButton(
            stringResource(R.string.action_save),
            onClick = {
                val name = packageNameOf(entry)
                save { it.copy(packageName = name) }
            },
            enabled = !invalid,
        )
    }
}
