package io.github.munzzyy.jackdaw.core.apk

import io.github.munzzyy.jackdaw.core.model.DeviceProfile

/** [skipped] pairs each APK left out with the reason, for display. */
data class SplitChoice(val chosen: List<BundleApk>, val skipped: List<Pair<BundleApk, String>>)

object SplitSelector {
    private val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val DENSITIES = linkedMapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )
    private val LANGUAGE = Regex("[a-z]{2,3}")

    private class ConfigSplit(val apk: BundleApk, val parent: String, val qualifier: String)

    fun select(apks: List<BundleApk>, device: DeviceProfile): SplitChoice {
        val manifests = apks.map { apk ->
            apk to (apk.manifest ?: throw ApkFormatException("${apk.entryName} must be extracted and inspected before selection"))
        }
        val bases = manifests.filter { it.second.split == null }
        if (bases.size != 1) throw ApkFormatException("Expected exactly one base APK, found ${bases.size}")
        val (baseApk, base) = bases.single()
        for ((apk, manifest) in manifests) {
            if (manifest.packageName != base.packageName || manifest.versionCode != base.versionCode) {
                throw ApkFormatException("${apk.entryName} is ${manifest.packageName} ${manifest.versionCode}, the base is ${base.packageName} ${base.versionCode}")
            }
        }
        val splitNames = manifests.mapNotNull { it.second.split }
        if (splitNames.size != splitNames.toSet().size) throw ApkFormatException("Two APKs declare the same split name")

        val chosen = mutableListOf(baseApk)
        val skipped = ArrayList<Pair<BundleApk, String>>()
        val configs = ArrayList<ConfigSplit>()
        val modules = mutableListOf("")
        for ((apk, manifest) in manifests) {
            val split = manifest.split ?: continue
            val config = configOf(split, manifest.configForSplit)
            if (config == null) {
                chosen.add(apk)
                modules.add(split)
            } else {
                configs.add(ConfigSplit(apk, config.first, config.second))
            }
        }
        for (module in modules) {
            val (take, leave) = chooseConfigs(configs.filter { it.parent == module }, device, module)
            chosen.addAll(take)
            skipped.addAll(leave)
        }
        configs.filter { it.parent !in modules }.forEach { skipped.add(it.apk to "its feature split ${it.parent} is not in the bundle") }
        return SplitChoice(chosen, skipped)
    }

    /** Returns (parent module, qualifier) for a config split, or null for a base or feature split. */
    private fun configOf(split: String, configForSplit: String?): Pair<String, String>? {
        val parent: String
        val qualifier: String
        when {
            split.startsWith("config.") -> {
                parent = ""
                qualifier = split.removePrefix("config.")
            }
            split.contains(".config.") -> {
                parent = split.substringBefore(".config.")
                qualifier = split.substringAfter(".config.")
            }
            else -> return null
        }
        return (configForSplit ?: parent) to qualifier
    }

    private fun chooseConfigs(splits: List<ConfigSplit>, device: DeviceProfile, module: String): Pair<List<BundleApk>, List<Pair<BundleApk, String>>> {
        val take = ArrayList<BundleApk>()
        val leave = ArrayList<Pair<BundleApk, String>>()

        val abiSplits = splits.filter { it.qualifier in ABIS }
        if (abiSplits.isNotEmpty()) {
            val deviceAbis = device.abis.map { it.replace('-', '_') }
            val pick = deviceAbis.firstNotNullOfOrNull { abi -> abiSplits.firstOrNull { it.qualifier == abi } }
                ?: throw IncompatibleDeviceException(
                    "No native code for this device (${device.abis.joinToString()}) in ${module.ifEmpty { "the base" }}; " +
                        "the bundle has ${abiSplits.joinToString { it.qualifier }}",
                )
            take.add(pick.apk)
            abiSplits.filter { it !== pick }.forEach { leave.add(it.apk to "device prefers ${pick.qualifier}") }
        }

        val densitySplits = splits.filter { it.qualifier in DENSITIES }
        if (densitySplits.isNotEmpty()) {
            val byDpi = densitySplits.sortedBy { DENSITIES.getValue(it.qualifier) }
            val pick = byDpi.firstOrNull { DENSITIES.getValue(it.qualifier) >= device.densityDpi } ?: byDpi.last()
            take.add(pick.apk)
            densitySplits.filter { it !== pick }.forEach { leave.add(it.apk to "device density ${device.densityDpi} fits ${pick.qualifier}") }
        }

        val languages = device.languages.map { it.substringBefore('-').substringBefore('_').lowercase() }.toSet()
        val languageSplits = splits.filter { it.qualifier !in ABIS && it.qualifier !in DENSITIES && LANGUAGE.matches(it.qualifier) }
        for (split in languageSplits) {
            if (split.qualifier in languages) take.add(split.apk) else leave.add(split.apk to "language ${split.qualifier} is not on the device")
        }

        val known = abiSplits + densitySplits + languageSplits
        splits.filter { it !in known }.forEach { leave.add(it.apk to "unrecognized configuration ${it.qualifier}") }
        return take to leave
    }
}
