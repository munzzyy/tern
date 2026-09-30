# Privacy

Tern has no server of its own and no account. It collects no analytics and
sends no crash reports, because there is nobody for it to report to.

## What leaves the device

Tern asks a host only for something you set up: an app you added, a search
you started, a page you pressed. Every request says it comes from Tern, with
the User-Agent `Tern/` and the version, and none pretends to be a browser or
another app. Each host sees what any web server sees: your address, the time,
and what was asked. This is every host, by what makes Tern ask it.

| What | Hosts |
|---|---|
| An app from GitHub | api.github.com, and github.com and hosts under githubusercontent.com for its files |
| An app from GitLab, Codeberg or another Forgejo, Gitea, GitLab or Jenkins server | The server you added |
| An app from SourceHut or SourceForge | git.sr.ht; sourceforge.net and the download mirror it sends you to |
| An app from F-Droid or IzzyOnDroid | f-droid.org or apt.izzysoft.de. When a check of an F-Droid app finds something new, also gitlab.com/fdroid/fdroiddata for its author and changes, and the changelog file on GitHub or GitLab that the entry names |
| An app from another repository in F-Droid's format | The repository you added |
| An app from a web page or a direct link | The site you gave, and the hosts its links lead to over HTTPS |
| An app from itch.io | The game's page on itch.io, and itchio-mirror.cb031a832f44726753d6267436f3b414.r2.cloudflarestorage.com for its file, only when you download it or open its size |
| Telegram | telegram.org, t.me, and cdn1 to cdn5.telesco.pe for the file |
| An app from Neutron Code | neutroncode.com |
| A search on the Add screen | The places ticked under "Search in": api.github.com, codeberg.org (or the Forgejo you chose), gitlab.com and search.f-droid.org until you choose others |
| Icons of apps not installed yet | For GitHub, raw.githubusercontent.com and avatars.githubusercontent.com; for a store, the one host it keeps its pictures on, named below; for every other source, the host the app comes from |
| "Read the project's page" on an app's page | The forge's API, only when you press it |
| An import from a link | The one address you typed |

The eight third-party stores are off until you turn on Settings, Network,
Third-party stores. While that is off Tern asks none of their hosts, a
search leaves them out, and an app from one waits in the list. With it on,
Tern asks a store only for the apps you add from it, and only searches the
three that can be searched when you tick them.

| Store | Hosts |
|---|---|
| APKPure | apkpure.com for the page of the app's versions, d.apkpure.com for the download, which sends you to data.winudf.com for the file, and image.winudf.com for the icon |
| Aptoide | The app's page on aptoide.com, ws2.aptoide.com for the app and for a search, pool.apk.aptoide.com for the file, pool.img.aptoide.com for the icon |
| APKCombo | apkcombo.com, and apks.39b7cb94d40914bac590886981b0ed6e.r2.cloudflarestorage.com for the file |
| APKMirror | www.apkmirror.com for the feed and the pages of the newest releases, downloadr2.apkmirror.com for the icon. APKMirror offers no file to Tern |
| Tencent App Store | a.app.qq.com for the app, imtt.dd.qq.com or dd.myapp.com for the file, pp.myapp.com for the icon |
| Huawei AppGallery | store-dre.hispace.dbankcloud.com first for every app, whatever the region, then the host it names for yours: store-drcn or store-dra.hispace.dbankcloud.com, or store-drru.hispace.dbankcloud.ru; appdlc-*.hispace.dbankcloud.com and appdl-*.dbankcdn.com for the file; appimg-dra.dbankcdn.com or appimg-drcn.dbankcdn.com for the icon |
| Galaxy Store | vas.samsungapps.com, and a host under samsungapps.com for the file |
| vivo App Store | h5-api.appstore.vivo.com.cn for the app and for a search, appstore.vivo.com.cn for the download, which sends you to a host under vivo.com.cn for the file, imgwsdl.vivo.com.cn or appstoreimg-ipv6.vivo.com.cn for the icon |

A store serves a different file to different phones, so two of them are told
something about the device. The Galaxy Store is told the Android version and
whether the phone runs 64-bit code, and it is asked for the model SM-S948B
and the region DBT, MCC 425 and MNC 01. That model and region are the same for
every copy of Tern and say nothing about your phone, unless you type another
model or region into an app's page because the store only serves that app to
your model. Huawei AppGallery wants a device id: Tern makes up a random one for
each day's session, which names nothing. Tern never reads the phone's model,
its name or any identifier of it to tell a store. Tencent offers a 64-bit and a 32-bit file, and Tern picks one itself.

Where a source lists no size for a file, opening the app's page asks the file's
host for one byte of it, to learn the size.

With a hubproxy set for GitHub, the requests for GitHub go to that host
instead, with no token.

While the stores are off, an address under any of these domains is not read
at all, not even as a web page: apkpure.com, apkpure.net, winudf.com,
aptoide.com, apkcombo.com, apkmirror.com, sj.qq.com, a.app.qq.com, dd.qq.com,
myapp.com, appgallery.huawei.com, appgallery.cloud.huawei.com,
appgallery.huawei.ru, dbankcloud.com, dbankcloud.ru, dbankcdn.com,
galaxystore.samsung.com, apps.samsung.com, apps.samsung.cn,
galaxyappstore.com, samsungapps.com and vivo.com.cn.

