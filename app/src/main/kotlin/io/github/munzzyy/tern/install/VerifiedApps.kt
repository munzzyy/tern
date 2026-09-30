package io.github.munzzyy.tern.install

/**
 * Verified Apps, which was AppVerifier, and the builds of it that Obtainium knows. Each is an app
 * of its own that checks an app's signing certificate against one it is given and against its own
 * list of apps it knows.
 */
object VerifiedApps {
    /** Newest name first: Privacy Guides' Verified Apps, its build for Google Play, AppVerifier, and a fork of AppVerifier. */
    val PACKAGES = listOf(
        "org.privacyguides.verifiedapps",
        "org.privacyguides.verifiedapps.play",
        "dev.soupslurpr.appverifier",
        "com.roundsalmon4.appverifier",
    )

    /** The page that says what Verified Apps is, as Obtainium links it. */
    const val ABOUT = "https://github.com/privacyguides/verified-apps-android"

    /** The first of [PACKAGES] that [takes] says will take what is to be sent, or null when none will. */
    fun first(takes: (String) -> Boolean): String? = PACKAGES.firstOrNull(takes)

    /**
     * Whether the checked file of an install goes to [verifier] before the installer: only for a
     * first install, only while the [setting] asks, and only with a person [there] to use it.
     */
    fun handsOver(setting: Boolean, firstInstall: Boolean, there: Boolean, verifier: String?): Boolean =
        setting && firstInstall && there && verifier != null
}
