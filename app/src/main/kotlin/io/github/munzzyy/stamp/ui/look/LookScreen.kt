package io.github.munzzyy.stamp.ui.look

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.engine.ColorSource
import io.github.munzzyy.stamp.engine.Contrast
import io.github.munzzyy.stamp.engine.Corners
import io.github.munzzyy.stamp.engine.Density
import io.github.munzzyy.stamp.engine.Engine
import io.github.munzzyy.stamp.engine.IconShape
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.engine.ThemeMode
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.LocalSnackbar
import io.github.munzzyy.stamp.ui.common.ChoiceChips
import io.github.munzzyy.stamp.ui.common.ScreenTop
import io.github.munzzyy.stamp.ui.common.SectionCard
import io.github.munzzyy.stamp.ui.common.SwitchRow
import io.github.munzzyy.stamp.ui.common.firstFocus
import io.github.munzzyy.stamp.ui.common.rememberActions
import io.github.munzzyy.stamp.ui.common.rememberScreenFocus
import io.github.munzzyy.stamp.ui.theme.CUSTOM_STRENGTH
import io.github.munzzyy.stamp.ui.theme.LocalLook
import io.github.munzzyy.stamp.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LookViewModel(private val engine: Engine) : ViewModel() {
    val settings = engine.settings

    /** [change] is given the settings as they are stored, so nothing stored in the meantime is lost. */
    fun update(onFailed: () -> Unit, change: (Settings) -> Settings) {
        val next = change(settings.value)
        if (next == settings.value) return
        viewModelScope.launch {
            try {
                engine.saveSettings(next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailed()
            }
        }
    }
}

@Composable
fun LookScreen(onBack: () -> Unit) {
    val engine = LocalEngine.current
    val vm = viewModel(key = "look") { LookViewModel(engine) }
    val stored by vm.settings.collectAsStateWithLifecycle()
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    var draft by remember { mutableStateOf<Settings?>(null) }
    val shown = draft ?: stored
    LaunchedEffect(stored) { draft = null }
    val change: ((Settings) -> Settings) -> Unit = { edit ->
        draft = edit(shown)
        vm.update(
            onFailed = {
                draft = null
                actions.say(failed)
            },
            change = edit,
        )
    }
    val screen = rememberScreenFocus()
    val look = LocalLook.current

    Scaffold(
        topBar = { ScreenTop(stringResource(R.string.look_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val sideBySide = maxWidth >= look.contentMaxWidth * SIDE_BY_SIDE
            val pinned = maxHeight >= look.rowHeight * PINNED_ROWS
            val preview: @Composable (Modifier) -> Unit = { place ->
                LookPreview(shown, place.focusProperties { canFocus = false })
            }
            val choices: @Composable () -> Unit = {
                Choices(
                    settings = shown,
                    onChange = change,
                    onHue = { hue -> draft = shown.copy(colorSource = ColorSource.CUSTOM, customHue = hue) },
                    modifier = Modifier.firstFocus(screen),
                )
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(look.gap)) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = look.screenPadding, bottom = look.gapSection),
                    ) { choices() }
                    Box(Modifier.width(look.contentMaxWidth * PREVIEW_SHARE).padding(end = look.screenPadding)) { preview(Modifier) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    val beside = Modifier.padding(horizontal = look.screenPadding).padding(bottom = look.gap)
                    if (pinned) preview(beside)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = look.screenPadding)
                            .padding(bottom = look.gapSection),
                    ) {
                        if (!pinned) preview(Modifier.padding(bottom = look.gap))
                        choices()
                    }
                }
            }
        }
    }
}

private const val SIDE_BY_SIDE = 0.85f
private const val PREVIEW_SHARE = 0.45f
private const val PINNED_ROWS = 7

@Composable
private fun Choices(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onHue: (Int) -> Unit, modifier: Modifier) {
    val look = LocalLook.current
    val dark = isDark(settings)
    Column(modifier.widthIn(max = look.contentMaxWidth), verticalArrangement = Arrangement.spacedBy(look.gap)) {
        SectionCard(title = stringResource(R.string.look_colours)) {
            ChoiceChips(
                title = stringResource(R.string.settings_theme),
                options = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                selected = ThemeMode.entries.indexOf(settings.theme),
                onSelect = { picked -> onChange { it.copy(theme = ThemeMode.entries[picked]) } },
            )
            Swatches(settings, dark, onChange)
            if (settings.colorSource == ColorSource.CUSTOM) {
                HueSlider(
                    hue = settings.customHue,
                    colorOf = remember(settings.contrast, dark) { { hue -> accentOf(hue % 360, CUSTOM_STRENGTH, settings, dark) } },
                    onChange = onHue,
                    onSettled = { hue -> onChange { it.copy(colorSource = ColorSource.CUSTOM, customHue = hue) } },
                )
            }
            ChoiceChips(
                title = stringResource(R.string.look_contrast),
                options = listOf(stringResource(R.string.contrast_standard), stringResource(R.string.contrast_medium), stringResource(R.string.contrast_high)),
                selected = Contrast.entries.indexOf(settings.contrast),
                onSelect = { picked -> onChange { it.copy(contrast = Contrast.entries[picked]) } },
                summary = stringResource(R.string.look_contrast_effect),
            )
            SwitchRow(
                title = stringResource(R.string.settings_pure_black),
                summary = stringResource(R.string.settings_pure_black_effect),
                checked = settings.pureBlack,
                onChange = { on -> onChange { it.copy(pureBlack = on) } },
            )
        }
        SectionCard(title = stringResource(R.string.look_shape_and_size)) {
            ChoiceChips(
                title = stringResource(R.string.look_density),
                options = listOf(stringResource(R.string.density_comfortable), stringResource(R.string.density_compact)),
                selected = Density.entries.indexOf(settings.density),
                onSelect = { picked -> onChange { it.copy(density = Density.entries[picked]) } },
                summary = stringResource(R.string.look_density_effect),
            )
            ChoiceChips(
                title = stringResource(R.string.look_corners),
                options = listOf(stringResource(R.string.corners_round), stringResource(R.string.corners_soft), stringResource(R.string.corners_sharp)),
                selected = Corners.entries.indexOf(settings.corners),
                onSelect = { picked -> onChange { it.copy(corners = Corners.entries[picked]) } },
            )
            ChoiceChips(
                title = stringResource(R.string.look_icon_shape),
                options = listOf(
                    stringResource(R.string.icon_shape_circle),
                    stringResource(R.string.icon_shape_squircle),
                    stringResource(R.string.icon_shape_square),
                ),
                selected = IconShape.entries.indexOf(settings.iconShape),
                onSelect = { picked -> onChange { it.copy(iconShape = IconShape.entries[picked]) } },
            )
        }
        SectionCard(title = stringResource(R.string.look_icons)) {
            SwitchRow(
                title = stringResource(R.string.look_source_icons),
                summary = stringResource(R.string.look_source_icons_effect),
                checked = settings.sourceIcons,
                onChange = { on -> onChange { it.copy(sourceIcons = on) } },
            )
        }
    }
}
