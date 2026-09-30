// expect: the door opens a connection without the proxy
// replaces: app/src/main/kotlin/io/github/munzzyy/tern/net/UrlConnectionHttp.kt
package io.github.munzzyy.tern.net

import java.net.URL

fun open(url: URL) = url.openConnection()
