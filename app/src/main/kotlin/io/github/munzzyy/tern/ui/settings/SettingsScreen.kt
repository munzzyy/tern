package io.github.munzzyy.tern.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.tern.BuildConfig
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.engine.ExportFormat
import io.github.munzzyy.tern.engine.InstallerChoice
import io.github.munzzyy.tern.engine.InstallerMode
import io.github.munzzyy.tern.engine.InstallerReadiness
import io.github.munzzyy.tern.engine.OrbotState
import io.github.munzzyy.tern.engine.ProxyMode
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.LocalSnackbar
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ChoiceRow
import io.github.munzzyy.tern.ui.common.InfoRow
import io.github.munzzyy.tern.ui.common.LocalNoTouch
import io.github.munzzyy.tern.ui.common.InstallPermissionQuestion
import io.github.munzzyy.tern.ui.common.LinkDialog
import io.github.munzzyy.tern.ui.common.ReadBlock
import io.github.munzzyy.tern.ui.common.SectionCard
import io.github.munzzyy.tern.ui.common.ScreenTop
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.firstFocus
import io.github.munzzyy.tern.ui.common.focusLook
import io.github.munzzyy.tern.ui.common.installSettingsIntent
import io.github.munzzyy.tern.ui.common.openNotificationSettings
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.rememberScreenFocus
import io.github.munzzyy.tern.ui.common.returnFocus
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.detail.minAgeLabel
import io.github.munzzyy.tern.ui.detail.updateModeEffect
import io.github.munzzyy.tern.ui.detail.updateModeLabel
import io.github.munzzyy.tern.ui.icons.Glyphs
import io.github.munzzyy.tern.ui.text.formatTime
import io.github.munzzyy.tern.ui.text.IntervalUnit
import io.github.munzzyy.tern.ui.text.intervalChoices
import io.github.munzzyy.tern.ui.text.intervalUnit
import io.github.munzzyy.tern.ui.text.ltr
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.status
import java.net.URLDecoder

const val SOURCE_URL = "https://github.com/munzzyy/tern"
const val ORBOT_URL = "https://github.com/guardianproject/orbot-android"
const val ORBOT_TAG = "settings_orbot"
const val PERMIT_ROW_TAG = "settings_install_permission"
const val INSTALLER_STATUS_TAG = "settings_installer_status"
const val EXPORT_ROW_TAG = "settings_export"

private val MIN_AGE_CHOICES = listOf(0, 1, 3, 7, 14, 30)
private const val STACK_FONT_SCALE = 1.5f

/** [onAdd] opens the Add screen with an address, where nothing is added until the user says so. */
@Composable
fun SettingsScreen(onImport: () -> Unit, onLook: () -> Unit, onAdd: (String) -> Unit = {}) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "settings") { SettingsViewModel(engine) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val update: ((Settings) -> Settings) -> Unit = { change -> vm.update({ actions.say(failed) }, change) }
    val screen = rememberScreenFocus()
    val look = LocalLook.current

    Scaffold(
        topBar = { ScreenTop(stringResource(R.string.tab_settings)) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = look.screenPadding)
                .padding(bottom = look.gapSection),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(look.gap),
                modifier = Modifier
                    .widthIn(max = look.contentMaxWidth)
                    .firstFocus(screen),
            ) {
                BackgroundSection(s, vm, update)
                DefaultsSection(s, update)
                if (showsNotifications(look.television)) NotificationsSection(s, update)
                InstallingSection(s, vm, update)
                TokensSection(vm)
                NetworkSection(s, update, onGetOrbot = { onAdd(ORBOT_URL) }, orbotFocus = Modifier.returnFocus(screen, "orbot"))
                AppearanceSection(s, update, onLook, Modifier.returnFocus(screen, "look"))
                DataSection(s, vm, update, onImport, Modifier.returnFocus(screen, "import"))
                AboutSection()
            }
        }
    }
}

/** A television posts notifications and shows none of them, so the rows about them would do nothing there. */
fun showsNotifications(television: Boolean): Boolean = !television

/** Android lets an installer claim the updates of what it installed from version 14 on. */
fun ownershipSupported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

private typealias Update = ((Settings) -> Settings) -> Unit

