// expect: Android's download service
package fixture

import android.app.DownloadManager
import android.net.Uri

fun download(dm: DownloadManager) = dm.enqueue(DownloadManager.Request(Uri.parse("https://example.org/a.apk")))
