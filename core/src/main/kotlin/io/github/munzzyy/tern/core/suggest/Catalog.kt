package io.github.munzzyy.tern.core.suggest

import io.github.munzzyy.tern.core.suggest.ConfirmedBy.APP_VERIFIER
import io.github.munzzyy.tern.core.suggest.ConfirmedBy.FDROID
import io.github.munzzyy.tern.core.suggest.SuggestedKind.GAMES
import io.github.munzzyy.tern.core.suggest.SuggestedKind.MAPS
import io.github.munzzyy.tern.core.suggest.SuggestedKind.MEDIA
import io.github.munzzyy.tern.core.suggest.SuggestedKind.MESSAGING
import io.github.munzzyy.tern.core.suggest.SuggestedKind.PRIVACY
import io.github.munzzyy.tern.core.suggest.SuggestedKind.READING
import io.github.munzzyy.tern.core.suggest.SuggestedKind.TOOLS

/** The starter list. docs/SUGGESTIONS.md says how an app gets on it and how it comes off. */
object Catalog {
    private fun github(path: String) = "https://github.com/$path"

    private fun tv(name: String, url: String, kind: SuggestedKind, key: String, id: String) =
        SuggestedApp(name, url, kind, television = true, summaryKey = key, packageName = id, fdroidId = id)

    private fun app(name: String, url: String, kind: SuggestedKind, key: String, id: String, fdroidId: String = id) =
        SuggestedApp(name, url, kind, television = false, summaryKey = key, packageName = id, fdroidId = fdroidId)

    private fun own(name: String, url: String, kind: SuggestedKind, key: String, id: String) =
        SuggestedApp(name, url, kind, television = false, summaryKey = key, packageName = id, own = true)

    private fun SuggestedApp.signedBy(fingerprint: String, confirmedBy: ConfirmedBy) =
        copy(signers = signers + fingerprint, confirmedBy = confirmedBy)

