package io.github.munzzyy.tern.enginetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.data.AppState
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.engine.AppStatus
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.DeviceApp
import io.github.munzzyy.tern.engine.real.Evaluator
import io.github.munzzyy.tern.engine.real.Texts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-Droid lists one build of a version for each processor, and the row offers the one this device runs. */
@RunWith(AndroidJUnit4::class)
class BuildPerProcessorTest {
    private val pkg = "org.videolan.vlc"
    private val armTv = DeviceProfile(abis = listOf("armeabi-v7a"), sdk = 30, densityDpi = 320, television = true)
    private val abis = mapOf(13060104L to "arm64-v8a", 13060103L to "x86_64", 13060101L to "armeabi-v7a")

    private fun build(code: Long) = Release(
        id = "$code", version = "3.6.1", versionCode = code,
        assets = listOf(Asset("${pkg}_$code.apk", "https://f-droid.org/repo/${pkg}_$code.apk")),
    )

    private val config = AppConfig(
        "vlc", SourceSpec(SourceTypes.FDROID, "https://f-droid.org/packages/$pkg", mapOf(SourceOptions.PACKAGE to pkg)), "VLC",
        packageName = pkg,
    )
    private val state = AppState(releases = listOf(build(13060104), build(13060103), build(13060101)), lastCheckedMs = 0)

    private fun inspect(asset: Asset, releaseId: String): FileFacts {
        val code = releaseId.toLong()
        return FileFacts(pkg, code, "3.6.1", listOf("aa"), emptyList(), emptyList(), 21, 34, false, true, nativeAbis = listOf(abis.getValue(code)))
    }

    private fun installed(code: Long) = DeviceApp(InstalledApp(pkg, "3.6.0", code, listOf("aa")), emptyList(), 34, null, null, emptySet())

    @Test
    fun anOlderBuildForThisProcessorIsOfferedTheNewVersionForThisProcessor() {
        val evaluator = Evaluator(Texts(targetContext), armTv) { 0L }
        val eval = evaluator.evaluate(config, state, installed(13060001), ::inspect)
        assertEquals(AppStatus.UPDATE_AVAILABLE, eval.status)
        assertEquals(13060101L, eval.latest?.versionCode)
        assertEquals("${pkg}_13060101.apk", eval.file?.asset?.name)
    }

    @Test
    fun theInstalledBuildForThisProcessorIsUpToDate() {
        val evaluator = Evaluator(Texts(targetContext), armTv) { 0L }
        assertEquals(AppStatus.UP_TO_DATE, evaluator.evaluate(config, state, installed(13060101), ::inspect).status)
    }

    @Test
    fun whenNoBuildRunsHereTheRowSaysSoAsBefore() {
        val evaluator = Evaluator(Texts(targetContext), armTv) { 0L }
        val elsewhere = state.copy(releases = listOf(build(13060104), build(13060103)))
        val eval = evaluator.evaluate(config, elsewhere, installed(13060001), ::inspect)
        assertEquals(AppStatus.ERROR, eval.status)
        assertEquals(ProblemKind.NO_FILE_FOR_DEVICE, eval.problem?.kind)
        assertEquals(13060104L, eval.latest?.versionCode)
    }
}
