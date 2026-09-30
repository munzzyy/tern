package io.github.munzzyy.tern

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.ImportSummary
import io.github.munzzyy.tern.engine.Settings
import io.github.munzzyy.tern.ui.LocalActionScope
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.add.STORES_ON_TAG
import io.github.munzzyy.tern.ui.add.StoresOffCard
import io.github.munzzyy.tern.ui.importing.CarriedNote
import io.github.munzzyy.tern.ui.importing.IMPORT_STORES_ON_TAG
import io.github.munzzyy.tern.ui.importing.ImportSummaryView
import io.github.munzzyy.tern.ui.theme.TernTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoresNoteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aStoreAddressWhileStoresAreOffSaysWhatTheyAreAndOffersTheSwitch() {
        var asked = 0
        compose.setContent {
            CompositionLocalProvider(LocalEngine provides fake, LocalActionScope provides rememberCoroutineScope()) {
                TernTheme(Settings()) { StoresOffCard(SourceTypes.APKPURE) { asked++ } }
            }
        }
        compose.onNodeWithText("APKPure is a third-party store").assertIsDisplayed()
        compose.onNodeWithText("not files from the developer", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(STORES_ON_TAG).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun anImportNamesTheAppsFromStoresAndWhyTheyArePaused() {
        runBlocking { fake.saveSettings(fake.settings.value.copy(thirdPartyStores = false)) }
        val summary = ImportSummary(2, 0, emptyList(), fromStores = listOf("Copy From A Store"))
        compose.setContent {
            CompositionLocalProvider(LocalEngine provides fake, LocalActionScope provides rememberCoroutineScope()) {
                TernTheme(Settings()) {
                    ImportSummaryView(summary.added, summary.alreadyPresent, summary.skipped) { CarriedNote(summary) {} }
                }
            }
        }
        compose.onNodeWithText("the first file Tern installs from its store decides", substring = true).assertIsDisplayed()
        compose.onNodeWithText("so these apps are paused", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Copy From A Store").assertIsDisplayed()
        compose.onNodeWithTag(IMPORT_STORES_ON_TAG).performClick()
        compose.waitUntil(5_000) { fake.settings.value.thirdPartyStores }
        assertTrue(fake.settings.value.thirdPartyStores)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("so these apps are paused", substring = true)).fetchSemanticsNodes().isEmpty() }
        runBlocking { fake.saveSettings(fake.settings.value.copy(thirdPartyStores = false)) }
    }
}
