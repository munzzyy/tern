package io.github.munzzyy.tern.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.text.Collator
import java.util.Locale

/**
 * The language Tern speaks when it should not follow the system. From Android 13 on, Android keeps
 * that choice for each app and applies it everywhere. Before that Tern keeps it itself, and puts it
 * on every screen and on the text shown outside them, in notifications and the widget.
 */
object AppLanguage {
    /** Every language Tern has, as language tags, the language of the source text first. */
    val TAGS: List<String> = listOf(
        "en", "ar", "bs", "ca", "cs", "da", "de", "eo", "es", "fa", "fr", "gl", "hu", "id", "it", "ja", "ko", "ml",
        "nl", "pl", "pt", "pt-BR", "ru", "sv", "tr", "uk", "vi", "zh-CN", "zh-TW",
    )

    private const val PREFS = "language"
    private const val KEY = "tag"

    /** The chosen language's tag, or null to follow the system. */
    fun chosen(context: Context): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
    } else {
        prefs(context).getString(KEY, null)?.takeIf { it in TAGS }
    }

    /**
     * Speaks [tag] from now on, or the system's language for null. Android 13 and newer draw open
     * screens again by themselves; before that the caller recreates its activity.
     */
    fun choose(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            val editor = prefs(context).edit()
            if (tag == null) editor.remove(KEY) else editor.putString(KEY, tag)
            editor.apply()
            applyTo(context.applicationContext)
        }
    }

    /** [base] speaking the chosen language, for an activity before Android 13. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = chosen(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }

    /**
     * Puts the chosen language on the app's own resources before Android 13, so that notifications
     * and the widget speak it too. Android puts the system's language back on every change of
     * configuration, so the app calls this again then.
     */
    @Suppress("DEPRECATION")
    fun applyTo(app: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val locales = chosen(app)?.let(LocaleList::forLanguageTags) ?: Resources.getSystem().configuration.locales
        val resources = app.resources
        if (resources.configuration.locales == locales) return
        val config = Configuration(resources.configuration)
        config.setLocales(locales)
        LocaleList.setDefault(locales)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /** [tag]'s name in its own language, as a list of languages shows it. */
    fun nameOf(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** [TAGS] in the order of their own names, as a person looking for theirs expects. */
    fun byName(within: Locale = Locale.getDefault()): List<String> {
        val collator = Collator.getInstance(within)
        return TAGS.sortedWith { a, b -> collator.compare(nameOf(a), nameOf(b)) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
