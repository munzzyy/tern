package io.github.munzzyy.jackdaw.core.source.web

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpRequest
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
import io.github.munzzyy.jackdaw.core.xml.XmlScanner

class SourceHutSource : Source {
    override val type: String = SourceTypes.SOURCEHUT

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase() != "git.sr.ht") return null
        val path = uri.path?.trimEnd('/') ?: return null
        if (!REPO_PATH.matches(path)) return null
        return SourceSpec(type, "https://git.sr.ht$path")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val feedUrl = "${spec.url}/refs/rss.xml"
        val key = validatorKey(spec, feedUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(feedUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $feedUrl")
            context.validators.put(key, Validator.from(it.headers))
            val root = try {
                XmlScanner.parse(it.text(2 * 1024 * 1024))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not read the refs feed", cause = e)
            }
            val channel = root.child("channel") ?: root
            val items = channel.children("item").take(3)
            if (items.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No refs at ${spec.url}")
            val releases = items.mapNotNull { item ->
                val tag = item.childText("title") ?: return@mapNotNull null
                val link = item.childText("link") ?: return@mapNotNull null
                val refUrl = Urls.parseHttps(link)?.toString() ?: return@mapNotNull null
                Release(id = tag, version = tag, pageUrl = refUrl, assets = fetchArtifacts(refUrl, context))
            }
            return CheckResult.Listing(SourceListing(releases = releases))
        }
    }

    private fun fetchArtifacts(refUrl: String, context: CheckContext): List<Asset> {
        val response = try {
            context.http.execute(HttpRequest(refUrl))
        } catch (_: java.io.IOException) {
            return emptyList()
        }
        return response.use { page ->
            if (!page.isSuccess) return emptyList()
            LinkScanner.anchors(page.text(4 * 1024 * 1024)).mapNotNull { link ->
                val resolved = Urls.resolve(page.url, link.href) ?: return@mapNotNull null
                val path = resolved.substringBefore('?').lowercase()
                if (INSTALLABLE.none { path.endsWith(it) }) return@mapNotNull null
                Asset(name = resolved.substringAfterLast('/'), url = resolved)
            }
        }
    }

    companion object {
        private val REPO_PATH = Regex("/~[^/]+/[^/]+")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
    }
}
