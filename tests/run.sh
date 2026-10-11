#!/usr/bin/env bash
# Runs the end-to-end tests against the real thing: brings the stack up (tests/stack.sh up), then runs every
# suite, or the ones named.
#
#   tests/run.sh                          integrity, security and performance
#   tests/run.sh security                 one suite
#   tests/run.sh integrity performance    two
#   tests/run.sh integrity --tests '*RiderRide*'     anything after the suites goes to Gradle
#   PHONES=3 tests/run.sh integrity       with three emulators, so the tests with three apps run too
#   HEADLESS=0 tests/run.sh               show the emulators' windows
#
# The stack is left running afterwards, so the next run starts at once: stop it with tests/stack.sh down.
# Results are in tests/build/reports/tests, measurements and failure screenshots in tests/.run/report.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SUITES=()
while [ $# -gt 0 ]; do
  case "$1" in integrity|security|performance) SUITES+=("$1"); shift ;; *) break ;; esac
done
[ ${#SUITES[@]} -gt 0 ] || SUITES=(integrity security performance)

"$HERE/stack.sh" up
if [ -z "${JAVA_HOME:-}" ]; then
  for d in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64; do
    [ -x "$d/bin/java" ] && export JAVA_HOME="$d" && break
  done
fi
cd "$HERE"
# --continue: a failing suite does not stop the ones after it. The exit code still says something failed.
exec ../apps/android/gradlew --continue "${SUITES[@]}" "$@"
