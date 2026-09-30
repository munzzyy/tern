package io.github.munzzyy.tern.core.source.store

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Hit
import io.github.munzzyy.tern.core.source.Searchable
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.forge.Iso8601
import io.github.munzzyy.tern.core.source.guarded

/**
 * Aptoide. The app's page at `<name>.<language>.aptoide.com` names the app's number, and
 * ws2.aptoide.com answers for that number with the version the store offers now: one release
 * with one file. Searched through the same service.
 */
class AptoideSource : Source, Searchable {
    override val type: String = SourceTypes.APTOIDE

    override val republishes: Boolean get() = true

    override val origin: String = "Aptoide"

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val name = APP_HOST.matchEntire(uri.host?.lowercase() ?: return null)?.groupValues?.get(1) ?: return null
        return SourceSpec(type, pageOf(name))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val id = appNumber(spec, context)
        val app = context.http.execute(HttpRequest("$API/getApp/app_id/$id")).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Aptoide has no app $id")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} from Aptoide")
            Json.parseObject(it.text(BODY_CAP)).obj("nodes")?.obj("meta")?.obj("data")
                ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Aptoide says nothing about app $id")
        }
        val file = app.obj("file") ?: throw SourceException(SourceErrorKind.NO_RELEASES, "Aptoide offers no file for ${spec.url}")
        val version = file.string("vername")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "Aptoide names no version for ${spec.url}")
        val address = listOfNotNull(file.string("path"), file.string("path_alt")).firstNotNullOfOrNull(::fileAddress)
            ?: throw SourceException(SourceErrorKind.NO_RELEASES, "Aptoide names no file on its own servers for ${spec.url}")
        val versionCode = file.long("vercode")
        val asset = Asset(name = Urls.segments(address).last(), url = address, size = file.long("filesize")?.takeIf { it > 0 })
        val release = Release(
            id = versionCode?.toString() ?: version,
            version = version,
            versionCode = versionCode,
            notes = app.obj("media")?.string("news")?.takeIf { it.isNotBlank() },
            notesFormat = NotesFormat.PLAIN,
            publishedAtMs = app.string("updated")?.let(::parseTime),
            pageUrl = spec.url,
            assets = listOf(asset),
        )
        val listing = SourceListing(
            releases = listOf(release),
            name = app.string("name"),
            author = app.obj("developer")?.string("name"),
            packageName = app.string("package")?.takeIf { FDroidSource.isValidPackage(it) },
            description = app.obj("media")?.string("summary")?.takeIf { it.isNotBlank() },
        )
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(app.string("icon"))))
    }

    /** The number the store knows the app by, which only its page tells. */
    private fun appNumber(spec: SourceSpec, context: CheckContext): Long {
        val html = context.http.execute(HttpRequest(spec.url)).use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "There is no Aptoide app at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for ${spec.url}")
            it.text(PAGE_CAP)
        }
        return APP_NUMBER.find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw SourceException(SourceErrorKind.NOT_FOUND, "The page at ${spec.url} shows no Aptoide app")
    }

    override fun search(query: String, context: CheckContext): List<Hit> = guarded(context) {
        val q = Urls.encodeSegment(query.trim().take(MAX_QUERY))
        if (q.isEmpty()) return@guarded emptyList()
        it.http.execute(HttpRequest("$API/apps/search?query=$q&limit=$MAX_HITS")).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${response.status} from the Aptoide search")
            val found = Json.parseObject(response.text(BODY_CAP)).obj("datalist")?.array("list")?.objects().orEmpty()
            found.mapNotNull(::hit).distinctBy { hit -> hit.url }.take(MAX_HITS)
        }
    }

    private fun hit(app: JsonObject): Hit? {
        val name = app.string("uname")?.lowercase()?.takeIf { NAME.matches(it) } ?: return null
        return Hit(
            name = app.string("name")?.takeIf { it.isNotBlank() } ?: return null,
            owner = app.obj("developer")?.string("name"),
            description = null,
            url = pageOf(name),
        )
    }

    private fun fileAddress(url: String): String? {
        if (!Urls.isHttps(url)) return null
        val normalized = Urls.normalize(url) ?: return null
        return normalized.takeIf { Urls.host(it) in FILE_HOSTS && Urls.segments(it).isNotEmpty() }
    }

    /** The service writes its times without a zone; they are read as UTC. */
    private fun parseTime(text: String): Long? = Iso8601.parseMs(text.trim().replace(' ', 'T') + "Z")

    companion object {
        private const val API = "https://ws2.aptoide.com/api/7"
        private const val PAGE_CAP = 4 * 1024 * 1024
        private const val BODY_CAP = 2 * 1024 * 1024
        private const val MAX_QUERY = 200
        private const val MAX_HITS = 20

        /** Where the service says Aptoide's files are. */
        val FILE_HOSTS = setOf("pool.apk.aptoide.com")

        private val NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
        private val APP_HOST = Regex("(${NAME.pattern})\\.[a-z]{2}\\.aptoide\\.com")
        private val APP_NUMBER = Regex(""""app"\s*:\s*\{\s*"id"\s*:\s*([0-9]{1,18})""")

        private fun pageOf(name: String) = "https://$name.en.aptoide.com/app"
    }
}
