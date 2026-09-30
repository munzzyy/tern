package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.StoredApp
import io.github.munzzyy.tern.engine.ProblemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PausedStoreTest {
    @Test
    fun aPausedStoreAppSaysWhyWithoutSayingPausedTwice() = runBlocking {
        Harness("paused").use { h ->
            h.engine.saveSettings(h.engine.settings.value.copy(thirdPartyStores = true))
            val config = AppConfig(id = "store", source = SourceSpec(SourceTypes.APKPURE, "https://apkpure.com/example/org.example.app"), name = "Store app")
            h.engine.store.putApp(config, AppState())
            h.engine.ready()
            h.engine.stored["store"] = StoredApp(config, AppState())
            h.engine.saveSettings(h.engine.settings.value.copy(thirdPartyStores = false))
            val problem = h.row("store").problem
            assertEquals(h.describe("store"), ProblemKind.STORES_OFF, problem?.kind)
            val status = targetContext.getString(R.string.stores_status_paused)
            assertFalse("the row reads \"$status. ${problem?.message}\"", problem!!.message.startsWith(status))
            assertTrue(h.forge.requests.isEmpty())
        }
    }
}
