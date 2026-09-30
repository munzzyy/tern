// expect: a name resolved on this device
package fixture

fun resolve() = java.net.InetAddress.getByName("example.org")
