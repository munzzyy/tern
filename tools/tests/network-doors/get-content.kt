// expect: what an address holds read straight from it
package fixture

fun fetch(address: Any) = (address as Holder).getContent()