@Composable
private fun BackgroundSection(s: Settings, vm: SettingsViewModel, update: Update) {
    val running by vm.runningCheck.collectAsStateWithLifecycle()
    SectionCard(title = stringResource(R.string.settings_background)) {
        ChoiceRow(
            title = stringResource(R.string.settings_interval),
            options = intervalChoices(s.checkEveryMinutes),
            selected = s.checkEveryMinutes,
            label = { intervalLabel(it) },
            onSelect = { m -> update { it.copy(checkEveryMinutes = m) } },
        )
        val on = s.checkEveryMinutes > 0
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
        ActionRow(
            title = stringResource(if (running) R.string.settings_check_now_running else R.string.settings_check_now),
            summary = stringResource(R.string.settings_check_now_effect),
            onClick = { vm.runBackgroundCheck() },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        SwitchRow(
            title = stringResource(R.string.settings_check_on_start),
            checked = s.checkOnStart,
            onChange = { v -> update { it.copy(checkOnStart = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_check_on_open),
            summary = stringResource(R.string.settings_check_on_open_effect),
            checked = s.checkOnOpen,
            onChange = { v -> update { it.copy(checkOnOpen = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_only_installed),
            summary = stringResource(R.string.settings_only_installed_effect),
            checked = s.onlyCheckInstalled,
            onChange = { v -> update { it.copy(onlyCheckInstalled = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_remove_uninstalled),
            summary = stringResource(R.string.settings_remove_uninstalled_effect),
            checked = s.removeUninstalled,
            onChange = { v -> update { it.copy(removeUninstalled = v) } },
        )
    }
}

@Composable
fun intervalLabel(minutes: Int): String {
    val (unit, count) = intervalUnit(minutes)
    return when (unit) {
        IntervalUnit.OFF -> stringResource(R.string.interval_off)
        IntervalUnit.MINUTES -> pluralStringResource(R.plurals.interval_minutes, count, count)
        IntervalUnit.HOURS -> pluralStringResource(R.plurals.interval_hours, count, count)
        IntervalUnit.DAYS -> pluralStringResource(R.plurals.interval_days, count, count)
    }
}

@Composable
private fun DefaultsSection(s: Settings, update: Update) {
    SectionCard(title = stringResource(R.string.settings_defaults)) {
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
        FileFilterRow(s, update)
    }
}

@Composable
private fun NotificationsSection(s: Settings, update: Update) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_notifications)) {
        SwitchRow(stringResource(R.string.settings_notify_updates), s.notifyUpdates, { v -> update { it.copy(notifyUpdates = v) } })
        SwitchRow(stringResource(R.string.settings_notify_installed), s.notifyInstalled, { v -> update { it.copy(notifyInstalled = v) } })
        SwitchRow(stringResource(R.string.settings_notify_failures), s.notifyFailures, { v -> update { it.copy(notifyFailures = v) } })
        SwitchRow(
            stringResource(R.string.settings_notify_names),
            s.notifyNames,
            { v -> update { it.copy(notifyNames = v) } },
            summary = stringResource(R.string.settings_notify_names_help),
        )
        ActionRow(
            title = stringResource(R.string.settings_system_notifications),
            summary = stringResource(R.string.settings_system_notifications_effect),
            trailing = Glyphs.OpenInNew,
            onClick = { openNotificationSettings(context) },
        )
    }
}

@Composable
private fun InstallingSection(s: Settings, vm: SettingsViewModel, update: Update) {
    SectionCard(title = stringResource(R.string.settings_installing)) {
        InstallerRows(s, update)
        if (s.installer != InstallerMode.OTHER_APP) InstallPermissionRow()
        if (vm.canDowngrade || s.allowDowngrades) {
            SwitchRow(
                title = stringResource(R.string.settings_allow_downgrades),
                summary = stringResource(R.string.settings_allow_downgrades_effect),
                checked = s.allowDowngrades,
                onChange = { v -> update { it.copy(allowDowngrades = v) } },
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_keep_installers),
            summary = stringResource(R.string.settings_keep_installers_effect),
            checked = s.keepInstallers,
            onChange = { v -> update { it.copy(keepInstallers = v) } },
        )
        if (ownershipSupported()) {
            SwitchRow(
                title = stringResource(R.string.settings_ownership),
                summary = stringResource(R.string.settings_ownership_effect),
                checked = s.claimUpdateOwnership,
                onChange = { v -> update { it.copy(claimUpdateOwnership = v) } },
            )
        }
    }
}

@Composable
fun installerLabel(mode: InstallerMode): String = stringResource(
    when (mode) {
        InstallerMode.SYSTEM -> R.string.installer_system
        InstallerMode.SHIZUKU -> R.string.installer_shizuku
        InstallerMode.ROOT -> R.string.installer_root
        InstallerMode.OTHER_APP -> R.string.installer_other_app
    },
)

@Composable
private fun installerEffect(mode: InstallerMode): String = stringResource(
    when (mode) {
        InstallerMode.SYSTEM -> R.string.installer_system_effect
        InstallerMode.SHIZUKU -> R.string.installer_shizuku_effect
        InstallerMode.ROOT -> R.string.installer_root_effect
        InstallerMode.OTHER_APP -> R.string.installer_other_app_effect
    },
)

/** What stands in the way of the chosen installer, and so why Android's own is used meanwhile. Null when nothing does. */
fun readinessWords(readiness: InstallerReadiness): Int? = when (readiness) {
    InstallerReadiness.READY -> null
    InstallerReadiness.SHIZUKU_NOT_RUNNING -> R.string.installer_shizuku_not_running
    InstallerReadiness.SHIZUKU_TOO_OLD -> R.string.installer_shizuku_too_old
    InstallerReadiness.SHIZUKU_NOT_ALLOWED -> R.string.installer_shizuku_not_allowed
    InstallerReadiness.NO_ROOT -> R.string.installer_no_root
    InstallerReadiness.NO_OTHER_APP -> R.string.installer_no_other_app
}

/**
 * The installer, how it stands, and what it takes to use it. Shizuku and root install without a
 * prompt; another app always asks. Whichever it is, the file passed the same checks before.
 */
@Composable
private fun InstallerRows(s: Settings, update: Update) {
    val engine = LocalEngine.current
    val readiness by engine.installerReadiness.collectAsStateWithLifecycle()
    val status = MaterialTheme.status
    ChoiceRow(
        title = stringResource(R.string.settings_installer),
        options = InstallerMode.entries,
        selected = s.installer,
        label = { installerLabel(it) },
        summary = installerEffect(s.installer),
        onSelect = { mode -> update { it.copy(installer = mode) } },
    )
    LifecycleResumeEffect(engine, s.installer) {
        if (s.installer != InstallerMode.SYSTEM) engine.recheckInstaller()
        onPauseOrDispose { }
    }
    if (s.installer == InstallerMode.SYSTEM) return
    val problem = readinessWords(readiness)
    ActionRow(
        title = stringResource(if (problem == null) R.string.installer_ready else R.string.installer_not_ready),
        summary = problem?.let { stringResource(it) },
        trailing = if (problem == null) Glyphs.Check else Glyphs.Caution,
        iconTint = if (problem == null) status.verified.color else status.caution.color,
        onClick = {
            when (readiness) {
                InstallerReadiness.SHIZUKU_NOT_ALLOWED -> if (!engine.askShizuku()) engine.recheckInstaller()
                else -> engine.recheckInstaller()
            }
        },
        modifier = Modifier.testTag(INSTALLER_STATUS_TAG),
    )
    when (s.installer) {
        InstallerMode.OTHER_APP -> OtherInstallerRow(s, update)
        InstallerMode.SHIZUKU, InstallerMode.ROOT -> SwitchRow(
            title = stringResource(R.string.installer_play),
            summary = stringResource(R.string.installer_play_effect),
            checked = s.playInstaller,
            onChange = { v -> update { it.copy(playInstaller = v) } },
        )
        InstallerMode.SYSTEM -> Unit
    }
}

@Composable
private fun OtherInstallerRow(s: Settings, update: Update) {
    val engine = LocalEngine.current
    val choices = remember(engine) { engine.installerChoices() }
    if (choices.isEmpty()) {
        InfoRow(stringResource(R.string.installer_pick_app), stringResource(R.string.installer_no_apps))
        return
    }
    val none = InstallerChoice("", "")
    val picked = choices.firstOrNull { it.packageName == s.otherInstaller } ?: none
    val noneLabel = stringResource(R.string.installer_pick_app_none)
    ChoiceRow(
        title = stringResource(R.string.installer_pick_app),
        options = choices,
        selected = picked,
        label = { if (it === none) noneLabel else it.label },
        onSelect = { choice -> update { it.copy(otherInstaller = choice.packageName) } },
    )
}

/** Says whether Android lets Tern install apps, and leads to the switch: the settings page where it can be opened, the way to it in words where not. */
@Composable
private fun InstallPermissionRow() {
    val engine = LocalEngine.current
    val context = LocalContext.current
    val canOpen = remember(engine) { engine.canOpenInstallSettings() }
    var allowed by remember { mutableStateOf(engine.mayInstall()) }
    var asking by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(engine) {
        allowed = engine.mayInstall()
        onPauseOrDispose { }
    }
    val status = MaterialTheme.status
    ActionRow(
        title = stringResource(R.string.permit_row),
        summary = stringResource(if (allowed) R.string.permit_row_given else R.string.permit_row_missing),
        trailing = if (allowed) Glyphs.Check else Glyphs.Caution,
        iconTint = if (allowed) status.verified.color else status.caution.color,
        onClick = {
            if (allowed && canOpen) {
                try {
                    context.startActivity(installSettingsIntent(context))
                } catch (_: ActivityNotFoundException) {
                    asking = true
                }
            } else {
                asking = true
            }
        },
        modifier = Modifier.testTag(PERMIT_ROW_TAG),
    )
    if (asking) {
        val answered = {
            asking = false
            allowed = engine.mayInstall()
        }
        InstallPermissionQuestion(canOpenSettings = canOpen, onReturned = answered, onDone = answered, onDismiss = { asking = false })
    }
}

@Composable
private fun TokensSection(vm: SettingsViewModel) {
    val hosts by vm.tokenHosts.collectAsStateWithLifecycle()
    val actions = rememberActions()
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val failed = stringResource(R.string.save_failed)
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var badHost by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.settings_tokens)) {
        ReadBlock {
            Text(stringResource(R.string.settings_tokens_effect), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        for (h in hosts.orEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(look.gap),
                modifier = Modifier.padding(start = look.cardPadding, end = look.focusRoom),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(look.glyph))
                Column(Modifier.weight(1f)) {
                    Text(ltr(h), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.token_saved), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(look.touchTarget)
                        .focusLook(MaterialTheme.shapes.extraLarge)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .clickable(role = Role.Button, onClick = { vm.setToken(h, null) { actions.say(failed) } }),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.token_remove, h), modifier = Modifier.size(look.glyph))
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
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.take(4096) },
                label = { Text(stringResource(R.string.token_value)) },
                supportingText = { Text(stringResource(R.string.token_value_help)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
            TonalButton(
                stringResource(R.string.token_save),
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
            )
        }
    }
}

@Composable
private fun NetworkSection(s: Settings, update: Update, onGetOrbot: () -> Unit, orbotFocus: Modifier) {
    SectionCard(title = stringResource(R.string.settings_network)) {
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
        if (s.proxy == ProxyMode.ORBOT) OrbotRow(onGetOrbot, orbotFocus)
        if (s.proxy == ProxyMode.CUSTOM) CustomProxy(s, update)
    }
}

/** What there is to say about each state of Orbot: the sentence, and the action when there is one. */
data class OrbotWords(val sentence: Int, val action: Int?)

fun orbotWords(state: OrbotState): OrbotWords = when (state) {
    OrbotState.NOT_INSTALLED -> OrbotWords(R.string.orbot_not_installed, R.string.orbot_get)
    OrbotState.OFF -> OrbotWords(R.string.orbot_off, R.string.orbot_open)
    OrbotState.STARTING -> OrbotWords(R.string.orbot_starting, null)
    OrbotState.ON -> OrbotWords(R.string.orbot_on, null)
    OrbotState.UNKNOWN -> OrbotWords(R.string.orbot_unknown, R.string.orbot_ask_again)
}

@Composable
private fun OrbotRow(onGet: () -> Unit, focus: Modifier) {
    val engine = LocalEngine.current
    val state by engine.orbot.collectAsStateWithLifecycle()
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val status = MaterialTheme.status
    LifecycleResumeEffect(engine) {
        engine.askOrbot()
        onPauseOrDispose { }
    }
    val words = orbotWords(state)
    val (glyph, tint) = when (state) {
        OrbotState.ON -> Glyphs.Check to status.verified.color
        OrbotState.NOT_INSTALLED, OrbotState.OFF -> Glyphs.Caution to status.caution.color
        OrbotState.STARTING -> Glyphs.Busy to scheme.onSurfaceVariant
        OrbotState.UNKNOWN -> Glyphs.Unknown to scheme.onSurfaceVariant
    }
    val stacked = LocalDensity.current.fontScale >= STACK_FONT_SCALE
    val action: @Composable () -> Unit = {
        words.action?.let {
            TonalButton(
                stringResource(it),
                onClick = {
                    when (state) {
                        OrbotState.NOT_INSTALLED -> onGet()
                        OrbotState.OFF -> if (!engine.openOrbot()) engine.askOrbot()
                        else -> engine.askOrbot()
                    }
                },
                modifier = focus,
            )
        }
    }
    Column(Modifier.testTag(ORBOT_TAG)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(look.gap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = look.rowPaddingHorizontal, end = look.focusRoom * 2, top = look.rowPaddingVertical, bottom = look.rowPaddingVertical),
        ) {
            Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(look.glyph))
            Text(
                stringResource(words.sentence),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!stacked) action()
        }
        if (stacked && words.action != null) {
            Box(Modifier.padding(start = look.rowPaddingHorizontal + look.glyph + look.gap, bottom = look.gapSmall)) { action() }
        }
        ReadBlock {
            Text(stringResource(R.string.orbot_note), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CustomProxy(s: Settings, update: Update) {
    val look = LocalLook.current
    var host by remember(s.proxyHost) { mutableStateOf(s.proxyHost) }
    var port by remember(s.proxyPort) { mutableStateOf(s.proxyPort.toString()) }
    val hostOk = isValidProxyHost(host)
    val portValue = parsePort(port)
    Column(
        verticalArrangement = Arrangement.spacedBy(look.gapSmall),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.take(253) },
            label = { Text(stringResource(R.string.proxy_host)) },
            isError = !hostOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().textFieldKeys(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.proxy_port)) },
            supportingText = { if (portValue == null) Text(stringResource(R.string.proxy_port_invalid)) },
            isError = portValue == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().textFieldKeys(),
        )
        val dirty = host.trim() != s.proxyHost || portValue != s.proxyPort
        if (dirty) {
            TonalButton(
                stringResource(R.string.action_save),
                onClick = { update { it.copy(proxyHost = host.trim(), proxyPort = portValue ?: it.proxyPort) } },
                enabled = hostOk && portValue != null,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun AppearanceSection(s: Settings, update: Update, onLook: () -> Unit, lookFocus: Modifier) {
    SectionCard(title = stringResource(R.string.settings_appearance)) {
        ActionRow(
            title = stringResource(R.string.look_title),
            summary = stringResource(R.string.look_summary),
            icon = Glyphs.Drop,
            onClick = onLook,
            modifier = lookFocus,
        )
        LanguageRow()
        if (!LocalNoTouch.current) {
            SwitchRow(
                title = stringResource(R.string.settings_swipe),
                summary = stringResource(R.string.settings_swipe_effect),
                checked = s.swipeActions,
                onChange = { v -> update { it.copy(swipeActions = v) } },
            )
            SwitchRow(
                title = stringResource(R.string.settings_haptics),
                summary = stringResource(R.string.settings_haptics_effect),
                checked = s.haptics,
                onChange = { v -> update { it.copy(haptics = v) } },
            )
        }
        CategoriesRow(s, update)
        SwitchRow(
            title = stringResource(R.string.settings_collapse_groups),
            checked = s.collapseGroups,
            onChange = { v -> update { it.copy(collapseGroups = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_phone_layout),
            summary = stringResource(R.string.settings_phone_layout_effect),
            checked = s.phoneLayout,
            onChange = { v -> update { it.copy(phoneLayout = v) } },
        )
    }
}

/** Android keeps the language of each app itself from version 13 on; before that the app follows the device. */
@Composable
private fun LanguageRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val actions = rememberActions()
    val failed = stringResource(R.string.action_failed)
    ActionRow(
        title = stringResource(R.string.settings_language),
        summary = stringResource(R.string.settings_language_effect),
        onClick = {
            try {
                context.startActivity(Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")))
            } catch (_: ActivityNotFoundException) {
                actions.say(failed)
            }
        },
    )
}

@Composable
private fun DataSection(s: Settings, vm: SettingsViewModel, update: Update, onImport: () -> Unit, importFocus: Modifier) {
    val engine = LocalEngine.current
    val context = LocalContext.current
    val actions = rememberActions()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val exportFailed = stringResource(R.string.export_failed)
    val resources = context.resources
    val hasPicker = remember(engine) { engine.hasFilePicker() }
    var outcome by rememberSaveable { mutableStateOf<String?>(null) }
    val done: (Int?) -> Unit = { count -> actions.say(if (count == null) exportFailed else resources.getQuantityString(R.plurals.exported, count, count)) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, onDone = done)
    }
    val obtainiumExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, ExportFormat.OBTAINIUM, done)
    }
    SectionCard(title = stringResource(R.string.settings_data)) {
        ActionRow(
            title = stringResource(R.string.settings_import),
            summary = stringResource(R.string.settings_import_effect),
            onClick = onImport,
            modifier = importFocus,
        )
        ActionRow(
            title = stringResource(
                when {
                    exporting -> R.string.settings_exporting
                    hasPicker -> R.string.settings_export
                    else -> R.string.door_export
                },
            ),
            summary = outcome ?: stringResource(R.string.settings_export_effect),
            onClick = {
                when {
                    exporting -> Unit
                    hasPicker -> exporter.launch("tern-apps.json")
                    else -> vm.exportToFolder { saved, problem ->
                        outcome = when {
                            saved != null -> resources.getString(R.string.door_export_done, ltr(saved.name.take(MAX_SHOWN)), ltr(saved.place.take(MAX_SHOWN)))
                            else -> problem ?: exportFailed
                        }
                    }
                }
            },
            modifier = Modifier
                .testTag(EXPORT_ROW_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (hasPicker) {
            ActionRow(
                title = stringResource(R.string.settings_export_obtainium),
                summary = stringResource(R.string.settings_export_obtainium_effect),
                onClick = { if (!exporting) obtainiumExporter.launch("obtainium-export.json") },
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_export_installed_only),
            checked = s.exportInstalledOnly,
            onChange = { v -> update { it.copy(exportInstalledOnly = v) } },
        )
        SwitchRow(
            title = stringResource(R.string.settings_export_settings),
            summary = stringResource(R.string.settings_export_settings_effect),
            checked = s.exportSettings,
            onChange = { v -> update { it.copy(exportSettings = v) } },
        )
        KeptExport(s, vm, update, hasPicker)
        SwitchRow(
            title = stringResource(R.string.settings_obtainium_links),
            summary = stringResource(R.string.settings_obtainium_links_effect),
            checked = s.openObtainiumLinks,
            onChange = { v -> update { it.copy(openObtainiumLinks = v) } },
        )
    }
}

private const val MAX_SHOWN = 120

/** Where the kept export goes when no folder was picked; the engine writes it there through Android's Downloads. */
private const val DOWNLOAD_PLACE = "Download/Tern"

/**
 * The export that keeps itself up to date. Once it is on, the person may pick the folder, one a
 * cloud app or a card may hold, and the row says when it was last written or why it was not.
 */
@Composable
private fun KeptExport(s: Settings, vm: SettingsViewModel, update: Update, hasPicker: Boolean) {
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val status by vm.exportStatus.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        if (folder != null) vm.keepIn(folder) { actions.say(failed) }
    }
    val current = status
    SwitchRow(
        title = stringResource(R.string.settings_kept_export),
        summary = when {
            !s.autoExport -> stringResource(R.string.settings_kept_export_effect)
            current?.problem != null -> current.problem
            current?.writtenAtMs != null -> stringResource(R.string.settings_kept_export_written, formatTime(current.writtenAtMs))
            else -> stringResource(R.string.settings_kept_export_effect)
        },
        checked = s.autoExport,
        onChange = { v -> update { it.copy(autoExport = v) } },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (s.autoExport && hasPicker) {
        ActionRow(
            title = stringResource(R.string.settings_kept_export_folder),
            summary = ltr(folderName(s.exportFolder) ?: DOWNLOAD_PLACE),
            onClick = { picker.launch(s.exportFolder?.let(Uri::parse)) },
        )
    }
}

/** The picked folder as a person knows it, "Documents/Backups" rather than its content address. */
fun folderName(folder: String?): String? {
    if (folder == null) return null
    val encoded = folder.substringAfter("/tree/", "").substringBefore('/')
    val id = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val path = id.substringAfter(':', id).trim('/')
    return (path.ifEmpty { id.substringBefore(':') }).take(MAX_SHOWN)
}

@Composable
private fun AboutSection() {
    var link by remember { mutableStateOf<String?>(null) }
    SectionCard(title = stringResource(R.string.settings_about)) {
        InfoRow(title = stringResource(R.string.about_version), value = BuildConfig.VERSION_NAME)
        InfoRow(title = stringResource(R.string.about_licence), value = stringResource(R.string.about_licence_name))
        ActionRow(
            title = stringResource(R.string.about_source),
            summary = SOURCE_URL,
            trailing = Glyphs.OpenInNew,
            onClick = { link = SOURCE_URL },
        )
    }
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
}
