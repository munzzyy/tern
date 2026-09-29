package io.github.munzzyy.jackdaw.core.apk

import io.github.munzzyy.jackdaw.core.net.HttpClient

/**
 * What an APK says about itself: its manifest and the signing certificates it CLAIMS. The
 * signatures are not checked, so [signers] is a claim until the device's installer accepts the file.
 * [schemes] holds the signature schemes present (see [SignatureScheme]), also unverified.
 */
data class ApkInfo(val manifest: ManifestInfo, val signers: List<SignerInfo>, val schemes: Set<Int>, val fileSize: Long)

object ApkInspector {
    const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_ABIS = 32

    fun inspect(source: RandomAccessSource): ApkInfo {
        val index = ZipIndex.open(source)
        val manifest = manifest(index)
        val v2Plus = ApkSigningBlock.read(source, index.centralDirectoryOffset).orEmpty()
        val hasJar = JarSignature.signatureFiles(index).isNotEmpty()
        val signers = v2Plus.ifEmpty { if (hasJar) JarSignature.signers(index) else emptyList() }
        val schemes = buildSet {
            if (hasJar) add(SignatureScheme.V1)
            v2Plus.forEach { add(it.scheme) }
        }
        return ApkInfo(manifest, signers, schemes, source.size)
    }

    /** Only the manifest and the ABI list: what split selection needs, without touching signatures. */
    fun manifest(source: RandomAccessSource): ManifestInfo = manifest(ZipIndex.open(source))

    fun inspectRemote(http: HttpClient, url: String, authorization: String? = null): ApkInfo =
        HttpRangeSource(http, url, authorization).use { inspect(it) }

    private fun manifest(index: ZipIndex): ManifestInfo {
        val entry = index.find("AndroidManifest.xml") ?: throw ApkFormatException("No AndroidManifest.xml")
        return BinaryManifest.parse(index.read(entry, MAX_MANIFEST_BYTES)).copy(nativeLibraryAbis = abis(index))
    }

    private fun abis(index: ZipIndex): List<String> {
        val out = LinkedHashSet<String>()
        for (entry in index.entries) {
            val parts = entry.name.split('/')
            if (parts.size != 3 || parts[0] != "lib" || parts[1].isEmpty() || parts[2].isEmpty()) continue
            if (out.add(parts[1]) && out.size > MAX_ABIS) throw ApkFormatException("More than $MAX_ABIS native library directories")
        }
        return out.toList()
    }
}
