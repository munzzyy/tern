package io.github.munzzyy.tern.core.apk

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.json.JsonObject

enum class BundleKind { XAPK, APKS, APKM, ZIP }

/** One APK inside a bundle. [manifest] is null when the entry is compressed and must be extracted before it can be read. */
data class BundleApk(val entryName: String, val size: Long, val stored: Boolean, val manifest: ManifestInfo?)

/**
 * A zip that carries a base APK and its splits. [declaredPackage] and [declaredVersionCode] come from
 * the bundle's own metadata file and are only a claim; the inner manifests win, and a disagreement is
 * an error.
 */
data class Bundle(
    val kind: BundleKind,
    val apks: List<BundleApk>,
    val expansionFiles: List<String>,
    val declaredPackage: String?,
    val declaredVersionCode: Long?,
)

object BundleIndex {
    private const val MAX_APKS = 512
    private const val MAX_METADATA = 1024 * 1024
    private const val OBB_PREFIX = "Android/obb/"

    fun read(source: RandomAccessSource): Bundle {
        val index = ZipIndex.open(source)
        val kind = when {
            index.find("manifest.json") != null -> BundleKind.XAPK
            index.find("info.json") != null -> BundleKind.APKM
            index.find("toc.pb") != null || index.entries.any { it.name.startsWith("meta.sai_v") } -> BundleKind.APKS
            else -> BundleKind.ZIP
        }
        val apkEntries = index.entries.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        if (apkEntries.isEmpty()) throw ApkFormatException("Bundle holds no APK")
        if (apkEntries.size > MAX_APKS) throw ApkFormatException("Bundle holds ${apkEntries.size} APKs")
        val apks = apkEntries.map { entry -> BundleApk(entry.name, entry.uncompressedSize, entry.isStored, inspectInPlace(index, entry)) }

        val metadata = when (kind) {
            BundleKind.XAPK -> metadata(index, "manifest.json")
            BundleKind.APKM -> metadata(index, "info.json")
            else -> null
        }
        val declaredPackage = metadata?.string(if (kind == BundleKind.XAPK) "package_name" else "pname")
        val declaredVersionCode = metadata?.long(if (kind == BundleKind.XAPK) "version_code" else "versioncode")
        val expansions = LinkedHashSet<String>()
        if (kind == BundleKind.XAPK && metadata != null) checkXapkLists(index, metadata, expansions)
        index.entries.filter { it.name.startsWith(OBB_PREFIX) && !it.isDirectory }.forEach { expansions.add(it.name) }

        checkConsistent(apks, declaredPackage, declaredVersionCode)
        return Bundle(kind, apks, expansions.toList(), declaredPackage, declaredVersionCode)
    }

    private fun inspectInPlace(index: ZipIndex, entry: ZipEntry): ManifestInfo? {
        if (!entry.isStored) return null
        if (entry.flags and 1 != 0) throw ApkFormatException("${entry.name} is encrypted")
        val window = WindowSource(index.source, index.dataOffset(entry), entry.compressedSize)
        return try {
            ApkInspector.manifest(window)
        } catch (e: ApkFormatException) {
            throw ApkFormatException("${entry.name}: ${e.message}", e)
        }
    }

    private fun metadata(index: ZipIndex, name: String): JsonObject {
        val entry = index.find(name) ?: throw ApkFormatException("No $name")
        val text = String(index.read(entry, MAX_METADATA), Charsets.UTF_8)
        return try {
            Json.parseObject(text)
        } catch (e: JsonException) {
            throw ApkFormatException("$name is not valid JSON", e)
        }
    }

    private fun checkXapkLists(index: ZipIndex, metadata: JsonObject, expansions: MutableSet<String>) {
        for (split in metadata.array("split_apks")?.objects().orEmpty()) {
            val file = split.string("file") ?: continue
            if (index.find(file) == null) throw ApkFormatException("manifest.json lists $file, which is not in the bundle")
        }
        for (expansion in metadata.array("expansions")?.objects().orEmpty()) {
            val file = expansion.string("file") ?: continue
            if (index.find(file) == null) throw ApkFormatException("manifest.json lists expansion $file, which is not in the bundle")
            expansions.add(file)
        }
    }

    private fun checkConsistent(apks: List<BundleApk>, declaredPackage: String?, declaredVersionCode: Long?) {
        val inspected = apks.mapNotNull { apk -> apk.manifest?.let { apk to it } }
        val bases = inspected.filter { it.second.split == null }
        if (bases.size > 1) throw ApkFormatException("Bundle holds ${bases.size} base APKs")
        val reference = bases.firstOrNull()?.second ?: inspected.firstOrNull()?.second ?: return
        for ((apk, manifest) in inspected) {
            if (manifest.packageName != reference.packageName || manifest.versionCode != reference.versionCode) {
                throw ApkFormatException("${apk.entryName} is ${manifest.packageName} ${manifest.versionCode}, the bundle is ${reference.packageName} ${reference.versionCode}")
            }
        }
        if (declaredPackage != null && declaredPackage != reference.packageName) {
            throw ApkFormatException("Bundle metadata says $declaredPackage, the APKs say ${reference.packageName}")
        }
        if (declaredVersionCode != null && declaredVersionCode != reference.versionCode) {
            throw ApkFormatException("Bundle metadata says version $declaredVersionCode, the APKs say ${reference.versionCode}")
        }
    }
}
