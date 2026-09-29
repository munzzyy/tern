# The starter list

A new install of Stamp shows an empty list and a field that wants a link. The
starter list is a short set of well known apps that can be added with one press
instead. It matters most on a television, where typing a link with a remote is
slow.

The list is built into the app. It is never fetched, so showing it needs no
network and tells nobody anything. An entry is only an address: pressing it does
what pasting that address would do, and every check Stamp makes on a file is
made on these files too.

| What | Where |
|---|---|
| The entries | `core/src/main/kotlin/io/github/munzzyy/stamp/core/suggest/Catalog.kt` |
| The summary lines | `app/src/main/res/values/strings_suggest.xml` |
| Which line belongs to which entry | `app/src/main/kotlin/io/github/munzzyy/stamp/engine/real/Suggestions.kt` |
| The rules as tests | `CatalogTest` in core, `SuggestionsTest` in app |
| The proof against the real services | `SuggestionsLiveTest` in core |

## What may be on the list

1. The app is open source, and one of these holds: F-Droid's main repository
   carries it, a well known organisation publishes it, or it is by the author
   of Stamp. Every entry names which.
2. A client that exists to remove the advertising from a video site is not
   listed, however popular it is. Neither is an app whose source is closed.
   Anyone can still add such an app by link or by search.
3. The address is the developer's own place of release: their repository on a
   forge, their own repository in F-Droid's format, or their own download
   page. Never a mirror and never a repack.
4. The live test proves the entry. What it asks is in the next section.
5. The list stays short: 20 to 28 entries, 6 to 10 of them at home on a
   television.
6. The summary line has at most 50 characters, says what the app is for, and
   praises nothing.
7. Apps by the author of Stamp come last in their kind, and their summary
   line ends in "by Stamp's author".

The order is: on a television the television entries first, then by kind, then
by name.

## What the live test asks

For each entry the test does what the app does with a link. It detects the
address, reads the releases, picks the release and the file that a newly added
app would be offered, and reads that file's manifest off the server without
downloading the file. The entry is proven when all of this holds:

- Detection stores the address exactly as the list writes it, and the source
  does not report that the project has moved.
- The file installs on a phone that runs 64-bit code only (arm64-v8a, Android
  16). Current phones are like that, and a file built for 32-bit only fails on
  them after the download.
- A television entry also installs on a 32-bit television (armeabi-v7a) with
  Android 10, the oldest Android that Stamp runs on, and its manifest declares
  `android.software.leanback`.
- The file's application id is the one the entry names.
- The file is not a debug build, and neither its name nor its version reads as
  a pre-release.
- F-Droid's main repository carries the id the entry names as its ground.

## Adding an app

1. Find the developer's own address by reading the project's own site or
   repository. Check the licence.
2. Add one line to `Catalog.kt`, a string named `suggest_<key>` to
   `strings_suggest.xml`, and the pair of the two to the table in
   `Suggestions.kt`.
3. Run the unit tests. They say what was forgotten:
   `./gradlew :core:test --tests '*CatalogTest' :app:testDebugUnitTest --tests '*SuggestionsTest'`
4. Run the live test for the new entry alone:
   `STAMP_SUGGEST="Name of the app" ./gradlew :core:cleanTest :core:test --tests '*SuggestionsLiveTest' -Dstamp.live=true`
5. Put the line it prints into the commit message.

Without `STAMP_SUGGEST` the live test runs over the whole list. It sends no
credentials, and GitHub answers 60 such requests an hour from one address. A
full run costs one of them for each entry that lives on GitHub.

The translations of a new line are made later, with the other strings.

## Taking an app off

An entry comes off when it fails the live test for a reason that will not go
away by itself: the project moved or ended, it stopped publishing files, it
closed its source, or F-Droid dropped it. A server that is down for a day is no
such reason. An entry also comes off when its developer asks.

Delete its line in `Catalog.kt`, its string, and its pair in `Suggestions.kt`.
The unit tests fail until all three are gone.

Nothing changes for people who already added the app. Stamp keeps following it
like any other app they added by link.

## The list on 2026-09-29

