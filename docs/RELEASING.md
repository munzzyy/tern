# Releasing

A release is a signed APK in `dist/`, built from a clean tree, with its SHA-256
next to it.

## Once: the signing key

The key lives outside the repository and its password in the system keyring.
Losing the key means nobody who installed Tern can ever be updated, so back
it up before the first release.

```sh
keytool -genkeypair -alias tern-release -keystore ~/keys/tern-release.jks \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Munzzyy"
secret-tool store --label "Tern release keystore" service tern-keystore key release
```

## Each release

1. Set `versionCode` and `versionName` in `app/build.gradle.kts`, add the entry
   to `CHANGELOG.md`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Run the device tests on every image: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`,
   then `bash tools/device-suite.sh <serial>` for each. Gradle's own connected task is not used,
   because it drops the install permission that old Android only takes from the host, and the
   tests that install something then step aside without a word. The script names every test
   that was skipped.
3. `bash tools/check-reproducible.sh ~/.cache/tern-reproducible` on the commit to release.
4. `bash tools/release.sh`. It runs the JVM tests, builds the release, runs
   `tools/check-apk.sh` on it, signs it with apksigner 34.0.0, and writes
   `dist/tern-<version>.apk`, `dist/tern.apk` and their `.sha256` files.
5. Install `dist/tern-<version>.apk` on an emulator over the previous
   release and walk through adding, installing and updating an app.
6. Tag, push, and create the release with both APK files and both checksum files.

apksigner comes from build-tools 34.0.0 on purpose. F-Droid copies the
developer's signature onto its own reproducible build, and its tool rejects
signatures made by newer build-tools.

## The same file from the same source

Two builds of one commit give the same unsigned file, bit for bit.
`tools/check-reproducible.sh` proves it by building two fresh copies in two
folders of different names without the build cache, and CI runs it on every
push. On 2026-09-29 both builds of commit `be0688ad` had the SHA-256
`b1c55ab01309b974f756798b9ff4109a401e024268796bc03d3f45625f301e56`.

What this does not prove yet: that a build on another machine, with another
copy of the JDK and the Android build tools, gives the same file. F-Droid's
build server is the place where that is found out.

## What the build trusts

- The Gradle wrapper jar and the Gradle distribution are held to the hashes
  Gradle publishes (`tools/check-wrapper.sh`, `distributionSha256Sum`).
- Every dependency and every build plugin is held to a SHA-256 in
  `gradle/verification-metadata.xml`. A file with another hash stops the build.
  The hashes were taken from this machine's downloads on 2026-09-29, over
  HTTPS from Google's and Maven Central's servers. Signatures of the
  publishers are not checked.
- The CI actions are named by commit, not by tag.

To change a dependency: change the version, run
`./gradlew --write-verification-metadata sha256 :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :core:lint`,
and read the difference in `gradle/verification-metadata.xml` before committing
it. The entries for `aapt2` on macOS and Windows are not written by a build on
Linux and have to be added by hand, as they were the first time.
