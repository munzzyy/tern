# Privacy

Jackdaw has no server of its own and no account. It collects no analytics and
sends no crash reports, because there is nobody for it to report to.

## What leaves the phone

Requests go to the sources you add, to check for releases and to download
files: GitHub, GitLab, Codeberg, F-Droid, a repository, a developer's site. Each
of those sees what any web server sees, which is your address, the time, and
the project you asked about. A search on the Add screen asks GitHub, Codeberg
and gitlab.com for the words you typed.

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
