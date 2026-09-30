package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.apk.HttpRangeSource
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.TokenProvider
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.data.Store
import io.github.munzzyy.tern.log.TernLog
import java.io.IOException

/** What a remote inspection cost, for measurement. */
data class InspectionCost(val url: String, val requests: Int, val bytes: Long)

/**
 * Reads an APK's identity from the server with range requests and caches it by the file's URL, size
 * and published digest. A file without size or digest is also keyed by its release, since the same
 * URL may serve new bytes.
 */
class FileInspector(
    private val http: HttpClient,
    private val store: Store,
    private val tokens: TokenProvider,
    private val sdk: Int,
) {
    @Volatile
    var lastCost: InspectionCost? = null
        private set

    fun key(asset: Asset, releaseId: String): String {
        val tail = if (asset.size == null && asset.sha256 == null) "|release=$releaseId" else ""
        return "${asset.url}|${asset.size}|${asset.sha256?.lowercase()}$tail"
    }

    fun cached(asset: Asset, releaseId: String): FileFacts? = store.facts(key(asset, releaseId))

    /**
     * Cached or read remotely; null when the file cannot be inspected before download. [where]
     * says where the file is to be read from now, which a source whose links expire works out.
     */
    fun inspect(asset: Asset, releaseId: String, where: () -> Download = { Download(asset.url) }): FileFacts? {
        cached(asset, releaseId)?.let { return it }
        if (asset.kind != AssetKind.APK) return null
        return try {
            val from = where()
            val sameHost = Urls.host(from.url) == Urls.host(asset.url)
            val authorization = if (asset.needsAuth && sameHost) tokens.tokenFor(Urls.host(asset.url))?.let { "Bearer $it" } else null
            val source = HttpRangeSource(http, from.url, authorization, extraHeaders = from.headers)
            val info = source.use { ApkInspector.inspect(it) }
            lastCost = InspectionCost(asset.url, source.requestCount, source.bytesFetched)
            val facts = FileFacts.of(info, sdk)
            store.putFacts(key(asset, releaseId), asset.url, asset.size, asset.sha256, facts)
            facts
        } catch (e: IOException) {
            TernLog.i(TAG, "Could not inspect ${asset.name} remotely: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: SourceException) {
            TernLog.i(TAG, "The source could not say where ${asset.name} is: ${e.message}")
            null
        }
    }

    fun remember(asset: Asset, releaseId: String, facts: FileFacts) {
        store.putFacts(key(asset, releaseId), asset.url, asset.size, asset.sha256, facts)
    }

    private companion object {
        const val TAG = "TernInspect"
    }
}
