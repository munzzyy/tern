package io.github.munzzyy.tern.core.select

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.source.SourceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileOriginTest {
    private fun foreign(type: String, source: String, address: String): String? =
        FileOrigin.foreignHost(type, source, Asset("app.apk", address))

    @Test
    fun aFileOnTheSourcesOwnSiteIsNotForeign() {
        assertNull(foreign(SourceTypes.GITHUB, "https://github.com/example/app", "https://github.com/example/app/releases/download/v1/app.apk"))
        assertNull(foreign(SourceTypes.FDROID, "https://apt.izzysoft.de/fdroid/index/apk/org.example", "https://apt.izzysoft.de/fdroid/repo/org.example_1.apk"))
        assertNull(foreign(SourceTypes.HTML, "https://www.example.org/download", "https://downloads.example.org/app.apk"))
        assertNull(foreign(SourceTypes.RUSTORE, "https://www.rustore.ru/catalog/app/org.example", "https://static.rustore.ru/a.apk"))
    }

    @Test
    fun aFileOnAnotherSiteIsNamedByItsHost() {
        assertEquals("objects.example-cdn.net", foreign(SourceTypes.HTML, "https://example.org/app", "https://objects.example-cdn.net/app.apk"))
        assertEquals("github.com", foreign(SourceTypes.GITLAB, "https://gitlab.com/example/app", "https://github.com/example/app/releases/download/v1/app.apk"))
    }

    @Test
    fun aStoresOwnFileHostIsItsOwn() {
        assertNull(foreign(SourceTypes.HUAWEI, "https://appgallery.huawei.com/app/C100", "https://appdl-1-drcn.dbankcdn.com/dl/appdl/application/apk/app.apk"))
        assertNull(foreign(SourceTypes.APKPURE, "https://apkpure.com/example/org.example", "https://data.winudf.com/v2/APK/app.apk"))
        assertNull(foreign(SourceTypes.SAMSUNG, "https://galaxystore.samsung.com/detail/org.example", "https://download.samsungapps.com/app.apk"))
        // What one store keeps elsewhere is no other source's own.
        assertEquals("data.winudf.com", foreign(SourceTypes.HTML, "https://example.org/app", "https://data.winudf.com/v2/APK/app.apk"))
    }

    @Test
    fun aSiteUnderACountrysSecondLevelKeepsThreeLabels() {
        assertEquals("example.co.uk", FileOrigin.site("downloads.example.co.uk"))
        assertEquals("vivo.com.cn", FileOrigin.site("appstore.vivo.com.cn"))
        assertEquals("example.org", FileOrigin.site("www.example.org"))
        assertEquals("example.de", FileOrigin.site("example.de"))
        assertEquals("192.168.1.20", FileOrigin.site("192.168.1.20"))
        assertEquals("other.co.uk", foreign(SourceTypes.HTML, "https://example.co.uk/app", "https://files.other.co.uk/app.apk")?.let(FileOrigin::site))
    }
}
