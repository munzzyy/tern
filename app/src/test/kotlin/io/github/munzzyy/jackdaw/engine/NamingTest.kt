package io.github.munzzyy.jackdaw.engine

import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.engine.real.Naming
import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    private fun config(name: String, url: String = "https://github.com/example/magpie", pkg: String? = "org.example.magpie") =
        AppConfig(id = "1", source = SourceSpec("github", url), name = name, packageName = pkg)

    @Test
    fun anAppNamedAfterItsRepositoryTakesItsOwnName() {
        assertEquals("Magpie", Naming.afterInstall(config("magpie"), "Magpie"))
        assertEquals("Magpie Journal", Naming.afterInstall(config("MAGPIE"), "Magpie Journal"))
        assertEquals("F-Droid", Naming.afterInstall(config("fdroidclient", "https://gitlab.com/fdroid/fdroidclient"), "F-Droid"))
        assertEquals("Magpie", Naming.afterInstall(config("org.example.magpie"), "Magpie"))
        assertEquals("Magpie", Naming.afterInstall(config("example.org", "https://example.org/get"), "Magpie"))
        assertEquals("Magpie", Naming.afterInstall(config(""), "Magpie"))
    }

    @Test
    fun aNameTheUserChoseStays() {
        assertEquals("My journal", Naming.afterInstall(config("My journal"), "Magpie"))
        assertEquals("magpie beta", Naming.afterInstall(config("magpie beta"), "Magpie"))
    }

    @Test
    fun withoutALabelNothingChanges() {
        assertEquals("magpie", Naming.afterInstall(config("magpie"), null))
        assertEquals("magpie", Naming.afterInstall(config("magpie"), "  "))
    }
}
