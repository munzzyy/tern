package io.github.munzzyy.tern.install

import android.util.Log
import io.github.munzzyy.tern.core.apk.ApkFormatException
import io.github.munzzyy.tern.core.apk.ApkInfo
import io.github.munzzyy.tern.core.apk.ApkInspector
import io.github.munzzyy.tern.core.apk.BundleApk
import io.github.munzzyy.tern.core.apk.BundleIndex
import io.github.munzzyy.tern.core.apk.FileSource
import io.github.munzzyy.tern.core.apk.IncompatibleDeviceException
import io.github.munzzyy.tern.core.apk.ManifestInfo
import io.github.munzzyy.tern.core.apk.SignatureVerdict
import io.github.munzzyy.tern.core.apk.SplitSelector
import io.github.munzzyy.tern.core.apk.WindowSource
import io.github.munzzyy.tern.core.apk.ZipEntry
import io.github.munzzyy.tern.core.apk.ZipIndex
import io.github.munzzyy.tern.core.engine.Block
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.engine.Inspection
import io.github.munzzyy.tern.core.engine.UpdateDecision
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.Texts
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * What passed the gate: the files to hand to the installer, and what Android itself read from the
 * base. Every file among them had its signature verified, by Android or by Tern's own verifier.
 */
data class GatePass(val apks: List<File>, val facts: FileFacts, val split: Boolean)

data class GateRequest(
    val file: File,
    val asset: Asset,
    val fileSha256: String,
    /** The publisher's checksum and where it came from, when there is one. */
    val expectedSha256: String?,
    val expectedPackage: String?,
    val pinnedSigners: List<String>,
    /** What PackageManager says is installed under a package name. */
    val installedOf: (String) -> InstalledApp?,
    val device: DeviceProfile,
    val staging: File,
    /** True when [pinnedSigners] are certificates Tern itself carries for this app, which changes what a refusal says. */
    val builtInPin: Boolean = false,
    /** An older version may replace a newer one: the person allowed it, and something lets Android do it. */
    val allowDowngrade: Boolean = false,
)

fun interface Gate {
    fun check(request: GateRequest): GatePass
}

