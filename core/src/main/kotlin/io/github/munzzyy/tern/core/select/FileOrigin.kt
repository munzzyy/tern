package io.github.munzzyy.tern.core.select

import io.github.munzzyy.tern.core.model.Asset
import io.github.munzzyy.tern.core.net.Urls
import io.github.munzzyy.tern.core.source.SourceTypes

/**
 * Where a file is served from, next to where its app's source is, as Obtainium's warning about a
 * file's origin compares them. A file on another site is not refused for it: every file passes
 * the same checks. It is only said, since the file does not come from where the app was found.
 */
object FileOrigin {
    /**
     * The host [asset] is served from when it lies outside the site of the source at [sourceUrl]
     * and outside the places a source of [sourceType] keeps its files; null when it is the source's own.
     */
    fun foreignHost(sourceType: String, sourceUrl: String, asset: Asset): String? {
        val home = site(Urls.host(sourceUrl))
        val host = Urls.host(asset.url)
        if (home.isEmpty() || host.isEmpty() || site(host) == home) return null
        if (FILE_STORES[sourceType].orEmpty().any { host == it || host.endsWith(".$it") }) return null
        return host
    }

    /**
     * The part of [host] a site owns: its last two labels, or three where the second to last is one
     * that countries give out under themselves, as in example.co.uk and vivo.com.cn.
     */
    fun site(host: String): String {
        val lower = host.lowercase().trimEnd('.')
        if (lower.contains(':') || lower.all { it.isDigit() || it == '.' }) return lower
        val labels = lower.split('.')
        if (labels.size <= 2) return lower
        val keep = if (labels.last().length == 2 && labels[labels.size - 2] in COUNTRY_SECOND_LEVELS) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    private val COUNTRY_SECOND_LEVELS = setOf("ac", "co", "com", "edu", "gob", "go", "gov", "mil", "ne", "net", "or", "org", "sch")

    /** Hosts on another site where a store or a forge keeps the files it lists, by the type of the source. */
    private val FILE_STORES: Map<String, Set<String>> = mapOf(
        SourceTypes.GITHUB to setOf("githubusercontent.com"),
        SourceTypes.GITHUB_ACTIONS to setOf("githubusercontent.com"),
        SourceTypes.HUAWEI to setOf("dbankcloud.com", "dbankcloud.ru", "dbankcdn.com"),
        SourceTypes.TENCENT to setOf("myapp.com"),
        SourceTypes.SAMSUNG to setOf("samsungapps.com", "galaxyappstore.com"),
        SourceTypes.COOLAPK to setOf("coolapkmarket.com"),
        SourceTypes.APKPURE to setOf("winudf.com"),
        SourceTypes.APKCOMBO to setOf("apks.39b7cb94d40914bac590886981b0ed6e.r2.cloudflarestorage.com"),
        SourceTypes.ITCHIO to setOf("itchio-mirror.cb031a832f44726753d6267436f3b414.r2.cloudflarestorage.com"),
        SourceTypes.UPTODOWN to setOf("uptodown.net"),
        SourceTypes.TELEGRAM to setOf("telesco.pe"),
    )
}
