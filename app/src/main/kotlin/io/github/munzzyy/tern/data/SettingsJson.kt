package io.github.munzzyy.tern.data

import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.engine.Settings

/**
 * The settings that may travel in an export and come back from one: the look, the list,
 * notifications, background checks and the defaults for new apps. What reaches past this device
 * or decides how it is protected stays out, both ways: no token, no proxy, no installer, no
 * link handling, no export folder, no file filter and no downgrades. A file that names more is
 * read for what it may set, and the rest is ignored.
 */
object SettingsJson {
    private val PORTABLE: List<Field<*>> = listOf(
        Field.number("checkEveryMinutes", { it.checkEveryMinutes }, 0..SettingsStore.MAX_MINUTES) { s, v -> s.copy(checkEveryMinutes = SettingsStore.cleanMinutes(v)) },
        Field.flag("checkOnStart", { it.checkOnStart }) { s, v -> s.copy(checkOnStart = v) },
        Field.flag("checkOnOpen", { it.checkOnOpen }) { s, v -> s.copy(checkOnOpen = v) },
        Field.flag("onlyCheckInstalled", { it.onlyCheckInstalled }) { s, v -> s.copy(onlyCheckInstalled = v) },
        Field.flag("removeUninstalled", { it.removeUninstalled }) { s, v -> s.copy(removeUninstalled = v) },
        Field.flag("onlyOnUnmetered", { it.onlyOnUnmetered }) { s, v -> s.copy(onlyOnUnmetered = v) },
        Field.flag("onlyWhileCharging", { it.onlyWhileCharging }) { s, v -> s.copy(onlyWhileCharging = v) },
        Field.choice("defaultUpdateMode", { it.defaultUpdateMode }) { s, v -> s.copy(defaultUpdateMode = v) },
        Field.flag("includePrereleasesByDefault", { it.includePrereleasesByDefault }) { s, v -> s.copy(includePrereleasesByDefault = v) },
        Field.number("minAgeDaysByDefault", { it.minAgeDaysByDefault }, 0..365) { s, v -> s.copy(minAgeDaysByDefault = v) },
        Field.flag("notifyUpdates", { it.notifyUpdates }) { s, v -> s.copy(notifyUpdates = v) },
        Field.flag("notifyInstalled", { it.notifyInstalled }) { s, v -> s.copy(notifyInstalled = v) },
        Field.flag("notifyFailures", { it.notifyFailures }) { s, v -> s.copy(notifyFailures = v) },
        Field.flag("notifyNames", { it.notifyNames }) { s, v -> s.copy(notifyNames = v) },
        Field.flag("notifyTracked", { it.notifyTracked }) { s, v -> s.copy(notifyTracked = v) },
        Field.flag("notifyChecking", { it.notifyChecking }) { s, v -> s.copy(notifyChecking = v) },
        Field.flag("keepInstallers", { it.keepInstallers }) { s, v -> s.copy(keepInstallers = v) },
        Field.choice("theme", { it.theme }) { s, v -> s.copy(theme = v) },
        Field.choice("colorSource", { it.colorSource }) { s, v -> s.copy(colorSource = v) },
        Field.choice("palette", { it.palette }) { s, v -> s.copy(palette = v) },
        Field.number("customHue", { it.customHue }, 0..359) { s, v -> s.copy(customHue = v) },
        Field.choice("contrast", { it.contrast }) { s, v -> s.copy(contrast = v) },
        Field.flag("pureBlack", { it.pureBlack }) { s, v -> s.copy(pureBlack = v) },
        Field.choice("density", { it.density }) { s, v -> s.copy(density = v) },
        Field.choice("corners", { it.corners }) { s, v -> s.copy(corners = v) },
        Field.choice("iconShape", { it.iconShape }) { s, v -> s.copy(iconShape = v) },
        Field.flag("sourceIcons", { it.sourceIcons }) { s, v -> s.copy(sourceIcons = v) },
        Field.choice("listSort", { it.listSort }) { s, v -> s.copy(listSort = v) },
        Field.flag("listDescending", { it.listDescending }) { s, v -> s.copy(listDescending = v) },
        Field.choice("listGrouping", { it.listGrouping }) { s, v -> s.copy(listGrouping = v) },
        Field.flag("updatesFirst", { it.updatesFirst }) { s, v -> s.copy(updatesFirst = v) },
        Field.flag("buryNotInstalled", { it.buryNotInstalled }) { s, v -> s.copy(buryNotInstalled = v) },
        Field.flag("swipeActions", { it.swipeActions }) { s, v -> s.copy(swipeActions = v) },
        Field.flag("collapseGroups", { it.collapseGroups }) { s, v -> s.copy(collapseGroups = v) },
        Field.flag("haptics", { it.haptics }) { s, v -> s.copy(haptics = v) },
        Field.flag("phoneLayout", { it.phoneLayout }) { s, v -> s.copy(phoneLayout = v) },
        Field("categoryColors", { it.categoryColors }, CategoryColors::encode, { v -> (v as? JsonObject)?.let(CategoryColors::decode) }) { s, v ->
            s.copy(categoryColors = v)
        },
        Field.number("customStrength", { it.customStrength }, 0..100) { s, v -> s.copy(customStrength = v) },
        Field.choice("colorStyle", { it.colorStyle }) { s, v -> s.copy(colorStyle = v) },
    )

    /** The keys a file may carry. */
    val KEYS: Set<String> = PORTABLE.mapTo(LinkedHashSet()) { it.key }

    fun encode(settings: Settings): JsonObject = JsonObject(PORTABLE.associateTo(LinkedHashMap()) { it.key to it.write(settings) })

    /** [current] with what [obj] sets among the portable settings; anything else in it is ignored. */
    fun apply(obj: JsonObject, current: Settings): Settings = PORTABLE.fold(current) { s, field -> field.read(obj[field.key], s) ?: s }

    private class Field<T>(
        val key: String,
        private val get: (Settings) -> T,
        private val encode: (T) -> JsonValue,
        private val decode: (JsonValue) -> T?,
        private val set: (Settings, T) -> Settings,
    ) {
        fun write(settings: Settings): JsonValue = encode(get(settings))

        fun read(value: JsonValue?, settings: Settings): Settings? = value?.let(decode)?.let { set(settings, it) }

        companion object {
            fun flag(key: String, get: (Settings) -> Boolean, set: (Settings, Boolean) -> Settings) =
                Field(key, get, { JsonBool(it) }, { (it as? JsonBool)?.value }, set)

            fun number(key: String, get: (Settings) -> Int, range: IntRange, set: (Settings, Int) -> Settings) =
                Field(key, get, { JsonNumber(it.toString()) }, { v -> (v as? JsonNumber)?.toLongOrNull()?.takeIf { it in range.first..range.last }?.toInt() }, set)

            inline fun <reified E : Enum<E>> choice(key: String, noinline get: (Settings) -> E, noinline set: (Settings, E) -> Settings) =
                Field(key, get, { JsonString(it.name) }, { v -> (v as? JsonString)?.value?.let { name -> enumValues<E>().firstOrNull { it.name == name } } }, set)
        }
    }
}
