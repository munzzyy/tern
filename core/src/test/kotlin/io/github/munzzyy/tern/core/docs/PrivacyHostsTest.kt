package io.github.munzzyy.tern.core.docs

import io.github.munzzyy.tern.core.icon.IconAddresses
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.store.ItchIoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PRIVACY.md names every host a store is read at and every host an icon may come from, so a new one cannot slip in unsaid. */
class PrivacyHostsTest {
    private val privacy = File("../PRIVACY.md").readText()

    @Test
    fun everyDomainOfAStoreIsNamed() {
        val registry = SourceRegistry.standard()
        val missing = registry.sources.filter { it.type in SourceTypes.THIRD_PARTY_STORES }.flatMap { source ->
            assertTrue("${source.type} names its domains", source.domains.isNotEmpty())
            source.domains.filter { it !in privacy }.map { "${source.type}: $it" }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun everyHostAnIconMayComeFromIsNamed() {
        val hosts = IconAddresses.OTHER_HOSTS.values.flatten() + IconAddresses.OTHER_HOSTS_UNDER.values.flatten()
        assertEquals(emptyList<String>(), hosts.distinct().filter { it !in privacy })
    }

    @Test
    fun theFileStoresOfTheDevelopersChannelsAreNamed() {
        assertEquals(emptyList<String>(), (ItchIoSource.FILE_HOSTS + "telesco.pe" + "search.f-droid.org").filter { it !in privacy })
    }
}
