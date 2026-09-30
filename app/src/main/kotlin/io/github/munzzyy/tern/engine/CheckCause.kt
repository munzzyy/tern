package io.github.munzzyy.tern.engine

/** What started a check, as Tern's own messages in the log say it. */
enum class CheckCause {
    /** Someone asked for it in Tern: the list, an app's page or its menu, or Check now in Settings. */
    ASKED,

    /** Tern was opened, and the setting asks for a check then. */
    OPENING,

    /** An app's page was opened, and the setting asks for a check then. */
    PAGE,

    /** A tern:// or obtainium:// link that asks for a check. */
    LINK,

    /** The launcher shortcut that checks. */
    SHORTCUT,

    WIDGET,

    /** The Quick Settings tile. */
    TILE,

    /** The background check, when the schedule runs it. */
    SCHEDULE,

    /** The background check again, for the apps that could not be checked before. */
    RETRY,

    /** The app was just added. */
    ADDED,

    /** The apps were just imported. */
    IMPORTED,

    /** The app's settings changed in a way that asks its source anew. */
    CHANGED,

    /** An install of an app set to check before it installs. */
    INSTALL,
}
