// expect: a socket made outside
package fixture

import java.net.Socket

fun connect() = Socket("example.org", 443)
