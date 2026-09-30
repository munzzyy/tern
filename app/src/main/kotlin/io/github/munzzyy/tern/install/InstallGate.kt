package io.github.munzzyy.tern.install

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
import io.github.munzzyy.tern.core.apk.TarReader
import io.github.munzzyy.tern.core.apk.WindowSource
import io.github.munzzyy.tern.core.apk.ZipEntry
import io.github.munzzyy.tern.core.apk.ZipIndex
import io.github.munzzyy.tern.core.compress.Packing
import io.github.munzzyy.tern.core.engine.Block
import io.github.munzzyy.tern.core.engine.InstalledApp
import io.github.munzzyy.tern.core.engine.Inspection
import io.github.munzzyy.tern.core.engine.UpdateDecision
import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.model.AssetKind
import io.github.munzzyy.tern.core.model.AssetPolicy
import io.github.munzzyy.tern.core.model.DeviceProfile
import io.github.munzzyy.tern.core.select.AssetPicker
import io.github.munzzyy.tern.core.select.AssetPolicyException
import io.github.munzzyy.tern.data.FileFacts
import io.github.munzzyy.tern.engine.ProblemKind
import io.github.munzzyy.tern.engine.real.Texts
import io.github.munzzyy.tern.log.TernLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipOutputStream

/**
 * What passed the gate: the files to hand to the installer, and what Android itself read from the
 * base. Every file among them had its signature verified, by Android or by Tern's own verifier.
 * [obbs] are the OBB files of the archive, which nothing signs: they only go to the app's OBB
 * folder, and only after the app was installed.
 */
data class GatePass(val apks: List<File>, val facts: FileFacts, val split: Boolean, val obbs: List<ObbFile> = emptyList())

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
    /** An older version may replace a newer one: the person picked it, allowed it, and something lets Android do it. */
    val allowDowngrade: Boolean = false,
    /** The versionCode the source named for this file, if it named one. */
    val claimedVersionCode: Long? = null,
    /** Which files inside an archive or a bundle may be installed, by their names there. */
    val installsInside: (String) -> Boolean = { true },
    /**
     * The splits fetched beside [file] when the source names each file of the app by itself. They
     * are chosen and checked with it as the APKs of one bundle. [fileSha256] is the base's own.
     */
    val parts: List<FetchedPart> = emptyList(),
    /** Whether the OBB files of an archive are unpacked, for an installer that can put them in place; otherwise only their names are read. */
    val unpacksObb: Boolean = false,
)

internal enum class Downgrade { NONE, ALLOWED, REFUSED, NOT_AS_NAMED }

/**
 * Whether a file of [file] may go over [installed]. An older one goes only where [allowed], and
 * never when the source named a higher versionCode than the file has: the source said it was
 * newer, so going back was not what anyone chose.
 */
