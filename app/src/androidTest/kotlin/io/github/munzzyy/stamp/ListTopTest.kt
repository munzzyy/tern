package io.github.munzzyy.stamp

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_UP
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.Phase
import io.github.munzzyy.stamp.engine.Progress
import io.github.munzzyy.stamp.ui.apps.APP_FILTERS_TAG
import io.github.munzzyy.stamp.ui.apps.APP_LIST_TAG
import io.github.munzzyy.stamp.ui.apps.APP_SEARCH_OPEN_TAG
import io.github.munzzyy.stamp.ui.apps.APP_SEARCH_TAG
import io.github.munzzyy.stamp.ui.apps.WAITING_BANNER_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What stands above the rows: the search, the filters and the banner for installs that wait. */
@RunWith(AndroidJUnit4::class)
class ListTopTest {
    @get:Rule val compose = createEmptyComposeRule()

    @After fun touch() = touchAgain()

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun rowExists(name: String): Boolean = try {
        compose.shownRow(name)
        true
    } catch (_: AssertionError) {
        false
    }

    private fun brushDraw() = fake.apps.value.single { it.id == "brushdraw" }

    private val inBanner = hasAnyAncestor(hasTestTag(WAITING_BANNER_TAG))

    @Test
    fun aShortListFoldsTheSearchIntoAGlyphThatOpensIntoAField() {
        launch("three").use {
            compose.waitFor(hasTestTag(APP_LIST_TAG))
            assertEquals("the field is folded away", 0, count(APP_SEARCH_TAG))
            compose.tagged(APP_SEARCH_OPEN_TAG).assertIsDisplayed().performClick()
            compose.waitFor(hasTestTag(APP_SEARCH_TAG))
            compose.tagged(APP_SEARCH_TAG).assertIsDisplayed().assertIsFocused()
            assertEquals("the glyph makes room for the field", 0, count(APP_SEARCH_OPEN_TAG))

            compose.tagged(APP_SEARCH_TAG).performTextInput("birch")
            compose.waitUntil(5_000) { !rowExists("Amber Notes 1") }
            assertTrue(rowExists("Birch Notes 2"))

            compose.onNodeWithContentDescription("Clear search").performClick()
            compose.waitUntil(5_000) { rowExists("Amber Notes 1") }
            assertEquals("an emptied field stays open", 1, count(APP_SEARCH_TAG))
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.waitUntil(5_000) { count(APP_SEARCH_TAG) == 0 }
            compose.tagged(APP_SEARCH_OPEN_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun backClosesTheSearchAndShowsEveryAppAgain() {
        launch("three").use { scenario ->
            compose.tagged(APP_SEARCH_OPEN_TAG).performClick()
            compose.waitFor(hasTestTag(APP_SEARCH_TAG))
            compose.tagged(APP_SEARCH_TAG).performTextInput("cobalt")
            compose.waitUntil(5_000) { !rowExists("Amber Notes 1") }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { count(APP_SEARCH_TAG) == 0 }
            assertTrue(rowExists("Amber Notes 1"))
            assertTrue(rowExists("Cobalt Notes 3"))
        }
    }

    @Test
    fun aLongListShowsTheFieldAtOnceExceptOnATelevision() {
        launch("default").use {
            compose.waitFor(hasTestTag(APP_LIST_TAG))
            if (withoutTouch) {
                compose.tagged(APP_SEARCH_OPEN_TAG).assertIsDisplayed()
                assertEquals("typing with a remote is the last resort, so the field stays folded", 0, count(APP_SEARCH_TAG))
            } else {
                compose.tagged(APP_SEARCH_TAG).assertIsDisplayed()
                assertEquals(0, count(APP_SEARCH_OPEN_TAG))
                assertEquals(0, compose.onAllNodes(hasContentDescription("Close search")).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun filtersAppearOnlyWhenThereIsMoreThanOneKindOfRow() {
        launch("three").use {
            compose.waitFor(hasTestTag(APP_FILTERS_TAG))
            for (label in listOf("All", "Updates", "Installed", "Not installed")) {
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(APP_FILTERS_TAG))).assertIsDisplayed()
            }
            runBlocking {
                fake.apps.value.filter { it.status != AppStatus.UP_TO_DATE }.forEach { fake.remove(it.id) }
            }
            assertEquals(1, fake.apps.value.size)
            compose.waitUntil(5_000) { count(APP_FILTERS_TAG) == 0 }
            assertTrue(rowExists("Cobalt Notes 3"))
        }
    }

    @Test
    fun theBannerNamesTheInstallThatWaitsAndConfirmsIt() {
        launch("default").use {
            compose.waitFor(hasTestTag(WAITING_BANNER_TAG))
            compose.onNode(hasText("Brush Draw waits for your go-ahead") and inBanner).assertIsDisplayed()
            compose.onNode(hasClickAction() and hasText("Confirm") and inBanner).performClick()
            compose.waitUntil(10_000) { brushDraw().status == AppStatus.UP_TO_DATE && brushDraw().progress == null }
            assertEquals("0.6", brushDraw().installed?.versionName)
            compose.waitUntil(5_000) { count(WAITING_BANNER_TAG) == 0 }
        }
    }

    @Test
    fun severalInstallsThatWaitAreCountedAndTheFirstIsConfirmed() {
        launch("default").use {
            fake.play("vaultkeys", Progress(Phase.WAITING_FOR_USER))
            compose.waitForText("2 installs wait for your go-ahead")
            compose.onNode(hasClickAction() and hasText("Confirm") and inBanner).performClick()
            compose.waitUntil(10_000) { brushDraw().progress == null }
            assertEquals(Phase.WAITING_FOR_USER, fake.apps.value.single { it.id == "vaultkeys" }.progress?.phase)
            compose.waitForText("Vault Keys waits for your go-ahead")
        }
    }

    @Test
    fun noBannerWhileNothingWaits() {
        launch("three").use {
            compose.waitFor(hasTestTag(APP_LIST_TAG))
            assertEquals(0, count(WAITING_BANNER_TAG))
        }
    }

    @Test
    fun theBannerIsReachedAndPressedWithTheRemote() {
        keysOnly()
        launch("default").use {
            val banner = hasClickAction() and hasText("Brush Draw waits for your go-ahead") and inBanner
            compose.waitFor(banner)
            compose.moveTo(banner, KEYCODE_DPAD_UP)
            compose.press(KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { brushDraw().status == AppStatus.UP_TO_DATE && brushDraw().progress == null }
            compose.waitUntil(5_000) { count(WAITING_BANNER_TAG) == 0 }
            compose.expect("when the banner leaves, focus stays on this screen") { compose.focusedLabel() != "nothing" && !compose.hasFocusOn(isTab) }
        }
    }
}
