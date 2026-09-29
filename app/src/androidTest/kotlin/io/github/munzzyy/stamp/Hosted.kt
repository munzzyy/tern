package io.github.munzzyy.stamp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.munzzyy.stamp.ui.LocalActionScope
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.ScrollToShow
import io.github.munzzyy.stamp.ui.common.LocalNoTouch
import io.github.munzzyy.stamp.ui.common.focusHighlight
import io.github.munzzyy.stamp.ui.look.isCut
import io.github.munzzyy.stamp.ui.theme.StampTheme

typealias HostRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * One screen by itself over the stand-in engine, at the text size and in the direction a test
 * asks for. [television] gives it the sizes and the type of a television on any device.
 */
@OptIn(ExperimentalFoundationApi::class)
fun HostRule.host(
    fontScale: Float = 1f,
    direction: LayoutDirection = LayoutDirection.Ltr,
    television: Boolean = false,
    content: @Composable () -> Unit,
) {
    setContent {
        val settings by fake.settings.collectAsState()
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalEngine provides fake,
            LocalActionScope provides rememberCoroutineScope(),
            LocalDensity provides Density(density.density, fontScale),
            LocalLayoutDirection provides direction,
            LocalNoTouch provides television,
            LocalBringIntoViewSpec provides ScrollToShow(0f),
        ) {
            StampTheme(settings, television = television) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = androidx.compose.ui.Modifier.focusHighlight()) { content() }
            }
        }
    }
}

/** Every text on screen that lost its end, except what is typed into a field, which moves sideways instead. */
fun ComposeTestRule.cutWords(): List<String> {
    val drawsText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
    return onAllNodes(drawsText and !hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.firstOrNull() ?: return@mapNotNull null
        if (layout.isCut()) "${layout.layoutInput.text.text} in ${layout.size}, ${layout.lineCount} lines" else null
    }
}
