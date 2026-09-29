package io.github.munzzyy.tern.engine

import io.github.munzzyy.tern.engine.real.Device
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first stub and the television's settings page are the names read off an Android TV 14 image. */
class StubTest {
    @Test
    fun whatATelevisionPutsInPlaceOfAFilePickerIsAStub() {
        assertTrue(Device.isStub("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs${'$'}DocumentsStub"))
        assertTrue(Device.isStub("com.android.frameworkpackagestubs", "com.android.frameworkpackagestubs.Picker"))
        assertTrue(Device.isStub("com.example.maker.tv", "com.example.maker.tv.SettingsStub"))
    }

    @Test
    fun aRealActivityIsNot() {
        assertFalse(Device.isStub("com.google.android.documentsui", "com.android.documentsui.picker.PickActivity"))
        assertFalse(Device.isStub("com.android.tv.settings", "com.android.tv.settings.device.apps.specialaccess.ExternalSourcesActivity"))
        assertFalse(Device.isStub("android", "com.android.internal.app.ResolverActivity"))
        assertFalse(Device.isStub("com.example.stubby", "com.example.stubby.stubborn"))
    }

    @Test
    fun anAnswerWithoutNamesIsNoStub() {
        assertFalse(Device.isStub(null, null))
    }
}
