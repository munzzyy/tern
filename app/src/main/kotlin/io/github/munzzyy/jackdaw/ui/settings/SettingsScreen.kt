package io.github.munzzyy.jackdaw.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.jackdaw.BuildConfig
import io.github.munzzyy.jackdaw.R
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import io.github.munzzyy.jackdaw.engine.ProxyMode
import io.github.munzzyy.jackdaw.engine.Settings
import io.github.munzzyy.jackdaw.engine.ThemeMode
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.LocalSnackbar
import io.github.munzzyy.jackdaw.ui.common.ActionRow
import io.github.munzzyy.jackdaw.ui.common.ChoiceRow
import io.github.munzzyy.jackdaw.ui.common.InfoRow
import io.github.munzzyy.jackdaw.ui.common.LinkDialog
import io.github.munzzyy.jackdaw.ui.common.SectionHeader
import io.github.munzzyy.jackdaw.ui.common.SwitchRow
import io.github.munzzyy.jackdaw.ui.common.openNotificationSettings
import io.github.munzzyy.jackdaw.ui.common.rememberActions
import io.github.munzzyy.jackdaw.ui.common.verticalKeysLeave
import io.github.munzzyy.jackdaw.ui.detail.minAgeLabel
import io.github.munzzyy.jackdaw.ui.detail.updateModeEffect
import io.github.munzzyy.jackdaw.ui.detail.updateModeLabel
import io.github.munzzyy.jackdaw.ui.icons.Glyphs
import io.github.munzzyy.jackdaw.ui.text.intervalChoices
import io.github.munzzyy.jackdaw.ui.theme.dynamicColorSupported

const val SOURCE_URL = "https://github.com/munzzyy/jackdaw"
private val MIN_AGE_CHOICES = listOf(0, 1, 3, 7, 14, 30)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onImport: () -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "settings") { SettingsViewModel(engine) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val update: ((Settings) -> Settings) -> Unit = { change -> vm.update({ actions.say(failed) }, change) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 840.dp)) {
                BackgroundSection(s, update)
                DefaultsSection(s, update)
                NotificationsSection(s, update)
                InstallingSection(s, update)
                TokensSection(vm)
                NetworkSection(s, update)
                AppearanceSection(s, update)
                DataSection(s, vm, update, onImport)
                AboutSection()
            }
        }
    }
}

private typealias Update = ((Settings) -> Settings) -> Unit

