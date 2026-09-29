package io.github.munzzyy.jackdaw.core.source.fdroid

import io.github.munzzyy.jackdaw.core.json.Json
import io.github.munzzyy.jackdaw.core.json.JsonObject
import io.github.munzzyy.jackdaw.core.json.JsonReader
import io.github.munzzyy.jackdaw.core.json.JsonString
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
import io.github.munzzyy.jackdaw.core.verify.Fingerprints
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.CodeSigner
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipException

class FDroidRepoSource : Source {
    override val type: String = SourceTypes.FDROID_REPO

    override fun match(url: String): SourceSpec? {
        val normalized = SCHEME.replace(url) { "https://" }
        val uri = Urls.parseHttps(normalized) ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        if (path.isEmpty()) return null
        if (!path.endsWith("/repo") && !path.contains("/fdroid/repo")) return null
        val base = "https://${uri.authority}$path"
        val fingerprint = queryParam(uri.rawQuery, "fingerprint")?.let(Fingerprints::normalize)
        val options = buildMap { if (fingerprint != null) put(SourceOptions.FINGERPRINT, fingerprint) }
        return SourceSpec(type, base, options)
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = spec.option(SourceOptions.PACKAGE) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Missing package option")
        val pinned = spec.option(SourceOptions.FINGERPRINT)

        val entryKey = validatorKey(spec, "entry.jar")
        val entryValidator = context.validators.get(entryKey)
        val entryUrl = "${spec.url}/entry.jar"
        val entryResponse = context.http.execute(HttpRequest(entryUrl, headers = entryValidator?.conditionalHeaders() ?: emptyMap()))
        var usedFallback = false
        val jarBytes: ByteArray
        entryResponse.use {
            when {
                it.isNotModified -> return CheckResult.Unchanged
                it.status == 404 -> {
                    usedFallback = true
                    jarBytes = ByteArray(0)
                }
                it.isSuccess -> {
                    context.validators.put(entryKey, Validator.from(it.headers))
                    jarBytes = it.bytes(ENTRY_JAR_CAP)
                }
                else -> throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $entryUrl")
            }
        }

        if (!usedFallback) {
            val verification = verifyJar(jarBytes, "entry.json", unsupportedOnFailure = false)
            val learned = checkFingerprint(pinned, verification.fingerprint)
            val entryObj = try {
                Json.parseObject(String(verification.signedBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "entry.json is not valid JSON", cause = e)
            }
            val indexInfo = entryObj.obj("index") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json is missing an index")
            val indexName = indexInfo.string("name") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json index has no name")
            val indexSha = indexInfo.string("sha256") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json index has no sha256")
            val indexSize = indexInfo.long("size") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json index has no size")

            val indexUrl = "${spec.url}$indexName"
            val found = fetchIndexV2(indexUrl, indexSha, indexSize, pkg, context)
                ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Package $pkg not found in $indexUrl")
            val releases = buildV2Releases(found, spec.url, context)
            if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable releases for $pkg")
            return CheckResult.Listing(
                SourceListing(releases = releases, packageName = pkg, name = found.name, description = found.summary, learnedOptions = learned),
            )
        }

        val v1Url = "${spec.url}/index-v1.jar"
        val v1Key = validatorKey(spec, "index-v1.jar")
        val v1Validator = context.validators.get(v1Key)
        val v1Response = context.http.execute(HttpRequest(v1Url, headers = v1Validator?.conditionalHeaders() ?: emptyMap()))
        val v1Bytes: ByteArray
        v1Response.use {
            if (it.isNotModified) return CheckResult.Unchanged
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "No entry.jar or index-v1.jar at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $v1Url")
            context.validators.put(v1Key, Validator.from(it.headers))
            v1Bytes = it.bytes(V1_JAR_CAP)
        }
        val verification = verifyJar(v1Bytes, "index-v1.json", unsupportedOnFailure = true)
        val learned = checkFingerprint(pinned, verification.fingerprint)
        val root = try {
            Json.parseObject(String(verification.signedBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SourceException(SourceErrorKind.PARSE, "index-v1.json is not valid JSON", cause = e)
        }
        val releases = buildV1Releases(root, pkg, spec.url, context)
        if (releases.isEmpty()) throw SourceException(SourceErrorKind.NO_RELEASES, "No installable releases for $pkg")
        return CheckResult.Listing(SourceListing(releases = releases, packageName = pkg, learnedOptions = learned))
    }

    private fun checkFingerprint(pinned: String?, learnedFingerprint: String): Map<String, String> {
        if (pinned == null) return mapOf(SourceOptions.FINGERPRINT to learnedFingerprint)
        if (!pinned.equals(learnedFingerprint, ignoreCase = true)) {
            throw SourceException(SourceErrorKind.AUTH, "The repository signing key changed")
        }
        return emptyMap()
    }

    private class Verification(val signedBytes: ByteArray, val fingerprint: String)

    private fun verifyJar(bytes: ByteArray, signedEntryName: String, unsupportedOnFailure: Boolean): Verification {
        var signedBytes: ByteArray? = null
        var signers: Array<CodeSigner>? = null
        try {
            JarInputStream(ByteArrayInputStream(bytes), true).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    val data = jar.readBytes()
                    if (entry.name == signedEntryName) {
                        signedBytes = data
                        signers = entry.codeSigners
                    }
                }
            }
        } catch (e: SecurityException) {
            throw if (unsupportedOnFailure) {
                SourceException(SourceErrorKind.UNSUPPORTED, "This repository signs with an algorithm this build will not verify", cause = e)
            } else {
                SourceException(SourceErrorKind.AUTH, "The repository archive signature is invalid", cause = e)
            }
        } catch (e: ZipException) {
            throw SourceException(SourceErrorKind.AUTH, "The repository archive is not a valid signed jar", cause = e)
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.AUTH, "The repository archive could not be read", cause = e)
        }

