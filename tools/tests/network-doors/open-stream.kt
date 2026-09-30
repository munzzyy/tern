// expect: a connection opened outside
package fixture

fun read(address: Any) = (address as Opener).openStream().readBytes()
