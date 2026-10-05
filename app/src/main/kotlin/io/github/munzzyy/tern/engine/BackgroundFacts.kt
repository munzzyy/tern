package io.github.munzzyy.tern.engine

/**
 * What Android does with the background check, read as it stands now. [lastRunMs] is when the
 * periodic job last ran to its end and [sinceMs] when it was last set anew; each is null until it
 * first happened. [canOpenAppInfo] is false where Android's page about an app is a stand-in that
 * opens nothing, as on some televisions.
 */
data class BackgroundFacts(
    val lastRunMs: Long? = null,
    val sinceMs: Long? = null,
    val scheduled: Boolean = true,
    val restricted: Boolean = false,
    val notificationsOn: Boolean = true,
    val updatesChannelOn: Boolean = true,
    val canOpenAppInfo: Boolean = true,
)
