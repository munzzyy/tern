package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.xml.XmlScanner
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.Locale

/**
 * APKPure, read from the app's page of older versions on apkpure.com, as a browser reads it. Each
 * version is one release, with a file for each build the store holds of it, fetched through the
 * site's own download address, d.apkpure.com/b/<APK or XAPK>/<package>?versionCode=<code>, which
 * sends on to the file on data.winudf.com. The page does not say which processors a build is for;
 * the download page of the newest version does, so that one page is read too when its version has
 * several builds, and on a known device the builds for processors it lacks are left out.
 */
class ApkPureSource : Source {
    override val type: String = SourceTypes.APKPURE

    override val republishes: Boolean get() = true

    override val domains: Set<String> get() = DOMAINS

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (SITES.none { host == it || host == "www.$it" || host == "m.$it" }) return null
        val segments = Urls.segments(uri.toString())
        // An address may start with a two-letter language, as in /de/<name>/<package>.
        val readings = if (segments.size >= 3 && segments[0].length == 2) listOf(segments.drop(1), segments) else listOf(segments)
        val (slug, pkg) = readings.firstOrNull { it.size >= 2 && it[0].any(Char::isLetterOrDigit) && FDroidSource.isValidPackage(it[1]) } ?: return null
        return SourceSpec(type, "$SITE/${Urls.encodeSegment(slug)}/$pkg", mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOf(spec)
        val page = pageOf(spec, pkg)
        val html = read("$page/versions", context) ?: throw SourceException(SourceErrorKind.NOT_FOUND, "APKPure has no app $pkg")
        val versions = versions(html, pkg)
        if (versions.isEmpty()) throw SourceException(SourceErrorKind.NOT_FOUND, "APKPure lists no version of $pkg")
        val newest = versions.first()
        val processors = if (newest.builds.size > 1) read("$page/download/${Urls.encodeSegment(newest.name)}", context)?.let(::processorsByCode).orEmpty() else emptyMap()
        val releases = versions.asSequence()
            .mapNotNull { release(page, pkg, it, processors, context.device) }
            .take(MAX_RELEASES)
            .toList()
        if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No version of $pkg on APKPure has a file for this device")
        val listing = SourceListing(
            releases = releases,
            name = text(TITLE, html),
            author = text(DEVELOPER, html),
            packageName = pkg,
            description = text(SUMMARY, html),
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(ICON.find(html)?.groupValues?.get(1)?.let(XmlScanner::decode))))
    }

    /** One build: whether it is a plain APK or APKPure's XAPK, and its version code. */
    private class Build(val kind: String, val code: Long)

    private class Version(val name: String, val builds: List<Build>, val size: Long?, val publishedAtMs: Long?)

    private fun release(page: String, pkg: String, version: Version, processors: Map<Long, List<String>>, device: DeviceProfile?): Release? {
        val assets = version.builds.mapNotNull { build ->
            val abis = processors[build.code].orEmpty()
            if (device != null && abis.isNotEmpty() && abis.none { it in device.abis }) return@mapNotNull null
            val name = "$pkg-${build.code}" + (if (abis.isEmpty()) "" else "-" + abis.joinToString(",")) + "." + build.kind.lowercase()
            val kind = Asset.kindOf(name)
            if (kind != AssetKind.APK && kind != AssetKind.BUNDLE) return@mapNotNull null
            val size = version.size.takeIf { version.builds.size == 1 }
            Asset(name = name, url = "$FILES/${build.kind}/$pkg?versionCode=${build.code}", size = size, kind = kind) to build.code
        }
        if (assets.isEmpty()) return null
        return Release(
            id = version.name,
            version = version.name,
            // Builds of one version may carry different codes; the lowest keeps any of them from reading as out of date.
            versionCode = assets.minOf { it.second },
            publishedAtMs = version.publishedAtMs,
            pageUrl = page,
            assets = assets.map { it.first },
        )
    }

    /** The versions the page lists, each once, the newest first by the day each was published. */
    private fun versions(html: String, pkg: String): List<Version> {
        val found = LinkedHashMap<String, Version>()
        for (tag in DIV.findAll(html)) {
            val attributes = tag.groupValues[1]
            if (!VERSION_ITEM.containsMatchIn(attributes)) continue
            val name = attribute(attributes, "data-dt-version")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION } ?: continue
            if (name in found) continue
            val listed = attribute(attributes, "data-dt-apklist")?.split(',').orEmpty() + listOfNotNull(attribute(attributes, "data-dt-apkid"))
            val builds = listed.mapNotNull { build(it.trim(), pkg) }.distinctBy { it.code }.take(MAX_BUILDS)
            if (builds.isEmpty()) continue
            val after = html.substring(tag.range.last + 1, minOf(html.length, tag.range.last + 1 + ITEM_WINDOW))
            found[name] = Version(
                name = name,
                builds = builds,
                size = attribute(attributes, "data-dt-filesize")?.toLongOrNull()?.takeIf { it > 0 },
                publishedAtMs = UPDATED.find(after)?.groupValues?.get(1)?.let(::dayMs),
            )
            if (found.size >= MAX_VERSIONS) break
        }
        // The page pins a version that is popular today near the top, whatever its age.
        return found.values.sortedByDescending { it.publishedAtMs ?: Long.MIN_VALUE }
    }

    /** A build named as the page names it, "b/XAPK/<id>", where the id is the package, the version code and more, joined by "_". */
    private fun build(apkId: String, pkg: String): Build? {
        val match = APK_ID.matchEntire(apkId) ?: return null
        val decoded = try {
            String(Base64.getUrlDecoder().decode(match.groupValues[2]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val front = decoded.substringBeforeLast('_', "")
        if (front.substringBeforeLast('_', "") != pkg) return null
        val code = front.substringAfterLast('_').toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Build(match.groupValues[1], code)
    }

    /** The processors of each build a download page describes, by the build's version code. */
    private fun processorsByCode(html: String): Map<Long, List<String>> {
        val found = HashMap<Long, List<String>>()
        for (code in BUILD_CODE.findAll(html).take(MAX_BUILDS)) {
            val window = html.substring(code.range.last + 1, minOf(html.length, code.range.last + 1 + BUILD_WINDOW))
            val said = ARCHITECTURE.find(window)?.groupValues?.get(1) ?: continue
            val abis = ABI.findAll(said).map { it.value }.filter { it in KNOWN_ABIS }.distinct().toList()
            val number = code.groupValues[1].toLongOrNull() ?: continue
            if (abis.isNotEmpty()) found.putIfAbsent(number, abis)
        }
        return found
    }

    private fun read(url: String, context: CheckContext): String? = context.http.execute(HttpRequest(url)).use {
        if (it.status == 404) return null
        if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} from APKPure")
        it.text(PAGE_CAP)
    }

    private fun packageOf(spec: SourceSpec): String {
        val pkg = spec.option(SourceOptions.PACKAGE) ?: Urls.segments(spec.url).lastOrNull()
        if (pkg == null || !FDroidSource.isValidPackage(pkg)) throw SourceException(SourceErrorKind.UNSUPPORTED, "${spec.url} names no app on APKPure")
        return pkg
    }

    /** The app's page on apkpure.com, where an address on another of the store's sites is read too. */
    private fun pageOf(spec: SourceSpec, pkg: String): String {
        val slug = Urls.segments(spec.url).firstOrNull()?.takeIf { it != pkg } ?: pkg
        return "$SITE/${Urls.encodeSegment(slug)}/$pkg"
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*"([^"]{0,4000})"""").find(attributes)?.groupValues?.get(1)?.let(XmlScanner::decode)

    private fun text(pattern: Regex, html: String): String? =
        pattern.find(html)?.groupValues?.get(1)?.let(XmlScanner::decode)?.replace(SPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** The page writes a day as "Sep 25, 2026", which is read as that day in UTC. */
    private fun dayMs(text: String): Long? = try {
        LocalDate.parse(text.trim(), DAY).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        const val SITE = "https://apkpure.com"
        const val FILES = "https://d.apkpure.com/b"
        const val PAGE_CAP = 4 * 1024 * 1024
        const val MAX_RELEASES = 30
        const val MAX_VERSIONS = 60
        const val MAX_VERSION = 100
        const val MAX_BUILDS = 20
        const val ITEM_WINDOW = 4000
        const val BUILD_WINDOW = 1500

        val SITES = listOf("apkpure.com", "apkpure.net")

        /** The store's sites, its download address and where that sends, and where its pictures are. */
        val DOMAINS = setOf("apkpure.com", "apkpure.net", "winudf.com")

        val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        val DIV = Regex("""<div\s([^>]{1,4000})>""")
        val VERSION_ITEM = Regex("""\bclass\s*=\s*"[^"]{0,300}\bver_download_link\b""")
        val APK_ID = Regex("""b/(APK|XAPK)/([A-Za-z0-9_-]{1,400})""")
        val UPDATED = Regex("""<span\s[^>]{0,300}class="[^"]{0,100}\bupdate-on\b[^"]{0,100}"[^>]{0,300}>([^<]{1,40})</span>""")
        val TITLE = Regex("""<a\s[^>]{0,1000}class="ver_title"[^>]{0,1000}>\s*<h1>([^<]{1,300})</h1>""")
        val DEVELOPER = Regex("""<p\s+class="ver_dev">\s*<a\b[^>]{0,1000}>([^<]{1,300})</a>""")
        val SUMMARY = Regex("""<p\s+class="ver-des">([^<]{1,2000})</p>""")
        val ICON = Regex("""<a\s[^>]{0,1000}class="ver-top-l"[^>]{0,1000}>\s*<img\s[^>]{0,2000}?\bsrc="([^"]{1,2000})"""")
        val BUILD_CODE = Regex("""<span\s+class="additional-info[^"]{0,100}">\((\d{1,18})\)</span>""")
        val ARCHITECTURE = Regex("""Architecture</span>\s*<span\s+class="value">([^<]{0,200})</span>""")
        val ABI = Regex("[a-z0-9_-]{1,20}")
        val SPACE = Regex("\\s+")
    }
}
