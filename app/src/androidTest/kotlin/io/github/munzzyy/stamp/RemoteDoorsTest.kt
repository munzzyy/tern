package io.github.munzzyy.stamp

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.HandoffEnd
import io.github.munzzyy.stamp.engine.OrbotState
import io.github.munzzyy.stamp.engine.ProxyMode
import io.github.munzzyy.stamp.engine.Received
import io.github.munzzyy.stamp.fake.FakeEngine
import io.github.munzzyy.stamp.fake.FakeLinks
import io.github.munzzyy.stamp.fake.FakeSuggestions
import io.github.munzzyy.stamp.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.stamp.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.stamp.ui.add.ADD_HANDOFF_TAG
import io.github.munzzyy.stamp.ui.add.ADD_INSTALL_TAG
import io.github.munzzyy.stamp.ui.add.PREVIEW_TAG
import io.github.munzzyy.stamp.ui.common.PERMIT_DIALOG_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_ARRIVALS_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_OPEN_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_QR_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_FILES_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_HANDOFF_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_FIELD_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_TAG
import io.github.munzzyy.stamp.ui.settings.EXPORT_ROW_TAG
import io.github.munzzyy.stamp.ui.settings.PERMIT_ROW_TAG
import io.github.munzzyy.stamp.ui.suggest.STARTER_ROW_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The ways to get apps onto a device without typing, with the keys of a remote and nothing else. */
@RunWith(AndroidJUnit4::class)
class RemoteDoorsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val row = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
    private val field = hasTestTag(ADD_FIELD_TAG)
    private val card = hasTestTag(ADD_HANDOFF_TAG)
    private val starter = hasTestTag(STARTER_ROW_TAG)
    private val inArrivals = hasAnyAncestor(hasTestTag(HANDOFF_ARRIVALS_TAG))

    @Before fun keys() = keysOnly()

    @After fun touch() = touchAgain()

    /** A screen places its focus in its first frames. A key that comes before that is a key on the screen before. */
    private fun landed() {
        if (withoutTouch) compose.assertFocusOn(hasText("1. Put the phone", substring = true), "without touch the steps take focus, so that they can be read")
        compose.waitForIdle()
    }

    private fun openAdd() {
        compose.assertFocusOn(row, "the list opens with focus on its first app")
        compose.openTab("Add")
        compose.assertFocusOn(field, "the Add screen opens with focus on its field")
    }

    @Test
    fun aWellKnownAppIsLookedAtAndBackReturnsToItsRow() {
        launch(FakeEngine.BARE).use {
            val before = fake.apps.value.size
            openAdd()
            compose.moveTo(card, KEYCODE_DPAD_DOWN, max = 4)
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(starter and hasText(FakeSuggestions.all[0].name), "down from the card reaches the first well known app")
            compose.press(KEYCODE_DPAD_DOWN)
            val second = starter and hasText(FakeSuggestions.all[1].name)
            compose.assertFocusOn(second, "down moves to the second")

            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.assertFocusOn(hasTestTag(ADD_CONFIRM_TAG), "the preview opens with focus on Add")
            compose.press(KEYCODE_DPAD_RIGHT)
            compose.assertFocusOn(hasTestTag(ADD_INSTALL_TAG), "right moves to Add and install")
            assertEquals(before, fake.apps.value.size)

            device.pressBack()
            compose.assertFocusOn(second, "back returns to the app that was looked at")
            compose.press(KEYCODE_DPAD_UP)
            compose.assertFocusOn(starter and hasText(FakeSuggestions.all[0].name), "and the list can be walked on from there")
        }
    }

    @Test
    fun theWholeListOfWellKnownAppsCanBeWalked() {
        launch(FakeEngine.BARE).use {
            openAdd()
            compose.moveTo(card, KEYCODE_DPAD_DOWN, max = 4)
            for (app in FakeSuggestions.all) {
                compose.press(KEYCODE_DPAD_DOWN)
                compose.assertFocusOn(starter and hasText(app.name), "down reaches ${app.name}")
            }
        }
    }

    @Test
    fun whatArrivesTakesFocusAndEveryArrivalCanBeReached() {
        launch(FakeEngine.BARE).use {
            openAdd()
            compose.moveTo(card, KEYCODE_DPAD_DOWN, max = 4)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            landed()

            fake.receive(Received.Link(FakeLinks.WARNED_APP))
            fake.receive(Received.ExportFile("my-apps.json", ByteArray(2_048)))
            val link = hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals
            val file = hasText("my-apps.json", substring = true) and inArrivals
            compose.assertFocusOn(link, "what arrives first takes focus", 5_000)
            compose.waitFor(file)
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(file, "down moves to the file")

            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasText("Import finished") and inArrivals)
            if (withoutTouch) compose.assertFocusOn(hasText("Import finished", substring = true), "the summary takes the focus the row had", 5_000)

            compose.moveTo(link, KEYCODE_DPAD_UP, max = 12)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.assertFocusOn(hasTestTag(ADD_CONFIRM_TAG), "the preview of a link that arrived opens with focus on Add")
            assertNull(fake.handoff.value)

            device.pressBack()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.assertFocusOn(inArrivals, "back returns to what has arrived", 5_000)

            device.pressBack()
            compose.assertFocusOn(card, "back from the handoff returns to the card that opened it")
        }
    }

    @Test
    fun aHandoffThatHasEndedOpensAgainWithTheMiddleKey() {
        launch(FakeEngine.BARE).use {
            openAdd()
            compose.moveTo(card, KEYCODE_DPAD_DOWN, max = 4)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            fake.endHandoff(HandoffEnd.EXPIRED)
            compose.assertFocusOn(hasTestTag(HANDOFF_OPEN_TAG), "the way to open it again takes the focus that the open handoff held")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            assertNotNull(fake.handoff.value)
        }
    }

    @Test
    fun theThreeDoorsOfImportAreWalked() {
        launch(FakeEngine.BARE).use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Settings")
            val import = hasText("Import apps", substring = true)
            compose.moveTo(import, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            val files = hasTestTag(DOOR_FILES_TAG)
            compose.assertFocusOn(files, "Import opens on its first door")

            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitForText("obtainium-export.json")
            compose.assertFocusOn(files, "opening the list leaves focus on its door")
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasText("stamp-apps-2026-09-29.json", substring = true), "down reaches the newest file")
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasText("obtainium-export.json", substring = true), "and the one after it")
            compose.press(KEYCODE_DPAD_DOWN)
            val link = hasTestTag(DOOR_LINK_TAG)
            compose.assertFocusOn(link, "down from the files reaches the second door")

            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(DOOR_LINK_FIELD_TAG))
            compose.press(KEYCODE_DPAD_DOWN)
            compose.assertFocusOn(hasTestTag(DOOR_LINK_FIELD_TAG), "down from the second door reaches its field")
            if (keyboardShown()) {
                device.pressBack()
                compose.waitUntil(3_000) { !keyboardShown() }
            }
            val phone = hasTestTag(DOOR_HANDOFF_TAG)
            compose.moveTo(phone, KEYCODE_DPAD_DOWN, max = 4)

            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            compose.waitFor(hasText("stamp-apps-2026-09-29.json", substring = true) and inArrivals)
            compose.assertFocusOn(inArrivals, "what arrived has focus")
            device.pressBack()
            compose.assertFocusOn(phone, "back from the handoff returns to the door that opened it")
            device.pressBack()
            compose.assertFocusOn(import, "back from Import returns to the row that opened it")
        }
    }

    @Test
    fun aFileIsImportedWithTheKeysAndFocusIsNotLost() {
        launch(FakeEngine.BARE).use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Settings")
            compose.moveTo(hasText("Import apps", substring = true), KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(hasTestTag(DOOR_FILES_TAG), "Import opens on its first door")
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitForText("obtainium-export.json")
            compose.press(KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitForText("Import finished")
            compose.waitForIdle()
            compose.expect("after the import something on the screen has focus") { compose.focusedLabel() != "nothing" && !compose.hasFocusOn(isTab) }
        }
    }

    @Test
    fun settingsAreWalkedToTheirEndAndFocusIsNeverLost() {
        launch(FakeEngine.BARE).use {
            fake.orbotAnswer = OrbotState.OFF
            runBlocking { fake.saveSettings(fake.settings.value.copy(proxy = ProxyMode.ORBOT)) }
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Settings")
            compose.assertFocusOn(hasText("How often", substring = true), "Settings opens on its first setting")
            val last = hasText("Source code", substring = true)
            val stops = mutableListOf<String>()
            repeat(60) {
                if (compose.hasFocusOn(last)) return@repeat
                compose.press(KEYCODE_DPAD_DOWN)
                val now = compose.focusedLabel()
                assertNotEquals("focus was lost after ${stops.lastOrNull()}", "nothing", now)
                stops += now
            }
            compose.assertFocusOn(last, "down reaches the last row, by way of $stops")
            for (wanted in listOf("Permission to install apps", "Keep downloaded files", "Proxy", "Open Orbot", "Look", "Import apps", "Export to the Download folder", "Open Obtainium links")) {
                assertTrue("down passes $wanted: $stops", stops.any { it.contains(wanted) })
            }
            if (withoutTouch) assertTrue("a television has no rows about notifications: $stops", stops.none { it.contains("When updates are found") })

            compose.moveTo(hasTestTag(EXPORT_ROW_TAG), KEYCODE_DPAD_UP)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.assertFocusOn(hasTestTag(EXPORT_ROW_TAG) and hasText("Saved as", substring = true), "the row that exported says where and keeps focus", 5_000)
        }
    }

    @Test
    fun theQuestionAboutInstallingIsAnsweredWithTheKeys() {
        launch(FakeEngine.BARE).use {
            compose.assertFocusOn(row, "the list opens with focus on its first app")
            compose.openTab("Settings")
            val permit = hasTestTag(PERMIT_ROW_TAG)
            compose.moveTo(permit, KEYCODE_DPAD_DOWN)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitFor(hasTestTag(PERMIT_DIALOG_TAG))
            compose.assertFocusOn(hasText("I have done it"), "the question opens on its answer")
            if (withoutTouch) {
                compose.press(KEYCODE_DPAD_UP)
                compose.assertFocusOn(hasText("Fire TV", substring = true) or hasText("Android TV", substring = true) or hasText("closes Stamp", substring = true), "up reaches what has to be read")
                compose.moveTo(hasText("I have done it") or hasText("Not now"), KEYCODE_DPAD_DOWN, max = 6)
            }
            compose.moveTo(hasText("Not now"), KEYCODE_DPAD_LEFT, max = 3)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag(PERMIT_DIALOG_TAG)).fetchSemanticsNodes().isEmpty() }
            compose.assertFocusOn(permit, "closing the question gives focus back to the row that asked it")
        }
    }
}
