# Privacy

Tern has no server of its own and no account. It collects no analytics and
sends no crash reports, because there is nobody for it to report to.

## What leaves the device

Requests go to the sources you add, to check for releases and to download
files: GitHub, GitLab, Codeberg, F-Droid, a repository, a developer's site. Each
of those sees what any web server sees, which is your address, the time, and
the project you asked about. A search on the Add screen asks GitHub, Codeberg
and gitlab.com for the words you typed.

The list of well known apps is part of Tern. Showing it asks nobody
anything. A request goes out when you press Look on one of them, to that app's
own address, the same as for a link you typed.

Until an app is installed, its row shows the icon its source offers. For an app
from GitHub that is a request to raw.githubusercontent.com, for the icon the
project keeps with its store listing, and where there is none a second one to
avatars.githubusercontent.com. For an app from GitLab, Codeberg, another
Forgejo or Gitea server, F-Droid, IzzyOnDroid or a repository in F-Droid's
format, the request goes to the host the app comes from, and follows a redirect
from there only over HTTPS. It carries no token and no cookie, and an icon that
was fetched is kept for seven days before it is asked for again. The setting
for icons from the source turns all of this off, and rows then show a letter.

An import from a link fetches the one address you typed, without a token.

With a proxy set, every request goes through it, and host names are resolved
by the proxy. While the proxy cannot be reached, Tern reaches nothing. It
never goes round the proxy. With Orbot chosen, Tern asks Orbot on this device
to start and to say how it is doing. That stays on the device.

A stored access token is sent to the host it was stored for and to no other.

Links to VirusTotal, a release page or a project page open in your browser,
under your browser's rules.

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

An export contains your app list and settings, and never tokens. On a device
without a file picker, such as a television, the export is written to
`Download/Tern/` on the shared storage, where other apps with access to your
files can read it.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | To reach the sources, to know when the device is offline, and to find the local address for "Send from a phone" |
| Install packages, update without user action | To install and update apps |
| Delete packages | To open Android's uninstall dialog |
| Query all packages | To read the installed version and signer of the apps you follow, and to see whether Orbot is installed |
| Enforce update ownership | For the optional update ownership setting |
| Notifications | To tell you about updates |
| Run at startup | To put the background check back after a reboot |
| Foreground service, data sync | To keep a download you started alive when you leave the app |

`tools/check-apk.sh` fails the build if the APK asks for anything that is not in
this table.