Two tests keep these lists true. `PrivacyHostsTest` fails when the code gives
a store or an icon a host this page does not name, and `StoresLiveTest` fails
when a store, asked for a real app, reaches a host outside its domains.

The list of well known apps is part of Tern. Showing it asks nobody
anything. A request goes out when you press Look on one of them, to that app's
own address, the same as for a link you typed.

Until an app is installed, its row shows the icon its source offers, from the
hosts in the tables above; `core/.../icon/IconAddresses.kt` lists them. A
request for an icon follows a redirect only over HTTPS and carries no token
and no cookie, and an icon that was fetched is kept for seven days before it
is asked for again. The setting for icons from the source turns all of this
off, and rows then show a letter.

With a proxy set, every request goes through it, and host names are resolved
by the proxy. While the proxy cannot be reached, Tern reaches nothing. It
never goes round the proxy. With Orbot chosen, Tern says hello to the proxy at
Orbot's port on this device to learn whether Orbot is connected, and asks an
older Orbot to start. That stays on the device.

A stored access token is sent to the host it was stored for and to no other.

With a hubproxy set for GitHub, that host sees every request Tern makes to
GitHub and can change what comes back. It is never sent a token or a cookie.

Links to VirusTotal, a release page or a project page open in your browser,
under your browser's rules. "Read the project's page" on an app's page asks
the forge's API for the project's README, with the token stored for that host
if there is one, and only when you press it.

"Check with Verified Apps" hands the package name of an app and the certificate
Tern holds it to to Verified Apps or AppVerifier, another app on this device,
and only when you press it. With "Show new apps to Verified Apps first" on,
which it is not at first, the checked file of an app's first install goes to
that app too, read-only, before the installer gets it. Either one goes only to
an app signed with the certificate its makers publish, never to another app
that took the same name. Nothing goes over the network for that.

With another app chosen as the installer, Tern hands that app each file once
the file has passed its checks, and that app alone may read it. With Shizuku
or root chosen, the file goes to Android's package manager through them. With
Dhizuku chosen, the file goes to Android's package manager in a session that
Dhizuku holds for Tern. None of this leaves the device.

## Send from a phone

When you open "Send from a phone", Tern serves one small page on the local
network for ten minutes, or until you leave the screen. The page takes links
and one export file from a phone or a computer on the same network.

What is sent is sealed in the sender's browser with the code the device
shows. The code is in the QR code, or you type it, and it is never sent
itself. Somebody else on the network can see that something was sent, when,
and how large it was. They cannot read it, change it, or send something of
their own.

Somebody who can change the traffic on your network, and not only read it,
can put a page of their own in the place of Tern's and learn the code from
whoever uses it. No page served on a local address can prevent that. What
arrives is only ever a suggestion: it is shown on the device, and nothing is
added until you have looked at it there.

The page opens only on Wi-Fi or a cable, never on a mobile network and never
while the device is behind a VPN.

## What stays on the device

The list of apps you follow, their settings, the activity log, downloaded files
until they are installed, and any tokens, which are encrypted under a key in
the Android Keystore. None of it is included in Android's cloud backup or in a
device-to-device transfer.

Notifications name the apps they are about, and a lock screen can show them.
Turn off "Name the apps in notifications" in Settings and they only say how
many.

An export contains your app list and settings, and never tokens. On a device
without a file picker, such as a television, the export is written to
`Download/Tern/` on the shared storage, where other apps with access to your
files can read it. With automatic export on, the same file is written again to
the folder you picked whenever the list changes.

The widget and the Quick Settings tile show how many updates there are, and
the widget names none of the apps.

With "Keep Tern's own messages in the log" on, which it is not at first, the
activity log also keeps Tern's own warnings and errors, an error with its kind,
its message and at most five lines of Tern's code, and the start and end of
each check: what started it, how many apps, how many updates, how long. Before
anything is kept, addresses lose their query, their fragment and any name and
password, and tokens, passwords, keys, email addresses and the device's own
network address become "…". Such an entry can name apps, packages and the
addresses of sources, as the rest of the log does. At most 500 are kept, and
Clear removes them. Nothing of it leaves the device unless you share or copy
the log.

If Tern stops unexpectedly, it writes what it was doing, a technical report
with the version of Tern and of Android, into a file of its own. The next
start shows it once, to read, copy or share; closing it deletes the file.
Nothing is sent unless you share it.

On Android 10 to 12, the language chosen in Tern's settings is kept by Tern.
From Android 13 on, Android keeps it.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | To reach the sources, to know when the device is offline, and to find the local address for "Send from a phone" |
| Install packages, update without user action | To install and update apps |
| Delete packages | To open Android's uninstall dialog |
| Query all packages | To read the installed version and signer of the apps you follow, and to see whether Orbot and AppVerifier are installed |
| Enforce update ownership | For the optional update ownership setting |
| Notifications | To tell you about updates |
| Run at startup | To put the background check back after a reboot |
| Foreground service, data sync | To keep a download you started alive when you leave the app |
| Shizuku (`moe.shizuku.manager.permission.API_V23`) | To install through Shizuku, when you choose it as the installer. Shizuku asks you before it lets Tern in |
| Dhizuku (`com.rosan.dhizuku.permission.API`) | To install through Dhizuku, when you choose it as the installer. It lets Dhizuku list Tern among the apps it may let in; Dhizuku asks you before it does |

`tools/check-apk.sh` fails the build if the APK asks for anything that is not in
this table.
