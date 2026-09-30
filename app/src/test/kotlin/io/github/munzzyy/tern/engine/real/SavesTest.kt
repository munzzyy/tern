package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.engine.Phase
import io.github.munzzyy.tern.engine.Progress
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavesTest {
    @Test
    fun aFileIsCountedUnderAKeyNoAppIdTakesUntilItIsDone() {
        val saves = Saves()
        val key = saves.begin("app-v1.zip", 100)
        assertTrue(key.startsWith(Saves.PREFIX))
        assertEquals("app-v1.zip", saves.name(key))
        assertEquals(mapOf(key to Progress(Phase.DOWNLOADING, 0, 100)), saves.all())
        saves.progress(key, 40, 100)
        assertEquals(Progress(Phase.DOWNLOADING, 40, 100), saves.all()[key])
        saves.end(key)
        assertNull(saves.name(key))
        assertTrue(saves.all().isEmpty())
        // A late report of progress does not bring it back.
        saves.progress(key, 90, 100)
        assertTrue(saves.all().isEmpty())
    }

    @Test
    fun twoFilesOfOneNameAreCountedApart() {
        val saves = Saves()
        val first = saves.begin("app.apk", null)
        val second = saves.begin("app.apk", null)
        assertFalse(first == second)
        assertEquals(2, saves.all().size)
    }

    @Test
    fun cancelStopsEverySaveUnderWay() {
        val saves = Saves()
        val one = Job()
        val two = Job()
        saves.track(one)
        saves.track(two)
        saves.cancelAll()
        assertTrue(one.isCancelled && two.isCancelled)
    }
}
