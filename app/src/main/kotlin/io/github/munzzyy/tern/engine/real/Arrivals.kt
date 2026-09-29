package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.UpdateMode

/** What is stored of an app that arrived in a file, where nobody looks at each app before it is stored. */
internal object Arrivals {
    /**
     * [imported] as it is stored under [id]. It is held to the certificates Tern carries for its
     * address. And it is never set to install by itself: a file can come from anyone, and that an
     * app updates without being asked is for the user of this device to switch on.
     */
    fun stored(imported: AppConfig, id: String, builtIn: BuiltInPins): AppConfig = imported.copy(
        id = id,
        pinnedSigners = builtIn.orElse(imported.source.url, imported.pinnedSigners),
        updates = if (imported.updates == UpdateMode.AUTO) UpdateMode.NOTIFY else imported.updates,
    )
}
