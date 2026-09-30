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

    /** fdroid, fdroid-repo: application id inside the repository. */
    const val PACKAGE = "package"

    /** fdroid-repo: SHA-256 of the repository signing certificate, lowercase hex. */
    const val FINGERPRINT = "fingerprint"

    /** html: regular expression a link must match to count as a download. */
    const val LINK_FILTER = "linkFilter"

    /** html: JSON array of regular expressions, one per intermediate page to follow before the final one. */
    const val STEPS = "steps"

    /** html: "link" (default), "text" or "page": where the version is read from. */
    const val VERSION_FROM = "versionFrom"

    /** html: "version" (default) or "page": how candidate links are ordered. */
    const val SORT = "sort"

    /** samsung: the device model the store is asked for, such as "SM-S948B". */
    const val DEVICE_MODEL = "deviceModel"

    /** samsung: the country and carrier code the store is asked for, such as "DBT". */
    const val CSC = "csc"
}
