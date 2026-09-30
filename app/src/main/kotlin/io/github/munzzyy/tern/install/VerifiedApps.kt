package io.github.munzzyy.tern.install

import android.content.pm.PackageManager

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

    /**
     * The signing certificate of each, SHA-256 in lowercase hex, as its makers publish it and
     * Privacy Guides' list of verified apps has it too. An app holding one of these names under
     * another certificate is not the verifier, and gets nothing.
     */
    val CERTIFICATES = mapOf(
        "org.privacyguides.verifiedapps" to "405c6bd2ca7c3aae8f463c6f8b55bcf0ddac431c5ed8eaff65d106c9817a207f",
        "org.privacyguides.verifiedapps.play" to "e858375b7c45cfc1d4e4f51b4ad659735b382c824fe2244836a814ad7af30061",
        "dev.soupslurpr.appverifier" to "3a04a80b2a88334c747485f0b2151640a38bb3d2d73a8eab81df503e0f0202b2",
        "com.roundsalmon4.appverifier" to "1e76f1a15cbe201f0fe26af27a12d91d0d3481fe7dcc7d89e9d2056930f6d5a9",
    )

    /** The page that says what Verified Apps is, as Obtainium links it. */
    const val ABOUT = "https://github.com/privacyguides/verified-apps-android"

    /**
     * The first of [PACKAGES] that is signed as [CERTIFICATES] says, by the current signers
     * [signersOf] reads, and that [takes] says will take what is to be sent; null when none is.
     */
    fun first(signersOf: (String) -> Collection<String>?, takes: (String) -> Boolean): String? =
        PACKAGES.firstOrNull { genuine(it, signersOf(it)) && takes(it) }

    fun genuine(packageName: String, signers: Collection<String>?): Boolean =
        !signers.isNullOrEmpty() && CERTIFICATES[packageName] in signers

    /** The current signers of [packageName] on this device, or null when it is not installed. */
    fun signers(pm: PackageManager, packageName: String): List<String>? = try {
        PackageManagerArchiveReader.certificates(pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo).first
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Whether the checked file of an install goes to [verifier] before the installer: only for a
     * first install, only while the [setting] asks, and only with a person [there] to use it.
     */
    fun handsOver(setting: Boolean, firstInstall: Boolean, there: Boolean, verifier: String?): Boolean =
        setting && firstInstall && there && verifier != null
}