/** Nothing reaches the installer without passing every step here, in order. */
class InstallGate(
    private val reader: ArchiveReader,
    private val texts: Texts,
    private val signatures: SignatureReader = SignatureReader.OWN,
) : Gate {
    override fun check(request: GateRequest): GatePass {
        checksum(request)
        request.staging.deleteRecursively()
        if (!request.staging.mkdirs()) throw StepFailure(ProblemKind.STORAGE, texts.cannotWrite())
        val chosen = try {
            choose(request)
        } catch (e: StepFailure) {
            request.staging.deleteRecursively()
            throw e
        } catch (e: IOException) {
            request.staging.deleteRecursively()
            throw StepFailure(ProblemKind.PARSE, texts.unreadableFile(e.message))
        }
        return try {
            judge(request, chosen)
        } catch (e: StepFailure) {
            request.staging.deleteRecursively()
            throw e
        }
    }

    private fun checksum(request: GateRequest) {
        val expected = request.expectedSha256 ?: return
        if (!expected.equals(request.fileSha256, ignoreCase = true)) {
            request.file.delete()
            throw StepFailure(ProblemKind.CHECKSUM_MISMATCH, texts.checksumMismatch())
        }
    }

    private class Chosen(val apks: List<File>, val split: Boolean)

    private fun choose(request: GateRequest): Chosen {
        if (isGzip(request.file)) throw StepFailure(ProblemKind.UNSUPPORTED, texts.tarUnsupported())
        FileSource(request.file).use { source ->
            val index = try {
                ZipIndex.open(source)
            } catch (e: ApkFormatException) {
                throw StepFailure(ProblemKind.PARSE, texts.notAnApk(e.message))
            }
            if (index.find("AndroidManifest.xml") != null) return Chosen(listOf(request.file), split = false)
            val bundleDeclared = request.asset.kind == AssetKind.BUNDLE
            if (bundleDeclared) BundleIndex.read(source)
            return fromArchive(index, request)
        }
    }

    private fun fromArchive(index: ZipIndex, request: GateRequest): Chosen {
        val entries = index.entries.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        if (entries.isEmpty()) throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.archiveHasNoApk())
        if (entries.size > MAX_APKS) throw StepFailure(ProblemKind.PARSE, texts.unreadableFile("${entries.size} APKs"))
        var budget = Downloader.MAX_BYTES
        val extracted = HashMap<ZipEntry, File>()
        fun fileFor(entry: ZipEntry): File = extracted.getOrPut(entry) {
            budget -= entry.uncompressedSize
            if (budget < 0) throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
            val target = File(request.staging, "apk-${extracted.size}.apk")
            ZipExtract.extract(index, entry, target, Downloader.MAX_BYTES)
            target
        }
        val manifests = entries.associateWith { entry ->
            if (entry.isStored) {
                ApkInspector.manifest(WindowSource(index.source, index.dataOffset(entry), entry.compressedSize))
            } else {
                FileSource(fileFor(entry)).use { ApkInspector.manifest(it) }
            }
        }
        val bases = entries.filter { manifests.getValue(it).split == null }
        if (bases.isEmpty()) throw StepFailure(ProblemKind.PARSE, texts.archiveHasNoBase())
        val chosenEntries: List<ZipEntry>
        val split: Boolean
        if (bases.size == 1) {
            val apks = entries.map { BundleApk(it.name, it.uncompressedSize, it.isStored, manifests.getValue(it)) }
            val selection = try {
                SplitSelector.select(apks, request.device)
            } catch (e: IncompatibleDeviceException) {
                throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(e.message))
            }
            val byName = entries.associateBy { it.name }
            chosenEntries = selection.chosen.map { byName.getValue(it.entryName) }
            split = chosenEntries.size > 1
        } else {
            chosenEntries = listOf(bestOf(bases, manifests, request.device))
            split = false
        }
        val files = chosenEntries.map(::fileFor)
        extracted.filterKeys { it !in chosenEntries }.values.forEach { it.delete() }
        return Chosen(files, split)
    }

    private fun bestOf(bases: List<ZipEntry>, manifests: Map<ZipEntry, ManifestInfo>, device: DeviceProfile): ZipEntry {
        val candidates = bases.map { Asset(name = it.name.substringAfterLast('/'), url = "zip:${it.name}") to it }
        val ranked = AssetPicker.rank(candidates.map { it.first }, device, AssetPolicy())
        val best = ranked.firstOrNull() ?: throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(null))
        val entry = candidates.first { it.first == best.asset }.second
        val abis = manifests.getValue(entry).nativeLibraryAbis
        if (abis.isNotEmpty() && abis.none { it in device.abis }) throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.noFileForDevice(abis.joinToString()))
        return entry
    }

    private class Part(val android: AndroidReading?, val ours: ApkInfo, val own: SignatureVerdict)

    private fun judge(request: GateRequest, chosen: Chosen): GatePass {
        var base: Pair<AndroidReading, ApkInfo>? = null
        val parts = ArrayList<Part>()
        for (apk in chosen.apks) {
            val ours = try {
                FileSource(apk).use { ApkInspector.inspect(it) }
            } catch (e: IOException) {
                throw StepFailure(ProblemKind.PARSE, texts.notAnApk(e.message))
            }
            val android = reader.read(apk)
            if (android != null && (android.packageName != ours.manifest.packageName || android.versionCode != ours.manifest.versionCode)) {
                throw StepFailure(ProblemKind.PACKAGE_MISMATCH, texts.readsDifferently())
            }
            val own = signatures.read(apk, request.device.sdk)
            if (own !is SignatureVerdict.Holds) Log.w(TAG, "Own check of ${ours.manifest.split ?: "the base"}: ${own.toString().take(MAX_LOGGED)}")
            if (android != null && SignerJudge.readByAndroid(android.signers, own) != SignerFinding.ACCEPTED) {
                throw StepFailure(ProblemKind.SIGNER_MISMATCH, texts.readsDifferently())
            }
            if (ours.manifest.split != null) {
                parts += Part(android, ours, own)
                continue
            }
            if (android == null) throw StepFailure(ProblemKind.PARSE, texts.androidRefusedFile())
            if (base != null) throw StepFailure(ProblemKind.PARSE, texts.archiveHasNoBase())
            base = android to ours
        }
        val (android, ours) = base ?: throw StepFailure(ProblemKind.PARSE, texts.archiveHasNoBase())
        if (android.signers.isEmpty()) throw StepFailure(ProblemKind.SIGNER_MISMATCH, texts.unsigned())
        for (part in parts) {
            if (part.ours.manifest.packageName != android.packageName || part.ours.manifest.versionCode != android.versionCode) {
                throw StepFailure(ProblemKind.PACKAGE_MISMATCH, texts.readsDifferently())
            }
            // Android reads a split by itself on some versions and not on others; either way its signer must be the base's.
            val finding = when {
                part.android == null -> SignerJudge.notReadByAndroid(android.signers, part.own)
                part.android.signers.toSet() == android.signers.toSet() -> SignerFinding.ACCEPTED
                else -> SignerFinding.PART_OTHER_SIGNER
            }
            when (finding) {
                SignerFinding.ACCEPTED -> Unit
                SignerFinding.PART_OTHER_SIGNER -> throw StepFailure(ProblemKind.SIGNER_MISMATCH, texts.splitSignerMismatch())
                SignerFinding.PART_UNPROVEN, SignerFinding.READ_DIFFERENTLY -> throw StepFailure(ProblemKind.SIGNER_MISMATCH, texts.partNotSigned())
            }
        }

        val inspection = Inspection(android.packageName, android.versionCode, android.versionName, android.signers, android.lineage)
        val installed = request.installedOf(request.expectedPackage ?: android.packageName)
        UpdateDecision.blockFor(inspection, installed, request.expectedPackage, request.pinnedSigners)?.let { (block, _) ->
            throw when (block) {
                Block.PACKAGE_MISMATCH -> StepFailure(ProblemKind.PACKAGE_MISMATCH, texts.packageMismatch(request.expectedPackage ?: installed?.packageName, android.packageName))
                Block.SIGNER_MISMATCH -> StepFailure(ProblemKind.SIGNER_MISMATCH, texts.signerMismatch())
                Block.PIN_MISMATCH -> StepFailure(ProblemKind.PIN_MISMATCH, if (request.builtInPin) texts.builtInPinMismatch() else texts.pinMismatch())
            }
        }
        if (installed != null && android.versionCode < installed.versionCode && !request.allowDowngrade) {
            throw StepFailure(ProblemKind.DOWNGRADE, texts.downgrade(installed.versionName, android.versionName))
        }
        if (android.testOnly || ours.manifest.testOnly) throw StepFailure(ProblemKind.UNSUPPORTED, texts.testOnly())
        val minSdk = android.minSdk ?: ours.manifest.minSdk
        if (minSdk != null && minSdk > request.device.sdk) throw StepFailure(ProblemKind.UNSUPPORTED, texts.needsNewerAndroid(minSdk))

        val facts = FileFacts(
            packageName = android.packageName,
            versionCode = android.versionCode,
            versionName = android.versionName,
            signers = android.signers,
            lineage = android.lineage,
            permissions = ours.manifest.permissions,
            minSdk = minSdk,
            targetSdk = android.targetSdk ?: ours.manifest.targetSdk,
            testOnly = false,
            verified = true,
        )
        return GatePass(chosen.apks, facts, chosen.split)
    }

    private fun isGzip(file: File): Boolean = FileInputStream(file).use { input ->
        val head = ByteArray(2)
        input.read(head) == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
    }

    companion object {
        private const val TAG = "InstallGate"
        private const val MAX_APKS = 512
        private const val MAX_LOGGED = 300
    }
}
