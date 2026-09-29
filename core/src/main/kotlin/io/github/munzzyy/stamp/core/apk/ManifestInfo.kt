package io.github.munzzyy.stamp.core.apk

/** What an APK's compiled AndroidManifest.xml declares about itself. */
data class ManifestInfo(
    val packageName: String,
    /** versionCodeMajor in the high 32 bits, versionCode in the low 32, as PackageInfo.getLongVersionCode reports it. */
    val versionCode: Long,
    /** Null when absent or when it is a resource reference that only the device can resolve. */
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val split: String?,
    val isFeatureSplit: Boolean,
    val configForSplit: String?,
    val permissions: List<String>,
    val features: List<String>,
    val debuggable: Boolean,
    val testOnly: Boolean,
    /** ABIs that have a lib/<abi>/ directory in the APK; filled by [ApkInspector], empty from [BinaryManifest.parse]. */
    val nativeLibraryAbis: List<String>,
)

object BinaryManifest {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTR_NAME = 16842755
    private const val ATTR_DEBUGGABLE = 16842767
    private const val ATTR_MIN_SDK = 16843276
    private const val ATTR_VERSION_CODE = 16843291
    private const val ATTR_VERSION_NAME = 16843292
    private const val ATTR_TARGET_SDK = 16843376
    private const val ATTR_TEST_ONLY = 16843378
    private const val ATTR_IS_FEATURE_SPLIT = 16844123
    private const val ATTR_VERSION_CODE_MAJOR = 16844150
    private const val MAX_LIST = 4096
    private const val MAX_PACKAGE = 255
    private val PERMISSION_ELEMENTS = setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")

    fun parse(bytes: ByteArray): ManifestInfo {
        val elements = BinaryXml.elements(bytes)
        val root = elements.firstOrNull() ?: throw ApkFormatException("Manifest has no elements")
        if (root.depth != 1 || root.name != "manifest") throw ApkFormatException("Root element is ${root.name}, not manifest")
        val children = elements.filter { it.depth == 2 }

        val packageName = plain(root, "package")?.string() ?: throw ApkFormatException("Manifest has no package name")
        if (!isValidName(packageName, minSegments = 2)) throw ApkFormatException("Invalid package name")
        val split = plain(root, "split")?.string()?.takeIf { it.isNotEmpty() }
        if (split != null && !isValidName(split, minSegments = 1)) throw ApkFormatException("Invalid split name")
        val configForSplit = plain(root, "configForSplit")?.string()?.takeIf { it.isNotEmpty() }

        val minor = android(root, ATTR_VERSION_CODE, "versionCode")?.int() ?: 0
        val major = android(root, ATTR_VERSION_CODE_MAJOR, "versionCodeMajor")?.int() ?: 0
        val usesSdk = children.firstOrNull { it.name == "uses-sdk" }
        val application = children.firstOrNull { it.name == "application" }

        return ManifestInfo(
            packageName = packageName,
            versionCode = (major.toLong() shl 32) or (minor.toLong() and 0xffffffffL),
            versionName = android(root, ATTR_VERSION_NAME, "versionName")?.string(),
            minSdk = usesSdk?.let { android(it, ATTR_MIN_SDK, "minSdkVersion") }?.int(),
            targetSdk = usesSdk?.let { android(it, ATTR_TARGET_SDK, "targetSdkVersion") }?.int(),
            split = split,
            isFeatureSplit = android(root, ATTR_IS_FEATURE_SPLIT, "isFeatureSplit")?.boolean() ?: false,
            configForSplit = configForSplit,
            permissions = names(children.filter { it.name in PERMISSION_ELEMENTS }),
            features = names(children.filter { it.name == "uses-feature" }),
            debuggable = application?.let { android(it, ATTR_DEBUGGABLE, "debuggable") }?.boolean() ?: false,
            testOnly = application?.let { android(it, ATTR_TEST_ONLY, "testOnly") }?.boolean() ?: false,
            nativeLibraryAbis = emptyList(),
        )
    }

    /** Android's own rule: dot-separated segments of [A-Za-z0-9_], each starting with a letter. */
    fun isValidName(name: String, minSegments: Int = 2): Boolean {
        if (name.isEmpty() || name.length > MAX_PACKAGE) return false
        val segments = name.split('.')
        return segments.size >= minSegments && segments.all { segment ->
            segment.isNotEmpty() && segment[0].isAsciiLetter() && segment.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun names(elements: List<XmlElement>): List<String> {
        val out = LinkedHashSet<String>()
        for (element in elements) {
            val name = android(element, ATTR_NAME, "name")?.string() ?: continue
            if (out.size >= MAX_LIST) throw ApkFormatException("More than $MAX_LIST entries in one manifest list")
            out.add(name)
        }
        return out.toList()
    }

    private fun android(element: XmlElement, id: Int, name: String): XmlAttribute? {
        val byId = element.attributes.filter { it.resourceId == id }
        if (byId.size > 1) throw ApkFormatException("Attribute $name appears twice on ${element.name}")
        byId.firstOrNull()?.let { return it }
        return element.attributes.firstOrNull { it.resourceId == 0 && it.namespace == ANDROID_NS && it.name == name }
    }

    private fun plain(element: XmlElement, name: String): XmlAttribute? {
        val matches = element.attributes.filter { it.namespace == null && it.name == name }
        if (matches.size > 1) throw ApkFormatException("Attribute $name appears twice on ${element.name}")
        return matches.firstOrNull()
    }
}
