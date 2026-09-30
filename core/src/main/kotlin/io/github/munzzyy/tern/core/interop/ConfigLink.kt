package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.web.RequestHeaders

/**
 * An app's settings as a link, the way Obtainium shares them: obtainium://app/ and the app as
 * Obtainium stores it, and the same link behind Obtainium's web page for chat apps that only
 * make web addresses clickable. Tern and Obtainium both open either, and show what the link
 * carries before anything is stored.
 *
 * A token is never part of one: Tern keeps tokens apart from an app's settings, and a request
 * header goes along only when it is one of a few that say how to ask and never who asks. What
 * is the person's own stays behind too: notes, categories and the favourite mark.
 */
object ConfigLink {
    /** The longest link Tern reads back, as its Add screen takes one. */
    const val MAX_LENGTH = 8_000

    private const val PREFIX = "obtainium://app/"

    /** The keys of Obtainium's own links. The rest of what it stores of an app is how the app stands on one device. */
    private val KEYS = listOf("id", "url", "author", "name", "preferredApkIndex", "additionalSettings", "overrideSource")

    /** The obtainium:// link to [app]; null when Obtainium has no source for it, or when Tern could not read it back. */
    fun of(app: AppConfig): String? {
        // Without a package name the id is one Obtainium replaces at the first install, as the export writes it.
        val entry = ObtainiumExport.entry(shared(app)) as? JsonObject ?: return null
        val fields = LinkedHashMap<String, JsonValue>()
        for (key in KEYS) entry[key]?.let { fields[key] = it }
        return (PREFIX + Urls.encodeSegment(Json.write(JsonObject(fields)))).takeIf { it.length <= MAX_LENGTH }
    }

    /** [of] behind Obtainium's web page, which hands it on to Tern or Obtainium, whichever opens such links. */
    fun web(app: AppConfig): String? = of(app)?.let { ObtainiumLink.WEB_REDIRECT + it }?.takeIf { it.length <= MAX_LENGTH }

    private fun shared(app: AppConfig): AppConfig =
        app.copy(notes = null, categories = emptyList(), favorite = false, source = RequestHeaders.plainOnly(app.source))
}
