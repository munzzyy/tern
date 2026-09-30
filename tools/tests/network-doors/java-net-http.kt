// expect: a second HTTP stack
package fixture

fun client() = java.net.http.HttpClient.newHttpClient()
