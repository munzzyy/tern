package io.github.munzzyy.jackdaw

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The switch only changes the setting; enabling the link handler is the engine's job inside saveSettings. */
@RunWith(AndroidJUnit4::class)
class ObtainiumLinksSwitchTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun handlerState(): Int = appContext.packageManager.getComponentEnabledSetting(
        ComponentName(appContext.packageName, "io.github.munzzyy.jackdaw.ObtainiumLinks"),
    )

    @Test
    fun switchStoresTheSettingAndLeavesTheComponentToTheEngine() {
        launch("default").use {
            val before = handlerState()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Open Obtainium links").performScrollTo().performClick()
            compose.waitUntil(5_000) { fake.settings.value.openObtainiumLinks }
            assertEquals(before, handlerState())
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, handlerState())
        }
    }
}
