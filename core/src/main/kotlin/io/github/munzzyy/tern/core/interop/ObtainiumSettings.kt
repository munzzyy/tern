package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonBool
import io.github.munzzyy.tern.core.json.JsonNumber
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue

/**
 * Obtainium's settings, as its exports carry them, under the names Tern keeps its portable
 * settings by, and back. Only what means the same in both goes across: the look of the list,
 * when to check, and the defaults for new apps. Tokens, installers and anything else stay behind,
 * both ways.
 */
object ObtainiumSettings {
    /** Where Obtainium's interval slider rests for each of its steps, 0 being "never". */
    private val SLIDER_STEPS = listOf(15, 30, 60, 120, 180, 360, 720, 1440, 4320, 10080, 20160, 43200)

    private class Link(val obtainium: String, val tern: String, val toTern: (JsonValue) -> JsonValue?, val toObtainium: (JsonValue) -> JsonValue?)

    private fun flag(obtainium: String, tern: String, inverted: Boolean = false) = Link(
        obtainium, tern,
        { (it as? JsonBool)?.let { b -> JsonBool(b.value != inverted) } },
        { (it as? JsonBool)?.let { b -> JsonBool(b.value != inverted) } },
    )

    private fun number(obtainium: String, tern: String) = Link(
        obtainium, tern,
        { v -> (v as? JsonNumber)?.toLongOrNull()?.let { JsonNumber(it.toString()) } },
        { v -> (v as? JsonNumber)?.toLongOrNull()?.let { JsonNumber(it.toString()) } },
    )

    /** An Obtainium setting kept as the index of one of its choices, or as the choice's name. */
    private fun choice(obtainium: String, tern: String, byIndex: Boolean, pairs: List<Pair<String, String>>) = Link(
        obtainium, tern,
        { v ->
            val key = when {
                byIndex -> (v as? JsonNumber)?.toLongOrNull()?.toInt()?.let { pairs.getOrNull(it)?.first }
                else -> (v as? JsonString)?.value
            }
            pairs.firstOrNull { it.first == key }?.let { JsonString(it.second) }
        },
        { v ->
            val index = pairs.indexOfFirst { it.second == (v as? JsonString)?.value }
            when {
                index < 0 -> null
                byIndex -> JsonNumber(index.toString())
                else -> JsonString(pairs[index].first)
            }
        },
    )

    private val LINKS = listOf(
        number("updateInterval", "checkEveryMinutes"),
        flag("bgUpdatesOnWiFiOnly", "onlyOnUnmetered"),
        flag("bgUpdatesWhileChargingOnly", "onlyWhileCharging"),
        flag("checkOnStart", "checkOnStart"),
        flag("checkUpdateOnDetailPage", "checkOnOpen"),
        flag("onlyCheckInstalledOrTrackOnlyApps", "onlyCheckInstalled"),
        flag("removeOnExternalUninstall", "removeUninstalled"),
        flag("includePrereleasesByDefault", "includePrereleasesByDefault"),
        number("minimumUpdateAgeDays", "minAgeDaysByDefault"),
        choice("theme", "theme", byIndex = true, listOf("system" to "SYSTEM", "light" to "LIGHT", "dark" to "DARK")),
        flag("useBlackTheme", "pureBlack"),
        choice("sortColumn", "listSort", byIndex = true, listOf("added" to "ADDED", "nameAuthor" to "NAME", "authorName" to "AUTHOR", "releaseDate" to "RELEASED")),
        Link(
            "sortOrder", "listDescending",
            { v -> (v as? JsonNumber)?.toLongOrNull()?.let { JsonBool(it == 1L) } },
            { v -> (v as? JsonBool)?.let { JsonNumber(if (it.value) "1" else "0") } },
        ),
        flag("pinUpdates", "updatesFirst"),
        flag("buryNonInstalled", "buryNotInstalled"),
        choice("groupBy", "listGrouping", byIndex = false, listOf("none" to "NONE", "category" to "CATEGORY", "source" to "SOURCE")),
        flag("disableSwipeActions", "swipeActions", inverted = true),
        flag("alwaysUsePhoneLayout", "phoneLayout"),
        flag("tactileFeedbackEnabled", "haptics"),
        flag("collapseGroupsOnStartup", "collapseGroups"),
        choice("appListDensity", "density", byIndex = false, listOf("standard" to "COMFORTABLE", "compact" to "COMPACT", "dense" to "MINIMAL")),
        // Obtainium keeps its categories as a map of name to colour written into a string.
        Link(
            "categories", "categoryColors",
            { v -> (v as? JsonString)?.value?.let { runCatching { Json.parseObject(it) }.getOrNull() } },
            { v ->
                (v as? JsonObject)?.let { colors ->
                    val unsigned = colors.fields.mapValues { (_, argb) -> (argb as? JsonNumber)?.toLongOrNull()?.let { JsonNumber((it and 0xFFFFFFFFL).toString()) } ?: argb }
                    JsonString(Json.write(JsonObject(unsigned)))
                }
            },
        ),
    )

    /** What [obtainium] sets that Tern has a setting for, under Tern's names. */
    fun toTern(obtainium: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()
        for (link in LINKS) {
            val value = obtainium[link.obtainium] ?: continue
            link.toTern(value)?.let { out[link.tern] = it }
        }
        return JsonObject(out)
    }

    /** Tern's portable settings [tern] under Obtainium's names, for an export Obtainium reads. */
    fun toObtainium(tern: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()
        for (link in LINKS) {
            val value = tern[link.tern] ?: continue
            link.toObtainium(value)?.let { out[link.obtainium] = it }
        }
        // Obtainium draws its slider from a value of its own; an interval between its steps leaves the slider where it was.
        val step = when (val minutes = (out["updateInterval"] as? JsonNumber)?.toLongOrNull()?.toInt()) {
            null -> null
            0 -> 0
            else -> SLIDER_STEPS.indexOf(minutes).takeIf { it >= 0 }?.plus(1)
        }
        step?.let { out["updateIntervalSliderVal"] = JsonNumber("$it.0") }
        return JsonObject(out)
    }
}
