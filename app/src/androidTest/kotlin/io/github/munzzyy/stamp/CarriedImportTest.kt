package io.github.munzzyy.stamp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.stamp.engine.ImportSummary
import io.github.munzzyy.stamp.engine.Settings
import io.github.munzzyy.stamp.ui.LocalActionScope
import io.github.munzzyy.stamp.ui.LocalEngine
import io.github.munzzyy.stamp.ui.importing.CarriedNote
import io.github.munzzyy.stamp.ui.importing.ImportSummaryView
import io.github.munzzyy.stamp.ui.theme.StampTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarriedImportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(summary: ImportSummary, opened: MutableList<String>) {
        compose.setContent {
            CompositionLocalProvider(LocalEngine provides fake, LocalActionScope provides rememberCoroutineScope()) {
                StampTheme(Settings()) {
                    ImportSummaryView(summary.added, summary.alreadyPresent, summary.skipped) { CarriedNote(summary) { opened += it } }
                }
            }
        }
    }

    @Test
    fun appsThatBroughtSettingsAreNamedAndLinked() {
        fake.loadScenario("default")
        fake.stepMs = 1
        val summary = runBlocking { fake.importFrom(android.net.Uri.EMPTY) }
        val opened = ArrayList<String>()
        show(summary, opened)
        compose.onNodeWithText("came with settings that somebody else chose", substring = true).assertIsDisplayed()
        compose.onNodeWithText("signing certificates the file named", substring = true).assertIsDisplayed()
        compose.onNodeWithText("filters from the file", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Plover Chat").performClick()
        assertEquals(2, compose.onAllNodes(hasText("Heron Books")).fetchSemanticsNodes().size)
        assertEquals(listOf("imported2"), opened)
    }

    @Test
    fun anImportWithoutCarriedSettingsSaysNothingAboutThem() {
        show(ImportSummary(2, 0, emptyList()), ArrayList())
        compose.onNodeWithText("2 apps added").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("somebody else chose", substring = true)).fetchSemanticsNodes().size)
    }
}
