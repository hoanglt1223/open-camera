#!/usr/bin/env bash
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds and runs the macrobenchmarks on a connected phone and stores the JSON results in
# benchmarks/<UTC timestamp>/, then compares them with the previous run (scripts/bench/compare.py).
#
#   scripts/bench/run.sh                 all benchmarks
#   scripts/bench/run.sh --test openPro  one benchmark method (repeatable)
#   scripts/bench/run.sh --profile       regenerate app/src/main/baseline-prof.txt instead
#
# Options: --no-build (reuse the APKs), --iterations N (default 5), --out DIR, --traces (keep
# the Perfetto traces next to the JSON). ADB selects the adb wrapper (default: adb), e.g.
# ADB=~/adb-gauguin.sh scripts/bench/run.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ADB="${ADB:-adb}"
APP_PKG=dev.opencamera.app.bench
TEST_PKG=dev.opencamera.macrobenchmark
RUNNER=androidx.test.runner.AndroidJUnitRunner
DEVICE_OUT=/sdcard/Android/media/$TEST_PKG

build=1 profile=0 traces=0 iterations=5 out="" tests=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) build=0 ;;
    --profile) profile=1 ;;
    --traces) traces=1 ;;
    --iterations) iterations="$2"; shift ;;
    --out) out="$2"; shift ;;
    --test) tests+=("$2"); shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

adb_() { "$ADB" "$@"; }

cleanup() {
  adb_ shell am force-stop "$APP_PKG" >/dev/null 2>&1 || true
  adb_ shell am force-stop "$TEST_PKG" >/dev/null 2>&1 || true
  adb_ shell input keyevent 3 >/dev/null 2>&1 || true
}
trap cleanup EXIT

cd "$ROOT"
if [ "$build" = 1 ]; then
  # Profile rules must use source names, so the generator runs on an unminified build.
  minify=()
  [ "$profile" = 1 ] && minify=(-PopenCamera.benchNoMinify=true)
  ./gradlew -q "${minify[@]}" :app:assembleBenchmark :macrobenchmark:assembleBenchmark
fi
APP_APK="$ROOT/app/build/outputs/apk/benchmark/app-benchmark.apk"
TEST_APK="$ROOT/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk"

adb_ install -r "$APP_APK" >/dev/null
adb_ install -r "$TEST_APK" >/dev/null
for p in CAMERA RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_MEDIA_IMAGES READ_MEDIA_VIDEO POST_NOTIFICATIONS; do
  adb_ shell pm grant "$APP_PKG" "android.permission.$p" >/dev/null 2>&1 || true
done

if [ "$profile" = 0 ]; then
  # Same state as a user after dexopt: the APK's baseline profile installed, then speed-profile.
  adb_ shell am start -W -n "$APP_PKG/dev.opencamera.app.CameraActivity" >/dev/null
  sleep 5
  adb_ shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
    "$APP_PKG/androidx.profileinstaller.ProfileInstallReceiver" >/dev/null
  sleep 2
  adb_ shell am force-stop "$APP_PKG"
  adb_ shell cmd package compile -m speed-profile -f "$APP_PKG" >/dev/null
fi

adb_ shell rm -rf "$DEVICE_OUT" >/dev/null 2>&1 || true
if [ "$profile" = 1 ]; then
  class="$TEST_PKG.BaselineProfileGenerator"
elif [ ${#tests[@]} -gt 0 ]; then
  class=$(printf "$TEST_PKG.CameraBenchmarks#%s," "${tests[@]}"); class=${class%,}
else
  class="$TEST_PKG.CameraBenchmarks"
fi
log=$(mktemp)
adb_ shell am instrument -w -r \
  -e class "$class" \
  -e iterations "$iterations" \
  -e targetPackage "$APP_PKG" \
  "$TEST_PKG/$RUNNER" | tee "$log"
status=0
grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$log" && status=1

files=$(adb_ shell "ls $DEVICE_OUT 2>/dev/null" | tr -d '\r')
if [ "$profile" = 1 ]; then
  prof=$(echo "$files" | grep -- '-baseline-prof.txt$' | grep -v startup | head -1)
  [ -n "$prof" ] || { echo "no baseline profile produced" >&2; exit 1; }
  tmp=$(mktemp)
  adb_ pull "$DEVICE_OUT/$prof" "$tmp" >/dev/null
  python3 "$ROOT/scripts/bench/merge-profile.py" "$tmp" "$ROOT/app/src/main/baseline-prof.txt"
  rm -f "$tmp"
  exit $status
fi

out="${out:-$ROOT/benchmarks/$(date -u +%Y%m%d-%H%M)}"
mkdir -p "$out"
for f in $files; do
  case "$f" in
    *.json) adb_ pull "$DEVICE_OUT/$f" "$out/" >/dev/null ;;
    *.perfetto-trace) [ "$traces" = 1 ] && { mkdir -p "$out/traces"; adb_ pull "$DEVICE_OUT/$f" "$out/traces/" >/dev/null; } ;;
  esac
done
cp "$log" "$out/instrument.log"
echo "results: $out"
python3 "$ROOT/scripts/bench/compare.py" "$out" || status=$?
exit $status
