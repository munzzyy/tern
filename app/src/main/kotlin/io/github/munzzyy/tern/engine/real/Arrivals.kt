package io.github.munzzyy.tern.engine.real

import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.UpdateMode
import io.github.munzzyy.tern.core.source.SourceOptions

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

    /**
     * [existing] with the settings a file or a link holds for it in [imported], once the person
     * asked for them. What says who the app is and who may sign it stays as Tern learned it or the
     * person set it: the name the source gives, the package name, the pins where there are any and
     * the key of its repository. It only installs by itself if it already did, and a release the
     * person skipped stays skipped.
     */
    fun replaced(existing: AppConfig, imported: AppConfig, builtIn: BuiltInPins): AppConfig {
        val repositoryKey = existing.source.option(SourceOptions.FINGERPRINT)
        return imported.copy(
            id = existing.id,
            source = existing.source.copy(
                options = if (repositoryKey == null) imported.source.options else imported.source.options + (SourceOptions.FINGERPRINT to repositoryKey),
            ),
            name = existing.name,
            author = existing.author,
            packageName = existing.packageName ?: imported.packageName,
            pinnedSigners = builtIn.orElse(existing.source.url, existing.pinnedSigners.ifEmpty { imported.pinnedSigners }),
            updates = if (imported.updates == UpdateMode.AUTO && existing.updates != UpdateMode.AUTO) existing.updates else imported.updates,
            releases = imported.releases.copy(skippedReleaseId = existing.releases.skippedReleaseId),
        )
    }
}
