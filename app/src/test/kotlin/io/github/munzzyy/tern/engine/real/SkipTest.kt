package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.StoredApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipTest {
    private val app = StoredApp(AppConfig(id = "a", source = SourceSpec(SourceTypes.FORGEJO, "https://forge.test/example/app"), name = "Kestrelwort"), AppState())

    @Test
    fun aSkipFromANotificationSkipsTheReleaseItWasAbout() {
        assertTrue(RealEngine.skips(offered = "v2.0", asked = "v2.0"))
    }

    @Test
    fun aSkipAboutAnOlderReleaseLeavesTheNewerOneOnOffer() {
        assertFalse(RealEngine.skips(offered = "v3.0", asked = "v2.0"))
        assertFalse(RealEngine.skips(offered = null, asked = "v2.0"))
    }

    @Test
    fun anAppTernInstallsSkipsTheReleaseAndATrackedOneMarksItSeen() {
        val skipped = RealEngine.setAside(app, "v2.0")
        assertEquals("v2.0", skipped.config.releases.skippedReleaseId)
        assertNull(skipped.state.seenReleaseId)

        val seen = RealEngine.setAside(app.copy(config = app.config.copy(trackOnly = true)), "v2.0")
        assertEquals("v2.0", seen.state.seenReleaseId)
        assertNull(seen.config.releases.skippedReleaseId)
    }
}
