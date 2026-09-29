#!/usr/bin/env bash
# Runs the engine's instrumented tests on the engine lane's emulator. Usage: engine-device-test.sh [TestClass[#method]] [extra -P args]
set -euo pipefail
root=$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-/home/cole/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-/home/cole/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5592}
filter=()
if [[ $# -gt 0 && -n $1 ]]; then
    filter=("-Pandroid.testInstrumentationRunnerArguments.class=io.github.munzzyy.jackdaw.enginetest.$1")
    shift
fi
cd "$root"
exec ./gradlew --console=plain :app:connectedDebugAndroidTest "${filter[@]}" "$@"
