package io.github.munzzyy.tern.core.source.web

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.version.Version
import io.github.munzzyy.tern.core.xml.XmlScanner

/**
 * A project's files on SourceForge, read from its feed. The address of a folder under `/files`
 * keeps the folder, and only the files in it are followed. A file's version is read from its
 * name, or else from the folders it is in, as in `/files/1.2.3/app.apk`.
 */
class SourceForgeSource : Source {
    override val type: String = SourceTypes.SOURCEFORGE

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()
        if (host != "sourceforge.net" && host != "www.sourceforge.net") return null
        val segments = Urls.segments(uri.toString())
        if (segments.size < 2 || segments[0] !in PROJECT_ROOTS || !PROJECT.matches(segments[1])) return null
        val base = "https://sourceforge.net/projects/${segments[1]}"
        // Only /projects/<name>/files names folders; the other pages of a project stand for all of its files.
        if (segments[0] != "projects" || segments.getOrNull(2) != "files") return SourceSpec(type, base)
        var folders = segments.drop(3)
        // SourceForge's link to the newest file of a project.
        if (folders == listOf("latest", "download")) return SourceSpec(type, base)
        if (folders.lastOrNull() == "download") folders = folders.dropLast(1)
        // The address of a file stands for the folder it is in.
        if (folders.lastOrNull()?.let { Asset.kindOf(it) != AssetKind.OTHER } == true) folders = folders.dropLast(1)
        if (folders.isEmpty()) return SourceSpec(type, base)
        return SourceSpec(type, "$base/files/" + folders.joinToString("/") { Urls.encodeSegment(it) })
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val segments = Urls.segments(spec.url)
        val project = segments.getOrNull(1)?.takeIf { PROJECT.matches(it) }
            ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "${spec.url} names no SourceForge project")
        val folder = segments.drop(3)
        val feedUrl = "https://sourceforge.net/projects/$project/rss?path=/" + folder.joinToString("/") { Urls.encodeSegment(it) }
        // What the address of a file in the folder starts with, its escapes read.
        val within = "https://sourceforge.net/projects/$project/files/" + folder.joinToString("") { "$it/" }
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
                val inside = Urls.decode(stripped.substringBefore('?')).takeIf { p -> p.startsWith(within) }?.removePrefix(within) ?: continue
                val fileName = stripped.substringAfterLast('/')
                val version = versionOf(fileName, inside.substringBeforeLast('/', "")) ?: continue
                grouped.getOrPut(version) { ArrayList() }.add(Asset(name = fileName, url = stripped))
            }
            if (grouped.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable files at ${spec.url}")
            val releases = grouped.entries
                .sortedWith(compareByDescending { Version.parse(it.key) })
                .take(30)
                .map { (version, assets) -> Release(id = version, version = version, assets = assets) }
            return CheckResult.Listing(SourceListing(releases = releases, name = project))
        }
    }

    companion object {
        private val PROJECT_ROOTS = setOf("projects", "p")
        private val PROJECT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
        private val INSTALLABLE = listOf(".apk", ".xapk", ".apks", ".apkm")

        /**
         * The version of a file: the dotted one in its name, else the dotted one in the [folders] it
         * is in below the folder followed, else those folders as they are. Null for a file with no
         * version in its name that lies right in the folder followed.
         */
        internal fun versionOf(fileName: String, folders: String): String? {
            VersionGuess.find(fileName)?.let { return it }
            val path = folders.trim('/')
            return VersionGuess.find(path) ?: path.takeIf { it.isNotEmpty() }
        }
    }
}
