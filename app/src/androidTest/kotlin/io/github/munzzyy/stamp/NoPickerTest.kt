package io.github.munzzyy.stamp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.fake.FakeEngine
import io.github.munzzyy.stamp.ui.handoff.HANDOFF_QR_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_FILES_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_HANDOFF_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_FIELD_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_GO_TAG
import io.github.munzzyy.stamp.ui.importing.DOOR_LINK_TAG
import io.github.munzzyy.stamp.ui.importing.IMPORT_LIST_TAG
import io.github.munzzyy.stamp.ui.settings.EXPORT_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Import and export on a device that has no file picker, which is the usual case on a television. */
@RunWith(AndroidJUnit4::class)
class NoPickerTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val imported = listOf("Heron Books", "Plover Chat", "Rook Budget")

    private fun names() = fake.apps.value.map { it.config.name }

    private fun openImport() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Import apps").performScrollTo().performClick()
        compose.waitFor(hasTestTag(IMPORT_LIST_TAG))
    }

    private fun show(tag: String) = compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasTestTag(tag))

    private fun showText(text: String) = compose.onNodeWithTag(IMPORT_LIST_TAG).performScrollToNode(hasText(text, substring = true))

    private fun theSameSummaryAsEveryImport(before: List<String>) {
        compose.waitForText("Import finished")
        for (line in listOf("3 apps added", "2 were already in your list", "2 could not be brought over:", "came with settings that somebody else chose")) {
            showText(line)
            compose.onNodeWithText(line, substring = true).assertIsDisplayed()
        }
        assertEquals(before + imported, (before + names()).distinct())
    }

    @Test
    fun withoutAPickerTheScreenOffersThreeDoorsAndNoPicker() {
        launch(FakeEngine.BARE).use {
            openImport()
            for (tag in listOf(DOOR_FILES_TAG, DOOR_LINK_TAG, DOOR_HANDOFF_TAG)) {
                show(tag)
                compose.tagged(tag).assertIsDisplayed()
            }
            assertEquals(0, compose.textCount("Pick a file"))
            compose.onNodeWithText("Bring in an export from Stamp or Obtainium.", substring = true).assertExists()
        }
    }

    @Test
    fun withAPickerTheScreenOffersThePickerAndNoDoors() {
        launch("default").use {
            openImport()
            compose.onNodeWithText("Pick a file").assertIsDisplayed()
            for (tag in listOf(DOOR_FILES_TAG, DOOR_LINK_TAG, DOOR_HANDOFF_TAG)) {
                assertEquals(0, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun aFileOnThisDeviceIsImported() {
        launch(FakeEngine.BARE).use {
            val before = names()
            openImport()
            show(DOOR_FILES_TAG)
            compose.tagged(DOOR_FILES_TAG).performClick()
            compose.waitForText("obtainium-export.json")
            showText("stamp-apps-2026-09-29.json")
            compose.onNode(hasText("stamp-apps-2026-09-29.json", substring = true) and hasText("Download/Stamp", substring = true)).assertIsDisplayed()
            assertEquals("listing files imports nothing", before, names())

            showText("obtainium-export.json")
            compose.onNodeWithText("obtainium-export.json", substring = true).performClick()
            theSameSummaryAsEveryImport(before)
        }
    }

    @Test
    fun aDeviceThatHoldsNoExportSaysWhereStampLooks() {
        launch(FakeEngine.BARE).use {
            fake.savedFiles = false
            openImport()
            show(DOOR_FILES_TAG)
            compose.tagged(DOOR_FILES_TAG).performClick()
            compose.waitForText("No export file was found. Stamp can read what it saved itself, and files placed in its own import folder.")

            compose.tagged(DOOR_FILES_TAG).performClick()
            compose.waitUntil(3_000) { compose.textCount("No export file was found", substring = true) == 0 }
        }
    }

    @Test
    fun aFileAtALinkIsImported() {
        launch(FakeEngine.BARE).use {
            val before = names()
            openImport()
            show(DOOR_LINK_TAG)
            compose.tagged(DOOR_LINK_TAG).performClick()
            show(DOOR_LINK_GO_TAG)
            compose.tagged(DOOR_LINK_GO_TAG).assertIsNotEnabled()
            compose.onNodeWithText("An https address that serves the file.").assertExists()
            compose.tagged(DOOR_LINK_FIELD_TAG).performTextReplacement("https://example.org/stamp-apps.json")
            compose.tagged(DOOR_LINK_GO_TAG).assertIsEnabled().performClick()
            theSameSummaryAsEveryImport(before)
        }
    }

    @Test
    fun aLinkThatCannotBeHadSaysWhyInTheEnginesWordsAndCanBeTriedAgain() {
        launch(FakeEngine.BARE).use {
            val before = names()
            openImport()
            show(DOOR_LINK_TAG)
            compose.tagged(DOOR_LINK_TAG).performClick()
            show(DOOR_LINK_FIELD_TAG)
            compose.tagged(DOOR_LINK_FIELD_TAG).performTextReplacement("https://example.org/missing.json")
            show(DOOR_LINK_GO_TAG)
            compose.tagged(DOOR_LINK_GO_TAG).performClick()
            compose.waitForText("The server answered 404: there is no file at that address.")
            assertEquals(before, names())
            compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
            compose.waitForText("The server answered 404: there is no file at that address.")
            assertEquals(before, names())
        }
    }

    @Test
    fun thePhoneIsTheThirdDoor() {
        launch(FakeEngine.BARE).use {
            openImport()
            show(DOOR_HANDOFF_TAG)
            compose.tagged(DOOR_HANDOFF_TAG).performClick()
            compose.waitFor(hasTestTag(HANDOFF_QR_TAG))
            assertNotNull(fake.handoff.value)
            device.pressBack()
            compose.waitFor(hasTestTag(IMPORT_LIST_TAG))
            compose.waitUntil(3_000) { fake.handoff.value == null }
        }
    }

    @Test
    fun exportWithoutAPickerSaysWhereTheFileWent() {
        launch(FakeEngine.BARE).use {
            compose.onNodeWithText("Settings").performClick()
            val row = compose.tagged(EXPORT_ROW_TAG).performScrollTo()
            row.assertIsDisplayed()
            compose.onNode(hasTestTag(EXPORT_ROW_TAG) and hasText("Export to the Download folder")).assertIsDisplayed()
            assertEquals(0, compose.textCount("Saved as", substring = true))

            row.performClick()
            compose.waitFor(hasTestTag(EXPORT_ROW_TAG) and hasText("Saved as", substring = true))
            val said = compose.onNode(hasTestTag(EXPORT_ROW_TAG) and hasText("stamp-apps-2026-09-29.json", substring = true) and hasText("Download/Stamp", substring = true))
            said.performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun exportWithAPickerIsCalledAsBefore() {
        launch("default").use {
            compose.onNodeWithText("Settings").performClick()
            compose.tagged(EXPORT_ROW_TAG).performScrollTo()
            compose.onNode(hasTestTag(EXPORT_ROW_TAG) and hasText("Export apps")).assertIsDisplayed()
            assertEquals(0, compose.textCount("Export to the Download folder"))
        }
    }
}
