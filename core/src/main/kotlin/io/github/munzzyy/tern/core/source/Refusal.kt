package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.net.Urls

/** Why Tern reads nothing from a site, not even as a plain web page. */
enum class Refusal {
    /** The site offers apps changed by someone other than their developer, such as unlocked or "mod" builds. */
    MODIFIED_APPS,

    /** The store answers only its own app, so reading it means pretending to be that app. */
    IMPERSONATION,
    ;

    companion object {
        private val SITES: Map<String, Refusal> = mapOf(
            "liteapks.com" to MODIFIED_APPS,
            "apk4free.net" to MODIFIED_APPS,
            "rockmods.net" to MODIFIED_APPS,
            "farsroid.com" to MODIFIED_APPS,
            "rustore.ru" to IMPERSONATION,
            "uptodown.com" to IMPERSONATION,
            "uptodown.net" to IMPERSONATION,
            "uptodown.app" to IMPERSONATION,
            "coolapk.com" to IMPERSONATION,
            "coolapkmarket.com" to IMPERSONATION,
        )

        /** The types Tern once had for these sites, which an older export or list may still name. */
        private val TYPES: Map<String, Refusal> = mapOf(
            "liteapks" to MODIFIED_APPS,
            "apk4free" to MODIFIED_APPS,
            "rockmods" to MODIFIED_APPS,
            "farsroid" to MODIFIED_APPS,
            "rustore" to IMPERSONATION,
            "uptodown" to IMPERSONATION,
            "coolapk" to IMPERSONATION,
        )

        /** The same sites under the names Obtainium's exports give them. */
        private val OBTAINIUM: Map<String, Refusal> = mapOf(
            "LiteAPKs" to MODIFIED_APPS,
            "Apk4Free" to MODIFIED_APPS,
            "RockMods" to MODIFIED_APPS,
            "Farsroid" to MODIFIED_APPS,
            "RuStore" to IMPERSONATION,
            "Uptodown" to IMPERSONATION,
            "CoolApk" to IMPERSONATION,
        )

        /** The refusal for an address on one of these sites or a host under it; null for every other address. */
        fun ofUrl(url: String): Refusal? {
            val host = Urls.parseHttps(url)?.host?.lowercase()?.trimEnd('.') ?: return null
            return SITES.entries.firstOrNull { (site, _) -> host == site || host.endsWith(".$site") }?.value
        }

        fun ofType(type: String?): Refusal? = TYPES[type]

        fun ofObtainium(name: String?): Refusal? = OBTAINIUM[name]
    }
}
