package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.engine.Detection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Store pages that route after a '#', pasted into the Add screen. */
@RunWith(AndroidJUnit4::class)
class HashRouteTest {
    private fun typeOf(found: Detection): String? = when (found) {
        is Detection.Found -> found.spec.type
        is Detection.Failed -> found.spec?.type
        else -> null
    }

    @Test
    fun aStorePageThatRoutesAfterTheHashIsReadAsThatStore() = runBlocking {
        val detail = "https://h5-api.appstore.vivo.com.cn/detailInfo?appId=40413"
        val routes = Routes(FakeForge()).json(detail, """{"id":40413,"title_zh":"Example","package_name":"org.example.app","version_name":"1.0","version_code":1}""")
        Harness("hashroute", http = routes).use { h ->
            h.engine.saveSettings(h.engine.settings.value.copy(thirdPartyStores = true))
            val vivo = h.engine.detect("https://h5.appstore.vivo.com.cn/#/details?appId=40413")
            assertEquals("vivo read as $vivo", SourceTypes.VIVO, typeOf(vivo))
            assertTrue(routes.requests.any { it.url == detail })
            val huawei = h.engine.detect("https://appgallery.huawei.com/#/app/C100336075")
            assertEquals("AppGallery read as $huawei", SourceTypes.HUAWEI, typeOf(huawei))
        }
    }
}
