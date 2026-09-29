package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveTest {
    private val old = SourceSpec(SourceTypes.FORGEJO, "https://forge.test/example/old")

    @Before
    fun setUp() = uninstallFixture()

    private fun Harness.moved(id: String = "moved", packageName: String? = PKG, pins: List<String> = emptyList()): String {
        forge.releases = listOf(FakeForge.Release("v1.0", listOf(FakeForge.File("app-v1.apk", asset("apk/app-v1.apk")))))
        val config = AppConfig(id = id, source = old, name = "Moved", packageName = packageName, pinnedSigners = pins)
        val state = AppState(movedTo = FakeForge.PROJECT)
        engine.store.putApp(config, state)
        runBlocking { engine.ready() }
        engine.stored[id] = StoredApp(config, state)
        engine.publish()
        return id
    }

    @Test
    fun theSameAppAtTheNewAddressReplacesTheSource() = runBlocking {
        Harness("move-ok").use { h ->
            val id = h.moved()
            assertEquals(FakeForge.PROJECT, h.row(id).movedTo)
            assertNull(h.engine.followMove(id))
            val config = h.engine.store.app(id)!!.config
            assertEquals(SourceTypes.FORGEJO, config.source.type)
            assertEquals(FakeForge.PROJECT, config.source.url)
            assertEquals(id, config.id)
            assertNull(h.row(id).movedTo)
            assertEquals("v1.0", h.state(id).releases.single().id)
            assertTrue(h.eventsFor(id).any { it.kind == EventKind.MOVED })
        }
    }

    @Test
    fun anotherPackageOrSignerOrAnUnknownAppKeepsTheOldAddress() = runBlocking {
        Harness("move-refused").use { h ->
            val other = h.moved(id = "other", packageName = "com.example.other")
            assertEquals(ProblemKind.PACKAGE_MISMATCH, h.engine.followMove(other)?.kind)
            val signer = h.moved(id = "signer", pins = listOf("00".repeat(32)))
            assertEquals(ProblemKind.PIN_MISMATCH, h.engine.followMove(signer)?.kind)
            val unknown = h.moved(id = "unknown", packageName = null)
            assertEquals(ProblemKind.PACKAGE_MISMATCH, h.engine.followMove(unknown)?.kind)
            for (id in listOf(other, signer, unknown)) {
                assertEquals(old, h.engine.store.app(id)!!.config.source)
                assertEquals(FakeForge.PROJECT, h.row(id).movedTo)
            }
            assertTrue(h.engine.events.value.none { it.kind == EventKind.MOVED })
        }
    }

    @Test
    fun anAddressAlreadyInTheListIsNotTakenTwice() = runBlocking {
        Harness("move-taken").use { h ->
            h.addFixture(id = "holder")
            val id = h.moved()
            assertEquals(ProblemKind.UNSUPPORTED, h.engine.followMove(id)?.kind)
            assertEquals(old, h.engine.store.app(id)!!.config.source)
        }
    }

    @Test
    fun keepingTheOldAddressHidesThatSuggestionButNotANewOne() = runBlocking {
        Harness("move-keep").use { h ->
            val id = h.moved()
            h.engine.keepAddress(id)
            assertNull(h.row(id).movedTo)
            assertEquals(FakeForge.PROJECT, h.state(id).keptAddress)
            assertEquals(old, h.engine.store.app(id)!!.config.source)
            assertTrue(h.forge.requests.isEmpty())

            h.engine.saveState(id) { it.copy(movedTo = FakeForge.PROJECT) }
            h.engine.publish()
            assertNull(h.row(id).movedTo)
            h.engine.saveState(id) { it.copy(movedTo = "https://forge.test/example/newer") }
            h.engine.publish()
            assertEquals("https://forge.test/example/newer", h.row(id).movedTo)
        }
    }
}
