package io.github.munzzyy.tern.core.interop

import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import io.github.munzzyy.tern.core.verify.Fingerprints

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

    /** Headers that carry no secret. Any other can hold a key, so it stays out of a link. */
    private val PLAIN_HEADERS = setOf("user-agent", "accept", "accept-language", "referer")

    /** The obtainium:// link to [app]; null when Obtainium has no source for it, or when Tern could not read it back. */
    fun of(app: AppConfig): String? {
        val entry = ObtainiumExport.entry(shared(app)) as? JsonObject ?: return null
        val fields = LinkedHashMap<String, JsonValue>()
        for (key in KEYS) entry[key]?.let { fields[key] = it }
        // Without a package name Obtainium takes an id of twelve hex digits as one to replace at the first install.
        if (app.packageName == null) fields["id"] = JsonString(Fingerprints.sha256(app.source.url.toByteArray()).take(TEMPORARY_ID))
        return (PREFIX + Urls.encodeSegment(Json.write(JsonObject(fields)))).takeIf { it.length <= MAX_LENGTH }
    }

    /** [of] behind Obtainium's web page, which hands it on to Tern or Obtainium, whichever opens such links. */
    fun web(app: AppConfig): String? = of(app)?.let { ObtainiumLink.WEB_REDIRECT + it }?.takeIf { it.length <= MAX_LENGTH }

    private fun shared(app: AppConfig): AppConfig =
        app.copy(notes = null, categories = emptyList(), favorite = false, source = withPlainHeaders(app.source))

    private fun withPlainHeaders(spec: SourceSpec): SourceSpec {
        val raw = spec.option(SourceOptions.HEADERS) ?: return spec
        val plain = runCatching { RequestHeaders.parse(raw) }.getOrDefault(emptyMap()).filterKeys { it.lowercase() in PLAIN_HEADERS }
        val options = if (plain.isEmpty()) spec.options - SourceOptions.HEADERS else spec.options + (SourceOptions.HEADERS to RequestHeaders.write(plain))
        return spec.copy(options = options)
    }

    private const val TEMPORARY_ID = 12
}
