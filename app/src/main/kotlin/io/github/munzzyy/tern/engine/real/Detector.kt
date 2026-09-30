package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.icon.IconAddresses
import io.github.munzzyy.tern.core.interop.ObtainiumImport
import io.github.munzzyy.tern.core.interop.ObtainiumImportException
import io.github.munzzyy.tern.core.interop.ObtainiumLink
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.engine.Detection
import io.github.munzzyy.tern.engine.Problem
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.SearchHit
import io.github.munzzyy.tern.engine.SignerState
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Turns what the user typed, pasted or shared into a source to add, or into search results. */
internal class Detector(private val e: RealEngine) {
    private val search = Search(e.http, e.tokens)

    suspend fun detect(input: String): Detection {
        val text = input.trim().take(MAX_INPUT)
        if (text.isEmpty()) return Detection.Failed(Problem(ProblemKind.NOT_FOUND, e.texts.nothingToSearch()))
        val target: Target = when (val link = ObtainiumLink.parse(text)) {
            is ObtainiumLink.Add -> Target(link.url, null, null)
            is ObtainiumLink.App -> fromObtainiumApp(link.json) ?: return Detection.Failed(Problem(ProblemKind.UNSUPPORTED, e.texts.notASource()))
            is ObtainiumLink.Apps -> return several(link.json) ?: Detection.Failed(Problem(ProblemKind.UNSUPPORTED, e.texts.notASource()))
            null -> if (looksLikeLink(text)) Target(text, null, null) else return Detection.Results(text, search.search(text))
        }
        return withContext(Dispatchers.IO) { resolve(target) }
    }

    private class Target(val url: String, val spec: SourceSpec?, val config: AppConfig?)

    private fun several(json: String): Detection.Results? = carriedPicks(json)

    private fun fromObtainiumApp(json: String): Target? {
        val app = try {
            ObtainiumImport.read("[$json]").apps.firstOrNull()
        } catch (_: ObtainiumImportException) {
            null
        } ?: return null
        return Target(app.source.url, app.source, app)
    }

    private suspend fun resolve(target: Target): Detection {
        val normalized = Urls.normalize(target.url) ?: return Detection.Failed(Problem(ProblemKind.NOT_FOUND, e.texts.notASource()))
        val context = CheckContext(e.http, InMemoryValidatorStore(), e.tokens, e.nowMs, e.device.profile)
        val repoSpec = target.spec ?: e.registry.match(normalized)
        if (repoSpec != null && repoSpec.type == SourceTypes.FDROID_REPO && repoSpec.option(SourceOptions.PACKAGE) == null) {
            return runInterruptible { repoResults(repoSpec, context) }
        }
        val outcome: Any = runInterruptible {
            try {
                val spec = target.spec ?: e.registry.detect(normalized, context) ?: SourceSpec(SourceTypes.HTML, normalized)
                spec to e.registry.check(spec, context)
            } catch (ex: SourceException) {
                ex
            }
        }
        if (outcome is SourceException) return Detection.Failed(e.checks.problemOf(outcome))
        @Suppress("UNCHECKED_CAST")
        val (spec, result) = outcome as Pair<SourceSpec, CheckResult>
        val listing = (result as? CheckResult.Listing)?.listing ?: return Detection.Failed(Problem(ProblemKind.NO_RELEASES, e.texts.notASource()))
        val learnedSpec = spec.copy(options = spec.options + listing.learnedOptions)
        val carried = try {
            target.config?.let { e.validated(it.copy(source = learnedSpec)) }
        } catch (ex: IllegalArgumentException) {
            return Detection.Failed(Problem(ProblemKind.PARSE, e.texts.checkParse(ex.message)))
        }
        return found(learnedSpec, listing, carried)
    }

    /** A repository address with no app of its own: what it carries, as picks for the Add screen. */
    private fun repoResults(spec: SourceSpec, context: CheckContext): Detection {
        val source = e.registry.get(SourceTypes.FDROID_REPO) as? FDroidRepoSource ?: return Detection.Failed(Problem(ProblemKind.UNSUPPORTED, e.texts.notASource()))
        val listing = try {
            source.listApps(spec, context)
        } catch (ex: SourceException) {
            return Detection.Failed(e.checks.problemOf(ex))
        }
        val origin = listing.repositoryName?.takeIf { it.isNotBlank() } ?: Urls.host(spec.url)
        val hits = listing.apps.map { app ->
            SearchHit(
                name = app.name,
                owner = null,
                description = app.summary,
                url = FDroidRepoSource.appAddress(spec.url, app.packageName, listing.fingerprint),
                origin = origin,
            )
        }
        return Detection.Results(spec.url, hits, listing.more)
    }

