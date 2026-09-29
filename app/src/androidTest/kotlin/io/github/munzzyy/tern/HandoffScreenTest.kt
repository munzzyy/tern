package io.github.munzzyy.tern

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.HandoffEnd
import io.github.munzzyy.tern.engine.Received
import io.github.munzzyy.tern.fake.FakeEngine
import io.github.munzzyy.tern.fake.FakeLinks
import io.github.munzzyy.tern.ui.EXTRA_SCENARIO
import io.github.munzzyy.tern.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.tern.ui.add.ADD_HANDOFF_TAG
import io.github.munzzyy.tern.ui.add.PREVIEW_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_ADDRESS_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_ARRIVALS_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_CODE_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_OPEN_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_QR_TAG
import io.github.munzzyy.tern.ui.handoff.HANDOFF_TIME_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The handoff screen over the stand-in engine, which plays the phone. */
@RunWith(AndroidJUnit4::class)
class HandoffScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val inArrivals = hasAnyAncestor(hasTestTag(HANDOFF_ARRIVALS_TAG))

    private fun names() = fake.apps.value.map { it.config.name }

    private fun openHandoff() {
        compose.onNodeWithText("Add").performClick()
        compose.tagged(ADD_HANDOFF_TAG).performScrollTo().performClick()
        compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
    }

    private fun closed() = compose.waitUntil(5_000) { fake.handoff.value == null }

    @Test
    fun theScreenOpensTheHandoffAndShowsItsCodeItsAddressAndTheTimeLeft() {
        launch(FakeEngine.BARE).use {
            assertNull(fake.handoff.value)
            openHandoff()
            val handoff = fake.handoff.value!!
            compose.tagged(HANDOFF_QR_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(HANDOFF_ADDRESS_TAG, useUnmergedTree = true).performScrollTo().assertTextContains(handoff.address, substring = true)
            val code = compose.tagged(HANDOFF_CODE_TAG).performScrollTo()
            for (group in handoff.code.split(' ')) code.assert(hasText(group))
            code.assert(hasContentDescription("k 4 m z, q 7 w d", substring = true))
            compose.tagged(HANDOFF_TIME_TAG).assertIsDisplayed()
            compose.onNodeWithText("Open for 10 more minutes").assertIsDisplayed()
            for (step in listOf("1. Put the phone on the same network", "2. Scan the code", "3. Paste links or pick your export file")) {
                compose.onNodeWithText(step, substring = true).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("Nothing that arrives is added until you have looked at it here.").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun whatThePhoneSendsIsListedAndALinkIsOnlyLookedAt() {
        launch(FakeEngine.BARE).use {
            val before = names()
            openHandoff()
            fake.receive(Received.Link(FakeLinks.WARNED_APP))
            fake.receive(Received.ExportFile("my-apps.json", ByteArray(2_048)))
            compose.waitFor(hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals)
            compose.waitFor(hasText("my-apps.json", substring = true) and inArrivals)
            compose.onNodeWithText("Arrived from the phone").performScrollTo().assertIsDisplayed()
            assertEquals("what arrives is added to nothing", before, names())
            assertEquals("what is listed was taken from the engine", 0, fake.handoff.value!!.waiting)

            compose.onNode(hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals).performScrollTo().performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.tagged(ADD_FIELD_TAG).assertTextContains(FakeLinks.WARNED_APP)
            compose.onNode(hasText("Wren") and hasAnyAncestor(hasTestTag(PREVIEW_TAG))).assertIsDisplayed()
            assertEquals("looking adds nothing", before, names())
            closed()

            device.pressBack()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            val row = hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals
            compose.waitFor(row)
            compose.onNode(row and hasText("Looked at")).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("my-apps.json", substring = true) and inArrivals).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aFileThatArrivedIsImportedOnlyWhenTheUserSaysSo() {
        launch(FakeEngine.BARE).use {
            val before = names()
            openHandoff()
            fake.receive(Received.ExportFile("my-apps.json", ByteArray(2_048)))
            val row = hasText("my-apps.json", substring = true) and inArrivals
            compose.waitFor(row)
            compose.waitForIdle()
            assertEquals(before, names())

            compose.onNode(row).performScrollTo().performClick()
            compose.waitFor(hasText("Import finished") and inArrivals)
            compose.onNode(hasText("3 apps added") and inArrivals).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("came with settings that somebody else chose", substring = true) and inArrivals).performScrollTo().assertIsDisplayed()
            assertEquals(before + listOf("Heron Books", "Plover Chat", "Rook Budget"), (before + names()).distinct())
        }
    }

    @Test
    fun leavingTheScreenClosesTheHandoff() {
        launch(FakeEngine.BARE).use {
            openHandoff()
            assertNotNull(fake.handoff.value)
            device.pressBack()
            closed()
            assertEquals(HandoffEnd.CLOSED, fake.handoffEnd.value)
            compose.onNodeWithText("Add an app").assertIsDisplayed()

            compose.tagged(ADD_HANDOFF_TAG).performScrollTo().performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            assertNotNull(fake.handoff.value)
            compose.onNodeWithText("Settings").performClick()
            closed()

            compose.onNodeWithText("Add").performClick()
            compose.tagged(ADD_HANDOFF_TAG).performScrollTo().performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.onNode(hasContentDescription("Back")).performClick()
            closed()
        }
    }

    @Test
    fun turningTheDeviceLeavesTheHandoffOpen() {
        launch(FakeEngine.BARE).use { scenario ->
            openHandoff()
            val handoff = fake.handoff.value
            // The intent that loaded the scenario would load it again, and that closes every handoff.
            scenario.onActivity { it.intent.removeExtra(EXTRA_SCENARIO) }
            scenario.recreate()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.waitForIdle()
            assertEquals(handoff?.address, fake.handoff.value?.address)
            assertEquals(handoff?.closesAtMs, fake.handoff.value?.closesAtMs)
        }
    }

    @Test
    fun theScreenSaysWhyTheHandoffEndedAndOpensItAgain() {
        val sentences = mapOf(
            HandoffEnd.EXPIRED to "The ten minutes are over. Open it again when you need it.",
            HandoffEnd.LEFT_SCREEN to "It closed because Tern left the screen.",
            HandoffEnd.USED_UP to "It closed because it was asked too many times. That does not happen by hand.",
        )
        launch(FakeEngine.BARE).use {
            openHandoff()
            for ((why, sentence) in sentences) {
                val opened = fake.handoff.value!!.closesAtMs
                fake.endHandoff(why)
                compose.waitForText(sentence)
                compose.onNodeWithText(sentence).assertIsDisplayed()
                assertEquals(0, compose.onAllNodes(hasTestTag(HANDOFF_QR_TAG)).fetchSemanticsNodes().size)
                assertEquals(0, compose.onAllNodes(hasTestTag(HANDOFF_TIME_TAG)).fetchSemanticsNodes().size)
                for (other in sentences.values - sentence) assertEquals(0, compose.textCount(other))

                Thread.sleep(5)
                compose.tagged(HANDOFF_OPEN_TAG).assertTextContains("Open again").performClick()
                compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
                assertNotEquals("a new handoff is open", opened, fake.handoff.value!!.closesAtMs)
                assertEquals(0, compose.textCount(sentence))
            }
        }
    }

    @Test
    fun nothingIsSaidAboutAHandoffThatWasClosedFromThisDevice() {
        launch(FakeEngine.BARE).use {
            openHandoff()
            fake.endHandoff(HandoffEnd.CLOSED)
            compose.waitFor(hasTestTag(HANDOFF_OPEN_TAG))
            assertEquals(0, compose.textCount("It closed because", substring = true))
            assertEquals(0, compose.textCount("The ten minutes are over", substring = true))
            compose.tagged(HANDOFF_OPEN_TAG).performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
        }
    }

    @Test
    fun whatArrivedStaysOnTheScreenAfterTheHandoffHasEnded() {
        launch(FakeEngine.BARE).use {
            openHandoff()
            fake.receive(Received.Link(FakeLinks.WARNED_APP))
            val row = hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals
            compose.waitFor(row)
            fake.endHandoff(HandoffEnd.EXPIRED)
            compose.waitForText("The ten minutes are over", 5_000)
            compose.onNode(row).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun whereThePageCannotBeOpenedTheScreenSaysSoInTheEnginesWords() {
        launch(FakeEngine.BARE).use {
            fake.localNetwork = false
            compose.onNodeWithText("Add").performClick()
            compose.tagged(ADD_HANDOFF_TAG).performScrollTo().performClick()
            compose.waitForText("This device is on no local network, so a phone cannot reach it.")
            assertNull(fake.handoff.value)
            assertEquals(0, compose.onAllNodes(hasTestTag(HANDOFF_QR_TAG)).fetchSemanticsNodes().size)

            fake.localNetwork = true
            compose.tagged(HANDOFF_OPEN_TAG).assertTextContains("Try again").performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            assertEquals(0, compose.textCount("no local network", substring = true))
        }
    }

    @Test
    fun theScreenIsKeptOnWhileTheHandoffIsOpenAndNoLonger() {
        launch(FakeEngine.BARE).use { scenario ->
            compose.onNodeWithText("Add").performClick()
            compose.waitForIdle()
            assertTrue("nothing keeps the screen on before", !keptOn(scenario))
            compose.tagged(ADD_HANDOFF_TAG).performScrollTo().performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.waitUntil(3_000) { keptOn(scenario) }

            fake.endHandoff(HandoffEnd.EXPIRED)
            compose.waitUntil(3_000) { !keptOn(scenario) }

            compose.tagged(HANDOFF_OPEN_TAG).performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.waitUntil(3_000) { keptOn(scenario) }
            device.pressBack()
            compose.waitUntil(3_000) { !keptOn(scenario) }
        }
    }

    private fun keptOn(scenario: ActivityScenario<MainActivity>): Boolean {
        var on = false
        scenario.onActivity { on = keepsScreenOn(it.window.decorView) }
        return on
    }

    private fun keepsScreenOn(view: View): Boolean {
        if (view.keepScreenOn) return true
        if (view !is ViewGroup) return false
        return (0 until view.childCount).any { keepsScreenOn(view.getChildAt(it)) }
    }
}
