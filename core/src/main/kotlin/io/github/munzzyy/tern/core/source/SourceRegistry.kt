package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.forge.ForgejoSource
import io.github.munzzyy.tern.core.source.forge.GitHubActionsSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.source.forge.GitLabSource
import io.github.munzzyy.tern.core.source.store.Apk4FreeSource
import io.github.munzzyy.tern.core.source.store.ApkComboSource
import io.github.munzzyy.tern.core.source.store.ApkMirrorSource
import io.github.munzzyy.tern.core.source.store.ApkPureSource
import io.github.munzzyy.tern.core.source.store.AptoideSource
import io.github.munzzyy.tern.core.source.store.CoolApkSource
import io.github.munzzyy.tern.core.source.store.FarsroidSource
import io.github.munzzyy.tern.core.source.store.HuaweiSource
import io.github.munzzyy.tern.core.source.store.ItchIoSource
import io.github.munzzyy.tern.core.source.store.LiteApksSource
import io.github.munzzyy.tern.core.source.store.RockModsSource
import io.github.munzzyy.tern.core.source.store.RuStoreSource
import io.github.munzzyy.tern.core.source.store.SamsungSource
import io.github.munzzyy.tern.core.source.store.TencentSource
import io.github.munzzyy.tern.core.source.store.UptodownSource
import io.github.munzzyy.tern.core.source.store.VivoSource
import io.github.munzzyy.tern.core.source.web.DirectSource
import io.github.munzzyy.tern.core.source.web.HtmlSource
import io.github.munzzyy.tern.core.source.web.JenkinsSource
import io.github.munzzyy.tern.core.source.web.NeutronCodeSource
import io.github.munzzyy.tern.core.source.web.SourceForgeSource
import io.github.munzzyy.tern.core.source.web.SourceHutSource
import io.github.munzzyy.tern.core.source.web.TelegramSource
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.text.Shown
import java.io.IOException

class SourceRegistry(val sources: List<Source>) {
    fun get(type: String): Source? = sources.firstOrNull { it.type == type }

    /**
     * The one way to run a check: transactional validators, every failure as a SourceException,
     * and every text of the listing that a person will read fit to be shown.
     */
    @Throws(SourceException::class)
    fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val source = get(spec.type) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Unknown source type ${spec.type}")
        return when (val result = guarded(context) { source.check(spec, it) }) {
            is CheckResult.Listing -> CheckResult.Listing(shown(result.listing))
            CheckResult.Unchanged -> result
        }
    }

    /**
     * Where to fetch [asset] of [spec] from now. What a source answers has to be an https address
     * like any other it lists; anything else is refused as a failure of the source.
     */
    @Throws(SourceException::class)
    fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download {
        val source = get(spec.type) ?: return Download(asset.url)
        val download = guarded(context) { source.resolve(spec, asset, it) }
        val url = download.url.takeIf { Urls.isHttps(it) }?.let(Urls::normalize)
            ?: throw SourceException(SourceErrorKind.PARSE, "The source named a download that is not an https address")
        return download.copy(url = url, headers = download.headers.filterKeys { it.lowercase() !in FORBIDDEN_HEADERS })
    }

    /** Sources that can be searched by name, in the order their hits are shown. */
    val searchable: List<Searchable> get() = sources.filterIsInstance<Searchable>()

    fun match(url: String): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        for (source in sources) {
            source.match(normalized)?.let { return it }
        }
        return null
    }

    fun detect(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        match(normalized)?.let { return it }
        for (source in sources) {
            val spec = try {
                source.probe(normalized, context)
            } catch (_: IOException) {
                null
            } catch (_: SourceException) {
                null
            }
            if (spec != null) return spec
        }
        return null
    }

    companion object {
        private const val MAX_NAME = 200
        private const val MAX_VERSION = 100
        private const val MAX_TITLE = 300
        private const val MAX_DESCRIPTION = 1000
        private const val MAX_ADDRESS = 2048

        /** A source may add headers to a download, never these: they carry credentials or change what the request means. */
        private val FORBIDDEN_HEADERS = setOf("authorization", "cookie", "proxy-authorization", "host", "range", "if-range")

        /**
         * Names, versions, titles and the description as they will be shown. What identifies a
         * release or a file, where it is and what it hashes to stay as the source gave them, and
         * the notes are cleaned where they are parsed.
         */
        internal fun shown(listing: SourceListing): SourceListing = listing.copy(
            name = Shown.lineOrNull(listing.name, MAX_NAME),
            author = Shown.lineOrNull(listing.author, MAX_NAME),
            description = Shown.lineOrNull(listing.description, MAX_DESCRIPTION),
            movedTo = Shown.lineOrNull(listing.movedTo, MAX_ADDRESS),
            releases = listing.releases.map { release ->
                release.copy(
                    version = Shown.line(release.version, MAX_VERSION),
                    title = Shown.lineOrNull(release.title, MAX_TITLE),
                    assets = release.assets.map { it.copy(name = Shown.line(it.name, MAX_NAME)) },
                )
            },
        )

        /**
         * Every source, in the order detection tries them: forges, then repositories, then stores
         * and other sites, then a bare download address, and the HTML page reader last.
         */
        fun standard(trackedInRepository: (repositoryUrl: String) -> Set<String> = { emptySet() }): SourceRegistry = SourceRegistry(
            listOf(
                GitHubSource(), GitHubActionsSource(), GitLabSource(), ForgejoSource(),
                FDroidSource(), FDroidRepoSource(trackedInRepository),
                HuaweiSource(), SamsungSource(), VivoSource(), TencentSource(), RuStoreSource(), CoolApkSource(), ItchIoSource(),
                TelegramSource(), NeutronCodeSource(),
                ApkPureSource(), AptoideSource(), UptodownSource(), ApkComboSource(), ApkMirrorSource(),
                FarsroidSource(), LiteApksSource(), Apk4FreeSource(), RockModsSource(),
                SourceForgeSource(), SourceHutSource(), JenkinsSource(), DirectSource(), HtmlSource(),
            ),
        )
    }
}