        val json = signedBytes ?: throw SourceException(SourceErrorKind.AUTH, "The repository archive has no $signedEntryName")
        val signerList = signers?.toList().orEmpty()
        if (signerList.isEmpty()) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName is not signed")
        if (signerList.size > 1) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName has more than one signer")
        val certificate = signerList[0].signerCertPath.certificates.firstOrNull()
            ?: throw SourceException(SourceErrorKind.AUTH, "The repository signing certificate is missing")
        return Verification(json, Fingerprints.sha256(certificate.encoded))
    }

    private class FoundPackage(val json: JsonObject, val name: String?, val summary: String?)

    private fun fetchIndexV2(indexUrl: String, expectedSha: String, expectedSize: Long, pkg: String, context: CheckContext): FoundPackage? {
        val response = context.http.execute(HttpRequest(indexUrl))
        return response.use { resp ->
            if (!resp.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${resp.status} for $indexUrl")
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CappedDigestInputStream(resp.body, digest, INDEX_CAP)
            val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8), maxDepth = 96)
            var found: JsonObject? = null
            try {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "packages") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val pkgName = reader.nextName()
                            if (pkgName == pkg) {
                                found = reader.readValue() as? JsonObject
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                reader.requireEndOfDocument()
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not parse the repository index", cause = e)
            }
            val digestHex = Fingerprints.toHex(digest.digest())
            if (!digestHex.equals(expectedSha, ignoreCase = true) || counting.count != expectedSize) {
                throw SourceException(SourceErrorKind.PARSE, "The downloaded index does not match the signed hash")
            }
            val packageJson = found ?: return@use null
            val metadata = packageJson.obj("metadata")
            FoundPackage(packageJson, localized(metadata?.obj("name")), localized(metadata?.obj("summary")))
        }
    }

    private fun localized(map: JsonObject?): String? {
        if (map == null) return null
        map.string("en-US")?.let { return it }
        map.string("en")?.let { return it }
        return map.fields.values.filterIsInstance<JsonString>().firstOrNull()?.value
    }

    private fun buildV2Releases(found: FoundPackage, repoBase: String, context: CheckContext): List<Release> {
        val versions = found.json.obj("versions")?.fields.orEmpty()
        val device = context.device
        val releases = versions.values.filterIsInstance<JsonObject>().mapNotNull { version ->
            val file = version.obj("file") ?: return@mapNotNull null
            val fileName = file.string("name") ?: return@mapNotNull null
            val manifest = version.obj("manifest") ?: return@mapNotNull null
            val versionName = manifest.string("versionName") ?: return@mapNotNull null
            val versionCode = manifest.long("versionCode") ?: return@mapNotNull null
            val nativecode = manifest.array("nativecode")?.strings().orEmpty()
            val minSdk = manifest.obj("usesSdk")?.long("minSdkVersion")
            if (device != null) {
                if (nativecode.isNotEmpty() && nativecode.none { it in device.abis }) return@mapNotNull null
                if (minSdk != null && minSdk > device.sdk) return@mapNotNull null
            }
            val signerHashes = manifest.obj("signer")?.array("sha256")?.strings().orEmpty()
            val prerelease = version.array("releaseChannels")?.isNotEmpty() == true
            val asset = Asset(
                name = fileName.substringAfterLast('/'),
                url = repoBase + fileName,
                size = file.long("size"),
                sha256 = file.string("sha256"),
                signers = signerHashes,
            )
            Release(id = versionCode.toString(), version = versionName, versionCode = versionCode, prerelease = prerelease, assets = listOf(asset))
        }
        return releases.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
    }

    private fun buildV1Releases(root: JsonObject, pkg: String, repoBase: String, context: CheckContext): List<Release> {
        val device = context.device
        val entries = root.array("packages")?.objects().orEmpty()
        val releases = entries.filter { it.string("packageName") == pkg }.mapNotNull { entry ->
            val versionName = entry.string("versionName") ?: return@mapNotNull null
            val versionCode = entry.long("versionCode") ?: return@mapNotNull null
            val apkName = entry.string("apkName") ?: return@mapNotNull null
            val nativecode = entry.array("nativecode")?.strings().orEmpty()
            val minSdk = entry.long("minSdkVersion")
            if (device != null) {
                if (nativecode.isNotEmpty() && nativecode.none { it in device.abis }) return@mapNotNull null
                if (minSdk != null && minSdk > device.sdk) return@mapNotNull null
            }
            val asset = Asset(name = apkName, url = "$repoBase/$apkName", size = entry.long("size"), sha256 = entry.string("hash"))
            Release(id = versionCode.toString(), version = versionName, versionCode = versionCode, assets = listOf(asset))
        }
        return releases.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery == null) return null
        for (pair in rawQuery.split('&')) {
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == name) return java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        return null
    }

    companion object {
        private const val MAX_RELEASES = 30
        private const val ENTRY_JAR_CAP = 1024 * 1024
        private const val V1_JAR_CAP = 32 * 1024 * 1024
        private const val INDEX_CAP = 96L * 1024 * 1024
        private val SCHEME = Regex("^fdroidrepos?://", RegexOption.IGNORE_CASE)
    }
}

private class CappedDigestInputStream(
    private val source: InputStream,
    private val digest: MessageDigest,
    private val cap: Long,
) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val b = source.read()
        if (b >= 0) {
            digest.update(b.toByte())
            count++
            if (count > cap) throw IOException("Index exceeds the $cap byte cap")
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = source.read(b, off, len)
        if (n > 0) {
            digest.update(b, off, n)
            count += n
            if (count > cap) throw IOException("Index exceeds the $cap byte cap")
        }
        return n
    }

    override fun close() {
        source.close()
    }
}