    val all: List<SuggestedApp> = listOf(
        tv("Jellyfin for Android TV", github("jellyfin/jellyfin-androidtv"), MEDIA, "jellyfin_tv", "org.jellyfin.androidtv")
            .signedBy("d881796ed2a67ff6ef9f676828723c6b1fa18e09388962cba4abc4a594a69131", FDROID),
        tv("Just Player", github("moneytoo/Player"), MEDIA, "just_player", "com.brouken.player"),
        tv("mpv-android", github("mpv-android/mpv-android"), MEDIA, "mpv", "is.xyz.mpv"),
        tv("Nova Video Player", github("nova-video-player/aos-AVP"), MEDIA, "nova", "org.courville.nova"),
        tv("Material Files", github("zhanghai/MaterialFiles"), TOOLS, "material_files", "me.zhanghai.android.files"),
        tv("Mullvad VPN", github("mullvad/mullvadvpn-app"), PRIVACY, "mullvad", "net.mullvad.mullvadvpn")
            .signedBy("7be21930c3b4d73906b08930450a1d3afbd22c98d9d8e987df8c1fbc2d0c90bb", APP_VERIFIER),
        tv("Proton VPN", github("ProtonVPN/android-app"), PRIVACY, "proton_vpn", "ch.protonvpn.android")
            .signedBy("dcc9439ec1a6c6a8d0203f3423ee42bcc8b970628e53cb73a0393f398dd5b853", APP_VERIFIER),
        tv("Moonlight", github("moonlight-stream/moonlight-android"), GAMES, "moonlight", "com.limelight"),

        app("Fossify Gallery", github("FossifyOrg/Gallery"), MEDIA, "fossify_gallery", "org.fossify.gallery")
            .signedBy("affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292", FDROID),
        app("Breezy Weather", github("breezy-weather/breezy-weather"), TOOLS, "breezy_weather", "org.breezyweather")
            .signedBy("29d435f70aa9aec3c1faff7f7ffa6e15785088d87f06ecfcab9c3cc62dc269d8", FDROID),
        app("F-Droid", "https://f-droid.org/packages/org.fdroid.fdroid", TOOLS, "fdroid", "org.fdroid.fdroid")
            .signedBy("43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab", APP_VERIFIER),
        app("HeliBoard", github("HeliBorg/HeliBoard"), TOOLS, "heliboard", "helium314.keyboard")
            .signedBy("5ec0a5313aa43558ee75b20b58ccd8194cdbf066df94a43ba288d933d60b86ce", FDROID),
        app("Obtainium", github("ImranR98/Obtainium"), TOOLS, "obtainium", "dev.imranr.obtainium.fdroid")
            .signedBy("b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362", FDROID),
        app("Aegis Authenticator", github("beemdevelopment/Aegis"), PRIVACY, "aegis", "com.beemdevelopment.aegis")
            .signedBy("c6db80a8e14e5230c1de8415ef820d13dc901d8fe33cf3acb57b6862d858a823", APP_VERIFIER),
        app("KeePassDX", github("Kunzisoft/KeePassDX"), PRIVACY, "keepassdx", "com.kunzisoft.keepass.free", fdroidId = "com.kunzisoft.keepass.libre")
            .signedBy("7d55b8af210381aabf960f07e17cf7857b6d2a642ca2da6bf0bdf1b200362f04", APP_VERIFIER),
        app("KOReader", github("koreader/koreader"), READING, "koreader", "org.koreader.launcher", fdroidId = "org.koreader.launcher.fdroid"),
        app("Element X", github("element-hq/element-x-android"), MESSAGING, "element_x", "io.element.android.x")
            .signedBy("6a2fdc3148049ce0d5c6e85010723b83fb207d20c7477f5c22ac53c877e92d47", FDROID),
        app("FairEmail", github("M66B/FairEmail"), MESSAGING, "fairemail", "eu.faircode.email")
            .signedBy("e02067249f5a350e0ec703fe9df4dd682e0291a09f0c2e041050bbe7c064f5c9", APP_VERIFIER),
        app("Tusky", "https://codeberg.org/tusky/Tusky", MESSAGING, "tusky", "com.keylesspalace.tusky"),
        app("Organic Maps", github("organicmaps/organicmaps"), MAPS, "organic_maps", "app.organicmaps.web", fdroidId = "app.organicmaps")
            .signedBy("b9c7ae79a5a90270df08a132e536b9c666f5bef1f59b304fcecf8687865e4b5b", APP_VERIFIER),
        app("OsmAnd", "https://download.osmand.net/releases/", MAPS, "osmand", "net.osmand", fdroidId = "net.osmand.plus"),

        own("Magpie", github("munzzyy/magpie"), PRIVACY, "magpie", "io.github.munzzyy.magpie")
            .signedBy("35d26c85cf963570aafda3dccce4d28fcb041f712cf15cd24ab8cc7d694be526", FDROID),
        own("Sepia", github("munzzyy/sepia"), PRIVACY, "sepia", "io.github.munzzyy.sepia"),
        own("Sweep", github("munzzyy/sweep"), PRIVACY, "sweep", "io.github.munzzyy.sweep"),
        own("Starling", github("munzzyy/starling"), MAPS, "starling", "app.starlingmap")
            .signedBy("dbb0c491530f7475409c4c2953e9f68a52519c2f681dd9e5f69938f3bf9b8c9e", FDROID),
    )

    /** The entry whose address is [url], in whatever case it is written. */
    fun entryAt(url: String, apps: List<SuggestedApp> = all): SuggestedApp? {
        val wanted = url.trim().trimEnd('/')
        return apps.firstOrNull { it.url.trimEnd('/').equals(wanted, ignoreCase = true) }
    }

    /** Television entries first on a television, then by kind, then by name, with the author's own last in their kind. */
    fun ordered(television: Boolean, apps: List<SuggestedApp> = all): List<SuggestedApp> = apps.sortedWith(
        compareBy<SuggestedApp> { television && !it.television }
            .thenBy { it.kind }
            .thenBy { it.own }
            .thenBy { it.name.lowercase() },
    )
}
