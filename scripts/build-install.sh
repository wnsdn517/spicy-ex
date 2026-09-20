#!/usr/bin/env bash
# Build a debug flavor and install it to the connected device in one step, with clean output.
#
# Usage: scripts/build-install.sh [full|lite] [--no-install] [--test]
#   full|lite     Which flavor to build (default: full).
#   --no-install  Build only; skip adb install/force-stop.
#   --test        Run the flavor's unit tests instead of assembling/installing.
#
# Requires JAVA_HOME to point at a JDK 17 install (AGP 8.8.2 / Gradle 8.14.2 target). Override by
# exporting JAVA_HOME before calling this script; otherwise it falls back to the common Windows
# Temurin 17 path.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

FLAVOR="full"
DO_INSTALL=1
RUN_TESTS=0
for arg in "$@"; do
  case "$arg" in
    full|lite) FLAVOR="$arg" ;;
    --no-install) DO_INSTALL=0 ;;
    --test) RUN_TESTS=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

: "${JAVA_HOME:=/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
export JAVA_HOME
if [ ! -x "$JAVA_HOME/bin/java" ] && [ ! -x "$JAVA_HOME/bin/java.exe" ]; then
  echo "ERROR: JAVA_HOME does not point at a JDK ($JAVA_HOME)." >&2
  echo "Set JAVA_HOME to a JDK 17 install and retry." >&2
  exit 1
fi

FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<<"${FLAVOR:0:1}")${FLAVOR:1}"

if [ "$RUN_TESTS" -eq 1 ]; then
  TEST_TASK=":app:test${FLAVOR_CAP}DebugUnitTest"
  echo "==> Running $FLAVOR debug unit tests ($TEST_TASK)"
  LOG="$(mktemp)"
  if ! ./gradlew "$TEST_TASK" --console=plain >"$LOG" 2>&1; then
    echo "TESTS FAILED. Last 60 lines:" >&2
    tail -60 "$LOG" >&2
    rm -f "$LOG"
    exit 1
  fi
  rm -f "$LOG"
  echo "==> Tests passed."
  exit 0
fi

TASK=":app:assemble${FLAVOR_CAP}Debug"
APK="app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"

echo "==> Building $FLAVOR debug ($TASK)"
LOG="$(mktemp)"
if ! ./gradlew "$TASK" --console=plain >"$LOG" 2>&1; then
  echo "BUILD FAILED. Last 60 lines:" >&2
  tail -60 "$LOG" >&2
  rm -f "$LOG"
  exit 1
fi
rm -f "$LOG"
echo "==> Build succeeded: $APK"

if [ "$DO_INSTALL" -eq 0 ]; then
  exit 0
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH; cannot install. Build artifact is ready at $APK." >&2
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "ERROR: no adb device/emulator connected (adb get-state failed)." >&2
  echo "Build artifact is ready at $APK - install manually once a device is connected." >&2
  exit 1
fi

echo "==> Installing to device"
INSTALL_LOG="$(mktemp)"
if ! adb install -r "$APK" >"$INSTALL_LOG" 2>&1; then
  echo "INSTALL FAILED:" >&2
  cat "$INSTALL_LOG" >&2
  rm -f "$INSTALL_LOG"
  exit 1
fi
grep -q "^Success" "$INSTALL_LOG" && echo "==> Installed" || { echo "INSTALL did not report Success:" >&2; cat "$INSTALL_LOG" >&2; rm -f "$INSTALL_LOG"; exit 1; }
rm -f "$INSTALL_LOG"

echo "==> Force-stopping Spotify so the module reloads fresh"
adb shell am force-stop com.spotify.music || echo "WARNING: force-stop failed (non-fatal)" >&2

echo "==> Done."
