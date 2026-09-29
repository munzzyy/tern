package io.github.munzzyy.jackdaw.core.source

import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.Urls
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.jackdaw.core.source.fdroid.FDroidSource
import io.github.munzzyy.jackdaw.core.source.forge.ForgejoSource
import io.github.munzzyy.jackdaw.core.source.forge.GitHubActionsSource
import io.github.munzzyy.jackdaw.core.source.forge.GitHubSource
import io.github.munzzyy.jackdaw.core.source.forge.GitLabSource
import io.github.munzzyy.jackdaw.core.source.web.DirectSource
import io.github.munzzyy.jackdaw.core.source.web.HtmlSource
import io.github.munzzyy.jackdaw.core.source.web.JenkinsSource
import io.github.munzzyy.jackdaw.core.source.web.SourceForgeSource
import io.github.munzzyy.jackdaw.core.source.web.SourceHutSource
import java.io.IOException

class SourceRegistry(val sources: List<Source>) {
    fun get(type: String): Source? = sources.firstOrNull { it.type == type }

    /** The one way to run a check: transactional validators, and every failure as a SourceException. */
    @Throws(SourceException::class)
    fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val source = get(spec.type) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Unknown source type ${spec.type}")
        return guarded(context) { source.check(spec, it) }
    }

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
        /**
         * Every source, in the order detection tries them: forges, then repositories, then other
         * sites, then a bare download address, and the HTML page reader last.
         */
        fun standard(trackedInRepository: (repositoryUrl: String) -> Set<String> = { emptySet() }): SourceRegistry = SourceRegistry(
            listOf(
                GitHubSource(), GitHubActionsSource(), GitLabSource(), ForgejoSource(),
                FDroidSource(), FDroidRepoSource(trackedInRepository),
                SourceForgeSource(), SourceHutSource(), JenkinsSource(), DirectSource(), HtmlSource(),
            ),
        )
    }
}
