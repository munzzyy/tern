package io.github.munzzyy.tern

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.ui.detail.DETAIL_PRIMARY_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun updateShowsProgressThenSucceeds() {
        launch("default").use {
            fake.stepMs = 80
            compose.shownRow("Trail Map").performClick()
            compose.tagged(DETAIL_PRIMARY_TAG).assertIsDisplayed()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()

            compose.waitFor(hasText(" of ", substring = true) and hasText("%", substring = true))
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Cancel"))

            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Open"), timeoutMs = 20_000)
            compose.waitForText("Up to date")
            val row = fake.apps.value.single { it.id == "trailmap" }
            assertEquals(AppStatus.UP_TO_DATE, row.status)
            assertEquals("1.5.0", row.installed?.versionName)
        }
    }

    @Test
    fun cancelStopsTheDownload() {
        launch("default").use {
            fake.stepMs = 400
            compose.shownRow("Trail Map").performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Cancel"))
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitFor(hasTestTag(DETAIL_PRIMARY_TAG) and hasText("Update"))
            assertEquals(null, fake.apps.value.single { it.id == "trailmap" }.progress)
        }
    }
}
