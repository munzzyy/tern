#!/usr/bin/env bash
# Runs the engine's instrumented tests on the engine lane's emulator. Usage: engine-device-test.sh [TestClass[#method]] [extra -P args]
set -euo pipefail
root=$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5592}
filter=()
if [[ $# -gt 0 ]]; then
    [[ -n $1 ]] && filter=("-Pandroid.testInstrumentationRunnerArguments.class=io.github.munzzyy.tern.enginetest.$1")
    shift
fi
cd "$root"
exec ./gradlew --console=plain :app:connectedDebugAndroidTest "${filter[@]}" "$@"
