// expect: a connection opened outside
package fixture

fun open(address: java.net.URI) = address.openConnection()
