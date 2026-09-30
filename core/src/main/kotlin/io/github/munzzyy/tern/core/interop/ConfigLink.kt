package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.web.RequestHeaders

/**
 * An app's settings as a link: obtainium://app/ and the app as Obtainium stores it, which Tern
 * and Obtainium both open, and two web addresses for chat apps that only make those clickable.
 * [tern] is Tern's own page with the app after '#', which a browser keeps to itself, so no server
 * learns what was shared. [web] is Obtainium's page, which is sent the whole link; it is offered
 * only by name, for someone who uses Obtainium. Both apps show what a link carries before
 * anything is stored.
 *
 * A token is never part of one: Tern keeps tokens apart from an app's settings, and a request
 * header goes along only when it is one of a few that say how to ask and never who asks. What
 * is the person's own stays behind too: notes, categories and the favourite mark.
 */
object ConfigLink {
    /** The longest link Tern reads back, as its Add screen takes one. */
    const val MAX_LENGTH = 8_000

    private const val PREFIX = "obtainium://app/"

    /** Tern's page that opens an app in Tern, or offers Tern to whoever does not have it yet. */
    const val ADD_PAGE = "https://tern.munzzyy.dev/add/"

    /** The keys of Obtainium's own links. The rest of what it stores of an app is how the app stands on one device. */
    private val KEYS = listOf("id", "url", "author", "name", "preferredApkIndex", "additionalSettings", "overrideSource")

    /** Headers that carry no secret. Any other can hold a key, so it stays out of a link. */
    private val PLAIN_HEADERS = setOf("accept", "accept-language", "referer")

    /** The obtainium:// link to [app]; null when Obtainium has no source for it, or when Tern could not read it back. */
    fun of(app: AppConfig): String? {
        // Without a package name the id is one Obtainium replaces at the first install, as the export writes it.
        val entry = ObtainiumExport.entry(shared(app)) as? JsonObject ?: return null
        val fields = LinkedHashMap<String, JsonValue>()
        for (key in KEYS) entry[key]?.let { fields[key] = it }
        return (PREFIX + Urls.encodeSegment(Json.write(JsonObject(fields)))).takeIf { it.length <= MAX_LENGTH }
    }

    /** [of] behind Obtainium's web page, which hands it on to Tern or Obtainium, whichever opens such links. Obtainium's server sees all of it. */
    fun web(app: AppConfig): String? = of(app)?.let { ObtainiumLink.WEB_REDIRECT + it }?.takeIf { it.length <= MAX_LENGTH }

    /** The app of [of] on Tern's page, after '#app='. The page makes a tern://app/ link of it. */
    fun tern(app: AppConfig): String? = of(app)?.let { ADD_PAGE + "#app=" + it.removePrefix(PREFIX) }?.takeIf { it.length <= MAX_LENGTH }

    /** Tern's page for the app at [sourceUrl], with the address after '#url='. */
    fun ternAddress(sourceUrl: String): String = ADD_PAGE + "#url=" + Urls.encodeSegment(sourceUrl)

    private fun shared(app: AppConfig): AppConfig =
        app.copy(notes = null, categories = emptyList(), favorite = false, source = withPlainHeaders(app.source))

    private fun withPlainHeaders(spec: SourceSpec): SourceSpec {
        val raw = spec.option(SourceOptions.HEADERS) ?: return spec
        val plain = runCatching { RequestHeaders.parse(raw) }.getOrDefault(emptyMap()).filterKeys { it.lowercase() in PLAIN_HEADERS }
        val options = if (plain.isEmpty()) spec.options - SourceOptions.HEADERS else spec.options + (SourceOptions.HEADERS to RequestHeaders.write(plain))
        return spec.copy(options = options)
    }
}
