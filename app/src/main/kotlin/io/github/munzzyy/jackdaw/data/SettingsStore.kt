package io.github.munzzyy.jackdaw.data

import android.content.Context
import android.content.SharedPreferences
import io.github.munzzyy.jackdaw.engine.Settings

class SettingsStore(context: Context, name: String = DEFAULT_NAME) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            checkEveryHours = prefs.getInt("checkEveryHours", d.checkEveryHours).coerceIn(0, MAX_HOURS),
            onlyOnUnmetered = prefs.getBoolean("onlyOnUnmetered", d.onlyOnUnmetered),
            onlyWhileCharging = prefs.getBoolean("onlyWhileCharging", d.onlyWhileCharging),
            defaultUpdateMode = enumOr(prefs.getString("defaultUpdateMode", null), d.defaultUpdateMode),
            includePrereleasesByDefault = prefs.getBoolean("includePrereleasesByDefault", d.includePrereleasesByDefault),
            minAgeDaysByDefault = prefs.getInt("minAgeDaysByDefault", d.minAgeDaysByDefault).coerceIn(0, 365),
            notifyUpdates = prefs.getBoolean("notifyUpdates", d.notifyUpdates),
            notifyInstalled = prefs.getBoolean("notifyInstalled", d.notifyInstalled),
            notifyFailures = prefs.getBoolean("notifyFailures", d.notifyFailures),
            keepInstallers = prefs.getBoolean("keepInstallers", d.keepInstallers),
            claimUpdateOwnership = prefs.getBoolean("claimUpdateOwnership", d.claimUpdateOwnership),
            openObtainiumLinks = prefs.getBoolean("openObtainiumLinks", d.openObtainiumLinks),
            theme = enumOr(prefs.getString("theme", null), d.theme),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            pureBlack = prefs.getBoolean("pureBlack", d.pureBlack),
            proxy = enumOr(prefs.getString("proxy", null), d.proxy),
            proxyHost = prefs.getString("proxyHost", null) ?: d.proxyHost,
            proxyPort = prefs.getInt("proxyPort", d.proxyPort),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putInt("checkEveryHours", s.checkEveryHours.coerceIn(0, MAX_HOURS))
            .putBoolean("onlyOnUnmetered", s.onlyOnUnmetered)
            .putBoolean("onlyWhileCharging", s.onlyWhileCharging)
            .putString("defaultUpdateMode", s.defaultUpdateMode.name)
            .putBoolean("includePrereleasesByDefault", s.includePrereleasesByDefault)
            .putInt("minAgeDaysByDefault", s.minAgeDaysByDefault.coerceIn(0, 365))
            .putBoolean("notifyUpdates", s.notifyUpdates)
            .putBoolean("notifyInstalled", s.notifyInstalled)
            .putBoolean("notifyFailures", s.notifyFailures)
            .putBoolean("keepInstallers", s.keepInstallers)
            .putBoolean("claimUpdateOwnership", s.claimUpdateOwnership)
            .putBoolean("openObtainiumLinks", s.openObtainiumLinks)
            .putString("theme", s.theme.name)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putBoolean("pureBlack", s.pureBlack)
            .putString("proxy", s.proxy.name)
            .putString("proxyHost", s.proxyHost)
            .putInt("proxyPort", s.proxyPort)
            .commit()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val DEFAULT_NAME = "settings"
        const val MAX_HOURS = 24 * 7
    }
}
