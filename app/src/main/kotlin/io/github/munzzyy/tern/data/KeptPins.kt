package io.github.munzzyy.tern.data

import android.content.Context
import android.content.SharedPreferences
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.verify.Fingerprints

/**
 * The certificates an app was held to when Tern took it out of the list because it was
 * uninstalled elsewhere, and its repository's key. Added again from the same source, the app is
 * held to them again, so a reinstall does not let whatever is served next become the pin.
 * Removing an app by hand forgets them: that is how a person lets an app change its signer.
 */
class KeptPins(context: Context, name: String = DEFAULT_NAME, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun keep(config: AppConfig) {
        val kept = Kept.of(config, nowMs()) ?: return
        val key = key(config.source)
        val others = prefs.all.filterKeys { it != key }
        val edit = prefs.edit()
        if (others.size >= MAX) {
            others.entries.sortedBy { Kept.decode(it.value as? String)?.atMs ?: 0L }.take(others.size - MAX + 1).forEach { edit.remove(it.key) }
        }
        edit.putString(key, kept.encode()).apply()
    }

    fun forget(spec: SourceSpec) {
        prefs.edit().remove(key(spec)).apply()
    }

    /** [config] held again to what was kept for its source, where it holds nothing of its own. */
    fun restore(config: AppConfig): AppConfig = Kept.decode(prefs.getString(key(config.source), null))?.onto(config) ?: config

    data class Kept(val signers: List<String>, val fingerprint: String?, val atMs: Long = 0L) {
        fun encode(): String = Json.write(Json.obj("signers" to signers, "fingerprint" to fingerprint, "at" to atMs))

        fun onto(config: AppConfig): AppConfig {
            val pins = config.pinnedSigners.ifEmpty { signers }
            val source = if (fingerprint != null && config.source.option(SourceOptions.FINGERPRINT) == null) {
                config.source.copy(options = config.source.options + (SourceOptions.FINGERPRINT to fingerprint))
            } else {
                config.source
            }
            return config.copy(pinnedSigners = pins, source = source)
        }

        companion object {
            fun of(config: AppConfig, atMs: Long): Kept? {
                val fingerprint = config.source.option(SourceOptions.FINGERPRINT)?.let(Fingerprints::normalize)
                if (config.pinnedSigners.isEmpty() && fingerprint == null) return null
                return Kept(config.pinnedSigners, fingerprint, atMs)
            }

            fun decode(text: String?): Kept? {
                val obj = try {
                    Json.parseObject(text ?: return null)
                } catch (_: JsonException) {
                    return null
                }
                val signers = obj.array("signers")?.strings().orEmpty().mapNotNull(Fingerprints::normalize).distinct().take(MAX_SIGNERS)
                val fingerprint = obj.string("fingerprint")?.let(Fingerprints::normalize)
                return Kept(signers, fingerprint, obj.long("at") ?: 0L).takeIf { signers.isNotEmpty() || fingerprint != null }
            }
        }
    }

    companion object {
        const val DEFAULT_NAME = "kept-pins"
        private const val MAX = 200
        private const val MAX_SIGNERS = 32

        /** One app's source: its kind, its address, and in a repository of many apps its package. */
        fun key(spec: SourceSpec): String {
            val pkg = spec.option(SourceOptions.PACKAGE)?.takeIf { spec.type == SourceTypes.FDROID_REPO }.orEmpty()
            return "${spec.type}|${spec.url.lowercase()}|$pkg"
        }
    }
}
