// expect: a URL made from a URI outside
package fixture

fun fetch(): ByteArray = java.net.URI("https://example.org/x").toURL().readBytes()
