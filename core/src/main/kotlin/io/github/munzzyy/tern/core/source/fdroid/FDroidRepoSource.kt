package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.apk.BinaryManifest
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonMergePatch
import io.github.munzzyy.tern.core.json.JsonNull
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonReader
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonToken
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.NotesFormat
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.Source
import io.github.munzzyy.tern.core.source.SourceErrorKind
import io.github.munzzyy.tern.core.source.SourceException
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.guarded
import io.github.munzzyy.tern.core.text.Shown
import io.github.munzzyy.tern.core.verify.Fingerprints
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.CodeSigner
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipException

/**
 * Any repository in F-Droid's format. [tracked] names every app the user follows in a repository,
 * so that one download serves them all instead of one download per app.
 */
class FDroidRepoSource(private val tracked: (repositoryUrl: String) -> Set<String> = { emptySet() }) : Source {
    override val type: String = SourceTypes.FDROID_REPO

    private val shared = SharedDownloads()

    override fun match(url: String): SourceSpec? {
        val normalized = SCHEME.replace(url) { "https://" }
        val uri = Urls.parseHttps(normalized) ?: return null
        val path = uri.path?.trimEnd('/') ?: return null
        if (path.isEmpty()) return null
        if (!path.endsWith("/repo") && !path.contains("/fdroid/repo")) return null
        val base = "https://${uri.authority}$path"
        val fingerprint = Urls.queryParam(uri.toString(), "fingerprint")?.let(Fingerprints::normalize)
        val pkg = Urls.queryParam(uri.toString(), "package")
        if (pkg != null && !BinaryManifest.isValidName(pkg)) return null
        val options = buildMap {
            if (fingerprint != null) put(SourceOptions.FINGERPRINT, fingerprint)
            if (pkg != null) put(SourceOptions.PACKAGE, pkg)
        }
        return SourceSpec(type, base, options)
    }

    /** One app found while listing everything a repository without a chosen package offers. Its texts are fit to be shown. */
    data class RepoApp(val packageName: String, val name: String, val summary: String?)

    /** What listing found: the repository's own name, fit to be shown, the fingerprint learned or confirmed while reading it, and its apps. */
    data class RepoListing(val apps: List<RepoApp>, val more: Boolean, val repositoryName: String?, val fingerprint: String)

    /**
     * The apps a repository address without a `package` option carries, read through the same
     * verified path [check] uses. At most [MAX_LIST_APPS], ordered by name without regard to case
     * and by package where names are equal, with [RepoListing.more] set when the repository holds
     * more than that. An entry whose package is not a package name is left out.
     */
    fun listApps(spec: SourceSpec, context: CheckContext): RepoListing = guarded(context) { listOnce(spec, it) }

