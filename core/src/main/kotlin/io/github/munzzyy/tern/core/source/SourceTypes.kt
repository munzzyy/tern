package io.github.munzzyy.tern.core.source

/** Values of SourceSpec.type and the option keys each type reads. Exports depend on these strings. */
object SourceTypes {
    const val GITHUB = "github"
    const val GITHUB_ACTIONS = "github-actions"
    const val GITLAB = "gitlab"
    const val FORGEJO = "forgejo"
    const val FDROID = "fdroid"
    const val FDROID_REPO = "fdroid-repo"
    const val HTML = "html"
    const val DIRECT = "direct"
    const val JENKINS = "jenkins"
    const val SOURCEHUT = "sourcehut"
    const val SOURCEFORGE = "sourceforge"

    // Stores where developers publish their own apps.
    const val HUAWEI = "huawei"
    const val SAMSUNG = "samsung"
    const val VIVO = "vivo"
    const val TENCENT = "tencent"
    const val RUSTORE = "rustore"
    const val COOLAPK = "coolapk"
    const val ITCHIO = "itchio"

    // The developer's own site, for one app.
    const val TELEGRAM = "telegram"
    const val NEUTRONCODE = "neutroncode"

    // Sites that republish apps somebody else built.
    const val APKPURE = "apkpure"
    const val APTOIDE = "aptoide"
    const val UPTODOWN = "uptodown"
    const val APKCOMBO = "apkcombo"
    const val APKMIRROR = "apkmirror"
    const val FARSROID = "farsroid"
    const val LITEAPKS = "liteapks"
    const val APK4FREE = "apk4free"
    const val ROCKMODS = "rockmods"

    /** Every type, in the order detection tries them. An export naming another type is refused. */
    val ALL: List<String> = listOf(
        GITHUB, GITHUB_ACTIONS, GITLAB, FORGEJO, FDROID, FDROID_REPO,
        HUAWEI, SAMSUNG, VIVO, TENCENT, RUSTORE, COOLAPK, ITCHIO, TELEGRAM, NEUTRONCODE,
        APKPURE, APTOIDE, UPTODOWN, APKCOMBO, APKMIRROR, FARSROID, LITEAPKS, APK4FREE, ROCKMODS,
        SOURCEFORGE, SOURCEHUT, JENKINS, DIRECT, HTML,
    )

    /** Sites that offer apps changed by someone other than their developer. Said plainly before an app is added from one. */
    val MODIFIED: Set<String> = setOf(LITEAPKS, APK4FREE, ROCKMODS)

    /**
     * Stores and mirrors that offer again what developers published elsewhere, each source of which
     * says so through [Source.republishes]. Their files are held to the developer's certificate
     * like any other, once one is known.
     */
    val REPUBLISHING: Set<String> = setOf(APKPURE, APTOIDE, UPTODOWN, APKCOMBO, APKMIRROR, FARSROID) + MODIFIED

    /**
     * Sources that only tell of new releases and offer no file Tern may install, each of which says
     * so through [Source.trackOnly]. Every app of one is track-only, whatever it was made or saved with.
     */
    val TRACK_ONLY: Set<String> = setOf(APKMIRROR, ROCKMODS)

    /**
     * The kinds a person may say an address is, in the order the Add screen offers them: the
     * general ones and those that are hosted anywhere first. Obtainium lets no address be read as
     * vivo's or CoolApk's store, and neither does Tern.
     */
    val OVERRIDABLE: List<String> = listOf(HTML, DIRECT, GITHUB, GITHUB_ACTIONS, GITLAB, FORGEJO, FDROID_REPO, JENKINS, SOURCEHUT).let { first ->
        first + ALL.filter { it !in first && it != VIVO && it != COOLAPK }
    }

    /**
     * Sources that read versions with the app's version pattern themselves, from more than a version:
     * the web page reader runs it over a link's address or the whole page. The release selector
     * leaves the pattern alone for them.
     */
    val READS_OWN_VERSIONS: Set<String> = setOf(HTML)

