#!/usr/bin/env bash
# Build the debug APK and install it to the connected device in one step, with clean output.
#
# Usage: scripts/build-install.sh [--no-install] [--test] [--serial SERIAL] [--install-sdk]
#   --no-install  Build only; skip adb install/force-stop.
#   --test        Run the unit tests instead of assembling/installing.
#   --serial      Use this adb device/emulator when more than one is connected.
#   --install-sdk Install command-line tools and the required Android SDK platform if missing.
#
# Requires a JDK 17 or newer and an Android SDK with platform android-35. JAVA_HOME, ANDROID_SDK_ROOT,
# ANDROID_HOME, local.properties, and PATH are supported; no platform-specific default paths are used.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Termux ships ARM64 adb/aapt2, while the command-line SDK usually contains x86-64 host
# binaries. Prefer the native Termux tools when available so root-enabled phone builds do not
# fail with an ELF/shell syntax error.
if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/adb" ]; then
  PATH="$PREFIX/bin:$PATH"
  export PATH
fi

DO_INSTALL=1
RUN_TESTS=0
INSTALL_SDK=0
ADB_SERIAL="${ANDROID_SERIAL:-}"
GRADLE_ARGS=()
if [ "$(uname -m 2>/dev/null || true)" = "aarch64" ] && command -v aapt2 >/dev/null 2>&1; then
  GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
  GRADLE_ARGS+=("-PSPICY_COMPILE_SDK=${SPICY_COMPILE_SDK:-34}")
fi
while [ "$#" -gt 0 ]; do
  arg="$1"
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --test) RUN_TESTS=1 ;;
    --install-sdk) INSTALL_SDK=1 ;;
    --serial)
      shift
      [ "$#" -gt 0 ] || { echo "ERROR: --serial requires a device serial." >&2; exit 1; }
      ADB_SERIAL="$1"
      ;;
    --help|-h)
      sed -n '2,10p' "$0"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
  shift
done

fail_check() {
  echo "ERROR: $1" >&2
  exit 1
}

if [ ! -f "$ROOT_DIR/gradlew" ]; then
  fail_check "Gradle wrapper was not found at $ROOT_DIR/gradlew."
fi

