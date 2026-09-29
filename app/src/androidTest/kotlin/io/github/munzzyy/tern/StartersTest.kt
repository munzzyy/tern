package io.github.munzzyy.tern

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.fake.FakeLinks
import io.github.munzzyy.tern.fake.FakeSuggestions
import io.github.munzzyy.tern.ui.add.ADD_CONFIRM_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIELD_TAG
import io.github.munzzyy.tern.ui.add.ADD_FIND_TAG
import io.github.munzzyy.tern.ui.add.AddScreen
import io.github.munzzyy.tern.ui.add.PREVIEW_PIN_TAG
import io.github.munzzyy.tern.ui.add.PREVIEW_TAG
import io.github.munzzyy.tern.ui.add.RESULTS_TAG
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import io.github.munzzyy.tern.ui.suggest.STARTERS_TAG
import io.github.munzzyy.tern.ui.suggest.STARTER_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The well known apps on the Add screen, in the app as a whole. */
@RunWith(AndroidJUnit4::class)
class StartersTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val pinned = FakeSuggestions.all.first { it.pinned }
    private val plain = FakeSuggestions.all.first { !it.pinned }

    private fun names() = fake.apps.value.map { it.config.name }

    private fun openAdd() {
        compose.onNodeWithText("Add").performClick()
        compose.waitFor(hasTestTag(STARTERS_TAG))
    }

    @Test
    fun lookShowsAPreviewAndTheAppIsAddedOnlyAfterAdd() {
        launch("default").use {
            val before = names()
            openAdd()
            compose.onNodeWithText("Well known apps").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Nothing is added until you say so.", substring = true).assertIsDisplayed()

            compose.onNode(hasTestTag(STARTER_ROW_TAG) and hasText(plain.name)).performScrollTo().performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.tagged(ADD_FIELD_TAG).assertTextContains(plain.url)
            compose.onNode(hasText(plain.name) and hasAnyAncestor(hasTestTag(PREVIEW_TAG))).assertIsDisplayed()
            assertEquals("looking at an app adds nothing", before, names())
            assertEquals("the list gives way to the preview", 0, compose.onAllNodes(hasTestTag(STARTERS_TAG)).fetchSemanticsNodes().size)

            compose.tagged(ADD_CONFIRM_TAG).performScrollTo().performClick()
            compose.waitFor(hasText("Install") and hasTestTag(DETAIL_PRIMARY_TAG))
            assertEquals(before + plain.name, (before + names()).distinct())
            assertEquals(null, fake.apps.value.single { it.config.name == plain.name }.progress)
        }
    }

    @Test
    fun anAppWhoseCertificateTernCarriesSaysSoOnItsRowAndAmongItsChecks() {
        launch("default").use {
            openAdd()
            val row = hasTestTag(STARTER_ROW_TAG) and hasText(pinned.name)
            compose.onNode(row).performScrollTo()
            compose.onNode(row and hasText("Certificate known")).assertIsDisplayed()
            assertEquals(0, compose.onAllNodes(hasTestTag(STARTER_ROW_TAG) and hasText(plain.name) and hasText("Certificate known")).fetchSemanticsNodes().size)

            compose.onNode(row).performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.tagged(PREVIEW_PIN_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("A file signed by anyone else is refused, also at the first install.", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun anAppWithoutSuchACertificateSaysNothingOfIt() {
        launch("default").use {
            openAdd()
            compose.onNode(hasTestTag(STARTER_ROW_TAG) and hasText(plain.name)).performScrollTo().performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            compose.tagged(ADD_CONFIRM_TAG).performScrollTo()
            assertEquals(0, compose.onAllNodes(hasTestTag(PREVIEW_PIN_TAG)).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun backFromThePreviewBringsTheListBack() {
        launch("default").use {
            openAdd()
            compose.onNode(hasTestTag(STARTER_ROW_TAG) and hasText(plain.name)).performScrollTo().performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            device.pressBack()
            compose.waitFor(hasTestTag(STARTERS_TAG))
            assertEquals("", compose.tagged(ADD_FIELD_TAG).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            assertEquals(0, compose.onAllNodes(hasTestTag(PREVIEW_TAG)).fetchSemanticsNodes().size)
            compose.onNodeWithText("Add an app").assertIsDisplayed()
        }
    }

    @Test
    fun theListIsThereOnlyWhileTheFieldIsEmpty() {
        launch("default").use {
            openAdd()
            compose.tagged(ADD_FIELD_TAG).performTextReplacement("feed")
            compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag(STARTERS_TAG)).fetchSemanticsNodes().isEmpty() }
            compose.tagged(ADD_FIELD_TAG).performTextReplacement("")
            compose.waitFor(hasTestTag(STARTERS_TAG))
        }
    }

    @Test
    fun aRepositoryGivenByItsAddressListsItsAppsAndSaysThatThereAreMore() {
        launch("default").use {
            val before = names()
            openAdd()
            compose.tagged(ADD_FIELD_TAG).performTextReplacement(FakeLinks.REPOSITORY)
            compose.tagged(ADD_FIND_TAG).performClick()
            compose.waitFor(hasTestTag(RESULTS_TAG))
            compose.onNode(hasText("Apps in", substring = true) and hasText("Example Apps", substring = true)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("This repository holds more apps than are shown here.", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.textCount("results for", substring = true))

            compose.onNode(hasText("Tide Table") and hasAnyAncestor(hasTestTag(RESULTS_TAG))).performScrollTo().performClick()
            compose.waitFor(hasTestTag(PREVIEW_TAG))
            assertEquals(before, names())
        }
    }

    @Test
    fun aSearchIsStillCalledASearch() {
        launch("default").use {
            openAdd()
            compose.tagged(ADD_FIELD_TAG).performTextReplacement("feed reader")
            compose.tagged(ADD_FIND_TAG).performClick()
            compose.waitFor(hasTestTag(RESULTS_TAG))
            compose.onNodeWithText("3 results for", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.textCount("Apps in", substring = true))
            assertEquals(0, compose.textCount("This repository holds more", substring = true))
        }
    }
}

/** The Add screen by itself, as a television and as a phone draw it. */
@RunWith(AndroidJUnit4::class)
class StarterGroupsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(television: Boolean) {
        fake.loadScenario("default")
        compose.host(television = television) { AddScreen(prefill = null, nonce = 0, onAdded = {}, onShow = {}) }
        compose.waitFor(hasTestTag(STARTERS_TAG))
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    @Test
    fun onATelevisionTheAppsForATelevisionComeFirstUnderTheirOwnHeading() {
        show(television = true)
        val made = FakeSuggestions.all.filter { it.forTelevision }
        val rest = FakeSuggestions.all.filterNot { it.forTelevision }
        assertTrue(made.isNotEmpty() && rest.isNotEmpty())
        val forTelevision = top("For this television")
        val forAny = top("For any device")
        assertTrue("the television's group is the first", forTelevision < forAny)
        for (app in made) {
            val at = top(app.name)
            assertTrue("${app.name} stands under the television's heading", at > forTelevision && at < forAny)
        }
        for (app in rest) assertTrue("${app.name} stands under the other heading", top(app.name) > forAny)
    }

    @Test
    fun onAPhoneTheListIsOneAndHasNoSuchHeadings() {
        show(television = false)
        assertEquals(0, compose.textCount("For this television"))
        assertEquals(0, compose.textCount("For any device"))
        assertEquals(FakeSuggestions.all.size, compose.onAllNodes(hasTestTag(STARTER_ROW_TAG)).fetchSemanticsNodes().size)
    }
}