@Composable
private fun BackgroundSection(s: Settings, update: Update) {
    SectionHeader(stringResource(R.string.settings_background))
    ChoiceRow(
        title = stringResource(R.string.settings_interval),
        options = intervalChoices(s.checkEveryHours),
        selected = s.checkEveryHours,
        label = { if (it == 0) stringResource(R.string.interval_off) else pluralStringResource(R.plurals.interval_hours, it, it) },
        onSelect = { h -> update { it.copy(checkEveryHours = h) } },
    )
    val on = s.checkEveryHours > 0
    SwitchRow(
        title = stringResource(R.string.settings_unmetered),
        summary = stringResource(R.string.settings_unmetered_effect),
        checked = s.onlyOnUnmetered,
        enabled = on,
        onChange = { v -> update { it.copy(onlyOnUnmetered = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.settings_charging),
        summary = stringResource(R.string.settings_charging_effect),
        checked = s.onlyWhileCharging,
        enabled = on,
        onChange = { v -> update { it.copy(onlyWhileCharging = v) } },
    )
}

@Composable
private fun DefaultsSection(s: Settings, update: Update) {
    SectionHeader(stringResource(R.string.settings_defaults))
    ChoiceRow(
        title = stringResource(R.string.setting_update_mode),
        options = UpdateMode.entries,
        selected = s.defaultUpdateMode,
        label = { updateModeLabel(it) },
        summary = updateModeEffect(s.defaultUpdateMode),
        onSelect = { m -> update { it.copy(defaultUpdateMode = m) } },
    )
    SwitchRow(
        title = stringResource(R.string.setting_prereleases),
        summary = stringResource(if (s.includePrereleasesByDefault) R.string.setting_prereleases_on else R.string.setting_prereleases_off),
        checked = s.includePrereleasesByDefault,
        onChange = { v -> update { it.copy(includePrereleasesByDefault = v) } },
    )
    ChoiceRow(
        title = stringResource(R.string.setting_min_age),
        options = (MIN_AGE_CHOICES + s.minAgeDaysByDefault).distinct().sorted(),
        selected = s.minAgeDaysByDefault,
        label = { minAgeLabel(it) },
        summary = stringResource(R.string.setting_min_age_effect),
        onSelect = { d -> update { it.copy(minAgeDaysByDefault = d) } },
    )
}

@Composable
private fun NotificationsSection(s: Settings, update: Update) {
    val context = LocalContext.current
    SectionHeader(stringResource(R.string.settings_notifications))
    SwitchRow(stringResource(R.string.settings_notify_updates), s.notifyUpdates, { v -> update { it.copy(notifyUpdates = v) } })
    SwitchRow(stringResource(R.string.settings_notify_installed), s.notifyInstalled, { v -> update { it.copy(notifyInstalled = v) } })
    SwitchRow(stringResource(R.string.settings_notify_failures), s.notifyFailures, { v -> update { it.copy(notifyFailures = v) } })
    ActionRow(
        title = stringResource(R.string.settings_system_notifications),
        summary = stringResource(R.string.settings_system_notifications_effect),
        icon = Glyphs.OpenInNew,
        onClick = { openNotificationSettings(context) },
    )
}

@Composable
private fun InstallingSection(s: Settings, update: Update) {
    SectionHeader(stringResource(R.string.settings_installing))
    SwitchRow(
        title = stringResource(R.string.settings_keep_installers),
        summary = stringResource(R.string.settings_keep_installers_effect),
        checked = s.keepInstallers,
        onChange = { v -> update { it.copy(keepInstallers = v) } },
    )
    val ownershipSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    SwitchRow(
        title = stringResource(R.string.settings_ownership),
        summary = stringResource(if (ownershipSupported) R.string.settings_ownership_effect else R.string.settings_ownership_unsupported),
        checked = s.claimUpdateOwnership && ownershipSupported,
        enabled = ownershipSupported,
        onChange = { v -> update { it.copy(claimUpdateOwnership = v) } },
    )
}

@Composable
private fun TokensSection(vm: SettingsViewModel) {
    val hosts by vm.tokenHosts.collectAsStateWithLifecycle()
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var badHost by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.settings_tokens))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_tokens_effect),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (h in hosts.orEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(h, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.token_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.setToken(h, null) { actions.say(failed) } }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.token_remove, h))
                }
            }
        }
        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it.take(260)
                badHost = false
            },
            label = { Text(stringResource(R.string.token_host)) },
            supportingText = { Text(stringResource(if (badHost) R.string.token_host_invalid else R.string.token_host_help)) },
            isError = badHost,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().verticalKeysLeave(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.take(4096) },
            label = { Text(stringResource(R.string.token_value)) },
            supportingText = { Text(stringResource(R.string.token_value_help)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().verticalKeysLeave(),
        )
        Button(
            onClick = {
                val normalized = normalizeHost(host)
                if (normalized == null) {
                    badHost = true
                } else {
                    vm.setToken(normalized, token.trim()) { actions.say(failed) }
                    host = ""
                    token = ""
                }
            },
            enabled = host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.token_save)) }
    }
}

@Composable
private fun NetworkSection(s: Settings, update: Update) {
    SectionHeader(stringResource(R.string.settings_network))
    ChoiceRow(
        title = stringResource(R.string.settings_proxy),
        options = ProxyMode.entries,
        selected = s.proxy,
        label = {
            stringResource(
                when (it) {
                    ProxyMode.NONE -> R.string.proxy_none
                    ProxyMode.ORBOT -> R.string.proxy_orbot
                    ProxyMode.CUSTOM -> R.string.proxy_custom
                },
            )
        },
        summary = stringResource(
            when (s.proxy) {
                ProxyMode.NONE -> R.string.proxy_none_effect
                ProxyMode.ORBOT -> R.string.proxy_orbot_effect
                ProxyMode.CUSTOM -> R.string.proxy_custom_effect
            },
        ),
        onSelect = { m -> update { it.copy(proxy = m) } },
    )
    if (s.proxy == ProxyMode.CUSTOM) CustomProxy(s, update)
}

