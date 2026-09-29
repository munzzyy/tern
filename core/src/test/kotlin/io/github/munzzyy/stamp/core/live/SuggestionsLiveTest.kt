package io.github.munzzyy.stamp.core.live

import io.github.munzzyy.stamp.core.apk.ApkInfo
import io.github.munzzyy.stamp.core.apk.ApkInspector
import io.github.munzzyy.stamp.core.engine.ReleaseSelector
import io.github.munzzyy.stamp.core.model.AssetKind
import io.github.munzzyy.stamp.core.model.AssetPolicy
import io.github.munzzyy.stamp.core.model.DeviceProfile
import io.github.munzzyy.stamp.core.model.ReleasePolicy
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.core.net.HttpRequest
import io.github.munzzyy.stamp.core.net.InMemoryValidatorStore
import io.github.munzzyy.stamp.core.net.PoliteHttp
import io.github.munzzyy.stamp.core.net.RateLimiter
import io.github.munzzyy.stamp.core.net.Urls
import io.github.munzzyy.stamp.core.select.AssetPicker
import io.github.munzzyy.stamp.core.source.CheckContext
import io.github.munzzyy.stamp.core.source.CheckResult
import io.github.munzzyy.stamp.core.source.SourceListing
import io.github.munzzyy.stamp.core.source.SourceRegistry
import io.github.munzzyy.stamp.core.source.SourceTypes
import io.github.munzzyy.stamp.core.suggest.Catalog
import io.github.munzzyy.stamp.core.suggest.SuggestedApp
import io.github.munzzyy.stamp.core.version.Version
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the starter list against the real services, one line for each entry. Off unless asked for:
 * ./gradlew :core:cleanTest :core:test --tests '*SuggestionsLiveTest' -Dstamp.live=true
 * STAMP_SUGGEST="Kodi,VLC" in the environment limits the run to the entries named.
 * It reads public data only and sends no credentials. GitHub answers 60 such requests an hour.
 */
class SuggestionsLiveTest {
    private val http = PoliteHttp(JvmHttp(), RateLimiter(), "Stamp-live-test/0.1")
    private val registry = SourceRegistry.standard()
    private val phone = DeviceProfile(listOf("arm64-v8a"), sdk = 36, densityDpi = 420)

    /** The oldest Android that Stamp itself runs on, with a 32-bit processor as most television boxes have. */
    private val television = DeviceProfile(listOf("armeabi-v7a", "armeabi"), sdk = 29, densityDpi = 320, television = true)
    private val inspected = HashMap<String, ApkInfo>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("live tests are off", System.getProperty("stamp.live") == "true")
    }

    @Test
    fun everySuggestionIsFoundAndOffersAFileThatInstalls() {
        val only = System.getenv("STAMP_SUGGEST").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
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
        val onTelevision = fit(listing, television)
        if (!onPhone.installs) problems += "nothing for a 64-bit phone"
        if (app.television && !onTelevision.installs) problems += "nothing for a 32-bit television"
        for (info in listOfNotNull(onPhone.info, onTelevision.info).distinct()) {
            if (info.manifest.packageName != app.packageName) problems += "serves ${info.manifest.packageName}, not ${app.packageName}"
            if (info.manifest.debuggable) problems += "offers a debug build"
        }
        if (onPhone.early || onTelevision.early) problems += "offers a pre-release as the newest release"
        if (app.television && onTelevision.info?.manifest?.features?.contains(LEANBACK) != true) problems += "does not declare $LEANBACK"

        val carried = app.fdroidId?.let { if (carries(it)) "F-Droid carries $it" else "F-Droid does not carry $it".also(problems::add) }
        val ground = carried ?: app.publisher?.let { "published by $it" } ?: if (app.own) "the author's own" else "no ground".also(problems::add)
        return problems to "${spec.type} ${spec.url} | phone: ${onPhone.text} | television: ${onTelevision.text} | $ground"
    }

    /** What Stamp would offer [device] with the settings a new app starts with, and whether that file can be installed there. */
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
    }
}
