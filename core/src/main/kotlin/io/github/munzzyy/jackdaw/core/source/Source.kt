package io.github.munzzyy.jackdaw.core.source

import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpClient
import io.github.munzzyy.jackdaw.core.net.ValidatorStore

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
)

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
)

sealed interface CheckResult {
    /** The source answered 304 or an identical feed: nothing changed since the stored validators. */
    data object Unchanged : CheckResult

    data class Listing(val listing: SourceListing) : CheckResult
}

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

    @Throws(SourceException::class)
    fun check(spec: SourceSpec, context: CheckContext): CheckResult

    /** Key under which conditional-request validators for [spec] are stored. */
    fun validatorKey(spec: SourceSpec, endpoint: String): String = "$type|${spec.url}|$endpoint"
}
