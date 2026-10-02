package io.github.munzzyy.tern.core.live

import io.github.munzzyy.tern.core.apk.ApkInfo
import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.engine.ReleaseSelector
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.ReleasePolicy
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.HttpRequest
import io.github.munzzyy.tern.core.net.InMemoryValidatorStore
import io.github.munzzyy.tern.core.net.PoliteHttp
import io.github.munzzyy.tern.core.net.RateLimiter
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.source.CheckContext
import io.github.munzzyy.tern.core.source.CheckResult
import io.github.munzzyy.tern.core.source.SourceListing
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceRegistry
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.fdroid.FDroidRepoSource
import io.github.munzzyy.tern.core.suggest.Catalog
import io.github.munzzyy.tern.core.suggest.ConfirmedBy
import io.github.munzzyy.tern.core.suggest.SuggestedApp
import io.github.munzzyy.tern.core.version.Version
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the starter list against the real services, one line for each entry. Off unless asked for:
 * ./gradlew :core:test --tests '*SuggestionsLiveTest' -Dtern.live=true
 * TERN_SUGGEST="Kodi,VLC" in the environment limits the run to the entries named.
 * It reads public data only and sends no credentials. GitHub answers 60 such requests an hour.
 */
class SuggestionsLiveTest {
    private val http = PoliteHttp(JvmHttp(), RateLimiter(), "Tern-live-test/0.1")
    private val registry = SourceRegistry.standard()
    private val phone = DeviceProfile(listOf("arm64-v8a"), sdk = 36, densityDpi = 420)

    /** A phone on the oldest Android that Tern itself runs on. */
    private val oldPhone = DeviceProfile(listOf("arm64-v8a"), sdk = 28, densityDpi = 420)

