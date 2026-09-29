package io.github.munzzyy.stamp.ui

import io.github.munzzyy.stamp.core.engine.InstalledApp
import io.github.munzzyy.stamp.core.model.AppConfig
import io.github.munzzyy.stamp.core.model.Asset
import io.github.munzzyy.stamp.core.model.Release
import io.github.munzzyy.stamp.core.model.SourceSpec
import io.github.munzzyy.stamp.engine.AppRow
import io.github.munzzyy.stamp.engine.AppStatus
import io.github.munzzyy.stamp.engine.FileChoice
import io.github.munzzyy.stamp.engine.Problem
import io.github.munzzyy.stamp.engine.Progress

fun testRow(
    id: String = "app",
    name: String = "App",
    status: AppStatus = AppStatus.UP_TO_DATE,
    installed: String? = "1.0",
    offered: String? = "1.0",
    certain: Boolean = true,
    trackOnly: Boolean = false,
    progress: Progress? = null,
    problem: Problem? = null,
    checking: Boolean = false,
    withFile: Boolean = true,
    categories: List<String> = emptyList(),
    lastChecked: Long? = null,
    type: String = "github",
): AppRow {
    val asset = Asset("app.apk", "https://example.org/app.apk", 1000)
    return AppRow(
        config = AppConfig(
            id = id,
            source = SourceSpec(type, "https://example.org/example/$id"),
            name = name,
            author = "Example",
            packageName = "org.example.$id",
            trackOnly = trackOnly,
            categories = categories,
        ),
        installed = installed?.let { InstalledApp("org.example.$id", it, 1, emptyList()) },
        status = status,
        statusCertain = certain,
        latest = offered?.let { Release(id = "v$it", version = it, assets = listOf(asset)) },
        file = if (withFile) FileChoice(asset, listOf("only file")) else null,
        progress = progress,
        problem = problem,
        checking = checking,
        lastCheckedMs = lastChecked,
    )
}
