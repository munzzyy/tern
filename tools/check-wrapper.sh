#!/usr/bin/env bash
# Fails when the Gradle wrapper in the repository is not the one Gradle published.
#
# The wrapper is a jar that runs before anything else does, and nobody reads a jar in review.
# The two hashes are the ones at services.gradle.org/distributions/ for the version named in
# gradle/wrapper/gradle-wrapper.properties. A new Gradle version changes all three together.
set -euo pipefail
cd "$(dirname "$0")/.."

version="9.7.1"
jar="7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
distribution="acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
properties=gradle/wrapper/gradle-wrapper.properties
fail=0

if [ "$(sha256sum gradle/wrapper/gradle-wrapper.jar | cut -d' ' -f1)" = "$jar" ]; then
  echo "ok   gradle-wrapper.jar is the published one for $version"
else
  echo "FAIL gradle-wrapper.jar is not the published one for $version"; fail=1
fi
if grep -qx "distributionUrl=https\\\\://services.gradle.org/distributions/gradle-$version-bin.zip" "$properties"; then
  echo "ok   the distribution comes from services.gradle.org, version $version"
else
  echo "FAIL the distribution address is not the expected one"; fail=1
fi
if grep -qx "distributionSha256Sum=$distribution" "$properties"; then
  echo "ok   the distribution is held to its published hash"
else
  echo "FAIL the distribution is not held to its published hash"; fail=1
fi
exit $fail