internal fun downgrade(file: Long, installed: Long?, allowed: Boolean, claimed: Long?): Downgrade = when {
    installed == null || file >= installed -> Downgrade.NONE
    claimed != null && file < claimed -> Downgrade.NOT_AS_NAMED
    allowed -> Downgrade.ALLOWED
    else -> Downgrade.REFUSED
}

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

    private class Chosen(val apks: List<File>, val split: Boolean, val obbs: List<ObbFile> = emptyList())

    private fun choose(request: GateRequest): Chosen {
        val file = if (request.parts.isNotEmpty()) {
            joined(request)
        } else {
            when (val packing = packingOf(request.file)) {
                Packing.ZSTD -> throw StepFailure(ProblemKind.UNSUPPORTED, texts.archiveCompressionUnsupported())
                Packing.NONE -> if (isTar(request.file)) fromTar(request, packing) else request.file
                else -> fromTar(request, packing)
            }
        }
        FileSource(file).use { source ->
            val index = try {
                ZipIndex.open(source)
            } catch (e: ApkFormatException) {
                throw StepFailure(ProblemKind.PARSE, texts.notAnApk(e.message))
            }
            if (index.find("AndroidManifest.xml") != null) return Chosen(listOf(file), split = false)
            val bundleDeclared = request.asset.kind == AssetKind.BUNDLE
            if (bundleDeclared) BundleIndex.read(source)
            return fromArchive(index, request)
        }
    }

    private fun fromArchive(index: ZipIndex, request: GateRequest): Chosen {
        val apks = index.entries.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.archiveHasNoApk())
        val entries = try {
            apks.filter { request.installsInside(it.name) }
        } catch (e: AssetPolicyException) {
            throw StepFailure(ProblemKind.PARSE, texts.patternProblem(e.message ?: ""))
        }
        if (entries.isEmpty()) throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.innerFilterMatchesNothing())
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
        // OBB files keep their names in the app's folder, so a name that could leave it refuses the archive.
        val obbEntries = index.entries.filter { !it.isDirectory && ObbNames.isObb(it.name) }
        if (obbEntries.size > MAX_APKS) throw StepFailure(ProblemKind.PARSE, texts.unreadableFile("${obbEntries.size} OBB files"))
        val obbs = obbEntries.mapIndexed { i, entry ->
            val name = ObbNames.of(entry.name) ?: throw StepFailure(ProblemKind.PARSE, texts.obbNameRefused())
            val file = if (request.unpacksObb) {
                budget -= entry.uncompressedSize
                if (budget < 0) throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
                File(request.staging, "obb-$i.obb").also { ZipExtract.extract(index, entry, it, Downloader.MAX_BYTES) }
            } else {
                null
            }
            ObbFile(name, file)
        }
        if (obbs.distinctBy { it.name }.size != obbs.size) throw StepFailure(ProblemKind.PARSE, texts.obbNameRefused())
        return Chosen(files, split, obbs)
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
            if (own !is SignatureVerdict.Holds) TernLog.w(TAG, "Own check of ${ours.manifest.split ?: "the base"}: ${own.toString().take(MAX_LOGGED)}")
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
        when (downgrade(android.versionCode, installed?.versionCode, request.allowDowngrade, request.claimedVersionCode)) {
            Downgrade.NONE, Downgrade.ALLOWED -> Unit
            Downgrade.REFUSED -> throw StepFailure(ProblemKind.DOWNGRADE, texts.downgrade(installed?.versionName, android.versionName))
            Downgrade.NOT_AS_NAMED -> throw StepFailure(ProblemKind.DOWNGRADE, texts.downgradeNotAsNamed(android.versionCode, request.claimedVersionCode ?: 0))
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
        return GatePass(chosen.apks, facts, chosen.split, chosen.obbs)
    }

    /**
     * A base and the splits fetched one by one beside it, put into one zip in the staging folder,
     * so that they are chosen and checked exactly as the APKs of a bundle are.
     */
    private fun joined(request: GateRequest): File {
        val target = File(request.staging, "parts.zip")
        val all = listOf(FetchedPart(request.asset.name, request.file)) + request.parts
        try {
            PartsZip.join(all, target, File(request.staging, "part.apk"), Downloader.MAX_BYTES, MAX_APKS)
        } catch (_: PartsZip.TooLarge) {
            throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
        } catch (e: ApkFormatException) {
            throw StepFailure(ProblemKind.PARSE, texts.notAnApk(e.message))
        }
        return target
    }

    /** How the file is compressed, as its first bytes say whatever its name. */
    private fun packingOf(file: File): Packing = FileInputStream(file).use { input ->
        val head = ByteArray(Packing.HEAD)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        Packing.of(head.copyOf(read))
    }

    /** A ustar or GNU tar archive names itself at the same place of its first header. */
    private fun isTar(file: File): Boolean = FileInputStream(file).use { input ->
        val head = ByteArray(TAR_MAGIC_AT + 5)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        read == head.size && String(head, TAR_MAGIC_AT, 5, Charsets.US_ASCII) == "ustar"
    }

    /**
     * The APKs and OBB files of a tar archive, plain or compressed with gzip, bzip2 or xz, put into a
     * zip in the staging folder, so that they are chosen and checked exactly as those of a zip are.
     * Only the files the app's filter lets through are taken, and never more than a download may
     * hold: the archive may not unpack to more than that either.
     */
    private fun fromTar(request: GateRequest, packing: Packing): File {
        val target = File(request.staging, "archive.zip")
        var total = 0L
        var count = 0
        Packing.open(FileInputStream(request.file).buffered(), packing, Downloader.MAX_BYTES).use { input ->
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                TarReader.read(input) { entry, body ->
                    val obb = ObbNames.isObb(entry.name)
                    if (!entry.name.endsWith(".apk", ignoreCase = true) && !obb) return@read
                    total += entry.size
                    if (total > Downloader.MAX_BYTES) throw StepFailure(ProblemKind.STORAGE, texts.fileTooLarge())
                    if (!obb && ++count > MAX_APKS) throw StepFailure(ProblemKind.PARSE, texts.unreadableFile("$count APKs"))
                    // The name inside the zip keeps the one the archive gave, so the filter reads the same name either way.
                    zip.putNextEntry(java.util.zip.ZipEntry(entry.name.trimStart('/').take(MAX_NAME)))
                    body.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        if (count == 0) throw StepFailure(ProblemKind.NO_FILE_FOR_DEVICE, texts.archiveHasNoApk())
        return target
    }

    companion object {
        private const val TAG = "InstallGate"
        private const val MAX_APKS = 512
        private const val TAR_MAGIC_AT = 257
        private const val MAX_NAME = 1024
        private const val MAX_LOGGED = 300
    }
}
