package io.github.munzzyy.tern.data

import android.content.Context
import android.content.SharedPreferences
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.engine.Settings

class SettingsStore(context: Context, name: String = DEFAULT_NAME) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            checkEveryMinutes = storedMinutes(d.checkEveryMinutes),
            onlyOnUnmetered = prefs.getBoolean("onlyOnUnmetered", d.onlyOnUnmetered),
            onlyWhileCharging = prefs.getBoolean("onlyWhileCharging", d.onlyWhileCharging),
            defaultUpdateMode = enumOr(prefs.getString("defaultUpdateMode", null), d.defaultUpdateMode),
            includePrereleasesByDefault = prefs.getBoolean("includePrereleasesByDefault", d.includePrereleasesByDefault),
            minAgeDaysByDefault = prefs.getInt("minAgeDaysByDefault", d.minAgeDaysByDefault).coerceIn(0, 365),
            notifyUpdates = prefs.getBoolean("notifyUpdates", d.notifyUpdates),
            notifyInstalled = prefs.getBoolean("notifyInstalled", d.notifyInstalled),
            notifyFailures = prefs.getBoolean("notifyFailures", d.notifyFailures),
            notifyNames = prefs.getBoolean("notifyNames", d.notifyNames),
            notifyTracked = prefs.getBoolean("notifyTracked", d.notifyTracked),
            notifyChecking = prefs.getBoolean("notifyChecking", d.notifyChecking),
            keepInstallers = prefs.getBoolean("keepInstallers", d.keepInstallers),
            claimUpdateOwnership = prefs.getBoolean("claimUpdateOwnership", d.claimUpdateOwnership),
            openObtainiumLinks = prefs.getBoolean("openObtainiumLinks", d.openObtainiumLinks),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            colorSource = enumOr(prefs.getString("colorSource", null), d.colorSource),
            palette = enumOr(prefs.getString("palette", null), d.palette),
            customHue = prefs.getInt("customHue", d.customHue).coerceIn(0, 359),
            contrast = enumOr(prefs.getString("contrast", null), d.contrast),
            pureBlack = prefs.getBoolean("pureBlack", d.pureBlack),
            density = enumOr(prefs.getString("density", null), d.density),
            corners = enumOr(prefs.getString("corners", null), d.corners),
            iconShape = enumOr(prefs.getString("iconShape", null), d.iconShape),
            sourceIcons = prefs.getBoolean("sourceIcons", d.sourceIcons),
            proxy = enumOr(prefs.getString("proxy", null), d.proxy),
            proxyHost = prefs.getString("proxyHost", null) ?: d.proxyHost,
            proxyPort = prefs.getInt("proxyPort", d.proxyPort),
            listSort = enumOr(prefs.getString("listSort", null), d.listSort),
            listDescending = prefs.getBoolean("listDescending", d.listDescending),
            listGrouping = enumOr(prefs.getString("listGrouping", null), d.listGrouping),
            updatesFirst = prefs.getBoolean("updatesFirst", d.updatesFirst),
            buryNotInstalled = prefs.getBoolean("buryNotInstalled", d.buryNotInstalled),
            swipeActions = prefs.getBoolean("swipeActions", d.swipeActions),
            autoExport = prefs.getBoolean("autoExport", d.autoExport),
            exportFolder = prefs.getString("exportFolder", null)?.takeIf { it.startsWith("content://") },
            exportInstalledOnly = prefs.getBoolean("exportInstalledOnly", d.exportInstalledOnly),
            exportSettings = prefs.getBoolean("exportSettings", d.exportSettings),
            installer = enumOr(prefs.getString("installer", null), d.installer),
            otherInstaller = prefs.getString("otherInstaller", null)?.takeIf { PACKAGE.matches(it) },
            playInstaller = prefs.getBoolean("playInstaller", d.playInstaller),
            checkOnStart = prefs.getBoolean("checkOnStart", d.checkOnStart),
            checkOnOpen = prefs.getBoolean("checkOnOpen", d.checkOnOpen),
            onlyCheckInstalled = prefs.getBoolean("onlyCheckInstalled", d.onlyCheckInstalled),
            globalFileFilter = cleanFilter(prefs.getString("globalFileFilter", null)),
            removeUninstalled = prefs.getBoolean("removeUninstalled", d.removeUninstalled),
            collapseGroups = prefs.getBoolean("collapseGroups", d.collapseGroups),
            haptics = prefs.getBoolean("haptics", d.haptics),
            phoneLayout = prefs.getBoolean("phoneLayout", d.phoneLayout),
            allowDowngrades = prefs.getBoolean("allowDowngrades", d.allowDowngrades),
            categoryColors = prefs.getString("categoryColors", null)
                ?.let { runCatching { CategoryColors.decode(Json.parseObject(it)) }.getOrNull() } ?: d.categoryColors,
            searchIn = prefs.getStringSet("searchIn", null)?.filterTo(LinkedHashSet()) { it.length <= MAX_ORIGIN }?.take(MAX_ORIGINS)?.toSet() ?: d.searchIn,
            keptExportName = KeptExportName.clean(prefs.getString("keptExportName", null)),
            keptExportFormat = enumOr(prefs.getString("keptExportFormat", null), d.keptExportFormat),
            customStrength = prefs.getInt("customStrength", d.customStrength).coerceIn(0, 100),
            customColor = if (prefs.contains("customColor")) prefs.getInt("customColor", 0) or OPAQUE else null,
            colorStyle = enumOr(prefs.getString("colorStyle", null), d.colorStyle),
            pinCertificates = prefs.getBoolean("pinCertificates", d.pinCertificates),
            autoInstalls = prefs.getBoolean("autoInstalls", d.autoInstalls),
            updateAllMode = enumOr(prefs.getString("updateAllMode", null), d.updateAllMode),
            confirmUpdateAll = prefs.getBoolean("confirmUpdateAll", d.confirmUpdateAll),
            searchForgejo = prefs.getString("searchForgejo", null)?.let(::cleanHost) ?: d.searchForgejo,
            searchMinStars = prefs.getInt("searchMinStars", d.searchMinStars).coerceIn(0, MAX_STARS),
        )
    }

    /** Tern kept hours before it kept minutes; a value stored as hours is read as that many minutes. */
    private fun storedMinutes(fallback: Int): Int = when {
        prefs.contains("checkEveryMinutes") -> cleanMinutes(prefs.getInt("checkEveryMinutes", fallback))
        prefs.contains("checkEveryHours") -> cleanMinutes(prefs.getInt("checkEveryHours", 0) * 60)
        else -> fallback
    }

    fun save(s: Settings) {
        prefs.edit()
            .putInt("checkEveryMinutes", cleanMinutes(s.checkEveryMinutes))
            .remove("checkEveryHours")
            .putBoolean("onlyOnUnmetered", s.onlyOnUnmetered)
            .putBoolean("onlyWhileCharging", s.onlyWhileCharging)
            .putString("defaultUpdateMode", s.defaultUpdateMode.name)
            .putBoolean("includePrereleasesByDefault", s.includePrereleasesByDefault)
            .putInt("minAgeDaysByDefault", s.minAgeDaysByDefault.coerceIn(0, 365))
            .putBoolean("notifyUpdates", s.notifyUpdates)
            .putBoolean("notifyInstalled", s.notifyInstalled)
            .putBoolean("notifyFailures", s.notifyFailures)
            .putBoolean("notifyNames", s.notifyNames)
            .putBoolean("notifyTracked", s.notifyTracked)
            .putBoolean("notifyChecking", s.notifyChecking)
            .putBoolean("keepInstallers", s.keepInstallers)
            .putBoolean("claimUpdateOwnership", s.claimUpdateOwnership)
            .putBoolean("openObtainiumLinks", s.openObtainiumLinks)
            .putString("theme", s.theme.name)
            .putString("colorSource", s.colorSource.name)
            .putString("palette", s.palette.name)
            .putInt("customHue", s.customHue.coerceIn(0, 359))
            .putString("contrast", s.contrast.name)
            .putBoolean("pureBlack", s.pureBlack)
            .putString("density", s.density.name)
            .putString("corners", s.corners.name)
            .putString("iconShape", s.iconShape.name)
            .putBoolean("sourceIcons", s.sourceIcons)
            .putString("proxy", s.proxy.name)
            .putString("proxyHost", s.proxyHost)
            .putInt("proxyPort", s.proxyPort)
            .putString("listSort", s.listSort.name)
            .putBoolean("listDescending", s.listDescending)
            .putString("listGrouping", s.listGrouping.name)
            .putBoolean("updatesFirst", s.updatesFirst)
            .putBoolean("buryNotInstalled", s.buryNotInstalled)
            .putBoolean("swipeActions", s.swipeActions)
            .putBoolean("autoExport", s.autoExport)
            .putString("exportFolder", s.exportFolder?.takeIf { it.startsWith("content://") })
            .putBoolean("exportInstalledOnly", s.exportInstalledOnly)
            .putBoolean("exportSettings", s.exportSettings)
            .putString("installer", s.installer.name)
            .putString("otherInstaller", s.otherInstaller?.takeIf { PACKAGE.matches(it) })
            .putBoolean("playInstaller", s.playInstaller)
            .putBoolean("checkOnStart", s.checkOnStart)
            .putBoolean("checkOnOpen", s.checkOnOpen)
            .putBoolean("onlyCheckInstalled", s.onlyCheckInstalled)
            .putString("globalFileFilter", cleanFilter(s.globalFileFilter))
            .putBoolean("removeUninstalled", s.removeUninstalled)
            .putBoolean("collapseGroups", s.collapseGroups)
            .putBoolean("haptics", s.haptics)
            .putBoolean("phoneLayout", s.phoneLayout)
            .putBoolean("allowDowngrades", s.allowDowngrades)
            .putStringSet("searchIn", s.searchIn.filterTo(LinkedHashSet()) { it.length <= MAX_ORIGIN })
            .putString("categoryColors", Json.write(CategoryColors.encode(s.categoryColors)))
            .putString("keptExportName", KeptExportName.clean(s.keptExportName))
            .putString("keptExportFormat", s.keptExportFormat.name)
            .putInt("customStrength", s.customStrength.coerceIn(0, 100))
            .also { if (s.customColor == null) it.remove("customColor") else it.putInt("customColor", s.customColor or OPAQUE) }
            .putString("colorStyle", s.colorStyle.name)
            .putBoolean("pinCertificates", s.pinCertificates)
            .putBoolean("autoInstalls", s.autoInstalls)
            .putString("updateAllMode", s.updateAllMode.name)
            .putBoolean("confirmUpdateAll", s.confirmUpdateAll)
            .putString("searchForgejo", cleanHost(s.searchForgejo))
            .putInt("searchMinStars", s.searchMinStars.coerceIn(0, MAX_STARS))
            .commit()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val DEFAULT_NAME = "settings"

        /** Thirty days, the longest Obtainium offers too. */
        const val MAX_MINUTES = 30 * 24 * 60

        /** The shortest period Android runs a job at. */
        const val MIN_MINUTES = 15
        const val MAX_FILTER = 500
        private const val MAX_ORIGIN = 60
        private const val MAX_ORIGINS = 40
        private const val OPAQUE = 0xFF000000.toInt()

        /** 0 stays off; anything else is taken to the range Android keeps to. */
        fun cleanMinutes(minutes: Int): Int = if (minutes <= 0) 0 else minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

        /** A filter as it may be stored: trimmed, not too long, and one that compiles; anything else is none. */
        fun cleanFilter(pattern: String?): String? {
            val trimmed = pattern?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_FILTER } ?: return null
            return trimmed.takeIf { runCatching { Regex(it) }.isSuccess }
        }

        /** A package name, so what goes into an Intent as one can be nothing else. */
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** The most stars a search may ask a project to have. */
        const val MAX_STARS = 1_000_000

        /** The host of a Forgejo to search, with a port that is not the usual one; Codeberg for anything that names none. */
        fun cleanHost(text: String?): String =
            Urls.normalize("https://" + text.orEmpty().trim().substringAfter("://"))?.let(Urls::authority)?.takeIf { it.isNotEmpty() } ?: Settings.DEFAULT_FORGEJO
    }
}
