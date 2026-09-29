package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import java.text.SimpleDateFormat
import java.util.Locale

class DirectSource : Source {
    override val type: String = SourceTypes.DIRECT

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val path = uri.path?.lowercase() ?: return null
        if (INSTALLABLE.none { path.endsWith(it) }) return null
        return SourceSpec(type, url)
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val key = validatorKey(spec, "asset")
        val validator = context.validators.get(key)
        val conditional = validator?.conditionalHeaders() ?: emptyMap()
        var response = context.http.execute(HttpRequest(spec.url, method = "HEAD", headers = conditional))
        if (response.status == 403 || response.status == 405) {
            response.close()
            response = context.http.execute(HttpRequest(spec.url, method = "GET", headers = conditional + ("Range" to "bytes=0-0")))
        }
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (!it.isSuccess && it.status != 206) {
                throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            }
            context.validators.put(key, Validator.from(it.headers))
            val etag = it.headers["ETag"]
            val lastModified = it.headers["Last-Modified"]
            val size = contentSize(it.headers)
            val id = etag ?: lastModified ?: size?.toString()
                ?: throw SourceException(SourceErrorKind.NO_RELEASES, "No identity for ${spec.url}")
            val version = lastModified?.let(::formatDate) ?: id.take(12)
            val name = spec.url.substringAfterLast('/').substringBefore('?')
            val release = Release(id = id, version = version, assets = listOf(Asset(name = name, url = spec.url, size = size)))
            return CheckResult.Listing(SourceListing(releases = listOf(release), name = name))
        }
    }

    private fun contentSize(headers: Headers): Long? {
        headers["Content-Range"]?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
        return headers["Content-Length"]?.toLongOrNull()
    }

    private fun formatDate(header: String): String? = try {
        val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header)
        SimpleDateFormat("yyyy.MM.dd", Locale.US).format(parsed)
    } catch (_: java.text.ParseException) {
        null
    }

    companion object {
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
    }
}
