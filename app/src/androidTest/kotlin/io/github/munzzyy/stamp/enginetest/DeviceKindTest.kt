package io.github.munzzyy.stamp.enginetest

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What each kind of device has to answer. A failure names the activity Android resolved, which is the fact to look at. */
@RunWith(AndroidJUnit4::class)
class DeviceKindTest {
    private val packages = targetContext.packageManager
    private val television = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun resolved(intent: Intent): String =
        packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "nothing"

    @Test
    fun aTelevisionHasNoFilePickerAndAPhoneHasOne() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
        Harness("kind-picker").use { h ->
            assertEquals("television=$television and the picker resolves to ${resolved(picker)}", !television, h.engine.hasFilePicker())
        }
    }

    @Test
    fun theStockImagesHaveASettingsPageForInstalling() {
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", targetContext.packageName, null))
        Harness("kind-settings").use { h ->
            assertTrue("television=$television and the page resolves to ${resolved(page)}", h.engine.canOpenInstallSettings())
        }
    }
}
