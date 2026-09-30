package io.github.munzzyy.tern.ui.importing

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.real.Capped
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.theme.LocalLook
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ADDRESSES_FIELD_TAG = "addresses_field"
const val ADDRESSES_SHOW_TAG = "addresses_show"

private const val MAX_FILE = 2 * 1024 * 1024

/** A list of addresses, typed, pasted or read from any text file such as an OPML list, added one by one like starred repositories. */
fun LazyListScope.addressesSection(state: StarsState, vm: StarsViewModel) {
    item(key = "addresses") {
        val look = LocalLook.current
        SectionCard(
            title = stringResource(R.string.import_addresses_heading),
            modifier = Modifier
                .padding(top = look.gap)
                .widthIn(max = look.contentMaxWidth),
        ) {
            ReadBlock { Text(stringResource(R.string.import_addresses_explain), style = MaterialTheme.typography.bodyLarge) }
            val inside = Modifier.padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2)
            when (state) {
                StarsState.Idle, is StarsState.Failed, StarsState.Loading -> AskAddresses(vm::showAddresses)
                is StarsState.Listed -> {
                    val n = state.hits.size
                    Words(if (n == 0) stringResource(R.string.import_addresses_none) else pluralStringResource(R.plurals.import_addresses_count, n, n))
                    QuietButton(stringResource(R.string.import_addresses_other), onClick = vm::reset, modifier = Modifier.padding(horizontal = look.focusRoom))
                }
                is StarsState.Adding -> {
                    Working(pluralStringResource(R.plurals.import_stars_adding, state.total, state.done, state.total), stringResource(R.string.import_stars_stop), vm::stop, inside)
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = inside.fillMaxWidth(),
                    )
                }
                is StarsState.Done -> {
                    val o = state.outcome
                    Column(inside) { ImportSummaryView(o.added, o.present, o.skipped.map { (name, reason) -> name to skipText(reason) }) }
                    QuietButton(stringResource(R.string.import_addresses_other), onClick = vm::reset, modifier = Modifier.padding(horizontal = look.focusRoom))
                }
            }
        }
    }
    if (state is StarsState.Listed) pickList(state, vm, "address")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskAddresses(onShow: (String) -> Unit) {
    val look = LocalLook.current
    val context = LocalContext.current
    val engine = LocalEngine.current
    val actions = rememberActions()
    val scope = rememberCoroutineScope()
    val unreadable = stringResource(R.string.import_addresses_unreadable)
    var text by rememberSaveable { mutableStateOf("") }
    val hasPicker = remember(engine) { engine.hasFilePicker() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val read = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { String(Capped.read(it, MAX_FILE), Charsets.UTF_8) }
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
                if (read == null) actions.say(unreadable) else onShow(read)
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_TYPED) },
            minLines = 3,
            maxLines = 8,
            label = { Text(stringResource(R.string.import_addresses_field)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .textFieldKeys()
                .testTag(ADDRESSES_FIELD_TAG),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(look.gapSmall)) {
            TonalButton(stringResource(R.string.import_addresses_show), onClick = { onShow(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag(ADDRESSES_SHOW_TAG))
            if (hasPicker) QuietButton(stringResource(R.string.import_addresses_file), onClick = { picker.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) })
        }
    }
}

private const val MAX_TYPED = 100_000
