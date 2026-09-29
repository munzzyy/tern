package io.github.munzzyy.tern.core.source.fdroid

import io.github.munzzyy.tern.core.json.JsonObject

/** Where a repository in F-Droid's format keeps the icon of an app. Measured against f-droid.org. */
internal object FDroidIcons {
    private val LOCALES = listOf("en-US", "en-GB", "en")

    /** The address the site itself serves the icon at. Its per-app answer names no icon. */
    fun byConvention(repositoryUrl: String, packageName: String): String? =
        if (FDroidSource.isValidPackage(packageName)) "$repositoryUrl/$packageName/en-US/icon.png" else null

    /** From one app of an index in the second format: metadata.icon, by locale, names the file. */
    fun fromIndex(app: JsonObject, repositoryUrl: String): String? {
        val icons = app.obj("metadata")?.obj("icon") ?: return null
        val name = preferred(icons) { it.string("name") }?.second ?: return null
        return if (insideRepository(name)) repositoryUrl + name else null
    }

    /** From an index in the first format: apps[].localized, by locale, names the file next to the app's other pictures. */
    fun fromFirstIndex(root: JsonObject, packageName: String, repositoryUrl: String): String? {
        val app = root.array("apps")?.objects()?.firstOrNull { it.string("packageName") == packageName } ?: return null
        val (locale, file) = preferred(app.obj("localized") ?: return null) { it.string("icon") } ?: return null
        val name = "/$packageName/$locale/$file"
        return if (insideRepository(name)) repositoryUrl + name else null
    }

    private fun preferred(byLocale: JsonObject, read: (JsonObject) -> String?): Pair<String, String>? {
        val known = LOCALES.asSequence() + byLocale.fields.keys.asSequence().take(MAX_LOCALES)
        return known.firstNotNullOfOrNull { locale -> byLocale.obj(locale)?.let(read)?.let { locale to it } }
    }

    private const val MAX_LOCALES = 200
}
