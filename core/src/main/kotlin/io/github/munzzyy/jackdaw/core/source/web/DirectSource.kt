package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Headers
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.HttpResponse
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.core.source.guarded
import java.text.SimpleDateFormat
import java.util.Locale

class DirectSource : Source {
    override val type: String = SourceTypes.DIRECT

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val path = uri.path?.lowercase() ?: return null
        if (INSTALLABLE.none { path.endsWith(it) }) return null
        return SourceSpec(type, uri.toString())
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val key = validatorKey(spec, "asset")
        val conditional = context.validators.get(key)?.conditionalHeaders() ?: emptyMap()
        ask(spec.url, conditional, context).use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no file at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            val probe = Probe.of(it.headers)
            val id = probe.identity ?: throw SourceException(SourceErrorKind.NO_RELEASES, "The server says nothing that identifies ${spec.url}")
            val version = it.headers["Last-Modified"]?.let(::formatDate) ?: ""
            val name = Urls.segments(spec.url).lastOrNull()?.take(200) ?: "download.apk"
            context.validators.put(key, Validator.from(it.headers))
            val release = Release(id = id, version = version, assets = listOf(Asset(name = name, url = spec.url, size = probe.size)))
            return CheckResult.Listing(SourceListing(releases = listOf(release), name = name))
        }
    }

    private fun formatDate(header: String): String? = try {
        val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header)
        SimpleDateFormat("yyyy.MM.dd", Locale.US).format(parsed)
    } catch (_: java.text.ParseException) {
        null
    }

    /** What a server says about a file without sending it. */
    internal class Probe(val identity: String?, val size: Long?) {
        companion object {
            fun of(headers: Headers): Probe {
                val size = headers["Content-Range"]?.substringAfterLast('/')?.toLongOrNull() ?: headers["Content-Length"]?.toLongOrNull()
                val identity = headers["ETag"]?.takeIf { it.isNotBlank() }
                    ?: headers["Last-Modified"]?.takeIf { it.isNotBlank() }
                    ?: size?.takeIf { it > 0 }?.toString()
                return Probe(identity?.take(200), size?.takeIf { it > 0 })
            }
        }
    }

    companion object {
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")

        /** HEAD, or the first byte where a server refuses HEAD. */
        private fun ask(url: String, headers: Map<String, String>, context: CheckContext): HttpResponse {
            val head = context.http.execute(HttpRequest(url, method = "HEAD", headers = headers))
            if (head.status != 403 && head.status != 405 && head.status != 501) return head
            head.close()
            return context.http.execute(HttpRequest(url, headers = headers + mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity")))
        }

        internal fun probe(url: String, context: CheckContext): Probe = ask(url, emptyMap(), context).use {
            if (it.isSuccess) Probe.of(it.headers) else Probe(null, null)
        }
    }
}
