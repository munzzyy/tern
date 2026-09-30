package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner

class FDroidSource : Source, Searchable {
    override val type: String = SourceTypes.FDROID

    override val origin: String = "F-Droid"

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        val pkg = when (host) {
            "f-droid.org" -> FDROID_PATH.find(path)?.groupValues?.get(1)
            "apt.izzysoft.de" -> IZZY_APT_PATH.find(path)?.groupValues?.get(1)
            "android.izzysoft.de" -> IZZY_ANDROID_PATH.find(path)?.groupValues?.get(1)
            else -> null
        } ?: return null
        if (!isValidPackage(pkg)) return null
        val canonical = if (host == "f-droid.org") "https://f-droid.org/packages/$pkg" else "https://apt.izzysoft.de/fdroid/index/apk/$pkg"
        return SourceSpec(type, canonical, mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
        val izzy = Urls.host(spec.url).let { it == "izzysoft.de" || it.endsWith(".izzysoft.de") }
        val apiUrl = if (izzy) "https://apt.izzysoft.de/fdroid/api/v1/packages/$pkg" else "https://f-droid.org/api/v1/packages/$pkg"
        val repoBase = if (izzy) "https://apt.izzysoft.de/fdroid/repo" else "https://f-droid.org/repo"
        val key = validatorKey(spec, apiUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(apiUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Package $pkg not found")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $apiUrl")
            context.validators.put(key, Validator.from(it.headers))
            val obj = Json.parseObject(it.text(2 * 1024 * 1024))
            // The answer may not rename the app that was asked for: the name goes into file and icon addresses.
            val packageName = pkg
            if (obj.string("packageName")?.let { it != pkg } == true) {
                throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
            }
            val suggested = obj.long("suggestedVersionCode") ?: Long.MAX_VALUE
            val entries = obj.array("packages")?.objects().orEmpty()
            val releases = entries.mapNotNull { entry ->
                val versionCode = entry.long("versionCode") ?: return@mapNotNull null
                val versionName = entry.string("versionName") ?: return@mapNotNull null
                val assetName = "${packageName}_$versionCode.apk"
                Release(
                    id = versionCode.toString(),
                    version = versionName,
                    versionCode = versionCode,
                    prerelease = versionCode > suggested,
                    assets = listOf(Asset(name = assetName, url = "$repoBase/$assetName")),
                )
            }.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $pkg")
            val listing = SourceListing(releases = releases, packageName = packageName)
            return CheckResult.Listing(listing.withIcons(spec.url, listOf(FDroidIcons.byConvention(repoBase, packageName))))
        }
    }

    /** The first page of search.f-droid.org, as the site shows it. Each hit is the package page of f-droid.org. */
    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) { searchOnce(query, it) }

    private fun searchOnce(query: String, context: CheckContext): List<Hit> {
        val words = query.trim().take(MAX_QUERY)
        if (words.isEmpty()) return emptyList()
        val url = "$SEARCH?q=${Urls.encodeSegment(words)}&lang=en"
        val html = context.http.execute(HttpRequest(url)).use {
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $SEARCH")
            it.text(SEARCH_CAP)
        }
        val headers = PACKAGE_HEADER.findAll(html).take(MAX_HEADERS).toList()
        return headers.asSequence().mapIndexedNotNull { i, header ->
            val href = HREF.find(header.value)?.groupValues?.get(1)?.let(XmlScanner::decode) ?: return@mapIndexedNotNull null
            val page = Urls.resolve(url, href)?.let(::match)?.url?.takeIf { it.startsWith(PACKAGES) } ?: return@mapIndexedNotNull null
            val block = html.substring(header.range.last + 1, minOf(headers.getOrNull(i + 1)?.range?.first ?: html.length, header.range.last + 1 + HIT_WINDOW))
            val name = inside(block, PACKAGE_NAME, "</h4")
            val summary = inside(block, PACKAGE_SUMMARY, "</span")
            Hit(name = name ?: page.removePrefix(PACKAGES), owner = null, description = summary, url = page)
        }.distinctBy { it.url }.take(MAX_HITS).toList()
    }

    /** The text of the element that [opening] finds in [block], up to [closing]. */
    private fun inside(block: String, opening: Regex, closing: String): String? {
        val from = opening.find(block)?.range?.last?.plus(1) ?: return null
        val to = block.indexOf(closing, from, ignoreCase = true)
        if (to < 0) return null
        return XmlScanner.decode(TAG.replace(block.substring(from, to), " ")).replace(SPACES, " ").trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MAX_RELEASES = 30
        private val FDROID_PATH = Regex("^(?:/[a-zA-Z-]{2,7})?/packages/([^/]+)$")
        private val IZZY_APT_PATH = Regex("^/fdroid/index/apk/([^/]+)$")
        private val IZZY_ANDROID_PATH = Regex("^/repo/apk/([^/]+)$")
        private val SEGMENT = Regex("^[A-Za-z][A-Za-z0-9_]*$")

        private const val SEARCH = "https://search.f-droid.org/"
        private const val PACKAGES = "https://f-droid.org/packages/"
        private const val SEARCH_CAP = 2 * 1024 * 1024
        private const val MAX_QUERY = 200
        private const val MAX_HITS = 25
        private const val MAX_HEADERS = 200
        private const val HIT_WINDOW = 8 * 1024
        private val PACKAGE_HEADER = Regex("""<a\s[^>]{0,1000}?class="(?:[^"]{0,500}\s)?package-header(?:\s[^"]{0,500})?"[^>]{0,1000}>""")
        private val HREF = Regex("""(?:^|\s)href="([^"]{1,2000})"""")
        private val PACKAGE_NAME = Regex("""class="(?:[^"]{0,500}\s)?package-name(?:\s[^"]{0,500})?"[^>]{0,1000}>""")
        private val PACKAGE_SUMMARY = Regex("""class="(?:[^"]{0,500}\s)?package-summary(?:\s[^"]{0,500})?"[^>]{0,1000}>""")
        private val TAG = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")

        fun isValidPackage(pkg: String): Boolean {
            val segments = pkg.split('.')
            return segments.size >= 2 && segments.all { SEGMENT.matches(it) }
        }
    }
}