@Composable
private fun CustomProxy(s: Settings, update: Update) {
    var host by remember(s.proxyHost) { mutableStateOf(s.proxyHost) }
    var port by remember(s.proxyPort) { mutableStateOf(s.proxyPort.toString()) }
    val hostOk = isValidProxyHost(host)
    val portValue = parsePort(port)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.take(253) },
            label = { Text(stringResource(R.string.proxy_host)) },
            isError = !hostOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().verticalKeysLeave(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.proxy_port)) },
            supportingText = { if (portValue == null) Text(stringResource(R.string.proxy_port_invalid)) },
            isError = portValue == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().verticalKeysLeave(),
        )
        val dirty = host.trim() != s.proxyHost || portValue != s.proxyPort
        if (dirty) {
            Button(
                onClick = { update { it.copy(proxyHost = host.trim(), proxyPort = portValue ?: it.proxyPort) } },
                enabled = hostOk && portValue != null,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

@Composable
private fun AppearanceSection(s: Settings, update: Update) {
    SectionHeader(stringResource(R.string.settings_appearance))
    ChoiceRow(
        title = stringResource(R.string.settings_theme),
        options = ThemeMode.entries,
        selected = s.theme,
        label = {
            stringResource(
                when (it) {
                    ThemeMode.SYSTEM -> R.string.theme_system
                    ThemeMode.LIGHT -> R.string.theme_light
                    ThemeMode.DARK -> R.string.theme_dark
                },
            )
        },
        onSelect = { t -> update { it.copy(theme = t) } },
    )
    SwitchRow(
        title = stringResource(R.string.settings_dynamic_color),
        summary = stringResource(if (dynamicColorSupported) R.string.settings_dynamic_color_effect else R.string.settings_dynamic_color_unsupported),
        checked = s.dynamicColor && dynamicColorSupported,
        enabled = dynamicColorSupported,
        onChange = { v -> update { it.copy(dynamicColor = v) } },
    )
    SwitchRow(
        title = stringResource(R.string.settings_pure_black),
        summary = stringResource(R.string.settings_pure_black_effect),
        checked = s.pureBlack,
        onChange = { v -> update { it.copy(pureBlack = v) } },
    )
}

@Composable
private fun DataSection(s: Settings, vm: SettingsViewModel, update: Update, onImport: () -> Unit) {
    val context = LocalContext.current
    val actions = rememberActions()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val exportFailed = stringResource(R.string.export_failed)
    val resources = context.resources
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            vm.export(uri) { count ->
                actions.say(if (count == null) exportFailed else resources.getQuantityString(R.plurals.exported, count, count))
            }
        }
    }
    SectionHeader(stringResource(R.string.settings_data))
    ActionRow(
        title = stringResource(R.string.settings_import),
        summary = stringResource(R.string.settings_import_effect),
        onClick = onImport,
    )
    ActionRow(
        title = stringResource(if (exporting) R.string.settings_exporting else R.string.settings_export),
        summary = stringResource(R.string.settings_export_effect),
        onClick = { if (!exporting) exporter.launch("jackdaw-apps.json") },
    )
    SwitchRow(
        title = stringResource(R.string.settings_obtainium_links),
        summary = stringResource(R.string.settings_obtainium_links_effect),
        checked = s.openObtainiumLinks,
        onChange = { v -> update { it.copy(openObtainiumLinks = v) } },
    )
}

@Composable
private fun AboutSection() {
    var link by remember { mutableStateOf<String?>(null) }
    SectionHeader(stringResource(R.string.settings_about))
    InfoRow(title = stringResource(R.string.about_version), value = BuildConfig.VERSION_NAME)
    InfoRow(title = stringResource(R.string.about_licence), value = stringResource(R.string.about_licence_name))
    ActionRow(
        title = stringResource(R.string.about_source),
        summary = SOURCE_URL,
        icon = Glyphs.OpenInNew,
        onClick = { link = SOURCE_URL },
    )
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}
