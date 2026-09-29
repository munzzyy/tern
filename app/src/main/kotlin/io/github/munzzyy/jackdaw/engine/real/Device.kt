package io.github.munzzyy.jackdaw.engine.real

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import io.github.munzzyy.jackdaw.core.engine.InstalledApp
import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import io.github.munzzyy.jackdaw.install.PackageManagerArchiveReader

/** An installed app as PackageManager reports it, with what silent updates depend on. */
data class DeviceApp(
    val app: InstalledApp,
    val lineage: List<String>,
    val targetSdk: Int,
    val installer: String?,
    val updateOwner: String?,
    val permissions: Set<String>,
    /** The name the app gives itself, as the launcher shows it. */
    val label: String? = null,
)

class Device(context: Context) {
    private val c = context.applicationContext
    private val pm: PackageManager = c.packageManager
    val sdk: Int = Build.VERSION.SDK_INT
    val ownPackage: String = c.packageName

    val profile: DeviceProfile by lazy {
        val config = c.resources.configuration
        val locales = config.locales
        DeviceProfile(
            abis = Build.SUPPORTED_ABIS.toList(),
            sdk = sdk,
            densityDpi = c.resources.displayMetrics.densityDpi,
            languages = (0 until locales.size()).map { locales[it].language },
            television = config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION,
            watch = pm.hasSystemFeature(PackageManager.FEATURE_WATCH),
            automotive = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
        )
    }

    fun read(packageName: String?): DeviceApp? {
        if (packageName.isNullOrEmpty()) return null
        val info: PackageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val (signers, lineage) = PackageManagerArchiveReader.certificates(info.signingInfo)
        val (installer, owner) = installSource(packageName)
        return DeviceApp(
            app = InstalledApp(packageName, info.versionName, info.longVersionCode, signers),
            lineage = lineage,
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            installer = installer,
            updateOwner = owner,
            permissions = info.requestedPermissions.orEmpty().toSet(),
            label = info.applicationInfo?.let { pm.getApplicationLabel(it).toString().trim().take(200) }?.takeIf { it.isNotEmpty() && it != packageName },
        )
    }

    private fun installSource(packageName: String): Pair<String?, String?> = try {
        val source = pm.getInstallSourceInfo(packageName)
        val owner = if (Build.VERSION.SDK_INT >= 34) source.updateOwnerPackageName else null
        source.installingPackageName to owner
    } catch (e: PackageManager.NameNotFoundException) {
        Log.w(TAG, "No install source for $packageName: ${e.message}")
        null to null
    }

    /**
     * Android's hint for whether an update can install without a prompt. The system decides; this
     * only predicts, from the rules documented for SessionParams.setRequireUserAction.
     */
    fun silentUpdateLikely(installed: DeviceApp?, offeredTargetSdk: Int?): Boolean? {
        if (installed == null) return false
        val floor = silentTargetFloor(sdk) ?: return false
        val ours = installed.installer == ownPackage || installed.updateOwner == ownPackage
        if (installed.updateOwner != null && installed.updateOwner != ownPackage) return false
        return ours && (offeredTargetSdk ?: installed.targetSdk) >= floor
    }

    companion object {
        private const val TAG = "JackdawDevice"

        /** Lowest targetSdk an app may have for a silent update, per Android release. Null below Android 12. */
        fun silentTargetFloor(sdk: Int): Int? = when {
            sdk < 31 -> null
            sdk <= 32 -> 29
            sdk == 33 -> 30
            sdk == 34 -> 31
            sdk == 35 -> 33
            sdk == 36 -> 34
            sdk == 37 -> 35
            else -> sdk - 2
        }
    }
}
