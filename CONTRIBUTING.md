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
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
bash tools/check-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew :app:connectedDebugAndroidTest   # needs an emulator or a phone
```

A change to a parser or a check comes with a test that fails without it. Break
your fix on purpose once and watch the test fail; a test that cannot fail is
worse than none.

## A new source

Implement `Source` in `core`. A source fetches and describes releases, newest
first. It does not filter them and does not pick a file; the engine does that.
Use conditional requests, keep to HTTPS, and send a token only to the host it
was stored for. Fixtures are synthetic: learn the real response shape, then
write your own with invented names.

Sources that republish other people's apps are out of scope.

## Looking at the screens

A debug build runs the real engine. To get the stand-in with its invented apps:

```sh
adb shell run-as io.github.munzzyy.stamp.debug touch files/use-stand-in
adb shell am start -n io.github.munzzyy.stamp.debug/io.github.munzzyy.stamp.MainActivity --es scenario default
```

Other scenarios: `empty`, `firstrun`, `many`, `errors`, `offline`. Check a
changed screen at 200% text, in dark mode, and with a keyboard or D-pad.

## Words

Plain and short. Every string lives in `res/values`, as one whole sentence with
placeholders, never as fragments glued together.
