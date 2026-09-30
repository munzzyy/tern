package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.ReleaseOrder
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.web.HtmlSource
import io.github.munzzyy.tern.core.source.web.HtmlStep
import io.github.munzzyy.tern.core.testing.FakeHttp
import io.github.munzzyy.tern.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apps Tern 0.1.0 saved or exported mean after the update what they meant before it. */
class V010ConfigTest {
    private val export = Fixtures.text("interop/tern-0.1.0-export.json")
    private fun rows() = Json.parseObject(export).array("apps")!!.objects()
    private fun web(): AppConfig = AppConfigJson.decode(rows()[0])
    private fun github(): AppConfig = AppConfigJson.decode(rows()[1])

    @Test
    fun aStepOfAWebPageAppStillTakesTheFirstMatchInThePagesOrder() {
        val steps = HtmlStep.parse(web().source.option(SourceOptions.STEPS))!!
        assertEquals(listOf(HtmlStep("/v/", pageOrder = true, firstLink = true)), steps)
        assertTrue(web().source.flag(SourceOptions.HIGHEST_VERSION))
        assertEquals("\\.apk$", web().source.option(SourceOptions.LINK_FILTER))
    }

    @Test
    fun theWaitOf0DaysV010AlwaysWroteFollowsTheSetting() {
        assertNull(web().releases.minAgeDays)
        assertNull(github().releases.minAgeDays)
        assertEquals(ReleaseOrder.VERSION, web().releases.order)
    }

    @Test
    fun otherSourcesAndEverythingElseComeThroughAsTheyWere() {
        val app = github()
        assertEquals(emptyMap<String, String>(), app.source.options)
        assertTrue(app.favorite)
        assertEquals(listOf("Tools"), app.categories)
        assertEquals(listOf("a894d99944e6d3a5f69bbdfc6f8c4a0a8bb0b85c1e0d0a8c5c1d2e3f4a5b6c7d"), web().pinnedSigners)
        assertEquals("^(\\d+\\.\\d+)", web().releases.versionExtract)
    }

    @Test
    fun aV010ExportIsReadTheSameWay() {
        val apps = TernExport.read(export)
        assertEquals(web().source, apps[0].source)
        assertNull(apps[1].releases.minAgeDays)
    }

    @Test
    fun whatIsSavedAgainIsReadAgainTheSame() {
        for (app in listOf(web(), github())) {
            val saved = AppConfigJson.encode(app)
            assertEquals(2L, saved.long("schema"))
            assertEquals(app, AppConfigJson.decode(Json.parseObject(Json.write(saved))))
        }
    }

    @Test
    fun aRowWrittenAfterV010IsNotChanged() {
        val later = AppConfigJson.encode(AppConfigJson.decode(rows()[1]).copy(releases = github().releases.copy(minAgeDays = 0)))
        val asSchema1 = Json.parseObject(Json.write(later).replace("\"schema\":2", "\"schema\":1"))
        assertEquals(0, AppConfigJson.decode(asSchema1).releases.minAgeDays)
    }

    @Test
    fun theV010WebPageAppFollows19OnThePage191101AndOffersTheHighestVersion() {
        val start = "https://example.com/project"
        val http = FakeHttp()
            .text(start, """<a href="/v/1.9/">1.9</a><a href="/v/1.10/">1.10</a><a href="/v/1.2/">1.2</a>""")
            .text("https://example.com/v/1.9/", """<a href="/beta/app-1.9.3.apk">beta</a><a href="/stable/app-1.9.1.apk">stable</a>""")
        val app = web()
        val listing = (HtmlSource().check(app.source, CheckContext(http, InMemoryValidatorStore(), app = app)) as CheckResult.Listing).listing
        assertEquals(setOf("1.9.3", "1.9.1"), listing.releases.map { it.version }.toSet())
        assertFalse(listing.releases.any { it.latest })
        // v0.1.0 took the highest version and read it with the app's pattern; natural order of the addresses would take /stable/.
        val chosen = ReleaseSelector.select(listing.releases, app.releases, 0L) { true }.candidate!!
        assertTrue(chosen.assets.single().url.endsWith("/beta/app-1.9.3.apk"))
        assertEquals("1.9", chosen.version)
    }
}
