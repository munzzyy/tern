package io.github.munzzyy.tern.core.icon

import io.github.munzzyy.tern.core.net.Urls

/** Decides which of the icon addresses a source names may be asked at all. */
object IconAddresses {
    const val MAX_ADDRESSES = 4
    const val MAX_LENGTH = 2048

    /**
     * The only hosts an icon may come from besides the source's own, by the host of the source.
     * The stores keep their images on their own CDNs, which the store's own pages load too.
     */
    val OTHER_HOSTS: Map<String, Set<String>> = mapOf(
        "github.com" to setOf("raw.githubusercontent.com", "avatars.githubusercontent.com"),
        "appgallery.huawei.com" to setOf("appimg-dra.dbankcdn.com", "appimg-drcn.dbankcdn.com"),
        "appgallery.huawei.ru" to setOf("appimg-dra.dbankcdn.com", "appimg-drcn.dbankcdn.com"),
        "detail-browser.vivo.com.cn" to setOf("imgwsdl.vivo.com.cn", "appstoreimg-ipv6.vivo.com.cn"),
        "sj.qq.com" to setOf("pp.myapp.com"),
        "www.coolapk.com" to setOf("pp.myapp.com"),
        "www.rustore.ru" to setOf("static.rustore.ru"),
    )

    /** What is left of [candidates], in their order, for a source at [sourceUrl]: HTTPS only, and no host but the allowed ones. */
    fun accepted(sourceUrl: String, candidates: List<String?>): List<String> {
        val home = Urls.host(sourceUrl)
        if (home.isEmpty()) return emptyList()
        val others = OTHER_HOSTS[home].orEmpty()
        return candidates.asSequence()
            .filterNotNull()
            .mapNotNull(::clean)
            .filter { address -> Urls.host(address).let { it == home || it in others } }
            .distinct()
            .take(MAX_ADDRESSES)
            .toList()
    }

    /**
     * What to store after a check. A listing that names nothing usable keeps what was [known],
     * as far as the source at [sourceUrl] may still name it.
     */
    fun afterCheck(sourceUrl: String, named: List<String>, known: List<String>): List<String> =
        accepted(sourceUrl, named).ifEmpty { accepted(sourceUrl, known) }

    private fun clean(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.length > MAX_LENGTH || !Urls.isHttps(trimmed)) return null
        return Urls.normalize(trimmed)
    }
}
