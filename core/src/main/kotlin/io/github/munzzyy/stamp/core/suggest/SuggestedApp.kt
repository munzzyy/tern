package io.github.munzzyy.stamp.core.suggest

enum class SuggestedKind { MEDIA, TOOLS, PRIVACY, READING, MESSAGING, MAPS, LAUNCHERS, GAMES }

/**
 * One app of the starter list. An entry has to say why it may be listed: F-Droid's main repository
 * carries it ([fdroidId]), a well known organisation publishes it ([publisher]), or it is [own].
 */
data class SuggestedApp(
    val name: String,
    /** The developer's own place of release, in the form detection settles on. */
    val url: String,
    val kind: SuggestedKind,
    /** Made for a television, or at home on one. */
    val television: Boolean,
    /** Names the summary line, which the app keeps as a string so that it can be translated. */
    val summaryKey: String,
    /** The id of the file the address serves. */
    val packageName: String,
    /** The id F-Droid's main repository carries the app under, which can differ from [packageName]. */
    val fdroidId: String? = null,
    val publisher: String? = null,
    /** Written by the author of Stamp. */
    val own: Boolean = false,
)
