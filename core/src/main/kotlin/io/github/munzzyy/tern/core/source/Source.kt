package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.icon.IconAddresses
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpClient
import io.github.munzzyy.tern.core.net.ValidatorStore

fun interface TokenProvider {
    /** Token for [host], or null. Hosts are compared exactly; a token never travels to another host. */
    fun tokenFor(host: String): String?

    companion object {
        val NONE = TokenProvider { null }
    }
}

class CheckContext(
    val http: HttpClient,
    val validators: ValidatorStore,
    val tokens: TokenProvider = TokenProvider.NONE,
    val nowMs: () -> Long = System::currentTimeMillis,
    /** Lets a source that publishes one file per architecture leave out the ones that cannot run here. */
    val device: DeviceProfile? = null,
    /**
     * The app being checked, for what it asks of its source beyond the spec: a forge lists the tags
     * of a project without releases for an app that is only tracked, and a web page is read with the
     * app's version pattern. Null while an address is looked at before there is an app.
     */
    val app: AppConfig? = null,
) {
    /** The same context, with validators written to [validators]. */
    fun withValidators(validators: ValidatorStore): CheckContext = CheckContext(http, validators, tokens, nowMs, device, app)
}

data class SourceListing(
    /** Newest first. */
    val releases: List<Release>,
    val name: String? = null,
    val author: String? = null,
    val packageName: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    /** Set when the project moved; the caller decides whether to follow it. */
    val movedTo: String? = null,
    /** Options learned on first contact that the caller must store in the spec, such as a repository fingerprint. */
    val learnedOptions: Map<String, String> = emptyMap(),
    /** Addresses to try for the icon after [iconUrl], in order. */
    val iconFallbacks: List<String> = emptyList(),
) {
    /** Every address the icon may be had at, the best first. */
    val iconUrls: List<String> get() = listOfNotNull(iconUrl) + iconFallbacks

    /** The same listing with the icon addresses of [candidates] that a source at [sourceUrl] may name. */
    fun withIcons(sourceUrl: String, candidates: List<String?>): SourceListing {
        val accepted = IconAddresses.accepted(sourceUrl, candidates)
        return copy(iconUrl = accepted.firstOrNull(), iconFallbacks = accepted.drop(1))
    }
}

sealed interface CheckResult {
    /** The source answered 304 or an identical feed: nothing changed since the stored validators. */
    data object Unchanged : CheckResult

    data class Listing(val listing: SourceListing) : CheckResult
}

/**
 * Where a file is fetched from right now. Most sources name a file by an address that lasts, and
 * that address is the one to fetch. Some stores hand out addresses that expire within minutes, or
 * want a header with the request; for those, [Source.resolve] asks again just before the download.
 */
data class Download(
    val url: String,
    /** Sent with the download and with the reads of the file's header before it. Never a token. */
    val headers: Map<String, String> = emptyMap(),
)

/** One thing a search found: a page Tern can add, and what the source says about it. */
data class Hit(
    val name: String,
    val owner: String?,
    val description: String?,
    /** An address [Source.match] accepts. */
    val url: String,
    val stars: Int? = null,
)

enum class SourceErrorKind { NOT_FOUND, AUTH, RATE_LIMITED, NETWORK, PARSE, UNSUPPORTED, NO_RELEASES }

class SourceException(
    val kind: SourceErrorKind,
    message: String,
    val retryAtMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface Source {
    /** Stable identifier stored in [SourceSpec.type]. */
    val type: String

    /**
     * Recognise [url] without touching the network. Returns the canonical spec, or null when this
     * source does not own the URL.
     */
    fun match(url: String): SourceSpec?

    /**
     * Recognise [url] by asking the server, for self-hosted instances whose host says nothing about
     * the software behind it. Default: no probing.
     */
    fun probe(url: String, context: CheckContext): SourceSpec? = null

    /**
     * Reads [url] as this source because the person said it is one, where [match] may not know
     * the address: a forge on a host of its own, a repository at an address of any shape. May ask
     * the server. Null when the address cannot be read this way. Default: what [match] takes.
     */
    fun matchForced(url: String, context: CheckContext): SourceSpec? = match(url)

    @Throws(SourceException::class)
    fun check(spec: SourceSpec, context: CheckContext): CheckResult

    /** Key under which conditional-request validators for [spec] are stored. */
    fun validatorKey(spec: SourceSpec, endpoint: String): String = "$type|${spec.url}|$endpoint"

    /**
     * Where to fetch [asset] from now. Default: its own address. A source whose addresses expire
     * asks its server again here, and a source may only answer with an address it would itself
     * have listed: on its own host or a host it names as its file store.
     */
    @Throws(SourceException::class)
    fun resolve(spec: SourceSpec, asset: Asset, context: CheckContext): Download = Download(asset.url)

    /**
     * True for a source that shows what exists and never offers a file, as when a site's owners
     * forbid downloads by others. An app from it is always track-only.
     */
    val trackOnly: Boolean get() = false

    /**
     * True for a store or a site that republishes apps it did not build. What it serves is held to
     * the same checks, but the person should know the file does not come from the developer.
     */
    val republishes: Boolean get() = false

    /**
     * The domains this source asks, each with the hosts under it, for a store whose every request
     * can be told by its host. While third-party stores are off none of them is asked, whatever an
     * address is read as. Empty for the sources that may be anywhere.
     */
    val domains: Set<String> get() = emptySet()
}

/** A source that can be searched by name. Each search is one or two requests and stores nothing. */
interface Searchable {
    /** Shown next to each hit, such as "Uptodown". */
    val origin: String

    @Throws(SourceException::class)
    fun search(query: String, context: CheckContext): List<Hit>
}
