package io.github.munzzyy.jackdaw.core.select

import io.github.munzzyy.jackdaw.core.model.Asset
import io.github.munzzyy.jackdaw.core.model.AssetKind
import io.github.munzzyy.jackdaw.core.model.AssetPolicy
import io.github.munzzyy.jackdaw.core.model.DeviceProfile
import java.util.regex.PatternSyntaxException

data class Pick(val asset: Asset, val score: Int, val reasons: List<String>)

class AssetPolicyException(message: String) : Exception(message)

object AssetPicker {
    private val SPECIAL_ABI_TOKENS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    private val ABI_ALIASES = mapOf(
        "arm64-v8a" to "arm64-v8a", "arm64" to "arm64-v8a", "aarch64" to "arm64-v8a", "armv8" to "arm64-v8a",
        "armeabi-v7a" to "armeabi-v7a", "armv7" to "armeabi-v7a", "armv7a" to "armeabi-v7a",
        "arm32" to "armeabi-v7a", "armeabi" to "armeabi-v7a",
        "x86_64" to "x86_64", "x64" to "x86_64", "amd64" to "x86_64",
        "x86" to "x86", "i686" to "x86", "i386" to "x86",
        "universal" to "any", "all" to "any", "fat" to "any", "noarch" to "any",
    )

    private val VARIANT_TOKENS = setOf("tv", "wear", "automotive")
    private val DEBUG_REASONS = mapOf(
        "debug" to "looks like a debug build",
        "test" to "looks like a test build",
        "tests" to "looks like a test build",
        "unsigned" to "is not signed",
    )

    private const val MAX_NAME_LENGTH = 512
    private const val ABI_BASE_SCORE = 400
    private const val ABI_STEP = 20
    private const val NO_ABI_BONUS = 60
    private const val ABI_MISMATCH_PENALTY = 1000
    private const val VARIANT_MATCH_BONUS = 80
    private const val VARIANT_MISMATCH_PENALTY = 400
    private const val DEBUG_PENALTY = 300
    private const val APK_OVER_BUNDLE_BONUS = 40

    fun rank(assets: List<Asset>, device: DeviceProfile, policy: AssetPolicy): List<Pick> {
        val include = compile(policy.include)
        val exclude = compile(policy.exclude)
        val deviceAbis = device.abis.map { canonicalAbi(it.lowercase()) }
        val deviceVariant = when {
            device.television -> "tv"
            device.watch -> "wear"
            device.automotive -> "automotive"
            else -> null
        }

        val picks = assets
            .filter { it.kind == AssetKind.APK || it.kind == AssetKind.BUNDLE }
            .mapNotNull { asset -> rankOne(asset, deviceAbis, deviceVariant, policy, include, exclude) }

        return picks.sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.asset.name })
    }

    private fun rankOne(
        asset: Asset,
        deviceAbis: List<String>,
        deviceVariant: String?,
        policy: AssetPolicy,
        include: Regex?,
        exclude: Regex?,
    ): Pick? {
        val name = asset.name.take(MAX_NAME_LENGTH)
        if (include != null && !include.containsMatchIn(name)) return null
        if (exclude != null && exclude.containsMatchIn(name)) return null

        val tokens = tokenize(name)
        var score = 0
        val reasons = mutableListOf<String>()

        val namedAbis = tokens.mapNotNull { ABI_ALIASES[it] }.distinct()
        val realAbis = namedAbis.filterNot { it == "any" }
        if (realAbis.isNotEmpty()) {
            val matches = realAbis.filter { it in deviceAbis }
            if (matches.isEmpty()) {
                if (policy.matchDevice) return null
                score -= ABI_MISMATCH_PENALTY
                reasons += "names an ABI this device does not run (${realAbis.joinToString()})"
            } else {
                val bestIndex = matches.minOf { deviceAbis.indexOf(it) }
                val bestAbi = matches.first { deviceAbis.indexOf(it) == bestIndex }
                score += ABI_BASE_SCORE - bestIndex * ABI_STEP
                reasons += "matches this device ($bestAbi)"
            }
        } else if ("any" in namedAbis) {
            score += NO_ABI_BONUS
            reasons += "matches this device (universal build)"
        } else {
            score += NO_ABI_BONUS - 1
            reasons += "no ABI named, should run on this device"
        }

        for (variant in tokens.filter { it in VARIANT_TOKENS }.distinct()) {
            if (variant == deviceVariant) {
                score += VARIANT_MATCH_BONUS
                reasons += "targets this device type ($variant)"
            } else {
                score -= VARIANT_MISMATCH_PENALTY
                reasons += "targets a different device type ($variant)"
            }
        }

        for (token in tokens.distinct()) {
            val reason = DEBUG_REASONS[token] ?: continue
            score -= DEBUG_PENALTY
            reasons += reason
        }

        if (asset.kind == AssetKind.APK) {
            score += APK_OVER_BUNDLE_BONUS
        }

        return Pick(asset, score, reasons)
    }

    private fun canonicalAbi(abi: String): String = ABI_ALIASES[abi] ?: abi

    private fun tokenize(name: String): List<String> {
        var remaining = name.lowercase()
        val found = mutableListOf<String>()
        for (special in SPECIAL_ABI_TOKENS) {
            val boundary = Regex("(?<![a-z0-9])" + Regex.escape(special) + "(?![a-z0-9])")
            if (boundary.containsMatchIn(remaining)) {
                found += special
                remaining = boundary.replace(remaining, " ")
            }
        }
        val rest = remaining.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return found + rest
    }

    private fun compile(pattern: String?): Regex? {
        if (pattern == null) return null
        val capped = pattern.take(MAX_NAME_LENGTH)
        return try {
            Regex(capped, RegexOption.IGNORE_CASE)
        } catch (e: PatternSyntaxException) {
            throw AssetPolicyException("Invalid asset filter pattern: ${e.message}")
        }
    }
}
