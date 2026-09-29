package io.github.munzzyy.stamp.engine.real

import io.github.munzzyy.stamp.R
import io.github.munzzyy.stamp.core.suggest.Catalog
import io.github.munzzyy.stamp.core.suggest.SuggestedApp
import io.github.munzzyy.stamp.core.suggest.SuggestedKind
import io.github.munzzyy.stamp.engine.Suggestion
import io.github.munzzyy.stamp.engine.SuggestionKind

/** The starter list of the core, with its summary lines in the user's language. */
internal object Suggestions {
    private val summaries: Map<String, Int> = mapOf(
        "jellyfin_tv" to R.string.suggest_jellyfin_tv,
        "just_player" to R.string.suggest_just_player,
        "mpv" to R.string.suggest_mpv,
        "nova" to R.string.suggest_nova,
        "material_files" to R.string.suggest_material_files,
        "mullvad" to R.string.suggest_mullvad,
        "proton_vpn" to R.string.suggest_proton_vpn,
        "moonlight" to R.string.suggest_moonlight,
        "fossify_gallery" to R.string.suggest_fossify_gallery,
        "breezy_weather" to R.string.suggest_breezy_weather,
        "fdroid" to R.string.suggest_fdroid,
        "heliboard" to R.string.suggest_heliboard,
        "obtainium" to R.string.suggest_obtainium,
        "aegis" to R.string.suggest_aegis,
        "keepassdx" to R.string.suggest_keepassdx,
        "koreader" to R.string.suggest_koreader,
        "element_x" to R.string.suggest_element_x,
        "fairemail" to R.string.suggest_fairemail,
        "tusky" to R.string.suggest_tusky,
        "organic_maps" to R.string.suggest_organic_maps,
        "osmand" to R.string.suggest_osmand,
        "magpie" to R.string.suggest_magpie,
        "sepia" to R.string.suggest_sepia,
        "sweep" to R.string.suggest_sweep,
        "starling" to R.string.suggest_starling,
    )

    fun summaryOf(key: String): Int? = summaries[key]

    fun kindOf(kind: SuggestedKind): SuggestionKind = when (kind) {
        SuggestedKind.MEDIA -> SuggestionKind.MEDIA
        SuggestedKind.TOOLS -> SuggestionKind.TOOLS
        SuggestedKind.PRIVACY -> SuggestionKind.PRIVACY
        SuggestedKind.READING -> SuggestionKind.READING
        SuggestedKind.MESSAGING -> SuggestionKind.MESSAGING
        SuggestedKind.MAPS -> SuggestionKind.MAPS
        SuggestedKind.LAUNCHERS -> SuggestionKind.LAUNCHERS
        SuggestedKind.GAMES -> SuggestionKind.GAMES
    }

    /** [text] turns a string resource into its text. */
    fun list(television: Boolean, text: (Int) -> String): List<Suggestion> = list(television, Catalog.all, text)

    fun list(television: Boolean, catalog: List<SuggestedApp>, text: (Int) -> String): List<Suggestion> = Catalog.ordered(television, catalog).mapNotNull { app ->
        val summary = summaryOf(app.summaryKey) ?: return@mapNotNull null
        Suggestion(app.name, text(summary), app.url, kindOf(app.kind), app.television, pinned = app.signers.isNotEmpty())
    }
}
