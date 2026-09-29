# Privacy

Stamp has no server of its own and no account. It collects no analytics and
sends no crash reports, because there is nobody for it to report to.

## What leaves the phone

Requests go to the sources you add, to check for releases and to download
files: GitHub, GitLab, Codeberg, F-Droid, a repository, a developer's site. Each
of those sees what any web server sees, which is your address, the time, and
the project you asked about. A search on the Add screen asks GitHub, Codeberg
and gitlab.com for the words you typed.

Until an app is installed, its row shows the icon its source offers. For an app
from GitHub that is a request to raw.githubusercontent.com, for the icon the
project keeps with its store listing, and where there is none a second one to
avatars.githubusercontent.com. For an app from GitLab, Codeberg, another
Forgejo or Gitea server, F-Droid, IzzyOnDroid or a repository in F-Droid's
format, the request goes to the host the app comes from, and follows a redirect
from there only over HTTPS. It carries no token and no cookie, and an icon that
was fetched is kept for seven days before it is asked for again. The setting
for icons from the source turns all of this off, and rows then show a letter.

With a proxy set, those requests go through it, and host names are resolved by
the proxy.

A stored access token is sent to the host it was stored for and to no other.

Links to VirusTotal, a release page or a project page open in your browser,
under your browser's rules.

## What stays on the phone

The list of apps you follow, their settings, the activity log, downloaded files
until they are installed, and any tokens, which are encrypted under a key in
the Android Keystore. None of it is included in Android's cloud backup or in a
device-to-device transfer. An export contains your app list and settings, and
never tokens.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | To reach the sources and to know when the phone is offline |
| Install packages, update without user action | To install and update apps |
| Delete packages | To open Android's uninstall dialog |
| Query all packages | To read the installed version and signer of the apps you follow |
| Enforce update ownership | For the optional update ownership setting |
| Notifications | To tell you about updates |
| Run at startup | To put the background check back after a reboot |
| Foreground service, data sync | To keep a download you started alive when you leave the app |

`tools/check-apk.sh` fails the build if the APK asks for anything that is not in
this table.