    private fun listOnce(spec: SourceSpec, context: CheckContext): RepoListing {
        val pinned = spec.option(SourceOptions.FINGERPRINT)
        val entryUrl = "${spec.url}/entry.jar"
        val entryResponse = context.http.execute(HttpRequest(entryUrl))
        var usedFallback = false
        val jarBytes: ByteArray
        entryResponse.use {
            when {
                it.status == 404 -> {
                    usedFallback = true
                    jarBytes = ByteArray(0)
                }
                it.isSuccess -> jarBytes = it.bytes(ENTRY_JAR_CAP)
                else -> throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $entryUrl")
            }
        }
        if (!usedFallback) {
            val verification = verifyJar(jarBytes, "entry.json", unsupportedOnFailure = false)
            checkFingerprint(pinned, verification.fingerprint)
            val entry = try {
                Json.parseObject(String(verification.signedBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "entry.json is not valid JSON", cause = e)
            }
            val index = signedFile(entry.obj("index"), "index")
            return streamV2AppList(spec.url, index, context, verification.fingerprint)
        }

        val v1Url = "${spec.url}/index-v1.jar"
        val v1Response = context.http.execute(HttpRequest(v1Url))
        val v1Bytes: ByteArray
        v1Response.use {
            if (it.status == 404) throw SourceException(SourceErrorKind.NOT_FOUND, "No entry.jar or index-v1.jar at ${spec.url}")
            if (!it.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${it.status} for $v1Url")
            v1Bytes = it.bytes(V1_JAR_CAP)
        }
        val verification = verifyJar(v1Bytes, "index-v1.json", unsupportedOnFailure = true)
        checkFingerprint(pinned, verification.fingerprint)
        val root = try {
            Json.parseObject(String(verification.signedBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SourceException(SourceErrorKind.PARSE, "index-v1.json is not valid JSON", cause = e)
        }
        val first = FirstByName(MAX_LIST_APPS)
        for (a in root.array("apps")?.objects().orEmpty()) {
            val pkg = a.string("packageName")?.takeIf { BinaryManifest.isValidName(it) } ?: continue
            first.offer(RepoApp(pkg, Shown.lineOrNull(a.string("name"), MAX_APP_STRING) ?: pkg, Shown.lineOrNull(a.string("summary"), MAX_APP_STRING)))
        }
        val repoName = Shown.lineOrNull(root.obj("repo")?.string("name"), MAX_APP_STRING)
        return RepoListing(first.apps(), first.more, repoName, verification.fingerprint)
    }

    /** Streams "packages" instead of parsing the whole index into memory, as [download] does for a single app. */
    private fun streamV2AppList(repositoryUrl: String, file: SignedFile, context: CheckContext, fingerprint: String): RepoListing {
        val url = repositoryUrl + file.name
        return context.http.execute(HttpRequest(url)).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${response.status} for $url")
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CappedDigestInputStream(response.body, digest, INDEX_CAP)
            val first = FirstByName(MAX_LIST_APPS)
            var repoName: String? = null
            try {
                val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8), maxDepth = 96)
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "repo" -> {
                            val repo = reader.readValue() as? JsonObject
                            repoName = Shown.lineOrNull(localized(repo?.obj("name")) ?: repo?.string("name"), MAX_APP_STRING)
                        }
                        "packages" -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val pkg = reader.nextName()
                                if (!BinaryManifest.isValidName(pkg)) {
                                    reader.skipValue()
                                    continue
                                }
                                val value = reader.readValue() as? JsonObject
                                val metadata = value?.obj("metadata")
                                val name = Shown.lineOrNull(localized(metadata?.obj("name")), MAX_APP_STRING) ?: pkg
                                val summary = Shown.lineOrNull(localized(metadata?.obj("summary")), MAX_APP_STRING)
                                first.offer(RepoApp(pkg, name, summary))
                            }
                            reader.endObject()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                reader.requireEndOfDocument()
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not read ${file.name} of the repository", cause = e)
            }
            if (Fingerprints.toHex(digest.digest()) != file.sha256 || counting.count != file.size) {
                throw SourceException(SourceErrorKind.PARSE, "${file.name} does not match the hash the repository signed")
            }
            RepoListing(first.apps(), first.more, repoName, fingerprint)
        }
    }

    /** A validly signed index that is older than one already seen is a replay, used to hide updates. */
    private fun refuseReplay(spec: SourceSpec, context: CheckContext, timestamp: Long?) {
        if (timestamp == null) throw SourceException(SourceErrorKind.PARSE, "The repository index carries no timestamp")
        val key = validatorKey(spec, "timestamp")
        val seen = context.validators.get(key)?.etag?.toLongOrNull()
        if (seen != null && timestamp < seen) {
            throw SourceException(SourceErrorKind.AUTH, "The repository served an index older than the one seen before")
        }
        context.validators.put(key, Validator(timestamp.toString(), null))
    }

    override fun check(spec: SourceSpec, context: CheckContext): CheckResult = guarded(context) { checkOnce(spec, it) }

