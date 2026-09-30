package io.github.munzzyy.tern

import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.munzzyy.tern.ui.common.lacksTouch
import org.junit.Assert.fail

val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** What a television is: no touch screen, so windows start out of touch mode and keys drive everything. */
fun keysOnly() = InstrumentationRegistry.getInstrumentation().setInTouchMode(false)

fun touchAgain() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)

val withoutTouch: Boolean get() = appContext.lacksTouch()

fun ComposeTestRule.press(vararg keys: Int) {
    for (key in keys) {
        device.pressKeyCode(key)
        waitForIdle()
    }
}

fun ComposeTestRule.focusedLabel(): String {
    val nodes = onAllNodes(isFocused()).fetchSemanticsNodes()
    return nodes.joinToString(" | ") { node ->
        val c = node.config
        c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: c.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: c.getOrNull(SemanticsProperties.EditableText)?.text
            ?: "unnamed"
    }.ifEmpty { "nothing" }
}

fun ComposeTestRule.hasFocusOn(matcher: SemanticsMatcher): Boolean =
    onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()

fun ComposeTestRule.assertFocusOn(matcher: SemanticsMatcher, why: String, timeoutMs: Long = 3_000) =
    expect(why, timeoutMs) { hasFocusOn(matcher) }

val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

/** Where Settings opens under keys: the minus button of how often, which only a device without touch has, or else the slider itself. */
val firstSetting: SemanticsMatcher get() = hasContentDescription(if (withoutTouch) "Check more often" else "How often")

/** Enough presses of down to walk Settings from top to bottom; a phone has more of them than a television. */
const val SETTINGS_STOPS = 60

fun ComposeTestRule.expect(why: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs, condition)
    } catch (_: Throwable) {
        fail("$why: focus is on ${focusedLabel()}")
    }
}

/** Presses [key] until focus reaches [matcher], the way a remote user gets there. */
fun ComposeTestRule.moveTo(matcher: SemanticsMatcher, key: Int, max: Int = 40) {
    repeat(max) {
        if (hasFocusOn(matcher)) return
        press(key)
    }
    if (!hasFocusOn(matcher)) fail("${KeyEvent.keyCodeToString(key)} never reached ${matcher.description}; focus is on ${focusedLabel()}")
}

private val tabs = listOf("Apps", "Add", "Activity", "Settings")

/** Reaches a tab on the rail (wide) or the bottom bar (narrow) with arrow keys only, then opens it. */
fun ComposeTestRule.openTab(label: String) {
    val wide = with(density) { onRoot().fetchSemanticsNode().size.width.toDp() } >= 600.dp
    moveTo(isTab, if (wide) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_DOWN)
    val at = tabs.indexOfFirst { hasFocusOn(isTab and hasText(it)) }
    val want = tabs.indexOf(label)
    val forward = if (wide) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
    val back = if (wide) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
    moveTo(isTab and hasText(label), if (want > at) forward else back)
    press(KeyEvent.KEYCODE_DPAD_CENTER)
}

fun keyboardShown(): Boolean = device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")
