package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Headers
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.HttpResponse
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Download
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import java.security.MessageDigest
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

    /** Any address, taken to serve the app's file itself, when the person says so. */
    override fun matchForced(url: String, context: CheckContext): SourceSpec? = Urls.normalize(url)?.let { SourceSpec(type, it) }

    /** An address that does not end in a file name, recognised by what the server says about it; the body is never read. */
    override fun probe(url: String, context: CheckContext): SourceSpec? {
        val address = Urls.normalize(url) ?: return null
        ask(address, emptyMap(), context).use {
            if (!it.isSuccess) return null
            return if (ServedFile.announced(it.headers, it.url) != null) SourceSpec(type, address) else null
        }
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    /** The file is fetched with the headers the address asks for. */
    override fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = Download(asset.url, RequestHeaders.of(spec))

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val headers = RequestHeaders.of(spec)
        val pseudo = PseudoVersion.of(spec)
        val key = validatorKey(spec, "asset")
        val conditional = context.validators.get(key)?.conditionalHeaders() ?: emptyMap()
        ask(spec.url, headers + conditional, context).use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no file at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            val probe = Probe.of(it.headers)
            val id = when (pseudo) {
                null -> probe.identity
                PseudoVersion.ETAG -> probe.etag
                PseudoVersion.LINK -> linkHash(Urls.normalize(it.url) ?: spec.url)
                PseudoVersion.HASH -> firstBytes(spec.url, context, headers).identity
            } ?: throw SourceException(
                SourceErrorKind.NO_RELEASES,
                if (pseudo == PseudoVersion.ETAG) "The server sends no ETag for ${spec.url}" else "The server says nothing that identifies ${spec.url}",
            )
            val version = it.headers["Last-Modified"]?.let(::formatDate) ?: ""
            val file = ServedFile.of(it.headers, it.url, spec.url)
                ?: throw SourceException(SourceErrorKind.NO_RELEASES, "${spec.url} no longer serves an app")
            context.validators.put(key, Validator.from(it.headers))
            val asset = Asset(name = file.name, url = spec.url, size = probe.size, kind = file.kind)
            val release = Release(id = id, version = version, assets = listOf(asset))
            return CheckResult.Listing(SourceListing(releases = listOf(release), name = file.name))
        }
    }

    private fun formatDate(header: String): String? = try {
        val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header)
        SimpleDateFormat("yyyy.MM.dd", Locale.US).format(parsed)
    } catch (_: java.text.ParseException) {
        null
    }

    /**
     * What tells a file from the one before it, and its size, as far as the server says without
     * sending it. [identity] is the ETag, else the Last-Modified, else the size, unless a
     * [PseudoVersion] says otherwise.
     */
    internal class Probe(val identity: String?, val size: Long?, val etag: String? = null) {
        companion object {
            fun of(headers: Headers): Probe {
                val size = headers["Content-Range"]?.substringAfterLast('/')?.toLongOrNull() ?: headers["Content-Length"]?.toLongOrNull()
                val etag = headers["ETag"]?.takeIf { it.isNotBlank() }?.take(200)
                val identity = etag
                    ?: headers["Last-Modified"]?.takeIf { it.isNotBlank() }
                    ?: size?.takeIf { it > 0 }?.toString()
                return Probe(identity?.take(200), size?.takeIf { it > 0 }, etag)
            }
        }
    }

    companion object {
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")

        /** How much of a file [PseudoVersion.HASH] reads. */
        const val HASHED_BYTES = 1024

        /** HEAD, or the first byte where a server refuses HEAD. */
        private fun ask(url: String, headers: Map<String, String>, context: CheckContext): HttpResponse {
            val head = context.http.execute(HttpRequest(url, method = "HEAD", headers = headers))
            if (head.status != 403 && head.status != 405 && head.status != 501) return head
            head.close()
            return context.http.execute(HttpRequest(url, headers = headers + mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity")))
        }

        internal fun probe(url: String, context: CheckContext, headers: Map<String, String> = emptyMap()): Probe = ask(url, headers, context).use {
            if (it.isSuccess) Probe.of(it.headers) else Probe(null, null)
        }

        /** What tells the file at [url] from the one before it, the way [pseudo] says; see [PseudoVersion]. */
        internal fun identify(url: String, pseudo: PseudoVersion?, context: CheckContext, headers: Map<String, String>): Probe = when (pseudo) {
            PseudoVersion.LINK -> Probe(linkHash(url), null)
            PseudoVersion.HASH -> firstBytes(url, context, headers)
            PseudoVersion.ETAG -> probe(url, context, headers).let { Probe(it.etag, it.size, it.etag) }
            null -> probe(url, context, headers)
        }

        /** A hash of the file's first [HASHED_BYTES] bytes, read with a Range request and never more, whether or not the server honours it. */
        internal fun firstBytes(url: String, context: CheckContext, headers: Map<String, String>): Probe {
            val request = HttpRequest(url, headers = headers + mapOf("Range" to "bytes=0-${HASHED_BYTES - 1}", "Accept-Encoding" to "identity"))
            context.http.execute(request).use {
                if (!it.isSuccess) return Probe(null, null)
                val buffer = ByteArray(HASHED_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val n = it.body.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled == 0) return Probe(null, Probe.of(it.headers).size)
                return Probe("sha256:" + sha256(buffer.copyOf(filled)), Probe.of(it.headers).size)
            }
        }

        internal fun linkHash(url: String): String = "link:" + sha256(url.toByteArray(Charsets.UTF_8))

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
