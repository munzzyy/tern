package io.github.munzzyy.jackdaw.data

import io.github.munzzyy.jackdaw.core.apk.ApkInfo
import io.github.munzzyy.jackdaw.core.engine.Inspection

/**
 * What one file said about itself. [verified] is true only when the signers came from Android's own
 * parser over the downloaded file; otherwise they are what our reader found in the file's header.
 */
data class FileFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: List<String>,
    val lineage: List<String>,
    val permissions: List<String>,
    val minSdk: Int?,
    val targetSdk: Int?,
    val testOnly: Boolean,
    val verified: Boolean,
    val checksumMatchedFrom: String? = null,
) {
    val inspection: Inspection get() = Inspection(packageName, versionCode, versionName, signers, lineage)

    companion object {
        fun of(info: ApkInfo, sdk: Int): FileFacts {
            val inspection = Inspection.of(info, sdk)
            return FileFacts(
                packageName = inspection.packageName,
                versionCode = inspection.versionCode,
                versionName = inspection.versionName,
                signers = inspection.signers,
                lineage = inspection.lineage,
                permissions = info.manifest.permissions,
                minSdk = info.manifest.minSdk,
                targetSdk = info.manifest.targetSdk,
                testOnly = info.manifest.testOnly,
                verified = false,
            )
        }
    }
}
