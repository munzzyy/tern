// expect: java.net.URL or a URLConnection outside
package fixture

import java.net.URL as Address

fun fetch(): ByteArray = Address("https://example.org/x").readBytes()