JAVA_BIN=""
if [ -n "${JAVA_HOME:-}" ]; then
  if [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
  elif [ -x "$JAVA_HOME/bin/java.exe" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java.exe"
  else
    fail_check "JAVA_HOME does not contain a Java executable: $JAVA_HOME"
  fi
elif JAVA_BIN="$(command -v java 2>/dev/null || true)"; then
  :
fi

if [ -z "$JAVA_BIN" ]; then
  fail_check "Java was not found. Install JDK 17+ or set JAVA_HOME to its location."
fi

JAVAC_BIN=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC_BIN="$JAVA_HOME/bin/javac"
else
  JAVAC_BIN="$(command -v javac 2>/dev/null || true)"
fi
if [ -z "$JAVAC_BIN" ]; then
  fail_check "javac was not found. A JDK is required; the installed Java runtime alone is insufficient."
fi

if [ -z "${JAVA_HOME:-}" ]; then
  JAVAC_REALPATH="$(readlink -f "$JAVAC_BIN" 2>/dev/null || printf '%s' "$JAVAC_BIN")"
  JDK_HOME="$(cd "$(dirname "$JAVAC_REALPATH")/.." && pwd)"
  if [ -x "$JDK_HOME/bin/java" ]; then
    JAVA_BIN="$JDK_HOME/bin/java"
    export JAVA_HOME="$JDK_HOME"
  fi
fi

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
  fail_check "JDK 17+ is required, but $JAVA_BIN reported version ${JAVA_MAJOR:-unknown}."
fi

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] && [ -f "$ROOT_DIR/local.properties" ]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
  # Java properties escape ':' and '\'; drop the escaping backslash from each pair.
  SDK_DIR="$(printf '%s' "$SDK_DIR" | sed 's/\\\(.\)/\1/g')"
  if command -v cygpath >/dev/null 2>&1; then
    SDK_DIR="$(cygpath -u "$SDK_DIR")"
  fi
fi
if [ -z "$SDK_DIR" ]; then
  for candidate in "$HOME/Android" "$HOME/Android/Sdk" "$HOME/Android/sdk" /usr/lib/android-sdk /opt/android-sdk; do
    if [ -f "$candidate/platforms/android-35/android.jar" ]; then
      SDK_DIR="$candidate"
      break
    fi
  done
fi
if [ -z "$SDK_DIR" ]; then
  for candidate in "$HOME/Android" "$HOME/Android/Sdk" "$HOME/Android/sdk" /usr/lib/android-sdk /opt/android-sdk; do
    if [ -d "$candidate" ]; then
      SDK_DIR="$candidate"
      break
    fi
  done
fi
if [ -z "$SDK_DIR" ]; then
  fail_check "Android SDK was not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or add sdk.dir to local.properties."
fi
if [ ! -d "$SDK_DIR" ]; then
  fail_check "Android SDK directory does not exist: $SDK_DIR"
fi
if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
  SDKMANAGER="$(command -v sdkmanager 2>/dev/null || true)"
  if [ -z "$SDKMANAGER" ]; then
    for candidate in "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
      "$SDK_DIR/cmdline-tools/bin/sdkmanager" "$SDK_DIR/tools/bin/sdkmanager"; do
      if [ -x "$candidate" ]; then
        SDKMANAGER="$candidate"
        break
      fi
    done
  fi
  if [ "$INSTALL_SDK" -eq 1 ]; then
    if [ -z "$SDKMANAGER" ]; then
      SDK_DIR="$HOME/Android/Sdk"
      TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
      TOOLS_TMP="$(mktemp -d)"
      TOOLS_ZIP="$TOOLS_TMP/command-line-tools.zip"
      echo "==> Installing Android Command-line Tools into $SDK_DIR"
      if ! mkdir -p "$SDK_DIR"; then
        rm -rf "$TOOLS_TMP"
        fail_check "Cannot create Android SDK directory: $SDK_DIR"
      fi
      if command -v curl >/dev/null 2>&1; then
        if ! curl -fL --retry 3 --output "$TOOLS_ZIP" "$TOOLS_URL"; then
          rm -rf "$TOOLS_TMP"
          fail_check "Could not download Android Command-line Tools from $TOOLS_URL"
        fi
      elif command -v wget >/dev/null 2>&1; then
        if ! wget -q --tries=3 -O "$TOOLS_ZIP" "$TOOLS_URL"; then
          rm -rf "$TOOLS_TMP"
          fail_check "Could not download Android Command-line Tools from $TOOLS_URL"
        fi
      else
        rm -rf "$TOOLS_TMP"
        fail_check "curl or wget is required to download Android Command-line Tools."
      fi
      command -v unzip >/dev/null 2>&1 || {
        rm -rf "$TOOLS_TMP"
        fail_check "unzip is required to install Android Command-line Tools."
      }
      if ! unzip -q "$TOOLS_ZIP" -d "$TOOLS_TMP"; then
        rm -rf "$TOOLS_TMP"
        fail_check "Could not extract Android Command-line Tools."
      fi
      mkdir -p "$SDK_DIR/cmdline-tools"
      rm -rf "$SDK_DIR/cmdline-tools/latest"
      mv "$TOOLS_TMP/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
      rm -rf "$TOOLS_TMP"
      SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
      chmod +x "$SDKMANAGER"
    fi
    echo "==> Installing Android SDK platform android-35"
    SDK_LOG="$(mktemp)"
    if ! yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" "platforms;android-35" >"$SDK_LOG" 2>&1; then
      if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
        echo "SDK INSTALL FAILED. Last 40 lines:" >&2
        tail -40 "$SDK_LOG" >&2
        rm -f "$SDK_LOG"
        exit 1
      fi
    fi
    rm -f "$SDK_LOG"
  fi
  if [ ! -f "$SDK_DIR/platforms/android-35/android.jar" ]; then
    if [ "$INSTALL_SDK" -eq 1 ]; then
      fail_check "sdkmanager completed but android-35 was not installed under $SDK_DIR."
    fi
    fail_check "Android SDK platform android-35 is missing under $SDK_DIR. Use --install-sdk if sdkmanager is available."
  fi
fi
export ANDROID_SDK_ROOT="$SDK_DIR"

ADB=()
USE_ROOT_INSTALL=0
if [ "$DO_INSTALL" -eq 1 ]; then
  command -v adb >/dev/null 2>&1 || true
  if [ -n "$ADB_SERIAL" ]; then
    ADB=(-s "$ADB_SERIAL")
  fi
  if command -v adb >/dev/null 2>&1 && adb "${ADB[@]}" get-state >/dev/null 2>&1; then
    :
  elif command -v su >/dev/null 2>&1 && su -c 'id -u' 2>/dev/null | grep -qx 0; then
    USE_ROOT_INSTALL=1
  else
    fail_check "No usable adb device and root pm install is unavailable. Connect one, set ANDROID_SERIAL, or use --no-install."
  fi
fi

echo "==> Environment OK (JDK $JAVA_MAJOR, Android SDK $SDK_DIR)"

if [ "$RUN_TESTS" -eq 1 ]; then
  TEST_TASK=":app:testDebugUnitTest"
  echo "==> Running debug unit tests ($TEST_TASK)"
  LOG="$(mktemp)"
  if ! bash ./gradlew "$TEST_TASK" "${GRADLE_ARGS[@]}" --console=plain >"$LOG" 2>&1; then
    echo "TESTS FAILED. Last 60 lines:" >&2
    tail -60 "$LOG" >&2
    rm -f "$LOG"
    exit 1
  fi
  rm -f "$LOG"
  echo "==> Tests passed."
  exit 0
fi

TASK=":app:assembleDebug"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Building debug APK ($TASK)"
LOG="$(mktemp)"
if ! bash ./gradlew "$TASK" "${GRADLE_ARGS[@]}" --console=plain >"$LOG" 2>&1; then
  echo "BUILD FAILED. Last 60 lines:" >&2
  tail -60 "$LOG" >&2
  rm -f "$LOG"
  exit 1
fi
rm -f "$LOG"
if [ ! -f "$ROOT_DIR/$APK" ]; then
  fail_check "Gradle completed but the expected APK was not produced: $ROOT_DIR/$APK"
fi
echo "==> Build succeeded: $APK"

if [ "$DO_INSTALL" -eq 0 ]; then
  exit 0
fi

echo "==> Installing to device"
INSTALL_LOG="$(mktemp)"
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  if ! su -c "pm install -r --user 0 '$ROOT_DIR/$APK'" >"$INSTALL_LOG" 2>&1; then
    echo "ROOT INSTALL FAILED:" >&2
    cat "$INSTALL_LOG" >&2
    rm -f "$INSTALL_LOG"
    exit 1
  fi
  # Clone/work-profile Spotify installations need the same module package visible in that user.
  su -c 'pm install-existing --user 11 com.eza.spicyex' >>"$INSTALL_LOG" 2>&1 || true
else
  if ! adb "${ADB[@]}" install -r "$APK" >"$INSTALL_LOG" 2>&1; then
  echo "INSTALL FAILED:" >&2
  cat "$INSTALL_LOG" >&2
  rm -f "$INSTALL_LOG"
    exit 1
  fi
fi
if [ "$USE_ROOT_INSTALL" -eq 1 ] || grep -q "^Success" "$INSTALL_LOG"; then
  echo "==> Installed"
else
  echo "INSTALL did not report Success:" >&2
  cat "$INSTALL_LOG" >&2
  rm -f "$INSTALL_LOG"
  exit 1
fi
rm -f "$INSTALL_LOG"

# Language models are never inside the APK.  On a rooted phone, install the exact pack produced
# from this checkout into Spotify's sandbox as part of the same operation, so the reading engine
# has its dictionaries without waiting for the in-app download.
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  MODEL_TASK=":app:packageLanguageModelPack"
  MODEL_LOG="$(mktemp)"
  echo "==> Packaging language models ($MODEL_TASK)"
  if ! bash ./gradlew "$MODEL_TASK" "${GRADLE_ARGS[@]}" --console=plain >"$MODEL_LOG" 2>&1; then
    echo "LANGUAGE MODEL PACK FAILED. Last 60 lines:" >&2
    tail -60 "$MODEL_LOG" >&2
    rm -f "$MODEL_LOG"
    exit 1
  fi
  rm -f "$MODEL_LOG"
  MODEL_ZIP="app/build/language-models/spicyex-language-models-v1.zip"
  [ -f "$MODEL_ZIP" ] || fail_check "Gradle completed but the language model pack was not produced: $MODEL_ZIP"
  MODEL_TMP="$(mktemp -d)"
  unzip -q -o "$MODEL_ZIP" -d "$MODEL_TMP"
  MODEL_FILES=(
    jmdict/JmdictFurigana.txt.gz
    jmdict/JmdictPreferredReadings.txt.gz
    tika/langdetect-20260320.bin
    kuromoji/characterDefinitions.bin
    kuromoji/connectionCosts.bin
    kuromoji/doubleArrayTrie.bin
    kuromoji/tokenInfoDictionary.bin
    kuromoji/tokenInfoFeaturesMap.bin
    kuromoji/tokenInfoPartOfSpeechMap.bin
    kuromoji/tokenInfoTargetMap.bin
    kuromoji/unknownDictionary.bin
  )
  for model_file in "${MODEL_FILES[@]}"; do
    [ -s "$MODEL_TMP/$model_file" ] || fail_check "Language model pack is missing or empty: $model_file"
  done
  if ! su -c "mkdir -p /data/user/0/com.spotify.music/files/language-models-v1.partial && rm -rf /data/user/0/com.spotify.music/files/language-models-v1.partial/* && cp -R '$MODEL_TMP'/. /data/user/0/com.spotify.music/files/language-models-v1.partial/ && touch /data/user/0/com.spotify.music/files/language-models-v1.partial/.ready && rm -rf /data/user/0/com.spotify.music/files/language-models-v1 && mv /data/user/0/com.spotify.music/files/language-models-v1.partial /data/user/0/com.spotify.music/files/language-models-v1"; then
    rm -rf "$MODEL_TMP"
    fail_check "Could not install language models into Spotify's sandbox."
  fi
  SPOTIFY_OWNER="$(su -c 'stat -c %u:%g /data/user/0/com.spotify.music 2>/dev/null' || true)"
  if [ -n "$SPOTIFY_OWNER" ]; then
    su -c "chown -R '$SPOTIFY_OWNER' /data/user/0/com.spotify.music/files/language-models-v1"
  fi
  rm -rf "$MODEL_TMP"
  echo "==> Language models installed into Spotify user 0"
fi

echo "==> Force-stopping Spotify so the module reloads fresh"
if [ "$USE_ROOT_INSTALL" -eq 1 ]; then
  su -c 'am force-stop com.spotify.music' || echo "WARNING: force-stop failed (non-fatal)" >&2
else
  adb "${ADB[@]}" shell am force-stop com.spotify.music || echo "WARNING: force-stop failed (non-fatal)" >&2
fi

echo "==> Done."
