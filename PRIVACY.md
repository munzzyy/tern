# Privacy

Tern has no server of its own and no account. It collects no analytics and
sends no crash reports, because there is nobody for it to report to.

## What leaves the device

Requests go to the sources you add, to check for releases and to download
files: GitHub, GitLab, Codeberg, F-Droid, a repository, a developer's site, or
a store such as APKPure or the Galaxy Store. Each of those sees what any web
server sees, which is your address, the time, and the project you asked about.
A search on the Add screen asks the places ticked under "Search in" for the
words you typed: GitHub, Codeberg, gitlab.com and F-Droid until you choose
others, and Aptoide, Uptodown, AppGallery, the vivo store or RuStore only when
you tick them.

A store serves a different file to different phones, so a few of them are told
what the file has to run on. APKPure, RuStore and the Galaxy Store are told
the Android version; RuStore also the processors, the screen density and
whether this is a TV; the Galaxy Store and Tencent whether the phone runs
64-bit code. None of them is told the phone's model, its name or any
identifier of it. The Galaxy Store is asked as a fixed model, which you can
change for an app, and RuStore as a made-up phone with an id drawn at random.

For an app from F-Droid's own repository, a check that finds a new answer also
reads the app's entry in F-Droid's data on gitlab.com, for its author and
changes, and the changelog file on GitHub or GitLab when that entry names one.
For an app from APKMirror it reads the pages of the newest releases on
www.apkmirror.com, for what changed and the size of the file. None of these
carries a token or a cookie.

The list of well known apps is part of Tern. Showing it asks nobody
anything. A request goes out when you press Look on one of them, to that app's
own address, the same as for a link you typed.

Until an app is installed, its row shows the icon its source offers. For an app
from GitHub that is a request to raw.githubusercontent.com, for the icon the
project keeps with its store listing, and where there is none a second one to
avatars.githubusercontent.com. For an app from GitLab, Codeberg, another
Forgejo or Gitea server, F-Droid, IzzyOnDroid or a repository in F-Droid's
format, the request goes to the host the app comes from, and follows a redirect
from there only over HTTPS. For an app from a store it goes to the store, or to
the one server the store keeps its pictures on, such as image.winudf.com for
APKPure; `core/.../icon/IconAddresses.kt` lists each of them. It carries no token and no cookie, and an icon that
was fetched is kept for seven days before it is asked for again. The setting
for icons from the source turns all of this off, and rows then show a letter.

An import from a link fetches the one address you typed, without a token.

With a proxy set, every request goes through it, and host names are resolved
by the proxy. While the proxy cannot be reached, Tern reaches nothing. It
never goes round the proxy. With Orbot chosen, Tern says hello to the proxy at
Orbot's port on this device to learn whether Orbot is connected, and asks an
older Orbot to start. That stays on the device.

A stored access token is sent to the host it was stored for and to no other.

Links to VirusTotal, a release page or a project page open in your browser,
under your browser's rules. "Read the project's page" on an app's page asks
the forge's API for the project's README, with the token stored for that host
if there is one, and only when you press it.

"Check with AppVerifier" hands the package name of an app and the certificate
Tern holds it to to AppVerifier, another app on this device, and only when you
press it. Nothing goes over the network for that.

With another app chosen as the installer, Tern hands that app each file once
the file has passed its checks, and that app alone may read it. With Shizuku
or root chosen, the file goes to Android's package manager through them. None
of this leaves the device.

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

`tools/check-apk.sh` fails the build if the APK asks for anything that is not in
this table.