    private fun found(spec: SourceSpec, listing: SourceListing, carried: AppConfig?): Detection.Found {
        val settings = e.settings.value
        val listed = listing.packageName?.takeIf { BinaryManifest.isValidName(it) }
        val builtIn = e.builtIn.of(spec.url)
        var config = carried?.copy(id = "detect", source = spec, packageName = carried.packageName ?: listed) ?: AppConfig(
            id = "detect",
            source = spec,
            name = listing.name?.take(200) ?: Urls.host(spec.url),
            author = listing.author?.take(200),
            packageName = listed,
            releases = ReleasePolicy(includePrereleases = settings.includePrereleasesByDefault, minAgeDays = settings.minAgeDaysByDefault),
        )
        if (builtIn.isNotEmpty()) config = config.copy(pinnedSigners = builtIn)
        val state = AppState(releases = listing.releases, lastCheckedMs = e.nowMs())
        val warnings = ArrayList<String>()

        var eval = e.evaluator.evaluate(config, state, e.readInstalled(config.packageName), e.inspector::inspect)
        if (carried == null && eval.latest == null && eval.problem?.message == e.texts.onlyPrereleases()) {
            config = config.copy(releases = config.releases.copy(includePrereleases = true))
            eval = e.evaluator.evaluate(config, state, e.readInstalled(config.packageName), e.inspector::inspect)
            warnings += e.texts.warnPrerelease()
        }
        val learned = eval.facts?.packageName
        if (config.packageName == null && learned != null) {
            config = config.copy(packageName = learned)
            eval = e.evaluator.evaluate(config, state, e.readInstalled(learned), e.inspector::inspect)
        }
        val installed = e.readInstalled(config.packageName)
        val tracked = e.findBySpec(spec)
        if (tracked != null) warnings += e.texts.warnTracked()
        if (installed != null && eval.verification?.signerState == SignerState.MISMATCH) warnings += e.texts.warnSignedDifferently()
        if (builtIn.isNotEmpty() && eval.problem?.kind == ProblemKind.PIN_MISMATCH) warnings += e.texts.warnBuiltInPin()
        if (eval.problem?.kind == ProblemKind.NO_FILE_FOR_DEVICE) warnings += e.texts.warnNoFile()
        val readPackage = eval.facts?.packageName
        if (carried?.packageName != null && readPackage != null && readPackage != carried.packageName) {
            warnings += e.texts.packageMismatch(carried.packageName, readPackage)
        }
        listing.movedTo?.let { warnings += e.texts.warnMoved(it) }
        if (Urls.isLocal(Urls.host(spec.url))) warnings += e.texts.warnLocalAddress()
        return Detection.Found(
            spec = spec,
            name = config.name,
            author = config.author,
            description = listing.description?.take(1000),
            release = eval.latest,
            file = eval.file,
            otherFiles = eval.otherFiles,
            verification = eval.verification,
            installed = installed?.app,
            alreadyTracked = tracked,
            warnings = warnings,
            carried = if (builtIn.isEmpty()) carried else carried?.copy(pinnedSigners = builtIn),
            iconUrls = IconAddresses.accepted(spec.url, listing.iconUrls),
            builtInPin = builtIn.isNotEmpty(),
        )
    }

    private fun looksLikeLink(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        if (text.contains("://")) return true
        return BARE_HOST.matches(text)
    }

    companion object {
        /**
         * A link that carries several apps, as Obtainium shares a list: each becomes a pick of its
         * own, a link to that one app, so its settings are shown before it is added, one at a time.
         * Null when the link carries nothing that can be added.
         */
        internal fun carriedPicks(json: String): Detection.Results? {
            val entries = try {
                Json.parseArray(json).objects()
            } catch (_: JsonException) {
                return null
            }
            val hits = entries.take(MAX_CARRIED).mapNotNull { entry ->
                val url = entry.string("url")?.let(Urls::normalize)?.takeIf(Urls::isHttps) ?: return@mapNotNull null
                SearchHit(
                    name = Shown.lineOrNull(entry.string("name"), 200) ?: Urls.host(url),
                    owner = Shown.lineOrNull(entry.string("author"), 200),
                    description = url,
                    url = "obtainium://app/" + URLEncoder.encode(Json.write(entry), "UTF-8").replace("+", "%20"),
                    origin = CARRIED_ORIGIN,
                )
            }
            if (hits.isEmpty()) return null
            return Detection.Results(Detection.Results.CARRIED, hits, more = entries.size > MAX_CARRIED)
        }

        private const val CARRIED_ORIGIN = "Obtainium"
        private const val MAX_CARRIED = 200
        private const val MAX_INPUT = 4096
        private val BARE_HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(:\\d{1,5})?(/\\S*)?$")
    }
}
