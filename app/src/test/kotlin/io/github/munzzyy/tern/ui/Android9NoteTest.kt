package io.github.munzzyy.tern.ui

import io.github.munzzyy.tern.ui.settings.showsAndroid9Note
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android9NoteTest {
    @Test
    fun androidNineIsToldOnceAfterTheFirstRun() {
        assertTrue(showsAndroid9Note(sdk = 28, firstRunDone = true, noted = false))
        assertFalse(showsAndroid9Note(sdk = 28, firstRunDone = true, noted = true))
        assertFalse(showsAndroid9Note(sdk = 28, firstRunDone = false, noted = false))
    }

    @Test
    fun newerAndroidIsNotTold() {
        assertFalse(showsAndroid9Note(sdk = 29, firstRunDone = true, noted = false))
        assertFalse(showsAndroid9Note(sdk = 36, firstRunDone = true, noted = false))
    }
}
