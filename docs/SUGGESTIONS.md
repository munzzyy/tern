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
- Where the list carries a certificate for the entry, the file names that
  certificate as its signer.

The line the test prints for an entry ends with the certificate the file names.

## The certificates the list carries

Stamp pins the certificate of an app at its first install and holds every later
file to it. That first file is taken on trust. For an entry of this list Stamp
can do better, because the list ships inside Stamp: where the list carries the
certificate, the first file is held to it too. A file signed by anyone else is
refused, with a sentence that says Stamp carries this app's certificate and the
file is signed with another.

A certificate is on the list only when two places name the same one:

1. The file at the entry's address, which is the developer's own place of
   release. The live test reads the signer the file names. Each file was also
   downloaded whole and checked with `apksigner verify --min-sdk-version 29
   --print-certs`, which ended with 0 and printed the same certificate.
2. A second place that does not depend on the first. Two were accepted:
   - F-Droid's signed index at `https://f-droid.org/repo`. F-Droid signs most
     apps with a key of its own. For some it builds the app from the source,
     finds the same file the developer published, and ships the developer's
     signature. Only then does its index name the developer's certificate.
     The second test of `SuggestionsLiveTest` prints what the index names.
   - The list of known apps inside AppVerifier,
     `app/src/main/kotlin/dev/soupslurpr/appverifier/InternalVerificationInfoDatabase.kt`
     in `github.com/soupslurpr/AppVerifier`, read at tree
     `d39e32effd1ac2ef9d93d9669cd8b5e2a21cf6da`. It names the places each
     certificate was seen at, and those are given below as it names them.

Read on 2026-09-29:

| App | Certificate, SHA-256 | Second place |
|---|---|---|
| Jellyfin for Android TV | `d881796ed2a67ff6ef9f676828723c6b1fa18e09388962cba4abc4a594a69131` | F-Droid's index, for org.jellyfin.androidtv 0.19.10 |
| Mullvad VPN | `7be21930c3b4d73906b08930450a1d3afbd22c98d9d8e987df8c1fbc2d0c90bb` | AppVerifier, seen at the app's website and GitHub |
| Proton VPN | `dcc9439ec1a6c6a8d0203f3423ee42bcc8b970628e53cb73a0393f398dd5b853` | AppVerifier, seen at the app's website, GitHub and Google Play |
| Fossify Gallery | `affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292` | F-Droid's index, for org.fossify.gallery 1.13.1 |
| Breezy Weather | `29d435f70aa9aec3c1faff7f7ffa6e15785088d87f06ecfcab9c3cc62dc269d8` | F-Droid's index, for org.breezyweather 6.2.2_freenet. AppVerifier names it too |
| F-Droid | `43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab` | AppVerifier, seen at F-Droid |
| HeliBoard | `5ec0a5313aa43558ee75b20b58ccd8194cdbf066df94a43ba288d933d60b86ce` | F-Droid's index, for helium314.keyboard 4.1 |
| Obtainium | `b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362` | F-Droid's index, for dev.imranr.obtainium.fdroid 1.6.17. The project's own README names it too |
| Aegis Authenticator | `c6db80a8e14e5230c1de8415ef820d13dc901d8fe33cf3acb57b6862d858a823` | AppVerifier, seen at GitHub and Google Play |
| KeePassDX | `7d55b8af210381aabf960f07e17cf7857b6d2a642ca2da6bf0bdf1b200362f04` | AppVerifier, seen at GitHub |
| Element X | `6a2fdc3148049ce0d5c6e85010723b83fb207d20c7477f5c22ac53c877e92d47` | F-Droid's index, for io.element.android.x 26.09.1. AppVerifier names it too |
| FairEmail | `e02067249f5a350e0ec703fe9df4dd682e0291a09f0c2e041050bbe7c064f5c9` | AppVerifier, seen at GitHub and Google Play |
| Organic Maps | `b9c7ae79a5a90270df08a132e536b9c666f5bef1f59b304fcecf8687865e4b5b` | AppVerifier, seen at GitHub |
| Magpie | `35d26c85cf963570aafda3dccce4d28fcb041f712cf15cd24ab8cc7d694be526` | F-Droid's index, for io.github.munzzyy.magpie 0.4.3 |
| Starling | `dbb0c491530f7475409c4c2953e9f68a52519c2f681dd9e5f69938f3bf9b8c9e` | F-Droid's index, for app.starlingmap 0.13.0 |

Three things to know about this table:

- AppVerifier's list is kept by people who look at the same places of release.
  It is a second pair of eyes at another time, not a second channel. A
  developer's account that was taken over before both looked would fool both.
- For F-Droid itself the two places are close: the file comes from F-Droid and
  AppVerifier saw the certificate at F-Droid.
- A project that changes its key and proves the descent of the new key from
  the old one in the file passes. A project that changes its key without that
  is refused until the list is corrected.

## Entries without a certificate

No second place was found for these, so the list carries no certificate for
them and their first file is taken on trust as before. The certificate the
developer's file names today is given so that the next search has something to
compare with. It is not in the app.

| App | The developer's file names | Looked at |
|---|---|---|
| Just Player | 3cb97dba57d3d0bc190bae5f1f3a841643bcd9399bc31028ce25775d93d0109d | F-Droid signs it with 0dd37a73...; not in AppVerifier; not in the README |
| mpv-android | fae7f9d02385cc24d96e88436603e23ea6aec7649ad1250abbb4837daab82fff | F-Droid signs it with 43509443...; not in AppVerifier; not in the README |
| Nova Video Player | 05af6804bcad9e7e6d22d2ffc39fca1857a4aeb4385097cad91b11e5bac1f615 | F-Droid signs it with dabea196...; not in AppVerifier; not in the README |
| Material Files | 873b9b60c77cf7f3cd5fae66d0fe112c4a86973e118ee8a29c346c4c673c97f0 | F-Droid signs it with 9c22a742...; not in AppVerifier; not in the README |
| Moonlight | d6ce3a4df15060fe488fe52441a549dee4ba199b36e2cb1ed9085cca8bfa1aca | F-Droid signs it with e781cffb...; not in AppVerifier; not in the README |
| KOReader | fd492a4fe001f17058b826da4358a1dceac38d26686f342522c5a497bcd4dccc | F-Droid signs it with 8c86266c...; not in AppVerifier; not in the README |
| Tusky | 9510eada78ea6eb0007b8a11888b1fcc3b67a51d31bc32e91f8516e3efe12fae | F-Droid signs it with e946901f...; not in AppVerifier; not in the README, not on tusky.app |
| OsmAnd | d192f4fffff2fae37f2821e4ca44f4cbe2483e7ffa24a8472043f685dd5bed27 | F-Droid signs net.osmand.plus with 38294eaa...; not in AppVerifier; not in the README |
| Sepia | ad9d5b2504baf0fc2e888f6bd08cd7a63a5342071b6d358b7d64d6a75d503a44 | F-Droid does not carry it |
| Sweep | 10319fc7dd916baa9e6e2e49a2323fa1b3ea395d50d36c4b966086e67b7e12e8 | F-Droid does not carry it |

Only the README at the head of each repository and the one site named were
read. A project may name its certificate somewhere else.

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
6. The line ends with the certificate the file names. Look for a second place
   that names the same one, as the section on certificates says. With one,
   add `.signedBy("<the certificate>", <the place>)` to the entry and a row to
   the table of certificates. Without one, add a row to the table of entries
   without a certificate. Never add a certificate that only the file names.

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
