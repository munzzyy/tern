package io.github.munzzyy.tern

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.ui.activity.ACTIVITY_LIST_TAG
import io.github.munzzyy.tern.ui.activity.ACTIVITY_STEPS_TAG
import io.github.munzzyy.tern.ui.activity.Headline
import io.github.munzzyy.tern.ui.activity.entriesOf
import io.github.munzzyy.tern.ui.activity.stepsToggleTag
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The log shows one entry for one thing that happened, with the steps behind it. */
@RunWith(AndroidJUnit4::class)
class ActivityEntriesTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun touch() = touchAgain()

    private val downloaded = "Downloaded pocketnotes-2.3.1-x86_64.apk (12 MB)."
    private val verified = "Signer matches the installed app. Checksum matched."

    private fun entry(app: String, headline: Headline) = entriesOf(fake.events.value).first { it.outcome.appId == app && it.headline == headline }

    private fun says(words: String) = hasContentDescription(words, substring = true)

    private fun scrollTo(words: String) = compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(says(words))

    /** Whether any entry of the list says [words], wherever in the list it is. */
    private fun listed(words: String): Boolean = try {
        scrollTo(words)
        true
    } catch (_: AssertionError) {
        false
    }

    private fun inSteps(text: String) = hasTestTag(ACTIVITY_STEPS_TAG) and hasText(text)

    @Test
    fun theStepsOfOneUpdateAreOneEntryAndUnfoldWhenAsked() {
        launch("default").use {
            compose.onNodeWithText("Activity").performClick()
            scrollTo("Pocket Notes. Updated to 2.3.1.")
            assertEquals("the steps are folded away", 0, compose.textCount(downloaded))
            assertEquals(0, compose.onAllNodes(says("Pocket Notes. Installed")).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodes(says(downloaded)).fetchSemanticsNodes().size)

            val toggle = stepsToggleTag(entry("pocketnotes", Headline.UPDATED).id)
            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(hasTestTag(toggle))
            compose.tagged(toggle).assertTextEquals("Show steps").performClick()
            compose.waitFor(inSteps(downloaded))
            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(hasTestTag(ACTIVITY_STEPS_TAG))
            for (step in listOf("Version 2.3.1 is available.", downloaded, verified, "Installed version 2.3.1.")) {
                compose.onNode(inSteps(step)).assertIsDisplayed()
            }
            compose.tagged(toggle).assertTextEquals("Hide steps").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(ACTIVITY_STEPS_TAG)).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun anEntryThatIsOneEventHasNoStepsToShow() {
        launch("default").use {
            compose.onNodeWithText("Activity").performClick()
            scrollTo("Trail Map. Update to 1.5.0 waits.")
            val single = entry("trailmap", Headline.UPDATE_WAITS)
            assertEquals(1, single.steps.size)
            assertEquals(0, compose.onAllNodes(hasTestTag(stepsToggleTag(single.id))).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun whatWentWrongSaysWhyWithoutBeingAsked() {
        launch("default").use {
            compose.onNodeWithText("Activity").performClick()
            scrollTo("Ferry Book. Blocked 4.1.0. Blocked: the new file is signed with a different certificate.")
            scrollTo("Ridge Radio. Check failed. GitHub is limiting requests from this network.")
        }
    }

    @Test
    fun anUpdateDoneNowBecomesOneEntry() {
        launch("default").use {
            compose.shownRow("Trail Map").performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitUntil(20_000) { fake.events.value.any { it.appId == "trailmap" && it.kind == EventKind.INSTALLED } }
            val done = entry("trailmap", Headline.UPDATED)
            assertEquals(listOf(EventKind.DOWNLOADED, EventKind.VERIFIED, EventKind.INSTALLED), done.steps.map { it.kind })

            compose.onNodeWithText("Activity").performClick()
            scrollTo("Trail Map. Updated to 1.5.0.")
            assertEquals(1, compose.onAllNodes(says("Trail Map. Updated to")).fetchSemanticsNodes().size)
            assertTrue(compose.onAllNodes(hasTestTag(stepsToggleTag(done.id))).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun problemsOnlyKeepsTheEntriesThatEndedBadly() {
        launch("default").use {
            compose.onNodeWithText("Activity").performClick()
            scrollTo("Pocket Notes. Updated to")
            compose.tagged(ACTIVITY_LIST_TAG).performScrollToNode(hasText("Problems only"))
            compose.onNodeWithText("Problems only").performClick()
            scrollTo("Ferry Book. Blocked")
            scrollTo("Lantern PDF. Could not install")
            val kept = entriesOf(fake.events.value).filter { it.isProblem }
            assertEquals(3, kept.size)
            assertFalse(listed("Pocket Notes. Updated to"))
            assertFalse(listed("waits"))
            assertFalse(listed("Imported 14 apps"))
        }
    }

    @Test
    fun theStepsOpenWithTheRemote() {
        keysOnly()
        launch("default").use {
            compose.openTab("Activity")
            compose.assertFocusOn(hasText("Problems only"), "Activity opens on its filter")
            val toggle = hasTestTag(stepsToggleTag(entry("cinderplayer", Headline.UPDATE_WAITS).id))
            compose.moveTo(toggle, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(inSteps("Version 8.1 is available."))
            compose.assertFocusOn(toggle and hasText("Hide steps"), "focus stays on the control that was pressed")
        }
    }
}
