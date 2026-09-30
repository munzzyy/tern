package io.github.munzzyy.tern.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ChoiceRow
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.detail.SourceOptionsCard
import io.github.munzzyy.tern.ui.icons.Collapse
import io.github.munzzyy.tern.ui.icons.Expand
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.fingerprint

const val ADD_OPTIONS_TAG = "add_options"
const val ADD_PACKAGE_TAG = "add_package"

/** What an address may be read as, Automatic first, as Obtainium's "override source" offers it. */
val READ_AS_CHOICES: List<String?> = listOf<String?>(null) + SourceTypes.OVERRIDABLE

/** [READ_AS_CHOICES] without the third-party stores while those are off. */
fun readAsChoices(storesOn: Boolean): List<String?> = if (storesOn) READ_AS_CHOICES else READ_AS_CHOICES.filter { it !in SourceTypes.THIRD_PARTY_STORES }

/** A kind of source as the choice names it. */
@Composable
fun readAsLabel(type: String?): String = when (type) {
    null -> stringResource(R.string.add_read_as_auto)
    SourceTypes.HTML -> stringResource(R.string.sources_web_page)
    SourceTypes.DIRECT -> stringResource(R.string.sources_direct)
    SourceTypes.FORGEJO -> stringResource(R.string.add_read_as_forgejo)
    else -> SourceTypes.displayName(type) ?: type
}

/**
 * What the person may say about an address before it is added: the kind of source to read it as,
 * the package name the app has to have, and the options of its source. Whatever they change is
 * read again, and the answer below shows what it does, before anything is stored.
 */
@Composable
fun ReadingCard(vm: AddViewModel, answered: Boolean) {
    val look = LocalLook.current
    SectionCard {
        ActionRow(
            title = stringResource(R.string.add_options_title),
            summary = vm.readAs?.let { stringResource(R.string.add_options_forced, readAsLabel(it)) } ?: stringResource(R.string.add_options_auto),
            icon = if (vm.optionsOpen) Glyphs.Collapse else Glyphs.Expand,
            onClick = vm::toggleOptions,
            modifier = Modifier.testTag(ADD_OPTIONS_TAG),
        )
        if (!vm.optionsOpen) return@SectionCard
        val settings by LocalEngine.current.settings.collectAsStateWithLifecycle()
        ChoiceRow(
            title = stringResource(R.string.add_read_as),
            options = readAsChoices(settings.thirdPartyStores),
            selected = vm.readAs,
            label = { readAsLabel(it) },
            onSelect = vm::pickReadAs,
            summary = stringResource(R.string.add_read_as_help),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(look.gapSmall),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
        ) {
            PackageNameField(vm.packageName, vm::editPackage, Modifier.testTag(ADD_PACKAGE_TAG))
            if (answered) {
                TonalButton(
                    stringResource(R.string.action_check_again),
                    onClick = vm::checkAgain,
                    enabled = isPackageName(vm.packageName),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
    val spec = vm.spec
    if (vm.optionsOpen && spec != null) {
        // The screen keeps the card off its sides already.
        CompositionLocalProvider(LocalLook provides look.copy(screenPadding = 0.dp)) {
            SourceOptionsCard("add", spec) { change -> vm.applyOptions(change(spec)) }
        }
    }
}

/** A package name for the app: Tern holds every file to it and learns none in its place. */
@Composable
fun PackageNameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val invalid = !isPackageName(value)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.add_package_label)) },
        supportingText = { Text(stringResource(if (invalid) R.string.add_package_invalid else R.string.add_package_help)) },
        isError = invalid,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.fingerprint(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = modifier
            .fillMaxWidth()
            .textFieldKeys(),
    )
}
