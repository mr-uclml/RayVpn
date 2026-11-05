#!/usr/bin/env bash
# Runs the server-selection checks on a desktop JVM.
#
# The selection pipeline is plain Java: deduplication, cache ranking, the parallel TCP
# sweep and the order in which servers are tried can all be exercised without a phone.
# The tests are compiled against the classes of a previous `tools/build.sh` run, so build
# once, then:
#
#   bash tools/selftest/run.sh
#
# Environment (same names as tools/build.sh, with the same defaults):
#   JAVA, JAVAC_JAR, ANDROID_JAR
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-$ROOT/build}"
JAVA="${JAVA:-java}"
JAVAC_JAR="${JAVAC_JAR:-}"
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-34/android.jar}"

[ -f "$ANDROID_JAR" ] || { echo "ANDROID_JAR not found: $ANDROID_JAR"; exit 1; }
[ -f "$OUT/libv2ray.jar" ] || { echo "run tools/build.sh first ($OUT/libv2ray.jar is missing)"; exit 1; }
[ -d "$OUT/classes" ] || { echo "run tools/build.sh first ($OUT/classes is missing)"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -n "$JAVAC_JAR" ]; then
    JAVAC=("$JAVA" -cp "$JAVAC_JAR" com.sun.tools.javac.Main)
else
    JAVAC=(javac)
fi

echo "▸ compiling the checks"
"${JAVAC[@]}" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" -cp "$OUT/classes:$OUT/libv2ray.jar" \
    -d "$WORK" $(find "$ROOT/tools/selftest" -name '*.java')

status=0
for test in LogicTest SweepTest CacheTest OrderTest; do
    echo
    echo "▸ $test"
    if ! "$JAVA" -cp "$WORK:$OUT/classes:$OUT/libv2ray.jar:$ANDROID_JAR" \
            "app.rayvpn.client.$test"; then
        status=1
    fi
done

echo
if [ "$status" -eq 0 ]; then
    echo "all checks passed"
else
    echo "some checks failed"
fi
exit "$status"