| App | Address | Id of the file | Ground |
|---|---|---|---|
| Jellyfin for Android TV | github.com/jellyfin/jellyfin-androidtv | org.jellyfin.androidtv | F-Droid |
| Just Player | github.com/moneytoo/Player | com.brouken.player | F-Droid |
| mpv-android | github.com/mpv-android/mpv-android | is.xyz.mpv | F-Droid |
| Nova Video Player | github.com/nova-video-player/aos-AVP | org.courville.nova | F-Droid |
| Material Files | github.com/zhanghai/MaterialFiles | me.zhanghai.android.files | F-Droid |
| Mullvad VPN | github.com/mullvad/mullvadvpn-app | net.mullvad.mullvadvpn | F-Droid |
| Proton VPN | github.com/ProtonVPN/android-app | ch.protonvpn.android | F-Droid |
| Moonlight | github.com/moonlight-stream/moonlight-android | com.limelight | F-Droid |
| Fossify Gallery | github.com/FossifyOrg/Gallery | org.fossify.gallery | F-Droid |
| Breezy Weather | github.com/breezy-weather/breezy-weather | org.breezyweather | F-Droid |
| F-Droid | f-droid.org/packages/org.fdroid.fdroid | org.fdroid.fdroid | F-Droid |
| HeliBoard | github.com/HeliBorg/HeliBoard | helium314.keyboard | F-Droid |
| Obtainium | github.com/ImranR98/Obtainium | dev.imranr.obtainium.fdroid | F-Droid |
| Aegis Authenticator | github.com/beemdevelopment/Aegis | com.beemdevelopment.aegis | F-Droid |
| KeePassDX | github.com/Kunzisoft/KeePassDX | com.kunzisoft.keepass.free | F-Droid, as com.kunzisoft.keepass.libre |
| KOReader | github.com/koreader/koreader | org.koreader.launcher | F-Droid, as org.koreader.launcher.fdroid |
| Element X | github.com/element-hq/element-x-android | io.element.android.x | F-Droid |
| FairEmail | github.com/M66B/FairEmail | eu.faircode.email | F-Droid |
| Tusky | codeberg.org/tusky/Tusky | com.keylesspalace.tusky | F-Droid |
| Organic Maps | github.com/organicmaps/organicmaps | app.organicmaps.web | F-Droid, as app.organicmaps |
| OsmAnd | download.osmand.net/releases/ | net.osmand | F-Droid, as net.osmand.plus |
| Magpie | github.com/munzzyy/magpie | io.github.munzzyy.magpie | the author's own |
| Sepia | github.com/munzzyy/sepia | io.github.munzzyy.sepia | the author's own |
| Sweep | github.com/munzzyy/sweep | io.github.munzzyy.sweep | the author's own |
| Starling | github.com/munzzyy/starling | app.starlingmap | the author's own |

The first eight are the television entries.

## Checked on 2026-09-29 and not listed

These did not pass:

| App | Why |
|---|---|
| Kodi | The files are in a download directory, `mirrors.kodi.tv/releases/android/arm/`. It holds betas with names like `kodi-22.0-Piers_beta1-armeabi-v7a.apk`. Stamp's page reader takes 22.0 from that name and misses the stage, so it offers beta 1 as the newest stable release. The directory holds the 32-bit files only, and each download is handed to a mirror. |
| VLC | `get.videolan.org` hands each download to a mirror. One request in six was sent to a plain HTTP address, which Stamp refuses. |
| LocalSend | Its files are named `arm32v7` and `arm64v8`. Stamp's file picker does not read those as processors, so a 64-bit phone is offered the 32-bit file. |
| Termux | The files it publishes on GitHub are debug builds. |
| AntennaPod | Its releases on GitHub carry no files. |
| Findroid | Does not declare leanback, so it is no television entry. |

These pass today, and the address is the one to use if one of them is added
later. Bitwarden and Thunderbird should wait until an entry can carry a filter
for the release tag.

| App | Address | Note |
|---|---|---|
| Bitwarden | github.com/bitwarden/android | One repository publishes the password manager and the authenticator under the same version numbers. Which one is offered depends on the order GitHub lists them in. |
| Thunderbird | github.com/thunderbird/thunderbird-android | The same, with K-9 Mail. |
| Syncthing-Fork | github.com/researchxxl/syncthing-android | The repository moved here from its first owner. |
| WireGuard | download.wireguard.com/android-client/ | Published by the WireGuard project. F-Droid does not carry it under this id. |
| Catima | github.com/CatimaLoyalty/Android | |
| Findroid | github.com/jarnedemeulemeester/findroid | As a phone entry. |
| Fossify Calendar | github.com/FossifyOrg/Calendar | |
| Immich | github.com/immich-app/immich | |
| Librera | github.com/foobnix/LibreraReader | |
| Nextcloud | github.com/nextcloud/android | The file offered is the one built for Google Play, which has no 32-bit code. |
