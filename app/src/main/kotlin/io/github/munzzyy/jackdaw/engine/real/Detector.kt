package io.github.munzzyy.jackdaw.engine.real

import io.github.munzzyy.jackdaw.core.apk.BinaryManifest
import io.github.munzzyy.jackdaw.core.interop.ObtainiumImport
import io.github.munzzyy.jackdaw.core.interop.ObtainiumImportException
import io.github.munzzyy.jackdaw.core.interop.ObtainiumLink
import io.github.munzzyy.jackdaw.core.model.AppConfig
import io.github.munzzyy.jackdaw.core.model.ReleasePolicy
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.InMemoryValidatorStore
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceTypes
import io.github.munzzyy.jackdaw.data.AppState
import io.github.munzzyy.jackdaw.engine.Detection
import io.github.munzzyy.jackdaw.engine.Problem
import io.github.munzzyy.jackdaw.engine.ProblemKind
import io.github.munzzyy.jackdaw.engine.SignerState
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
            is ObtainiumLink.Apps -> return Detection.Failed(Problem(ProblemKind.UNSUPPORTED, e.texts.severalApps()))
            null -> if (looksLikeLink(text)) Target(text, null, null) else return Detection.Results(text, search.search(text))
        }
        return withContext(Dispatchers.IO) { resolve(target) }
    }

    private class Target(val url: String, val spec: SourceSpec?, val config: AppConfig?)

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

    private fun found(spec: SourceSpec, listing: SourceListing, carried: AppConfig?): Detection.Found {
        val settings = e.settings.value
        val listed = listing.packageName?.takeIf { BinaryManifest.isValidName(it) }
        var config = carried?.copy(id = "detect", source = spec, packageName = carried.packageName ?: listed) ?: AppConfig(
            id = "detect",
            source = spec,
            name = listing.name?.take(200) ?: Urls.host(spec.url),
            author = listing.author?.take(200),
            packageName = listed,
            releases = ReleasePolicy(includePrereleases = settings.includePrereleasesByDefault, minAgeDays = settings.minAgeDaysByDefault),
        )
        val state = AppState(releases = listing.releases)
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
        if (eval.problem?.kind == ProblemKind.NO_FILE_FOR_DEVICE) warnings += e.texts.warnNoFile()
        val readPackage = eval.facts?.packageName
        if (carried?.packageName != null && readPackage != null && readPackage != carried.packageName) {
            warnings += e.texts.packageMismatch(carried.packageName, readPackage)
        }
        listing.movedTo?.let { warnings += e.texts.warnMoved(it) }
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
            carried = carried,
        )
    }

    private fun looksLikeLink(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        if (text.contains("://")) return true
        return BARE_HOST.matches(text)
    }

    private companion object {
        const val MAX_INPUT = 4096
        val BARE_HOST = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(:\\d{1,5})?(/\\S*)?$")
    }
}
