package io.github.munzzyy.stamp

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.ui.common.InstallGuard
import io.github.munzzyy.stamp.ui.common.Kept
import io.github.munzzyy.stamp.ui.common.PreferenceWishStore
import io.github.munzzyy.stamp.ui.common.Wanted
import io.github.munzzyy.stamp.ui.detail.DETAIL_PRIMARY_TAG
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class InstallPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun waiting() = fake.apps.value.first { it.status == AppStatus.UPDATE_AVAILABLE && it.progress == null && it.problem == null }

    @After
    fun tearDown() {
        fake.installsAllowed = true
    }

    @Test
    fun anInstallAsksForThePermissionFirstAndCarriesOnOnceItIsGiven() {
        launch("default").use {
            val row = waiting()
            fake.installsAllowed = false
            compose.shownRow(row.config.name).performClick()
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()

            compose.waitForText("Allow Stamp to install apps")
            compose.onNodeWithText("The install carries on by itself.", substring = true).assertIsDisplayed()
            assertNull("the install started without the permission", fake.apps.value.first { it.id == row.id }.progress)

            compose.onNodeWithText("Open settings").performClick()
            assertNotNull("Android's own settings did not open", device.wait(Until.findObject(By.pkg(SETTINGS)), 10_000))
            fake.installsAllowed = true
            device.pressBack()

            compose.waitUntil(10_000) { fake.apps.value.first { it.id == row.id }.let { it.progress != null || it.status == AppStatus.UP_TO_DATE } }
            assertEquals(0, compose.textCount("Allow Stamp to install apps"))
        }
    }

    @Test
    fun comingBackWithoutThePermissionStartsNothing() {
        launch("default").use {
            val row = waiting()
            fake.installsAllowed = false
            compose.shownRow(row.config.name).performClick()
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitForText("Allow Stamp to install apps")

            compose.onNodeWithText("Open settings").performClick()
            assertNotNull("Android's own settings did not open", device.wait(Until.findObject(By.pkg(SETTINGS)), 10_000))
            device.pressBack()

            compose.waitUntil(10_000) { compose.textCount("Allow Stamp to install apps") == 0 }
            compose.waitForIdle()
            assertNull(fake.apps.value.first { it.id == row.id }.progress)
            assertEquals(AppStatus.UPDATE_AVAILABLE, fake.apps.value.first { it.id == row.id }.status)
        }
    }

    @Test
    fun notNowClosesTheQuestionAndStartsNothing() {
        launch("default").use {
            val row = waiting()
            fake.installsAllowed = false
            compose.shownRow(row.config.name).performClick()
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitForText("Allow Stamp to install apps")

            compose.onNodeWithText("Not now").performClick()

            compose.waitUntil(5_000) { compose.textCount("Allow Stamp to install apps") == 0 }
            fake.installsAllowed = true
            compose.waitForIdle()
            assertNull(fake.apps.value.first { it.id == row.id }.progress)
        }
    }

    @Test
    fun whatWasWantedBeforeAndroidClosedTheAppStartsWhenItIsOpenedAgain() {
        val id = launch("default").use { waiting().id }

        launch("default", kept = Kept(listOf(Wanted.One(id)), System.currentTimeMillis() - 60_000)).use {
            compose.waitUntil(10_000) { fake.apps.value.first { it.id == id }.let { it.progress != null || it.status == AppStatus.UP_TO_DATE } }
            assertEquals(0, compose.textCount("Allow Stamp to install apps"))
            assertNull("what was wanted is still written down", PreferenceWishStore(appContext).read())
        }
    }

    @Test
    fun whatWasWantedLongAgoStartsNothing() {
        val id = launch("default").use { waiting().id }

        launch("default", kept = Kept(listOf(Wanted.One(id)), System.currentTimeMillis() - InstallGuard.KEEP_MS - 60_000)).use {
            compose.waitForText("Apps")
            compose.waitForIdle()
            Thread.sleep(1_000)
            val row = fake.apps.value.first { it.id == id }
            assertNull(row.progress)
            assertEquals(AppStatus.UPDATE_AVAILABLE, row.status)
        }
    }

    @Test
    fun theQuestionSaysThatAndroidWillCloseTheAppOnlyWhereItDoes() {
        launch("default").use {
            val row = waiting()
            fake.installsAllowed = false
            compose.shownRow(row.config.name).performClick()
            compose.tagged(DETAIL_PRIMARY_TAG).performClick()
            compose.waitForText("Allow Stamp to install apps")

            val said = compose.textCount("closes Stamp when you turn the switch on", substring = true)
            assertEquals(if (Build.VERSION.SDK_INT in 30..32) 1 else 0, said)
            compose.onNodeWithText("Not now").performClick()
        }
    }

    private companion object {
        /** A television keeps its settings in a package of its own. */
        val SETTINGS: Pattern = Pattern.compile("com\\.android\\.(tv\\.)?settings")
    }
}
