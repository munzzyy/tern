package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
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
import java.io.ByteArrayOutputStream
import java.io.IOException

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
        // The answer may not rename the app that was asked for: the name goes into file and icon addresses.
        val packageName = pkg
        val releases = response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Package $pkg not found")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $apiUrl")
            context.validators.put(key, Validator.from(it.headers))
            val obj = Json.parseObject(it.text(2 * 1024 * 1024))
            if (obj.string("packageName")?.let { it != pkg } == true) {
                throw SourceException(SourceErrorKind.PARSE, "Asked for $pkg and was answered for another package")
            }
            val suggested = obj.long("suggestedVersionCode") ?: Long.MAX_VALUE
            val entries = obj.array("packages")?.objects().orEmpty()
            entries.mapNotNull { entry ->
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
        }
        if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $pkg")
        // F-Droid's own data says who wrote the app and where its changes are told; IzzyOnDroid keeps none of it there.
        val about = if (izzy) null else about(packageName, context)
        val listing = SourceListing(releases = withChangelog(releases, about?.changelog), packageName = packageName, author = about?.author)
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(FDroidIcons.byConvention(repoBase, packageName))))
    }

    private class About(val author: String?, val changelog: String?)

    /**
     * The author and the changelog F-Droid's data names for [pkg]. A changelog kept as a file on
     * GitHub or GitLab is read, as much of it as is shown; any other is its address. Null when the
     * data cannot be had: the versions do not wait for it.
     */
    private fun about(pkg: String, context: CheckContext): About? {
        val lines = try {
            context.http.execute(HttpRequest("$METADATA/$pkg.yml")).use { if (it.isSuccess) it.text(METADATA_CAP).lines() else null }
        } catch (_: IOException) {
            null
        } ?: return null
        val changelog = field(lines, "Changelog")?.let { address -> rawFile(address)?.let { read(it, context) } ?: address }
        return About(field(lines, "AuthorName"), changelog?.let(::cut))
    }

    /** The first part of the file at [url], or null when it cannot be read. */
    private fun read(url: String, context: CheckContext): String? = try {
        context.http.execute(HttpRequest(url)).use { response ->
            if (!response.isSuccess) return@use null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < CHANGELOG_READ) {
                val n = response.body.read(buffer, 0, minOf(buffer.size, CHANGELOG_READ - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8).takeIf { it.isNotBlank() }
        }
    } catch (_: IOException) {
        null
    }

    /** The newest release and the newest that is not a pre-release carry the changelog: one of them is the one offered. */
    private fun withChangelog(releases: List<Release>, changelog: String?): List<Release> {
        if (changelog == null) return releases
        val stable = releases.indexOfFirst { !it.countsAsPrerelease }
        return releases.mapIndexed { i, release -> if (i == 0 || i == stable) release.copy(notes = changelog, notesFormat = NotesFormat.MARKDOWN) else release }
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

        /** Where F-Droid keeps what it knows of each app beyond its builds, one file per package. */
        private const val METADATA = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata"
        private const val METADATA_CAP = 256 * 1024

        /** As much of a changelog as Obtainium shows, in characters. */
        private const val MAX_CHANGELOG = 2048

        /** Bytes read of a changelog file: more than [MAX_CHANGELOG] characters take in any script. */
        private const val CHANGELOG_READ = 16 * 1024

        /** The value of the first line of [lines] that sets [name], without the quotes around it. */
        internal fun field(lines: List<String>, name: String): String? {
            val value = lines.firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ")?.trim() ?: return null
            val unquoted = when {
                value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1).replace("''", "'")
                value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1)
                else -> value
            }
            return unquoted.trim().takeIf { it.isNotEmpty() }
        }

        /** The raw form of a file on GitHub or GitLab named by its page, as in github.com/o/r/blob/main/CHANGELOG.md; null for any other address. */
        internal fun rawFile(address: String): String? {
            val uri = Urls.parseHttps(address) ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")
            if ((host != "github.com" && host != "gitlab.com") || uri.port != -1) return null
            // The path starts with the owner and the project, and "blob" follows them before the branch and the file.
            val segments = uri.rawPath.orEmpty().split('/')
            val blob = segments.indices.firstOrNull { it >= 3 && segments[it] == "blob" }?.takeIf { it + 2 <= segments.lastIndex } ?: return null
            val path = segments.mapIndexed { i, segment -> if (i == blob) "raw" else segment }.joinToString("/")
            return "https://$host$path" + uri.rawQuery?.let { "?$it" }.orEmpty()
        }

        /** [text] cut to [MAX_CHANGELOG] characters, never between the two halves of one, and marked as cut. */
        internal fun cut(text: String): String {
            if (text.length <= MAX_CHANGELOG) return text
            val end = if (Character.isHighSurrogate(text[MAX_CHANGELOG - 1])) MAX_CHANGELOG - 1 else MAX_CHANGELOG
            return text.substring(0, end) + "…"
        }
    }
}