    /** The oldest Android that Tern itself runs on, with a 32-bit processor as most television boxes have. */
    private val television = DeviceProfile(listOf("armeabi-v7a", "armeabi"), sdk = 28, densityDpi = 320, television = true)
    private val inspected = HashMap<String, ApkInfo>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests are off", System.getProperty("tern.live") == "true")
    }

    @Test
    fun everySuggestionIsFoundAndOffersAFileThatInstalls() {
        val only = System.getenv("TERN_SUGGEST").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val unproven = ArrayList<String>()
        for (app in Catalog.all) {
            if (only.isNotEmpty() && app.name.lowercase() !in only) continue
            val (problems, facts) = try {
                prove(app)
            } catch (e: Exception) {
                listOf("${e.javaClass.simpleName}: ${e.message}") to ""
            }
            val verdict = if (problems.isEmpty()) "PASS" else "FAIL (${problems.joinToString("; ")})"
            println("$verdict | ${app.name} | $facts")
            if (problems.isNotEmpty()) unproven += app.name
        }
        assertTrue("Not proven today: $unproven", unproven.isEmpty())
    }

    /**
     * What F-Droid's signed index names as the signer of each entry it carries, one line for each.
     * F-Droid signs most apps with a key of its own. Where the index names the signer of the
     * developer's own file instead, F-Droid has built the same file from the source and ships the
     * developer's signature, and then the two places confirm each other. The index is downloaded
     * once for all entries.
     */
    @Test
    fun whatFDroidNamesAsTheSignerOfEachEntry() {
        val only = System.getenv("TERN_SUGGEST").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val wanted = Catalog.all.filter { only.isEmpty() || it.name.lowercase() in only }.associateWith { it.fdroidId ?: it.packageName }
        val repository = FDroidRepoSource { wanted.values.toSet() }
        val differing = ArrayList<String>()
        for ((app, id) in wanted) {
            val spec = SourceSpec(SourceTypes.FDROID_REPO, FDROID_REPOSITORY, mapOf(SourceOptions.PACKAGE to id))
            val line = try {
                val listing = (repository.check(spec, CheckContext(http, InMemoryValidatorStore())) as CheckResult.Listing).listing
                val newest = listing.releases.first()
                val signers = newest.assets.flatMap { it.signers }.distinct()
                val earlier = listing.releases.drop(1).flatMap { r -> r.assets.flatMap { it.signers } }.distinct() - signers.toSet()
                if (app.confirmedBy == ConfirmedBy.FDROID && signers.none { it in app.signers }) differing += app.name
                "$id ${newest.version} | the index names as its signer ${signers.joinToString().ifEmpty { "nobody" }}" +
                    (if (earlier.isEmpty()) "" else ", and for earlier versions ${earlier.joinToString()}") +
                    " | the index is signed by ${listing.learnedOptions[SourceOptions.FINGERPRINT]}"
            } catch (e: Exception) {
                if (app.confirmedBy == ConfirmedBy.FDROID) differing += app.name
                "$id | ${e.javaClass.simpleName}: ${e.message}"
            }
            println("FDROID | ${app.name} | $line")
        }
        assertTrue("F-Droid no longer names the signer the list carries: $differing", differing.isEmpty())
    }

    private class Fit(val text: String, val info: ApkInfo? = null, val installs: Boolean = false, val early: Boolean = false)

    private fun prove(app: SuggestedApp): Pair<List<String>, String> {
        val context = CheckContext(http, InMemoryValidatorStore())
        val address = Urls.normalize(app.url) ?: return listOf("the address does not parse") to ""
        val spec = registry.detect(address, context) ?: SourceSpec(SourceTypes.HTML, address)
        val listing = (registry.check(spec, context) as CheckResult.Listing).listing

        val problems = ArrayList<String>()
        if (spec.url != app.url) problems += "detection settles on ${spec.url}"
        listing.movedTo?.let { problems += "moved to $it" }

        val onPhone = fit(listing, phone)
        val onOldPhone = fit(listing, oldPhone)
        val onTelevision = fit(listing, television)
        if (!onPhone.installs) problems += "nothing for a 64-bit phone"
        if (!onOldPhone.installs) problems += "nothing for a phone on Android 9"
        if (app.television && !onTelevision.installs) problems += "nothing for a 32-bit television"
        val signedBy = LinkedHashSet<String>()
        for ((info, device) in listOf(onPhone.info to phone, onOldPhone.info to oldPhone, onTelevision.info to television)) {
            if (info == null) continue
            if (info.manifest.packageName != app.packageName) problems += "serves ${info.manifest.packageName}, not ${app.packageName}"
            if (info.manifest.debuggable) problems += "offers a debug build"
            val signers = info.signersFor(device.sdk).map { it.sha256 }
            signedBy += signers
            if (app.signers.isNotEmpty() && signers.none { it in app.signers }) problems += "is signed by ${signers.joinToString()}, which the list does not carry"
        }
        if (onPhone.early || onOldPhone.early || onTelevision.early) problems += "offers a pre-release as the newest release"
        if (app.television && onTelevision.info?.manifest?.features?.contains(LEANBACK) != true) problems += "does not declare $LEANBACK"

        val carried = app.fdroidId?.let { if (carries(it)) "F-Droid carries $it" else "F-Droid does not carry $it".also(problems::add) }
        val ground = carried ?: app.publisher?.let { "published by $it" } ?: if (app.own) "the author's own" else "no ground".also(problems::add)
        val pins = if (app.signers.isEmpty()) "the list carries no certificate" else "the list carries it"
        val signed = "the file names as its signer ${signedBy.joinToString().ifEmpty { "nobody" }}, $pins"
        return problems.distinct() to "${spec.type} ${spec.url} | phone: ${onPhone.text} | Android 9 phone: ${onOldPhone.text} | television: ${onTelevision.text} | $ground | $signed"
    }

    /** What Tern would offer [device] with the settings a new app starts with, and whether that file can be installed there. */
    private fun fit(listing: SourceListing, device: DeviceProfile): Fit {
        val release = ReleaseSelector.select(listing.releases, ReleasePolicy(), System.currentTimeMillis()) {
            AssetPicker.rank(it.assets, device, AssetPolicy()).isNotEmpty()
        }.candidate ?: return Fit("no stable release with a file")
        val asset = AssetPicker.rank(release.assets, device, AssetPolicy()).first().asset
        val version = release.version.ifBlank { release.id.take(24) }
        if (asset.kind != AssetKind.APK) return Fit("$version ${asset.name}, a bundle that cannot be read before download")

        val info = inspected.getOrPut(asset.url) { ApkInspector.inspectRemote(http, asset.url) }
        val manifest = info.manifest
        val abis = manifest.nativeLibraryAbis
        val why = buildList {
            if (abis.isNotEmpty() && abis.none { it in device.abis }) add("built for ${abis.joinToString()}")
            manifest.minSdk?.takeIf { it > device.sdk }?.let { add("needs API $it") }
            if (manifest.testOnly) add("test only")
            if (info.signersFor(device.sdk).isEmpty()) add("unsigned")
        }
        val native = if (abis.isEmpty()) "no native code" else abis.joinToString(" ")
        val tv = if (LEANBACK in manifest.features) ", leanback" else ""
        val text = "$version ${asset.name} (${manifest.packageName} ${manifest.versionCode}, $native, API ${manifest.minSdk}$tv)"
        val early = listOfNotNull(asset.name, manifest.versionName).any { Version.parse(it).isPrerelease }
        return Fit(if (why.isEmpty()) text else "$text REFUSED: ${why.joinToString()}", info, why.isEmpty(), early)
    }

    private fun carries(id: String): Boolean = http.execute(HttpRequest("https://f-droid.org/api/v1/packages/$id")).use {
        when (it.status) {
            200 -> true
            404 -> false
            else -> error("F-Droid answered ${it.status} for $id")
        }
    }

    private companion object {
        const val LEANBACK = "android.software.leanback"
        const val FDROID_REPOSITORY = "https://f-droid.org/repo"
    }
}
