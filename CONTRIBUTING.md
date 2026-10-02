# Contributing

Bug reports, fixes and new sources are welcome.

## Layout

`core` is plain Kotlin with no Android and no dependencies: parsers, sources, the
update decision. Everything in it is tested on the JVM. `app` is the Android
client: the engine under `engine/real`, `install`, `work`, `data` and `net`, and
the screens under `ui`. The screens talk to the engine through one interface,
`engine/Engine.kt`, and a stand-in engine with invented apps lets them be built
and tested without a network.

## Before you open a pull request

```sh
export ANDROID_HOME=~/Android/Sdk
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease
bash tools/check-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
bash tools/device-suite.sh <serial>   # needs an emulator or a phone
```

Run the device tests with `tools/device-suite.sh` and not with Gradle's connected
task. The connected task drops a permission that Android 10 and 11 only take from
the host, and the tests that install something then step aside without a word.
The script names every test that was skipped and why.

CI runs the engine's device tests on an Android 16 emulator with
`bash tools/ci-emulator.sh`, which boots a throwaway emulator and works the same
on your machine. The screen tests and the other Android images stay manual.

A change to a parser or a check comes with a test that fails without it. Break
your fix on purpose once and watch the test fail; a test that cannot fail is
worse than none.

## A new source

Implement `Source` in `core`. A source fetches and describes releases, newest
first. It does not filter them and does not pick a file; the engine does that.
Use conditional requests, keep to HTTPS, and send a token only to the host it
was stored for. Fixtures are synthetic: learn the real response shape, then
write your own with invented names.

Add one real app of the new source to `LiveSourcesTest` and run it on a device:

```sh
bash tools/device-suite.sh <serial> -e live true -e class io.github.munzzyy.tern.enginetest.LiveSourcesTest
```

The JVM tests cannot stand in for this. Android compiles patterns with ICU, and a
pattern Java takes can stop Tern on start on every device.

A store that serves its own copies of other people's apps goes behind the
Third-party stores setting, which starts off. Sites that offer modified apps,
and stores that can only be read by pretending to be their own app, are out of
scope.

## Looking at the screens

A debug build runs the real engine. To get the stand-in with its invented apps:

```sh
adb shell run-as io.github.munzzyy.tern.debug touch files/use-stand-in
adb shell am start -n io.github.munzzyy.tern.debug/io.github.munzzyy.tern.MainActivity --es scenario default
```

Other scenarios: `empty`, `firstrun`, `many`, `errors`, `offline`. Check a
changed screen at 200% text, in dark mode, and with a keyboard or D-pad.

## Words

Plain and short. Every string lives in `res/values`, as one whole sentence with
placeholders, never as fragments glued together.
