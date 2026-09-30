package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOptionsTest {
    private val page = SourceSpec(
        SourceTypes.HTML,
        "https://example.org/downloads",
        mapOf(
            SourceOptions.LINK_FILTER to "\\.apk$",
            SourceOptions.STEPS to """["releases",{"filter":"arm64","text":true,"arch":true}]""",
            SourceOptions.SORT to "page",
        ),
    )

    @Test
    fun theDraftShowsWhatIsStoredAndSavesWhatWasTyped() {
        val draft = OptionsDraft.of(page)
        assertEquals("releases\narm64", draft.steps)
        val saved = draft.copy(steps = "releases\narm64\nlatest", headers = "User-Agent: Mozilla/5.0").applyTo(page)
        val steps = Json.parseArray(saved.option(SourceOptions.STEPS)!!)
        // A step that was an object keeps what it said about the link's text and the processor.
        assertEquals(Json.parseObject("""{"filter":"arm64","text":true,"arch":true}"""), steps[1])
        assertEquals("latest", (steps[2] as io.github.munzzyy.tern.core.json.JsonString).value)
        assertEquals("page", saved.option(SourceOptions.SORT))
        assertEquals("""{"User-Agent":"Mozilla/5.0"}""", saved.option(SourceOptions.HEADERS))
        assertNull(draft.copy(linkFilter = "  ").applyTo(page).option(SourceOptions.LINK_FILTER))
    }

    @Test
    fun whatCannotBeSentOrUsedIsCaughtBeforeSaving() {
        assertNull(OptionsDraft.headerMap("Cookie: session=1"))
        assertNull(OptionsDraft.headerMap("Authorization: Bearer x"))
        assertNull(OptionsDraft.headerMap("Accept: a\nACCEPT: b"))
        assertEquals(mapOf("Accept" to "text/html"), OptionsDraft.headerMap(" Accept : text/html \n"))
        assertTrue("headers" in OptionsDraft(headers = "Cookie: x").invalid(SourceTypes.DIRECT))
        assertTrue("linkFilter" in OptionsDraft(linkFilter = "(").invalid(SourceTypes.HTML))
        assertTrue("workflow" in OptionsDraft(workflow = "build").invalid(SourceTypes.GITHUB_ACTIONS))
        assertEquals(emptySet<String>(), OptionsDraft(workflow = "build.yml", branch = "main").invalid(SourceTypes.GITHUB_ACTIONS))
        assertTrue("csc" in OptionsDraft(csc = "EUROPE").invalid(SourceTypes.SAMSUNG))
    }
}
