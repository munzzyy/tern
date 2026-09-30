// expect: java.net.URL or a URLConnection outside
package fixture

import java.net.URL

fun fetch(): String = URL("https://example.org/x").readText()
