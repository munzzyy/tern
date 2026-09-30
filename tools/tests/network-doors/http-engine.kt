// expect: a second HTTP stack
package fixture

import android.content.Context

fun engine(context: Context) = android.net.http.HttpEngine.Builder(context).build()
