// expect: the probe connects to something other than 127.0.0.1
// replaces: app/src/main/kotlin/io/github/munzzyy/tern/net/ProxyProbe.kt
package io.github.munzzyy.tern.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private val THIS_DEVICE = byteArrayOf(127, 0, 0, 1)

fun probe(port: Int) = Socket().connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)), port))
