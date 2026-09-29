package io.github.munzzyy.jackdaw.core.engine

import io.github.munzzyy.jackdaw.core.model.Release
import io.github.munzzyy.jackdaw.core.version.Version

data class InstalledApp(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    /** SHA-256 of the current signing certificates, lowercase hex. */
    val signers: List<String>,
)

/** What Jackdaw itself last installed for an app. */
data class InstallRecord(val releaseId: String, val version: String, val versionCode: Long)

/** What a file says about itself, read from the server or from disk. */
data class Inspection(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: List<String>,
    /** Earlier certificates the current signer proves descent from. */
    val lineage: List<String> = emptyList(),
)

enum class Block { PACKAGE_MISMATCH, SIGNER_MISMATCH }

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
     * Decides from the most reliable fact available: the file's own versionCode when an inspection
     * or a signed index provides one, then Jackdaw's record of what it installed, then version text.
     */
    fun decide(
        release: Release,
        installed: InstalledApp?,
        record: InstallRecord?,
        inspection: Inspection?,
        expectedPackage: String?,
        pinnedSigners: List<String>,
        indexSigners: List<String> = emptyList(),
    ): Decision {
        if (inspection != null) {
            blockFor(inspection, installed, expectedPackage, pinnedSigners)?.let { (block, detail) ->
                return Decision.Blocked(release, block, detail)
            }
            return byCode(release, installed, inspection.versionCode)
        }

        if (indexSigners.isNotEmpty()) {
            val allowed = allowedSigners(installed, pinnedSigners)
            if (allowed.isNotEmpty() && indexSigners.none { it in allowed }) {
                return Decision.Blocked(release, Block.SIGNER_MISMATCH, "The repository lists a different signing certificate")
            }
        }
        release.versionCode?.let { return byCode(release, installed, it) }

        if (installed == null) return Decision.NotInstalled(release)
        if (record != null && record.versionCode == installed.versionCode && record.releaseId == release.id) {
            return Decision.UpToDate(release)
        }
        val installedName = installed.versionName
        if (installedName != null && Version.same(release.version, installedName)) return Decision.UpToDate(release)
        return Decision.NeedsInspection(release)
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
        val allowed = allowedSigners(installed, pinnedSigners)
        if (allowed.isEmpty()) return null
        if (inspection.signers.isEmpty()) return Block.SIGNER_MISMATCH to "The file carries no signing certificate"
        val known = inspection.signers.any { it in allowed } || inspection.lineage.any { it in allowed }
        return if (known) null else Block.SIGNER_MISMATCH to "The file is signed with a different certificate"
    }

    /** A pin is a promise made by the user or an import; it outranks whatever is installed. */
    private fun allowedSigners(installed: InstalledApp?, pinned: List<String>): Set<String> =
        if (pinned.isNotEmpty()) pinned.mapTo(HashSet()) { it.lowercase() } else installed?.signers.orEmpty().mapTo(HashSet()) { it.lowercase() }

    private fun byCode(release: Release, installed: InstalledApp?, offered: Long): Decision = when {
        installed == null -> Decision.NotInstalled(release)
        offered > installed.versionCode -> Decision.UpdateAvailable(release, certain = true)
        offered == installed.versionCode -> Decision.UpToDate(release)
        else -> Decision.UpToDate(release, installedIsNewer = true)
    }
}
