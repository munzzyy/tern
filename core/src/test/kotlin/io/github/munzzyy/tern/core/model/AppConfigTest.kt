package io.github.munzzyy.tern.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppConfigTest {
    private val config = AppConfig(id = "a", source = SourceSpec("github", "https://github.com/example/app"), name = "app", author = "example")

    @Test
    fun theNameAndAuthorThePersonChoseAreShownInsteadOfTheSources() {
        val chosen = config.copy(customName = "Example App", customAuthor = "Example Labs")
        assertEquals("Example App", chosen.shownName)
        assertEquals("Example Labs", chosen.shownAuthor)
        assertEquals("Example App", chosen.copy(name = "renamed-by-source", author = null).shownName)
        assertEquals("Example Labs", chosen.copy(author = null).shownAuthor)
    }

    @Test
    fun withoutAChoiceOrWithABlankOneTheSourcesNameIsShown() {
        assertEquals("app", config.shownName)
        assertEquals("example", config.shownAuthor)
        assertEquals("app", config.copy(customName = " ").shownName)
        assertNull(config.copy(author = null, customAuthor = "").shownAuthor)
    }

    @Test
    fun theNewSwitchesAreOffByDefault() {
        assertFalse(config.muted)
        assertFalse(config.refreshFirst)
        assertFalse(config.playInstaller)
        assertFalse(config.assets.archives)
        assertEquals(ReleaseOrder.VERSION, config.releases.order)
        assertEquals(VersionFrom.TAG, config.releases.versionFrom)
    }
}
