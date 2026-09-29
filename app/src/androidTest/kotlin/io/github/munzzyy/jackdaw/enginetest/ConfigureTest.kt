package io.github.munzzyy.jackdaw.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.jackdaw.core.model.UpdateMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigureTest {
    @Test
    fun aChangeMadeFromAStaleRowKeepsWhatAnInstallLearned() = runBlocking {
        Harness("configure").use { h ->
            val id = h.addFixture(packageName = null)
            h.engine.saveApp(id) { it.copy(config = it.config.copy(pinnedSigners = listOf(PIN), packageName = PKG)) }
            assertEquals(emptyList<String>(), h.row(id).config.pinnedSigners)

            h.engine.configure(id) { it.copy(updates = UpdateMode.AUTO) }

            val stored = h.engine.store.app(id)!!.config
            assertEquals(UpdateMode.AUTO, stored.updates)
            assertEquals(listOf(PIN), stored.pinnedSigners)
            assertEquals(PKG, stored.packageName)
            assertEquals(stored, h.row(id).config)
        }
    }

    @Test
    fun aChangeCannotGiveTheAppAnotherId() = runBlocking {
        Harness("configure-id").use { h ->
            val id = h.addFixture()
            h.engine.configure(id) { it.copy(id = "someone-else", name = "Renamed") }
            assertEquals("Renamed", h.engine.store.app(id)!!.config.name)
            assertEquals(null, h.engine.store.app("someone-else"))
        }
    }

    @Test
    fun aChangeThatIsNotValidIsRefusedAndNothingIsStored() = runBlocking {
        Harness("configure-bad").use { h ->
            val id = h.addFixture()
            val before = h.engine.store.app(id)!!.config
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { h.engine.configure(id) { it.copy(packageName = "not a package") } }
            }
            assertEquals(before, h.engine.store.app(id)!!.config)
            assertEquals(before, h.row(id).config)
        }
    }

    private companion object {
        val PIN = "ab".repeat(32)
    }
}
