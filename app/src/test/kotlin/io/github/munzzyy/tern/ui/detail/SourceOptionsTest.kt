package io.github.munzzyy.tern.ui.detail

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.HtmlStep
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
        assertEquals(listOf(HtmlStep("releases"), HtmlStep("arm64", byText = true, arch = true)), draft.steps)
        val typed = draft.steps + HtmlStep(" latest ", pageOrder = true, firstLink = true, lastSegment = true, anyText = true)
        val saved = draft.copy(steps = typed, headers = "Referer: https://example.com/").applyTo(page)
        val steps = Json.parseArray(saved.option(SourceOptions.STEPS)!!)
        assertEquals("releases", (steps[0] as io.github.munzzyy.tern.core.json.JsonString).value)
        assertEquals(Json.parseObject("""{"filter":"arm64","text":true,"arch":true}"""), steps[1])
        assertEquals(Json.parseObject("""{"filter":"latest","pageOrder":true,"firstLink":true,"lastSegment":true,"anyText":true}"""), steps[2])
        assertTrue("steps" in draft.copy(steps = listOf(HtmlStep(" "))).invalid(SourceTypes.HTML))
        assertTrue("steps" in draft.copy(steps = listOf(HtmlStep("("))).invalid(SourceTypes.HTML))
        assertEquals("page", saved.option(SourceOptions.SORT))
        assertEquals("""{"Referer":"https://example.com/"}""", saved.option(SourceOptions.HEADERS))
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
        assertNull("Tern says who it is to every site", OptionsDraft.headerMap("User-Agent: Mozilla/5.0"))
        assertTrue("headers" in OptionsDraft(headers = "user-agent: Example/1.0").invalid(SourceTypes.HTML))
    }
}
