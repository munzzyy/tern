# Stamp and Obtainium, point by point

Obtainium is the app that made this kind of tool normal, and a lot of people
depend on it. This page is about where the two differ, with evidence for both
sides, and it ends with what Obtainium does that Stamp does not.

Claims about Obtainium refer to version 1.6.18, commit `af286fa8` (2026-09-13),
and to its issue tracker as of 2026-09-28. Claims about Stamp name the test or
the measurement behind them. Where something was measured, it was measured on an
Android 16 emulator on 2026-09-29.

## Size and start

| | Obtainium | Stamp |
|---|---|---|
| Release APK, arm64 | 26,088,490 bytes (v1.6.17, `app-arm64-v8a-fdroid-release.apk`) | 4,205,357 bytes, all architectures, 29 languages |
| Toolkit | Flutter | Kotlin and Jetpack Compose |
| Cold start to first frame | not measured | 687 ms, the middle of three cold starts (`am start -W`, release build) |

Stamp's APK carries one native library, a 10 KB path helper that comes with
Compose, built for all four architectures. One file serves every device. The
translations are 1.1 MB of the total.

## Whether an update exists

Obtainium compares the release's version text with the installed app's version
name. Its maintainer describes mismatches between the two as a structural
problem (issue 946, and 920 for F-Droid). Tags such as `v1.2.3-fdroid` against a
version name of `1.2.3` are the usual cause of an update that never goes away.

Stamp decides by version code. It gets the code from a signed repository
index, or by reading the file's manifest off the server before download. For
`github.com/munzzyy/magpie` that cost 4 requests and 105,967 bytes of a
6,036,975 byte file. Version text is compared only for files that cannot be read
that way, and the row then says "Update likely".
Tests: `UpdateDecisionTest`, `RemoteInspectTest`, `LiveSmokeTest`.

## Whether an install happened

In the background, Obtainium writes the new version into its own database before
handing the file to the installer (`lib/providers/apps_provider_install.dart`,
lines 705 to 714, marked `TODO(#896)`). If Android then refuses the file, the app
still reads as updated. Issue 1550, open, 55 comments, reports exactly that.

Stamp never stores an installed version. It reads the package manager. An
install counts when `PackageInstaller` reports success and the package manager
then shows the expected version code. Device test: `HeadlineTest`, and the
refused-downgrade case in `GateTest`.

## What is checked before the installer sees a file

| Check | Obtainium | Stamp |
|---|---|---|
| Download integrity | Length compared with `Content-Length` (`apps_provider.dart`, lines 436 to 447); no hash | SHA-256 of every download, compared with the publisher's checksum when one exists |
| Signing certificate | Compared with the installed app, and with hashes the user pasted in by hand (issue 255) | Compared with the installed app and with a pin that is set by itself at first install; both must agree |
| Read before download | No | Package, version code and certificate, shown as claims |
| Two parsers | No | Stamp's and Android's must agree on package and version code |
| Splits in a bundle | Not applicable, XAPK is open as issue 682 | Every part held to the base's signer |

Device tests: `GateTest`, `TamperTest`, `BundleSignerTest`.

## The network

| | Obtainium | Stamp |
|---|---|---|
| Cleartext HTTP | Allowed (`AndroidManifest.xml`, line 10, `usesCleartextTraffic="true"`) | Refused in the manifest, the network security configuration and the client |
| Conditional requests | None: `etag`, `if-none-match` and `304` do not occur in its sources | ETag and Last-Modified on every source |
| GitHub without a token | One API call per app per check, against a limit of 60 an hour | The release feed first; the API only when the feed changed |
| A token and redirects | Issue 2423 reported a GitHub token reaching Codeberg | Stored per exact host, dropped for the rest of a redirect chain once the host changes (`NetTest`) |
| Third-party F-Droid repositories | Index fetched and decoded whole per app per check; the repository signature is not checked (`lib/app_sources/fdroidrepo.dart`) | `entry.jar` verified, signer pinned, an older index refused, diffs applied, one download for all apps of a repository |
| Proxy | Requested in issue 121 | SOCKS, with names resolved by the proxy: a capture showed no DNS packets leaving the device |

## Background checks

Obtainium registers a WorkManager task every 15 minutes with a network
constraint (`lib/main.dart`, lines 212 to 221) and decides inside the task
whether a check is due. Issues 25, 608 and 2199 are about checks that do not run.

Stamp registers one persisted periodic job with Android's job scheduler at the
interval the user chose, with the constraints the user chose, and puts it back on
boot, after its own update and at every start. Device test: `JobTest`.

Measured on the release build: with Stamp's process dead, a forced run of the
job took an app from 0.4.0 to 0.4.4 in 5 seconds with the launcher in front
and no prompt, and posted "Magpie was updated". After a force stop Android
drops the job, as it does for every app; opening Stamp registered it again
and the job ran at once.

A check and an install that finishes can evaluate the same app at the same
moment. They take turns, so the row cannot be left on the older of the two
states. `EvaluationOrderTest` runs them against each other 2000 times.

Not yet observed: behaviour in Doze over many hours, and after a reboot.

## Asked for in Obtainium's tracker, present in Stamp

| Request | Issue | In Stamp |
|---|---|---|
| Android TV | 281, 46 upvotes | Run on an Android TV 14 emulator with remote keys only, release build: add an app, allow installs, install, settings, the activity log. Device tests: `RemoteAddTest`, `RemoteWalkTest` |
| GitHub Actions builds | 102 | Yes, with a token |
| Share a link into the app | 109 | Yes |
| Work out the source from the link | 1108 | Yes |
| XAPK and split APKs | 682, 1056 | Yes, installed as one session |
| Keep the file when an install fails | 1978 | Yes |
| Older versions | 2934 | The version history installs any listed release; going back needs the app removed first, which Android requires |
| Update ownership | 2078 | A setting, off by default. Not yet tested |
| VirusTotal | 462 | A link to the file's page there, by its SHA-256. Nothing is uploaded |

## What Obtainium does that Stamp does not

- More sources. Obtainium reads app stores and mirror sites: APKPure, Aptoide,
  Uptodown, APKCombo, APKMirror (tracking only), Huawei AppGallery, Samsung
  Galaxy Store, RuStore, the vivo and Tencent stores, CoolApk, itch.io and
  others. Stamp reads the developer's own channels and leaves mirrors out.
- Shizuku and root installers, which make first installs silent and bring silent
  updates to Android 10 and 11.
- Translations made by people. Obtainium has 29 languages from its users.
  Stamp has 28 made by a machine and checked by a second one, which no native
  speaker has reviewed.
- Years of use on real phones, a wiki, a video guide, and a directory of
  crowdsourced app configurations.
- A web view of the app's page inside the app.
