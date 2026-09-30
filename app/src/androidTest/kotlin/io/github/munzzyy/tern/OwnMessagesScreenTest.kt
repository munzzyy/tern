package io.github.munzzyy.tern

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.ui.activity.ACTIVITY_LIST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tern's own messages in the log: none until the setting is on, then marked as Tern's own, and hidden by their filter. */
@RunWith(AndroidJUnit4::class)
class OwnMessagesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun says(words: String) = hasContentDescription(words, substring = true)

    @Test
    fun aCheckIsInTheLogOnceTheSettingIsOnAndItsFilterHidesIt() {
        launch("default").use {
            runBlocking { fake.check(null) }
            assertTrue("nothing of Tern's own while the setting is off", fake.events.value.none { it.kind == EventKind.OWN_NOTE })
            compose.onNodeWithText("Activity").performClick()
            assertEquals("no filter while there is nothing to filter", 0, compose.textCount("Tern's messages"))

            runBlocking {
                fake.saveSettings(fake.settings.value.copy(keepOwnMessages = true))
                fake.check(null)
            }
            compose.waitFor(hasText("Tern's messages"))
            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(says("Note from Tern. A check of"))

            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(hasText("Tern's messages"))
            compose.onNodeWithText("Tern's messages").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(says("Note from Tern")).fetchSemanticsNodes().isEmpty() }
            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(says("Pocket Notes. Updated to"))
        }
    }
}