    private fun checkOnce(spec: SourceSpec, context: CheckContext): CheckResult {
        val pkg = packageOption(spec)
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
            val entry = try {
                Json.parseObject(String(verification.signedBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "entry.json is not valid JSON", cause = e)
            }
            val index = signedFile(entry.obj("index"), "index")
            val timestamp = entry.long("timestamp") ?: throw SourceException(SourceErrorKind.PARSE, "The repository index carries no timestamp")
            refuseReplay(spec, context, timestamp)

            val held = held(spec, context, pkg)
            val current: JsonObject = when {
                held != null && held.timestamp == timestamp -> return CheckResult.Unchanged
                held != null && entry.obj("diffs")?.obj(held.timestamp.toString()) != null -> {
                    val diff = signedFile(entry.obj("diffs")?.obj(held.timestamp.toString()), "diff")
                    when (val patch = download(spec.url, diff, DIFF_CAP, pkg, context).found[pkg]) {
                        null -> {
                            hold(spec, context, pkg, timestamp, held.json)
                            return CheckResult.Unchanged
                        }
                        JsonNull -> throw SourceException(SourceErrorKind.NOT_FOUND, "The repository no longer carries $pkg")
                        else -> JsonMergePatch.apply(held.json, patch) as? JsonObject
                            ?: throw SourceException(SourceErrorKind.PARSE, "The repository diff does not describe $pkg")
                    }
                }
                else -> download(spec.url, index, INDEX_CAP, pkg, context).found[pkg] as? JsonObject
                    ?: throw SourceException(SourceErrorKind.NOT_FOUND, "Package $pkg not found in ${spec.url}")
            }
            hold(spec, context, pkg, timestamp, current)

            val metadata = current.obj("metadata")
            return CheckResult.Listing(
                SourceListing(
                    releases = buildV2Releases(current, spec.url, context),
                    packageName = pkg,
                    name = localized(metadata?.obj("name")),
                    description = localized(metadata?.obj("summary")),
                    learnedOptions = learned,
                ).withIcons(spec.url, listOf(FDroidIcons.fromIndex(current, spec.url))),
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
        refuseReplay(spec, context, root.obj("repo")?.long("timestamp"))
        val listing = SourceListing(releases = buildV1Releases(root, pkg, spec.url, context), packageName = pkg, learnedOptions = learned)
        return CheckResult.Listing(listing.withIcons(spec.url, listOf(FDroidIcons.fromFirstIndex(root, pkg, spec.url))))
    }

    private class SignedFile(val name: String, val sha256: String, val size: Long)

    private fun signedFile(described: JsonObject?, what: String): SignedFile {
        val name = described?.string("name") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json names no $what")
        val sha256 = described.string("sha256")?.let(Fingerprints::normalize) ?: throw SourceException(SourceErrorKind.PARSE, "entry.json gives no hash for its $what")
        val size = described.long("size") ?: throw SourceException(SourceErrorKind.PARSE, "entry.json gives no size for its $what")
        if (!insideRepository(name)) throw SourceException(SourceErrorKind.PARSE, "entry.json names a $what outside the repository")
        return SignedFile(name, sha256, size)
    }

    /** What is known about an app from the last index read, so that the next change costs a diff. */
    private class Held(val timestamp: Long, val json: JsonObject)

    private fun held(spec: SourceSpec, context: CheckContext, pkg: String): Held? {
        val stored = context.validators.get(validatorKey(spec, "package:$pkg")) ?: return null
        val timestamp = stored.lastModified?.toLongOrNull() ?: return null
        val json = try {
            Json.parse(stored.etag ?: return null) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return Held(timestamp, json)
    }

    private fun hold(spec: SourceSpec, context: CheckContext, pkg: String, timestamp: Long, json: JsonObject) {
        val key = validatorKey(spec, "package:$pkg")
        val text = Json.write(json)
        if (text.length > MAX_HELD) context.validators.remove(key) else context.validators.put(key, Validator(text, timestamp.toString()))
    }

    private fun checkFingerprint(pinned: String?, learnedFingerprint: String): Map<String, String> {
        if (pinned == null) return mapOf(SourceOptions.FINGERPRINT to learnedFingerprint)
        if (!pinned.equals(learnedFingerprint, ignoreCase = true)) {
            throw SourceException(SourceErrorKind.AUTH, "The repository signing key changed")
        }
        return emptyMap()
    }

    private class Verification(val signedBytes: ByteArray, val fingerprint: String)

    /**
     * Read through JarFile, which finds the manifest wherever it sits. F-Droid writes it last, and a
     * stream reader that expects it first reports such an archive as unsigned.
     */
    private fun verifyJar(bytes: ByteArray, signedEntryName: String, unsupportedOnFailure: Boolean): Verification {
        var signedBytes: ByteArray? = null
        var signers: Array<CodeSigner>? = null
        val file = try {
            File.createTempFile("repository", ".jar")
        } catch (e: IOException) {
            throw SourceException(SourceErrorKind.NETWORK, "No room to check the repository archive", cause = e)
        }
        try {
            file.writeBytes(bytes)
            JarFile(file, true).use { jar ->
                if (jar.size() > MAX_JAR_ENTRIES) throw SourceException(SourceErrorKind.AUTH, "The repository archive holds too many entries")
                val entry = jar.getJarEntry(signedEntryName)
                if (entry != null) {
                    if (entry.size > MAX_SIGNED_ENTRY) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName is too large")
                    signedBytes = jar.getInputStream(entry).use { upTo(it, MAX_SIGNED_ENTRY + 1) }
                    signers = entry.codeSigners
                    // Java counts a SHA-1 signature as none and Android may not, so the answer is given here, the same everywhere.
                    if (!strongDigest(entry.attributes?.keys.orEmpty().map { it.toString() })) {
                        throw SourceException(SourceErrorKind.UNSUPPORTED, "This repository signs its index with SHA-1, which is no longer safe to rely on")
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
        } finally {
            file.delete()
        }

        val json = signedBytes ?: throw SourceException(SourceErrorKind.AUTH, "The repository archive has no $signedEntryName")
        if (json.size > MAX_SIGNED_ENTRY) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName is too large")
        val signerList = signers?.toList().orEmpty()
        if (signerList.isEmpty()) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName is not signed")
        if (signerList.size > 1) throw SourceException(SourceErrorKind.AUTH, "$signedEntryName has more than one signer")
        val certificate = signerList[0].signerCertPath.certificates.firstOrNull()
            ?: throw SourceException(SourceErrorKind.AUTH, "The repository signing certificate is missing")
        return Verification(json, Fingerprints.sha256(certificate.encoded))
    }

    /**
     * Reads one signed file of the repository, a full index or a diff, and keeps what it says about
     * every tracked app. A value of JsonNull means a diff removes the app.
     */
    private fun download(repositoryUrl: String, file: SignedFile, cap: Long, pkg: String, context: CheckContext): Extract {
        val key = "$repositoryUrl|${file.sha256}"
        shared.get(key)?.takeIf { pkg in it.searched }?.let { return it }

        val wanted = (tracked(repositoryUrl).asSequence().take(MAX_TRACKED) + pkg).toSet()
        val url = repositoryUrl + file.name
        return context.http.execute(HttpRequest(url)).use { response ->
            if (!response.isSuccess) throw SourceException(SourceErrorKind.NETWORK, "Unexpected status ${response.status} for $url")
            val digest = MessageDigest.getInstance("SHA-256")
            val counting = CappedDigestInputStream(response.body, digest, cap)
            val found = HashMap<String, JsonValue>()
            try {
                val reader = JsonReader(InputStreamReader(counting, Charsets.UTF_8), maxDepth = 96)
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "packages" || reader.peek() != JsonToken.BEGIN_OBJECT) {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in wanted) found[name] = reader.readValue() else reader.skipValue()
                    }
                    reader.endObject()
                }
                reader.endObject()
                reader.requireEndOfDocument()
            } catch (e: Exception) {
                throw SourceException(SourceErrorKind.PARSE, "Could not read ${file.name} of the repository", cause = e)
            }
            if (Fingerprints.toHex(digest.digest()) != file.sha256 || counting.count != file.size) {
                throw SourceException(SourceErrorKind.PARSE, "${file.name} does not match the hash the repository signed")
            }
            Extract(wanted, found).also { shared.put(key, it) }
        }
    }

    private fun localized(map: JsonObject?): String? {
        if (map == null) return null
        map.string("en-US")?.let { return it }
        map.string("en")?.let { return it }
        return map.fields.values.filterIsInstance<JsonString>().firstOrNull()?.value
    }

    private fun buildV2Releases(app: JsonObject, repoBase: String, context: CheckContext): List<Release> {
        val versions = app.obj("versions")?.fields.orEmpty()
        val device = context.device
        val releases = versions.values.filterIsInstance<JsonObject>().mapNotNull { version ->
            val file = version.obj("file") ?: return@mapNotNull null
            val fileName = file.string("name")?.takeIf(::insideRepository) ?: return@mapNotNull null
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
                sha256 = file.string("sha256")?.let(Fingerprints::normalize),
                signers = signerHashes.mapNotNull(Fingerprints::normalize),
            )
            Release(
                id = versionCode.toString(),
                version = versionName,
                versionCode = versionCode,
                notes = localized(version.obj("whatsNew")),
                notesFormat = NotesFormat.PLAIN,
                publishedAtMs = version.long("added"),
                prerelease = prerelease,
                assets = listOf(asset),
            )
        }
        return releases.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
    }

    private fun buildV1Releases(root: JsonObject, pkg: String, repoBase: String, context: CheckContext): List<Release> {
        val device = context.device
        // In this format "packages" maps each package to the list of its versions.
        val entries = root.obj("packages")?.array(pkg)?.objects().orEmpty()
        val releases = entries.filter { it.string("packageName") == pkg }.mapNotNull { entry ->
            val versionName = entry.string("versionName") ?: return@mapNotNull null
            val versionCode = entry.long("versionCode") ?: return@mapNotNull null
            val apkName = entry.string("apkName")?.takeIf { insideRepository("/$it") } ?: return@mapNotNull null
            val nativecode = entry.array("nativecode")?.strings().orEmpty()
            val minSdk = entry.long("minSdkVersion")
            if (device != null) {
                if (nativecode.isNotEmpty() && nativecode.none { it in device.abis }) return@mapNotNull null
                if (minSdk != null && minSdk > device.sdk) return@mapNotNull null
            }
            val asset = Asset(
                name = apkName,
                url = "$repoBase/$apkName",
                size = entry.long("size"),
                sha256 = entry.string("hash")?.takeIf { entry.string("hashType").equals("sha256", ignoreCase = true) }?.let(Fingerprints::normalize),
                signers = listOfNotNull(entry.string("signer")?.let(Fingerprints::normalize)),
            )
            Release(id = versionCode.toString(), version = versionName, versionCode = versionCode, publishedAtMs = entry.long("added"), assets = listOf(asset))
        }
        return releases.sortedByDescending { it.versionCode }.take(MAX_RELEASES)
    }

    companion object {
        private const val MAX_RELEASES = 30
        private const val MAX_TRACKED = 500
        private const val MAX_HELD = 512 * 1024
        private const val DIFF_CAP = 32L * 1024 * 1024
        private const val MAX_JAR_ENTRIES = 64
        private const val MAX_SIGNED_ENTRY = 40 * 1024 * 1024
        private const val ENTRY_JAR_CAP = 1024 * 1024
        private const val V1_JAR_CAP = 32 * 1024 * 1024
        private const val INDEX_CAP = 96L * 1024 * 1024
        private const val MAX_LIST_APPS = 200
        private const val MAX_APP_STRING = 200
        private val SCHEME = Regex("^fdroidrepos?://", RegexOption.IGNORE_CASE)

        /** The address of one app of a repository, as [match] reads it. Both values are encoded, so neither can add to the address. */
        fun appAddress(repositoryUrl: String, packageName: String, fingerprint: String): String =
            "$repositoryUrl?package=${Urls.encodeSegment(packageName)}&fingerprint=${Urls.encodeSegment(fingerprint)}"
    }
}

/** At most [limit] bytes of [input]. InputStream has a method for this, which Android only has from version 13 on. */
internal fun upTo(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The package a spec asks for. It comes from a link or an import, so it is checked before it goes into an address. */
internal fun packageOption(spec: SourceSpec): String {
    val pkg = spec.option(SourceOptions.PACKAGE) ?: throw SourceException(SourceErrorKind.UNSUPPORTED, "Missing package option")
    if (!BinaryManifest.isValidName(pkg)) throw SourceException(SourceErrorKind.UNSUPPORTED, "The package option is not a package name")
    return pkg
}

/** True when the manifest vouches for the file with SHA-256 or stronger. A file with no digest at all is left to the signer check. */
internal fun strongDigest(attributeNames: List<String>): Boolean {
    val digests = attributeNames.filter { it.endsWith("-Digest", ignoreCase = true) }.map { it.uppercase() }
    return digests.isEmpty() || digests.any { it.startsWith("SHA-256") || it.startsWith("SHA-384") || it.startsWith("SHA-512") || it.startsWith("SHA256") || it.startsWith("SHA384") || it.startsWith("SHA512") }
}

/** True for a file name that stays inside the repository it was read from. */
internal fun insideRepository(name: String): Boolean =
    name.startsWith("/") && !name.startsWith("//") && name.length <= 512 &&
        name.split('/').none { it == ".." || it == "." } && name.none { it == '\\' || it == '?' || it == '#' || it.code < 0x20 }

/**
 * The first [limit] apps in the order of the list, kept while reading. It holds one more than it
 * hands out, which is how it knows there were more, and never more than that.
 */
internal class FirstByName(private val limit: Int) {
    private val kept = java.util.TreeSet(ORDER)

    val held: Int get() = kept.size

    val more: Boolean get() = kept.size > limit

    fun offer(app: FDroidRepoSource.RepoApp) {
        kept.add(app)
        if (kept.size > limit + 1) kept.pollLast()
    }

    fun apps(): List<FDroidRepoSource.RepoApp> = kept.take(limit)

    companion object {
        val ORDER: Comparator<FDroidRepoSource.RepoApp> =
            compareBy<FDroidRepoSource.RepoApp, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.packageName }
    }
}

/** What one signed file said about the apps that were looked for in it. */
private class Extract(val searched: Set<String>, val found: Map<String, JsonValue>)

/** The last few files read, kept in memory so that apps of one repository share a download. */
private class SharedDownloads {
    private val recent = LinkedHashMap<String, Extract>()

    @Synchronized
    fun get(key: String): Extract? = recent[key]

    @Synchronized
    fun put(key: String, extract: Extract) {
        recent.remove(key)
        recent[key] = extract
        while (recent.size > KEPT) recent.remove(recent.keys.first())
    }

    private companion object {
        const val KEPT = 4
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
