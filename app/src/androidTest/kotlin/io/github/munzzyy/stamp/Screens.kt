package io.github.munzzyy.stamp

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.munzzyy.stamp.fake.FakeEngine
import io.github.munzzyy.stamp.ui.EXTRA_SCENARIO
import io.github.munzzyy.stamp.ui.apps.APP_LIST_TAG

val appContext: Context get() = ApplicationProvider.getApplicationContext()

val fake: FakeEngine get() = appContext.engine as FakeEngine

fun mainIntent(scenario: String): Intent =
    Intent(appContext, MainActivity::class.java).putExtra(EXTRA_SCENARIO, scenario)

/** Loads a scenario with short delays so flows finish in seconds, then opens the app. */
fun launch(scenario: String, intent: Intent = mainIntent(scenario)): ActivityScenario<MainActivity> {
    fake.stepMs = 25
    fake.detectDelayMs = 150
    return ActivityScenario.launch(intent)
}

fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) {
    waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 10_000) = waitFor(hasText(text, substring = true), timeoutMs)

fun ComposeTestRule.row(name: String): SemanticsNodeInteraction = onNode(hasContentDescription("$name.", substring = true))

/** Scrolls the app list until the row is on screen, then returns it. */
fun ComposeTestRule.shownRow(name: String): SemanticsNodeInteraction {
    onNodeWithTag(APP_LIST_TAG).performScrollToNode(hasContentDescription("$name.", substring = true))
    return row(name)
}

fun ComposeTestRule.tagged(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag)

fun ComposeTestRule.textCount(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size

/** Like assertIsDisplayed, but a failure says where the node and the focus were, which is what a layout bug needs. */
fun ComposeTestRule.assertShown(text: String) {
    val node = onNodeWithText(text)
    try {
        node.assertIsDisplayed()
    } catch (e: AssertionError) {
        val found = onAllNodes(hasText(text)).fetchSemanticsNodes()
        val focused = onAllNodes(isFocused()).fetchSemanticsNodes()
        val where = found.joinToString { "at ${it.boundsInWindow} of root ${(it.root as? ViewRootForTest)?.view?.let { v -> v.width to v.height }}, placed=${it.layoutInfo.isPlaced}" }
        val focus = focused.joinToString { "${it.config.getOrNull(SemanticsProperties.Text) ?: it.config.getOrNull(SemanticsProperties.ContentDescription)} at ${it.boundsInWindow}" }
        throw AssertionError("\"$text\" is not shown: $where; focus on: $focus; font scale ${appContext.resources.configuration.fontScale}", e)
    }
}

