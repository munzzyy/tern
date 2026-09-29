package io.github.munzzyy.stamp.core.source.web

import io.github.munzzyy.stamp.core.json.Json
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.net.Validator
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.Source
import io.github.munzzyy.stamp.core.source.SourceErrorKind
import io.github.munzzyy.stamp.core.source.SourceException
import io.github.munzzyy.stamp.core.source.SourceListing
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.source.guarded

class JenkinsSource : Source {
    override val type: String = SourceTypes.JENKINS

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        val idx = path.indexOf("/job/")
        if (idx == -1) return null
        val prefix = path.substring(0, idx)
        val jobPart = path.substring(idx)
        if (!JOB_PATH.matches(jobPart)) return null
        return SourceSpec(type, "https://${uri.authority}$prefix$jobPart")
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val apiUrl = "${spec.url}/lastSuccessfulBuild/api/json"
        val key = validatorKey(spec, apiUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(apiUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "No successful build at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $apiUrl")
            context.validators.put(key, Validator.from(it.headers))
            val obj = Json.parseObject(it.text(2 * 1024 * 1024))
            val number = obj.long("number") ?: throw SourceException(SourceErrorKind.PARSE, "Jenkins build has no number")
            val timestamp = obj.long("timestamp")
            val assets = obj.array("artifacts")?.objects().orEmpty().mapNotNull { artifact ->
                val relativePath = artifact.string("relativePath") ?: return@mapNotNull null
                val fileName = artifact.string("fileName") ?: relativePath.substringAfterLast('/')
                Asset(name = fileName, url = "${spec.url}/lastSuccessfulBuild/artifact/$relativePath")
            }
            val release = Release(id = number.toString(), version = number.toString(), publishedAtMs = timestamp, assets = assets)
            return CheckResult.Listing(SourceListing(releases = listOf(release)))
        }
    }

    companion object {
        private val JOB_PATH = Regex("(/job/[^/]+)+")
    }
}
