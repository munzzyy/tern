package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.source.fdroid.FDroidSource
import io.github.munzzyy.tern.core.source.forge.ForgejoSource
import io.github.munzzyy.tern.core.source.forge.GitHubActionsSource
import io.github.munzzyy.tern.core.source.forge.GitHubSource
import io.github.munzzyy.tern.core.source.forge.GitLabSource
import io.github.munzzyy.tern.core.source.store.ApkComboSource
import io.github.munzzyy.tern.core.source.store.ApkMirrorSource
import io.github.munzzyy.tern.core.source.store.ApkPureSource
import io.github.munzzyy.tern.core.source.store.AptoideSource
import io.github.munzzyy.tern.core.source.store.HuaweiSource
import io.github.munzzyy.tern.core.source.store.ItchIoSource
import io.github.munzzyy.tern.core.source.store.SamsungSource
import io.github.munzzyy.tern.core.source.store.TencentSource
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

/**
 * Every source Tern reads, and what it will not read: the sites [Refusal] names, always, and the
 * [SourceTypes.THIRD_PARTY_STORES] while [storesAllowed] says no.
 */
class SourceRegistry(val sources: List<Source>, private val storesAllowed: () -> Boolean = { true }) {
    fun get(type: String): Source? = sources.firstOrNull { it.type == type }

    /** Why an address is not read now. */
    sealed interface Closed {
        data class Refused(val refusal: Refusal) : Closed

        /** It belongs to the third-party store [type], and those are off. */
        data class StoresOff(val type: String) : Closed
    }

    /** Why [url] is not read now, or null when it may be. Nothing is asked of the network. */
    fun closed(url: String): Closed? {
        Refusal.ofUrl(url)?.let { return Closed.Refused(it) }
        if (storesAllowed()) return null
        val host = Urls.parseHttps(url)?.host?.lowercase()?.trimEnd('.')
        val candidates = listOfNotNull(Urls.hashRouted(url), Urls.normalize(url))
        val store = sources.firstOrNull { source ->
            source.type in SourceTypes.THIRD_PARTY_STORES &&
                (source.domains.any { host == it || host?.endsWith(".$it") == true } || candidates.any { source.match(it) != null })
        }
        return store?.let { Closed.StoresOff(it.type) }
    }

    /**
     * True for an app of a third-party store while those are off, whatever it is read as: it is
     * kept, and nothing of it is asked for.
     */
    fun paused(spec: SourceSpec): Boolean =
        !storesAllowed() && (spec.type in SourceTypes.THIRD_PARTY_STORES || closed(spec.url) is Closed.StoresOff)

    private fun refuseClosed(spec: SourceSpec) {
        (Refusal.ofType(spec.type) ?: Refusal.ofUrl(spec.url))?.let { throw SourceException(SourceErrorKind.UNSUPPORTED, refusalText(it)) }
        if (paused(spec)) throw SourceException(SourceErrorKind.UNSUPPORTED, "Third-party stores are off")
    }

    /**
     * The one way to run a check: transactional validators, every failure as a SourceException,
     * and every text of the listing that a person will read fit to be shown.
     */
    @Throws(SourceException::class)
    fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        refuseClosed(spec)
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
        refuseClosed(spec)
        val source = get(spec.type) ?: return Download(asset.url)
        val download = guarded(context) { source.resolve(spec, asset, it) }
        val url = download.url.takeIf { Urls.isHttps(it) }?.let(Urls::normalize)
            ?: throw SourceException(SourceErrorKind.PARSE, "The source named a download that is not an https address")
        return download.copy(url = url, headers = download.headers.filterKeys { it.lowercase() !in FORBIDDEN_HEADERS })
    }

    /** Sources that can be searched by name, in the order their hits are shown. */
    val searchable: List<Searchable> get() = sources.filter { storesAllowed() || it.type !in SourceTypes.THIRD_PARTY_STORES }.filterIsInstance<Searchable>()

    fun match(url: String): SourceSpec? {
        if (closed(url) != null) return null
        // A store that routes its pages after '#' reads the address as typed; a page reader would only see its front page.
        Urls.hashRouted(url)?.let { routed ->
            for (source in sources) {
                if (source.type == SourceTypes.HTML || source.type == SourceTypes.DIRECT) continue
                source.match(routed)?.let { return it }
            }
        }
        val normalized = Urls.normalize(url) ?: return null
        for (source in sources) {
            source.match(normalized)?.let { return it }
        }
        return null
    }

    /**
     * [url] read as a source of [type] because the person said so, as Obtainium's "override source"
     * reads it. Null when the address cannot be read that way, or [type] is not one a person may pick.
     */
    fun readAs(url: String, type: String, context: CheckContext): SourceSpec? {
        if (type !in SourceTypes.OVERRIDABLE || closed(url) != null) return null
        if (type in SourceTypes.THIRD_PARTY_STORES && !storesAllowed()) return null
        val source = get(type) ?: return null
        if (type != SourceTypes.HTML && type != SourceTypes.DIRECT) {
            Urls.hashRouted(url)?.let { routed -> source.match(routed)?.let { return it } }
        }
        val normalized = Urls.normalize(url) ?: return null
        return try {
            source.matchForced(normalized, context)
        } catch (_: IOException) {
            null
        } catch (_: SourceException) {
            null
        }
    }

    fun detect(url: String, context: CheckContext): SourceSpec? {
        val normalized = Urls.normalize(url) ?: return null
        if (closed(url) != null) return null
        match(url)?.let { return it }
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
                    sourceArchives = release.sourceArchives.map { it.copy(name = Shown.line(it.name, MAX_NAME)) },
                )
            },
        )

        /**
         * Every source, in the order detection tries them: forges, then repositories, then stores
         * and other sites, then a bare download address, and the HTML page reader last.
         */
        fun standard(
            trackedInRepository: (repositoryUrl: String) -> Set<String> = { emptySet() },
            storesAllowed: () -> Boolean = { true },
        ): SourceRegistry = SourceRegistry(
            listOf(
                GitHubSource(), GitHubActionsSource(), GitLabSource(), ForgejoSource(),
                FDroidSource(), FDroidRepoSource(trackedInRepository),
                HuaweiSource(), SamsungSource(), VivoSource(), TencentSource(), ItchIoSource(),
                TelegramSource(), NeutronCodeSource(),
                ApkPureSource(), AptoideSource(), ApkComboSource(), ApkMirrorSource(),
                SourceForgeSource(), SourceHutSource(), JenkinsSource(), DirectSource(), HtmlSource(),
            ),
            storesAllowed,
        )

        /** What a refusal is called in a failure of a source, which is read by people who work on Tern. */
        fun refusalText(refusal: Refusal): String = when (refusal) {
            Refusal.MODIFIED_APPS -> "Tern does not read sites that offer modified apps"
            Refusal.IMPERSONATION -> "Tern cannot read this store without pretending to be its app"
        }
    }
}
