# Releasing

A release is a signed APK in `dist/`, built from a clean tree, with its SHA-256
next to it.

## Once: the signing key

The key lives outside the repository and its password in the system keyring.
Losing the key means nobody who installed Jackdaw can ever be updated, so back
it up before the first release.

```sh
keytool -genkeypair -alias jackdaw-release -keystore ~/keys/jackdaw-release.jks \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Munzzyy"
secret-tool store --label "Jackdaw release keystore" service jackdaw-keystore key release
```

## Each release

1. Set `versionCode` and `versionName` in `app/build.gradle.kts`, add the entry
   to `CHANGELOG.md`, and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Run the device tests on an emulator: `./gradlew :app:connectedDebugAndroidTest`.
3. `bash tools/release.sh`. It runs the JVM tests, builds the release, runs
   `tools/check-apk.sh` on it, signs it with apksigner 34.0.0, and writes
   `dist/jackdaw-<version>.apk`, `dist/jackdaw.apk` and their `.sha256` files.
4. Install `dist/jackdaw-<version>.apk` on an emulator over the previous
   release and walk through adding, installing and updating an app.
5. Tag, push, and create the release with both APK files and both checksum files.

apksigner comes from build-tools 34.0.0 on purpose. F-Droid copies the
developer's signature onto its own reproducible build, and its tool rejects
signatures made by newer build-tools.
