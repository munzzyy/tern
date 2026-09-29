package io.github.munzzyy.tern.core.engine

import io.github.munzzyy.tern.core.apk.ApkInfo
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.Release
import io.github.munzzyy.tern.core.version.Version

data class InstalledApp(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    /** SHA-256 of the current signing certificates, lowercase hex. */
    val signers: List<String>,
)

/** What Tern itself last installed for an app, and which file it was. */
data class InstallRecord(
    val releaseId: String,
    val version: String,
    val versionCode: Long,
    val fileSha256: String? = null,
    val fileSize: Long? = null,
) {
    /** False when the publisher replaced the file under the same release. */
    fun sameFile(asset: Asset?): Boolean {
        if (asset == null) return true
        val sha = asset.sha256
        if (fileSha256 != null && sha != null) return fileSha256.equals(sha, ignoreCase = true)
        val size = asset.size
        if (fileSize != null && size != null) return fileSize == size
        return true
    }
}

/** What a file says about itself, read from the server or from disk. */
data class Inspection(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: List<String>,
    /** Earlier certificates the current signer proves descent from. */
    val lineage: List<String> = emptyList(),
) {
    companion object {
        /** Takes the signers a device running [sdk] goes by, not every certificate the file names. */
        fun of(info: ApkInfo, sdk: Int): Inspection {
            val signers = info.signersFor(sdk)
            return Inspection(
                packageName = info.manifest.packageName,
                versionCode = info.manifest.versionCode,
                versionName = info.manifest.versionName,
                signers = signers.map { it.sha256 },
                lineage = signers.flatMap { it.lineage }.distinct(),
            )
        }
    }
}

enum class Block {
    PACKAGE_MISMATCH,

    /** Signed by someone other than the signer of the installed app. Android itself would refuse it. */
    SIGNER_MISMATCH,

    /** Signed by someone other than the certificate pinned for this app. */
    PIN_MISMATCH,
}

sealed interface Decision {
    data class NotInstalled(val release: Release) : Decision

    /** [installedIsNewer] is true when the device runs something newer than the source offers. */
    data class UpToDate(val release: Release, val installedIsNewer: Boolean = false) : Decision

    /** [certain] is false when only the version text could be compared. */
    data class UpdateAvailable(val release: Release, val certain: Boolean) : Decision

    /** The names do not settle it; the file has to be inspected. */
    data class NeedsInspection(val release: Release) : Decision

    data class Blocked(val release: Release, val block: Block, val detail: String) : Decision
}

object UpdateDecision {
    /**
     * Decides from the file's own version code, which an inspection or a signed index provides.
     * Without one, only Tern's record of having installed this very file settles it. Version
     * text never does: a rebuilt release keeps its name, and a name that differs says nothing
     * about the code. [guess] is for files that cannot be inspected.
     */
    fun decide(
        release: Release,
        installed: InstalledApp?,
        record: InstallRecord?,
        inspection: Inspection?,
        expectedPackage: String?,
        pinnedSigners: List<String>,
        indexSigners: List<String> = emptyList(),
        file: Asset? = null,
    ): Decision {
        if (inspection != null) {
            blockFor(inspection, installed, expectedPackage, pinnedSigners)?.let { (block, detail) ->
                return Decision.Blocked(release, block, detail)
            }
            return byCode(release, installed, inspection.versionCode)
        }

        if (indexSigners.isNotEmpty()) {
            signerBlock(indexSigners, emptyList(), installed, pinnedSigners)?.let { (block, detail) ->
                return Decision.Blocked(release, block, detail)
            }
        }
        release.versionCode?.let { return byCode(release, installed, it) }

        if (installed == null) return Decision.NotInstalled(release)
        val stillWhatWeInstalled = record != null && record.versionCode == installed.versionCode &&
            record.releaseId == release.id && record.sameFile(file)
        return if (stillWhatWeInstalled) Decision.UpToDate(release) else Decision.NeedsInspection(release)
    }

    /** For a file that cannot be inspected before download. Compares version text and says so. */
    fun guess(release: Release, installed: InstalledApp, record: InstallRecord?): Decision {
        val offered = Version.parse(release.version)
        val reference = when {
            record != null && record.versionCode == installed.versionCode -> Version.parse(record.version)
            installed.versionName != null -> Version.parse(installed.versionName)
            else -> null
        }
        if (reference == null || !offered.isComparable || !reference.isComparable) {
            return Decision.UpdateAvailable(release, certain = false)
        }
        val order = offered.compareTo(reference)
        return when {
            order > 0 -> Decision.UpdateAvailable(release, certain = false)
            order == 0 -> Decision.UpToDate(release)
            else -> Decision.UpToDate(release, installedIsNewer = true)
        }
    }

    fun blockFor(
        inspection: Inspection,
        installed: InstalledApp?,
        expectedPackage: String?,
        pinnedSigners: List<String>,
    ): Pair<Block, String>? {
        if (expectedPackage != null && inspection.packageName != expectedPackage) {
            return Block.PACKAGE_MISMATCH to "Expected $expectedPackage but the file is ${inspection.packageName}"
        }
        if (installed != null && inspection.packageName != installed.packageName) {
            return Block.PACKAGE_MISMATCH to "Installed app is ${installed.packageName} but the file is ${inspection.packageName}"
        }
        return signerBlock(inspection.signers, inspection.lineage, installed, pinnedSigners)
    }

    /**
     * The installed app and the pin must both agree. A pin can arrive with an import, so it may
     * add a condition but never lift the one Android enforces: the signer of what is installed.
     * A rotated key counts where it proves descent from the known one.
     */
    private fun signerBlock(signers: List<String>, lineage: List<String>, installed: InstalledApp?, pinned: List<String>): Pair<Block, String>? {
        val onPhone = installed?.signers.orEmpty()
        if (onPhone.isEmpty() && pinned.isEmpty()) return null
        if (signers.isEmpty()) return Block.SIGNER_MISMATCH to "The file carries no signing certificate"
        val presented = (signers + lineage).mapTo(HashSet()) { it.lowercase() }
        if (onPhone.isNotEmpty() && onPhone.none { it.lowercase() in presented }) {
            return Block.SIGNER_MISMATCH to "The file is signed with a different certificate than the installed app"
        }
        if (pinned.isNotEmpty() && pinned.none { it.lowercase() in presented }) {
            return Block.PIN_MISMATCH to "The file is signed with a different certificate than the one pinned for this app"
        }
        return null
    }

    private fun byCode(release: Release, installed: InstalledApp?, offered: Long): Decision = when {
        installed == null -> Decision.NotInstalled(release)
        offered > installed.versionCode -> Decision.UpdateAvailable(release, certain = true)
        offered == installed.versionCode -> Decision.UpToDate(release)
        else -> Decision.UpToDate(release, installedIsNewer = true)
    }
}
