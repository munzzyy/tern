package io.github.munzzyy.stamp.install

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.github.munzzyy.stamp.core.verify.Fingerprints
import java.io.File

/** What Android's own package parser says about a file. [signers] are the current signers only. */
data class AndroidReading(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: List<String>,
    val lineage: List<String>,
    val minSdk: Int?,
    val targetSdk: Int?,
    val testOnly: Boolean,
)

fun interface ArchiveReader {
    /** Null when Android refuses to read the file. */
    fun read(file: File): AndroidReading?
}

class PackageManagerArchiveReader(private val pm: PackageManager) : ArchiveReader {
    override fun read(file: File): AndroidReading? {
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        return reading(info)
    }

    companion object {
        fun reading(info: PackageInfo): AndroidReading {
            val app: ApplicationInfo? = info.applicationInfo
            val (signers, lineage) = certificates(info.signingInfo)
            return AndroidReading(
                packageName = info.packageName,
                versionCode = info.longVersionCode,
                versionName = info.versionName,
                signers = signers,
                lineage = lineage,
                minSdk = app?.minSdkVersion,
                targetSdk = app?.targetSdkVersion,
                testOnly = app != null && app.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            )
        }

        /** Current signers, then the certificates the current one descends from. */
        fun certificates(signing: SigningInfo?): Pair<List<String>, List<String>> {
            if (signing == null) return emptyList<String>() to emptyList()
            val current = signing.apkContentsSigners.orEmpty().map(::sha256)
            val history = if (signing.hasMultipleSigners()) emptyList() else signing.signingCertificateHistory.orEmpty().map(::sha256)
            return current.distinct() to history.distinct()
        }

        private fun sha256(signature: Signature): String = Fingerprints.sha256(signature.toByteArray())
    }
}
