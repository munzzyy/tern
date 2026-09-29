package io.github.munzzyy.stamp

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.HandoffEnd
import io.github.munzzyy.stamp.engine.OrbotState
import io.github.munzzyy.stamp.engine.ProxyMode
import io.github.munzzyy.stamp.engine.Received
import io.github.munzzyy.stamp.fake.FakeEngine
import io.github.munzzyy.stamp.fake.FakeLinks
import io.github.munzzyy.stamp.fake.FakeSuggestions
import io.github.munzzyy.stamp.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.stamp.ui.add.ADD_FIND_TAG
import io.github.munzzyy.stamp.ui.add.ADD_HANDOFF_TAG
import io.github.munzzyy.stamp.ui.add.ADD_INSTALL_TAG
import io.github.munzzyy.stamp.ui.add.AddScreen
import io.github.munzzyy.stamp.ui.add.PREVIEW_TAG
import io.github.munzzyy.stamp.ui.common.InstallPermissionQuestion
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_ADDRESS_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_ARRIVALS_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_CODE_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_OPEN_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_QR_TAG
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_TIME_TAG
import io.github.munzzyy.stamp.ui.handoff.HandoffScreen
import io.github.munzzyy.stamp.ui.importing.DOOR_FILES_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_HANDOFF_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_FIELD_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_GO_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_TAG
import io.github.munzzyy.stamp.ui.importing.IMPORT_LIST_TAG
import io.github.munzzyy.stamp.ui.importing.ImportScreen
import io.github.munzzyy.stamp.ui.settings.EXPORT_ROW_TAG
import io.github.munzzyy.stamp.ui.settings.ORBOT_TAG
import io.github.munzzyy.stamp.ui.settings.PERMIT_ROW_TAG
import io.github.munzzyy.stamp.ui.settings.SettingsScreen
import io.github.munzzyy.stamp.ui.suggest.STARTER_ROW_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every screen of this part at twice the text size, left to right and right to left: nothing is cut and everything can be reached. */
@RunWith(AndroidJUnit4::class)
class DoorsLargeTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun bare() {
        fake.loadScenario(FakeEngine.BARE)
        fake.stepMs = 25
        fake.detectDelayMs = 150
    }

    private fun nothingIsCut(where: String) = assertEquals("cut while $where is on screen", emptyList<String>(), compose.cutWords())

    /** What stands in the top of a screen or in the title of a question scrolls with nothing, and is on screen as it is. */
    private fun SemanticsNodeInteraction.reached(): SemanticsNodeInteraction {
        val scrolls = fetchSemanticsNode().let { node -> generateSequence(node.parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) } }
        return if (scrolls) performScrollTo() else this
    }

    private fun shown(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).reached().assertIsDisplayed()
        nothingIsCut(tag)
    }

    private fun shownWords(text: String) {
        compose.onNodeWithText(text, substring = true).reached().assertIsDisplayed()
        nothingIsCut(text)
    }

    private fun adding(direction: LayoutDirection, television: Boolean) {
        compose.host(fontScale = 2f, direction = direction, television = television) { AddScreen(prefill = null, nonce = 0, onAdded = {}, onShow = {}) }
        shown(ADD_FIND_TAG)
        shown(ADD_HANDOFF_TAG)
        shownWords("Well known apps")
        shownWords("Nothing is added until you say so.")
        for (app in FakeSuggestions.all) {
            compose.onNode(hasTestTag(STARTER_ROW_TAG) and hasText(app.name)).performScrollTo().assertIsDisplayed()
            nothingIsCut(app.name)
        }
        val pinned = FakeSuggestions.all.first { it.pinned }
        compose.onNode(hasTestTag(STARTER_ROW_TAG) and hasText(pinned.name)).performScrollTo().performClick()
        compose.waitFor(hasTestTag(PREVIEW_TAG))
        shown(ADD_CONFIRM_TAG)
        shown(ADD_INSTALL_TAG)
        shownWords("File to install")
        shownWords("Stamp carries the certificate of this app's developer.")
    }

    @Test
    fun addingOnAPhone() = adding(LayoutDirection.Ltr, television = false)

    @Test
    fun addingOnAPhoneRightToLeft() = adding(LayoutDirection.Rtl, television = false)

    @Test
    fun addingOnATelevision() = adding(LayoutDirection.Ltr, television = true)

    private fun sending(direction: LayoutDirection, television: Boolean) {
        compose.host(fontScale = 2f, direction = direction, television = television) { HandoffScreen(onBack = {}, onLook = {}, onOpenApp = {}) }
        compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
        fake.receive(Received.Link(FakeLinks.WARNED_APP + "/releases/download/v1.0.0/" + "a-long-name-".repeat(12)))
        fake.receive(Received.ExportFile("my-apps-with-a-long-name-2026-09-29.json", ByteArray(2_048)))
        val inArrivals = hasAnyAncestor(hasTestTag(HANDOFF_ARRIVALS_TAG))
        compose.waitFor(hasText("my-apps-with-a-long-name", substring = true) and inArrivals)
        shown(HANDOFF_TIME_TAG)
        shownWords("Arrived from the phone")
        compose.onNode(hasText(FakeLinks.WARNED_APP, substring = true) and inArrivals).performScrollTo().assertIsDisplayed()
        nothingIsCut("the link that arrived")
        compose.onNode(hasText("my-apps-with-a-long-name", substring = true) and inArrivals).performScrollTo().assertIsDisplayed()
        nothingIsCut("the file that arrived")
        shown(HANDOFF_QR_TAG)
        shownWords("1. Put the phone on the same network")
        shownWords("3. Paste links or pick your export file")
        shown(HANDOFF_ADDRESS_TAG)
        shown(HANDOFF_CODE_TAG)
        shownWords("It is never sent itself.")
        shownWords("Nothing that arrives is added until you have looked at it here.")

        compose.onNode(hasText("my-apps-with-a-long-name", substring = true) and inArrivals).performScrollTo().performClick()
        compose.waitFor(hasText("Import finished") and inArrivals)
        shownWords("3 apps added")
        shownWords("came with settings that somebody else chose")

        fake.endHandoff(HandoffEnd.USED_UP)
        compose.waitFor(hasTestTag(HANDOFF_OPEN_TAG))
        shownWords("It closed because it was asked too many times.")
        shown(HANDOFF_OPEN_TAG)
    }

    @Test
    fun sendingFromAPhoneOnAPhone() = sending(LayoutDirection.Ltr, television = false)

    @Test
    fun sendingFromAPhoneOnAPhoneRightToLeft() = sending(LayoutDirection.Rtl, television = false)

    @Test
    fun sendingFromAPhoneOnATelevision() = sending(LayoutDirection.Ltr, television = true)

    @Test
    fun theCodeAndTheAddressReadLeftToRightInARightToLeftLanguage() {
        compose.host(direction = LayoutDirection.Rtl) { HandoffScreen(onBack = {}, onLook = {}, onOpenApp = {}) }
        compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
        val width = compose.onRoot().fetchSemanticsNode().size.width
        val back = compose.onNode(hasContentDescription("Back")).fetchSemanticsNode().boundsInRoot
        assertTrue("the way back is on the right: $back of $width", back.left > width / 2)
        val groups = fake.handoff.value!!.code.split(' ')
        val lefts = groups.map { group ->
            compose.onNode(hasText(group) and hasAnyAncestor(hasTestTag(HANDOFF_CODE_TAG)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val firstLine = lefts.filter { it.top == lefts.first().top }
        assertTrue("more than one group stands on the first line: $lefts", firstLine.size > 1)
        assertEquals("the groups of a line run from the left: $lefts", firstLine.map { it.left }.sorted(), firstLine.map { it.left })
    }

    private fun importing(direction: LayoutDirection, television: Boolean) {
        compose.host(fontScale = 2f, direction = direction, television = television) { ImportScreen(onBack = {}) }
        fun reach(tag: String) {
            compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
            nothingIsCut(tag)
        }
        fun reachWords(text: String) {
            compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasText(text, substring = true))
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
            nothingIsCut(text)
        }
        reachWords("Bring in an export from Stamp or Obtainium.")
        reach(DOOR_FILES_TAG)
        compose.onNodeWithTag(DOOR_FILES_TAG).performClick()
        compose.waitForText("obtainium-export.json")
        reachWords("stamp-apps-2026-09-29.json")
        reachWords("obtainium-export.json")
        reach(DOOR_LINK_TAG)
        compose.onNodeWithTag(DOOR_LINK_TAG).performClick()
        reach(DOOR_LINK_FIELD_TAG)
        reachWords("An https address that serves the file.")
        reach(DOOR_LINK_GO_TAG)
        reach(DOOR_HANDOFF_TAG)

        reachWords("obtainium-export.json")
        compose.onNodeWithText("obtainium-export.json", substring = true).performClick()
        compose.waitForText("Import finished")
        reachWords("3 apps added")
        reachWords("2 could not be brought over:")
        reach(DOOR_FILES_TAG)
    }

    @Test
    fun importingOnAPhone() = importing(LayoutDirection.Ltr, television = false)

    @Test
    fun importingOnAPhoneRightToLeft() = importing(LayoutDirection.Rtl, television = false)

    @Test
    fun importingOnATelevision() = importing(LayoutDirection.Ltr, television = true)

    private fun settings(direction: LayoutDirection, television: Boolean) {
        fake.orbotAnswer = OrbotState.OFF
        runBlocking { fake.saveSettings(fake.settings.value.copy(proxy = ProxyMode.ORBOT)) }
        compose.host(fontScale = 2f, direction = direction, television = television) { SettingsScreen(onImport = {}, onLook = {}) }
        shownWords("How often")
        shown(PERMIT_ROW_TAG)
        shownWords("Keep downloaded files")
        shownWords("Save token")
        shownWords("Proxy")
        compose.waitFor(hasText("Orbot is installed and not connected.") and hasAnyAncestor(hasTestTag(ORBOT_TAG)))
        shown(ORBOT_TAG)
        shownWords("Open Orbot")
        shownWords("It never goes round the proxy.")
        shownWords("Import apps")
        shown(EXPORT_ROW_TAG)
        compose.onNodeWithTag(EXPORT_ROW_TAG).performClick()
        compose.waitFor(hasTestTag(EXPORT_ROW_TAG) and hasText("Saved as", substring = true))
        shown(EXPORT_ROW_TAG)
        shownWords("Open Obtainium links")
        shownWords("Source code")
    }

    @Test
    fun settingsOnAPhone() = settings(LayoutDirection.Ltr, television = false)

    @Test
    fun settingsOnAPhoneRightToLeft() = settings(LayoutDirection.Rtl, television = false)

    @Test
    fun settingsOnATelevision() = settings(LayoutDirection.Ltr, television = true)

    private fun asking(direction: LayoutDirection, television: Boolean) {
        compose.host(fontScale = 2f, direction = direction, television = television) {
            InstallPermissionQuestion(canOpenSettings = false, onReturned = {}, onDone = {}, onDismiss = {})
        }
        shownWords("Allow Stamp to install apps")
        shownWords("this device has no page Stamp can open for you")
        shownWords("Android TV and Google TV: Settings, Apps, Security and restrictions, Unknown sources")
        shownWords("Fire TV: Settings, My Fire TV, Developer options, Install unknown apps")
        compose.onNodeWithText("I have done it").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        nothingIsCut("the answers")
    }

    @Test
    fun theQuestionAboutInstallingOnAPhone() = asking(LayoutDirection.Ltr, television = false)

    @Test
    fun theQuestionAboutInstallingOnAPhoneRightToLeft() = asking(LayoutDirection.Rtl, television = false)

    @Test
    fun theQuestionAboutInstallingOnATelevision() = asking(LayoutDirection.Ltr, television = true)
}
