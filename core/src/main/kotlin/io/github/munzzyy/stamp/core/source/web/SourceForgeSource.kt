package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.net.Validator
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.Source
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceListing
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.source.guarded
import io.github.munzzyy.stamp.core.version.Version
import io.github.munzzyy.stamp.core.xml.XmlScanner

class SourceForgeSource : Source {
    override val type: String = SourceTypes.SOURCEFORGE

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        if (uri.host?.lowercase() != "sourceforge.net") return null
        val path = uri.path?.trimEnd('/') ?: return null
        val match = PROJECT_PATH.find(path) ?: return null
        return SourceSpec(type, "https://sourceforge.net/projects/${match.groupValues[1]}")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val feedUrl = "${spec.url}/rss?path=/"
        val key = validatorKey(spec, feedUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(feedUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $feedUrl")
            context.validators.put(key, Validator.from(it.headers))
            val root = try {
                XmlScanner.parse(it.text(4 * 1024 * 1024))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not read the SourceForge feed", cause = e)
            }
            val channel = root.child("channel") ?: root
            val grouped = LinkedHashMap<String, MutableList<Asset>>()
            for (item in channel.children("item")) {
                val named = item.childText("link") ?: item.childText("guid") ?: continue
                val link = Urls.resolve(feedUrl, named) ?: continue
                if (Urls.authority(link) != Urls.authority(spec.url)) continue
                val stripped = link.removeSuffix("/download")
                val path = stripped.substringBefore('?').lowercase()
                if (INSTALLABLE.none { path.endsWith(it) }) continue
                val fileName = stripped.substringAfterLast('/')
                val version = VersionGuess.find(fileName) ?: continue
                grouped.getOrPut(version) { ArrayList() }.add(Asset(name = fileName, url = stripped))
            }
            if (grouped.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable files at ${spec.url}")
            val releases = grouped.entries
                .sortedWith(compareByDescending { Version.parse(it.key) })
                .take(30)
                .map { (version, assets) -> Release(id = version, version = version, assets = assets) }
            return CheckResult.Listing(SourceListing(releases = releases))
        }
    }

    companion object {
        private val PROJECT_PATH = Regex("^/projects/([^/]+)(?:/files.*)?$")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")
    }
}
