package io.github.munzzyy.stamp.core.suggest

import io.github.munzzyy.stamp.core.suggest.SuggestedKind.GAMES
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.MAPS
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.MEDIA
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.MESSAGING
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.PRIVACY
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.READING
import io.github.munzzyy.stamp.core.suggest.SuggestedKind.TOOLS

/** The starter list. docs/SUGGESTIONS.md says how an app gets on it and how it comes off. */
object Catalog {
    private fun github(path: String) = "https://github.com/$path"

    private fun tv(name: String, url: String, kind: SuggestedKind, key: String, id: String) =
        SuggestedApp(name, url, kind, television = true, summaryKey = key, packageName = id, fdroidId = id)

    private fun app(name: String, url: String, kind: SuggestedKind, key: String, id: String, fdroidId: String = id) =
        SuggestedApp(name, url, kind, television = false, summaryKey = key, packageName = id, fdroidId = fdroidId)

    private fun own(name: String, url: String, kind: SuggestedKind, key: String, id: String) =
        SuggestedApp(name, url, kind, television = false, summaryKey = key, packageName = id, own = true)

    val all: List<SuggestedApp> = listOf(
        tv("Jellyfin for Android TV", github("jellyfin/jellyfin-androidtv"), MEDIA, "jellyfin_tv", "org.jellyfin.androidtv"),
        tv("Just Player", github("moneytoo/Player"), MEDIA, "just_player", "com.brouken.player"),
        tv("mpv-android", github("mpv-android/mpv-android"), MEDIA, "mpv", "is.xyz.mpv"),
        tv("Nova Video Player", github("nova-video-player/aos-AVP"), MEDIA, "nova", "org.courville.nova"),
        tv("Material Files", github("zhanghai/MaterialFiles"), TOOLS, "material_files", "me.zhanghai.android.files"),
        tv("Mullvad VPN", github("mullvad/mullvadvpn-app"), PRIVACY, "mullvad", "net.mullvad.mullvadvpn"),
        tv("Proton VPN", github("ProtonVPN/android-app"), PRIVACY, "proton_vpn", "ch.protonvpn.android"),
        tv("Moonlight", github("moonlight-stream/moonlight-android"), GAMES, "moonlight", "com.limelight"),

        app("Fossify Gallery", github("FossifyOrg/Gallery"), MEDIA, "fossify_gallery", "org.fossify.gallery"),
        app("Breezy Weather", github("breezy-weather/breezy-weather"), TOOLS, "breezy_weather", "org.breezyweather"),
        app("F-Droid", "https://f-droid.org/packages/org.fdroid.fdroid", TOOLS, "fdroid", "org.fdroid.fdroid"),
        app("HeliBoard", github("HeliBorg/HeliBoard"), TOOLS, "heliboard", "helium314.keyboard"),
        app("Obtainium", github("ImranR98/Obtainium"), TOOLS, "obtainium", "dev.imranr.obtainium.fdroid"),
        app("Aegis Authenticator", github("beemdevelopment/Aegis"), PRIVACY, "aegis", "com.beemdevelopment.aegis"),
        app("KeePassDX", github("Kunzisoft/KeePassDX"), PRIVACY, "keepassdx", "com.kunzisoft.keepass.free", fdroidId = "com.kunzisoft.keepass.libre"),
        app("KOReader", github("koreader/koreader"), READING, "koreader", "org.koreader.launcher", fdroidId = "org.koreader.launcher.fdroid"),
        app("Element X", github("element-hq/element-x-android"), MESSAGING, "element_x", "io.element.android.x"),
        app("FairEmail", github("M66B/FairEmail"), MESSAGING, "fairemail", "eu.faircode.email"),
        app("Tusky", "https://codeberg.org/tusky/Tusky", MESSAGING, "tusky", "com.keylesspalace.tusky"),
        app("Organic Maps", github("organicmaps/organicmaps"), MAPS, "organic_maps", "app.organicmaps.web", fdroidId = "app.organicmaps"),
        app("OsmAnd", "https://download.osmand.net/releases/", MAPS, "osmand", "net.osmand", fdroidId = "net.osmand.plus"),

        own("Magpie", github("munzzyy/magpie"), PRIVACY, "magpie", "io.github.munzzyy.magpie"),
        own("Sepia", github("munzzyy/sepia"), PRIVACY, "sepia", "io.github.munzzyy.sepia"),
        own("Sweep", github("munzzyy/sweep"), PRIVACY, "sweep", "io.github.munzzyy.sweep"),
        own("Starling", github("munzzyy/starling"), MAPS, "starling", "app.starlingmap"),
    )

    /** Television entries first on a television, then by kind, then by name, with the author's own last in their kind. */
    fun ordered(television: Boolean, apps: List<SuggestedApp> = all): List<SuggestedApp> = apps.sortedWith(
        compareBy<SuggestedApp> { television && !it.television }
            .thenBy { it.kind }
            .thenBy { it.own }
            .thenBy { it.name.lowercase() },
    )
}
