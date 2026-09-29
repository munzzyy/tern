package io.github.munzzyy.jackdaw.core.source.fdroid

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.model.SourceSpec
import io.github.munzzyy.jackdaw.core.net.HttpRequest
import io.github.munzzyy.jackdaw.core.net.Validator
import io.github.munzzyy.jackdaw.core.source.CheckContext
import io.github.munzzyy.jackdaw.core.source.CheckResult
import io.github.munzzyy.jackdaw.core.source.Source
import io.github.munzzyy.jackdaw.core.source.SourceErrorKind
import io.github.munzzyy.jackdaw.core.source.SourceException
import io.github.munzzyy.jackdaw.core.source.SourceListing
import io.github.munzzyy.jackdaw.core.source.SourceOptions
import io.github.munzzyy.jackdaw.core.source.SourceTypes

class FDroidSource : Source {
    override val type: String = SourceTypes.FDROID

    override fun match(url: String): SourceSpec? {
        val uri = Urls.parseHttps(url) ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        val pkg = when (host) {
            "f-droid.org" -> FDROID_PATH.find(path)?.groupValues?.get(1)
            "apt.izzysoft.de" -> IZZY_APT_PATH.find(path)?.groupValues?.get(1)
            "android.izzysoft.de" -> IZZY_ANDROID_PATH.find(path)?.groupValues?.get(1)
            else -> null
        } ?: return null
        if (!isValidPackage(pkg)) return null
        val canonical = if (host == "f-droid.org") "https://f-droid.org/packages/$pkg" else "https://apt.izzysoft.de/fdroid/index/apk/$pkg"
        return SourceSpec(type, canonical, mapOf(SourceOptions.PACKAGE to pkg))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = spec.option(SourceOptions.PACKAGE) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Missing package option")
        val izzy = spec.url.contains("izzysoft.de")
        val apiUrl = if (izzy) "https://apt.izzysoft.de/fdroid/api/v1/packages/$pkg" else "https://f-droid.org/api/v1/packages/$pkg"
        val repoBase = if (izzy) "https://apt.izzysoft.de/fdroid/repo" else "https://f-droid.org/repo"
        val key = validatorKey(spec, apiUrl)
        val validator = context.validators.get(key)
        val response = context.http.execute(HttpRequest(apiUrl, headers = validator?.conditionalHeaders() ?: emptyMap()))
        response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "Package $pkg not found")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $apiUrl")
            context.validators.put(key, Validator.from(it.headers))
            val obj = Json.parseObject(it.text(2 * 1024 * 1024))
            val packageName = obj.string("packageName") ?: pkg
            val suggested = obj.long("suggestedVersionCode") ?: Long.MAX_VALUE
            val entries = obj.array("packages")?.objects().orEmpty()
            val releases = entries.mapNotNull { entry ->
                val versionCode = entry.long("versionCode") ?: return@mapNotNull null
                val versionName = entry.string("versionName") ?: return@mapNotNull null
                val assetName = "${packageName}_$versionCode.apk"
                Release(
                    id = versionCode.toString(),
                    version = versionName,
                    versionCode = versionCode,
                    prerelease = versionCode > suggested,
                    assets = listOf(Asset(name = assetName, url = "$repoBase/$assetName")),
                )
            }.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No releases for $pkg")
            return CheckResult.Listing(SourceListing(releases = releases, packageName = packageName))
        }
    }

    companion object {
        private const val MAX_RELEASES = 30
        private val FDROID_PATH = Regex("^(?:/[a-zA-Z-]{2,7})?/packages/([^/]+)$")
        private val IZZY_APT_PATH = Regex("^/fdroid/index/apk/([^/]+)$")
        private val IZZY_ANDROID_PATH = Regex("^/repo/apk/([^/]+)$")
        private val SEGMENT = Regex("^[A-Za-z][A-Za-z0-9_]*$")

        fun isValidPackage(pkg: String): Boolean {
            val segments = pkg.split('.')
            return segments.size >= 2 && segments.all { SEGMENT.matches(it) }
        }
    }
}