    /** The name a person knows the source by. Null for the general ones, which are named by their host. */
    fun displayName(type: String): String? = when (type) {
        GITHUB -> "GitHub"
        GITHUB_ACTIONS -> "GitHub Actions"
        GITLAB -> "GitLab"
        FORGEJO -> "Forgejo"
        FDROID -> "F-Droid"
        FDROID_REPO -> "F-Droid repository"
        JENKINS -> "Jenkins"
        SOURCEHUT -> "SourceHut"
        SOURCEFORGE -> "SourceForge"
        HUAWEI -> "Huawei AppGallery"
        SAMSUNG -> "Galaxy Store"
        VIVO -> "vivo App Store"
        TENCENT -> "Tencent App Store"
        RUSTORE -> "RuStore"
        COOLAPK -> "CoolApk"
        ITCHIO -> "itch.io"
        TELEGRAM -> "Telegram"
        NEUTRONCODE -> "Neutron Code"
        APKPURE -> "APKPure"
        APTOIDE -> "Aptoide"
        UPTODOWN -> "Uptodown"
        APKCOMBO -> "APKCombo"
        APKMIRROR -> "APKMirror"
        FARSROID -> "Farsroid"
        LITEAPKS -> "LiteAPKs"
        APK4FREE -> "APK4Free"
        ROCKMODS -> "RockMods"
        else -> null
    }
}

object SourceOptions {
    /** github-actions: workflow file name such as "build.yml". */
    const val WORKFLOW = "workflow"

    /** github-actions: branch whose successful runs are followed. */
    const val BRANCH = "branch"

    /** github, forgejo: "true" to also ask the forge which release it marks as latest, which then comes first. Costs a request. */
    const val VERIFY_LATEST = "verifyLatest"

    /** github, forgejo: "true" to date a release by the newest upload or update of its files instead of its own date. */
    const val ASSET_DATE = "assetDate"

    /** fdroid, fdroid-repo: application id inside the repository. */
    const val PACKAGE = "package"

    /** fdroid-repo: SHA-256 of the repository signing certificate, lowercase hex. */
    const val FINGERPRINT = "fingerprint"

    /** html: regular expression a link's decoded address must match to count as a download. */
    const val LINK_FILTER = "linkFilter"

    /** html: "true" to match [LINK_FILTER] against what a link says instead of its address. */
    const val LINK_TEXT = "linkText"

    /**
     * html: JSON array, one entry per intermediate page to follow before the final one, at most
     * ten. An entry is a regular expression the link's decoded address must match, or an object
     * `{"filter": "..."}` that can also hold these flags, each `true` when set: `text` matches the
     * link's text instead, `arch` prefers links that name this device's processor, and
     * `pageOrder`, `firstLink`, `lastSegment` and `anyText` mean for that page what [SORT] "page",
     * [FIRST_LINK], [LAST_SEGMENT] and [ANY_TEXT] mean for the last one. On each page the matching
     * links are put in order and the last is followed, as Obtainium does.
     */
    const val STEPS = "steps"

    /**
     * html: "true" to turn the order of the links around, so that the first in natural order, or the
     * first on the page with [SORT] "page", is the one taken: Obtainium's "take first link".
     */
    const val FIRST_LINK = "firstLink"

    /** html: "true" to order links, and read versions without a pattern, by only the last segment of their address. */
    const val LAST_SEGMENT = "lastSegment"

    /**
     * html: "true" to also find addresses outside `<a>` tags: in JSON strings, in the text, and in
     * other tags' attributes. A page without any link tag is always read that way.
     */
    const val ANY_TEXT = "anyText"

    /** html, direct: JSON object of extra headers sent with the page and the file requests. See [io.github.munzzyy.tern.core.source.web.RequestHeaders]. */
    const val HEADERS = "headers"

    /**
     * html, direct: "hash", "link" or "etag", what tells a file from the one before it when no
     * version can be read. See [io.github.munzzyy.tern.core.source.web.PseudoVersion]. Unset: the
     * server's ETag, else its Last-Modified, else the size.
     */
    const val PSEUDO = "pseudo"

    /**
     * html: "link" (default), "text" or "page": what the app's version pattern, or without one a
     * guess, reads the version from. "link" is the link's decoded address, "text" what the link
     * says, and "page" the whole page with its line breaks written as \n, as Obtainium's
     * versionExtractWholePage has it.
     */
    const val VERSION_FROM = "versionFrom"

    /**
     * html: "page" to keep the links in the order of the page. Otherwise they are put in natural
     * order of their address, numbers read as numbers. The last link in that order is the one
     * taken, and the release it belongs to is marked as the latest.
     */
    const val SORT = "sort"

    /** farsroid: "true" to take the name of each file as a version, each file then a release of its own. */
    const val FILE_VERSION = "fileVersion"

    /** samsung: the device model the store is asked for, such as "SM-S948B". */
    const val DEVICE_MODEL = "deviceModel"

    /** samsung: the country and carrier code the store is asked for, such as "DBT". */
    const val CSC = "csc"
}
