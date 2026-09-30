// expect: a web view
package fixture

import android.content.Context

fun show(context: Context) = android.webkit.WebView(context).loadUrl("https://example.org")
