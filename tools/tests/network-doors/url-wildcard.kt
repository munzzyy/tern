// expect: java.net.URL or a URLConnection outside
package fixture

import java.net.*

fun fetch(): Any = URL("https://example.org/x").content
