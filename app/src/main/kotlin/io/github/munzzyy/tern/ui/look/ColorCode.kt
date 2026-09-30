package io.github.munzzyy.tern.ui.look

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.engine.ColorSource
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.common.ActionRow
import io.github.munzzyy.tern.ui.common.ColorDot
import io.github.munzzyy.tern.ui.common.focusHighlight
import io.github.munzzyy.tern.ui.common.focusWhenShown
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.text.ltr
import io.github.munzzyy.tern.ui.theme.seedOfColor

/** #RRGGBB for an opaque colour. */
fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** A colour typed as six hexadecimal digits, with or without #; eight digits carry an alpha, which is dropped. */
fun colorOfHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return 0xFF000000.toInt() or (digits.takeLast(6).toInt(16))
}

/**
 * The user's own colour as a code, as Obtainium lets one be typed or pasted. The scheme keeps the
 * code's hue and strength; the table's tones keep every pair readable.
 */
@Composable
fun ColorCodeRow(settings: Settings, dark: Boolean, onChange: ((Settings) -> Settings) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val shown = settings.customColor ?: accentOf(settings.customHue, settings.ownStrength, settings, dark).toArgb()
    ActionRow(
        title = stringResource(R.string.look_color_code),
        summary = ltr(hexOf(shown)),
        onClick = { editing = true },
    )
    if (!editing) return
    var text by rememberSaveable { mutableStateOf(hexOf(shown)) }
    val color = colorOfHex(text)
    AlertDialog(
        modifier = Modifier.focusHighlight(),
        onDismissRequest = { editing = false },
        title = { Text(stringResource(R.string.look_color_code)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(9) },
                singleLine = true,
                isError = color == null,
                leadingIcon = color?.let { { ColorDot(Color(it)) } },
                supportingText = { Text(stringResource(if (color == null) R.string.look_color_code_invalid else R.string.look_color_code_effect)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().textFieldKeys(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (color != null) {
                        val (hue, strength) = seedOfColor(color)
                        onChange { it.copy(colorSource = ColorSource.CUSTOM, customHue = hue, customStrength = strength, customColor = color) }
                    }
                    editing = false
                },
                enabled = color != null,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }, modifier = Modifier.focusWhenShown()) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
