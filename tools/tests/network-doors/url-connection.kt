// expect: java.net.URL or a URLConnection outside
package fixture

fun status(connection: Any) = (connection as javax.net.ssl.HttpsURLConnection).responseCode
