package io.github.munzzyy.tern.core.handoff

import java.net.Inet4Address
import java.net.InetAddress

object LocalAddress {
    /**
     * The address a handoff may listen on: the first IPv4 address of a private network among
     * [candidates] (10.x, 172.16 to 172.31, 192.168.x), or null when there is none.
     */
    fun pick(candidates: List<InetAddress>): Inet4Address? =
        candidates.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
}
