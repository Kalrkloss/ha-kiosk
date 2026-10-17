#!/usr/bin/env bash
#
# Builds ha-kiosk.apk with the Android build-tools only (aapt2, javac, d8, zipalign, apksigner).
# No Gradle, no Android Studio project.
#
# Requirements:
#   - Android SDK with build-tools and one platform (set ANDROID_SDK_ROOT or ANDROID_HOME)
#   - JDK 8+ (javac, keytool)
#   - python3 (used to inject classes.dex into the APK, so no zip binary is needed)
#
# Usage:
#   ./build.sh                 # release build, signed with ./kiosk.keystore
#   DEBUG=1 ./build.sh         # same, but android:debuggable="true" (enables run-as / log files)
#   KEYSTORE=/path/k.jks KS_PASS=... KEY_PASS=... ./build.sh
#
# If no keystore exists yet, create one first:
#   keytool -genkeypair -keystore kiosk.keystore -alias kiosk -keyalg RSA -keysize 2048 \
#           -validity 10000 -storepass changeit -keypass changeit -dname "CN=Kiosk"
# The keystore is git-ignored on purpose: never commit signing material.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  echo "error: set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK path" >&2
  exit 1
fi

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -n "$BT" ] && [ -n "$PLATFORM" ] || { echo "error: no build-tools/platform found in $SDK" >&2; exit 1; }
ANDROID_JAR="$PLATFORM/android.jar"
PKG="com.hermes.kiosk"

KEYSTORE="${KEYSTORE:-kiosk.keystore}"
KS_PASS="${KS_PASS:-changeit}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
KEY_ALIAS="${KEY_ALIAS:-kiosk}"
OUT="${OUT:-ha-kiosk.apk}"
MIN_SDK=23

[ -f "$KEYSTORE" ] || { echo "error: keystore '$KEYSTORE' not found (see header of this script)" >&2; exit 1; }

echo "build-tools : $BT"
echo "platform    : $PLATFORM"

rm -rf build
mkdir -p build/classes build/dex build/gen

# --- manifest (optionally with android:debuggable) -------------------------------------------
MANIFEST=app/AndroidManifest.xml
if [ "${DEBUG:-0}" = "1" ]; then
  sed 's|<application android:label="HA Kiosk">|<application android:label="HA Kiosk" android:debuggable="true">|' \
      "$MANIFEST" > build/AndroidManifest.xml
  MANIFEST=build/AndroidManifest.xml
  echo "debug build : android:debuggable=true"
fi

# --- resources --------------------------------------------------------------------------------
"$BT/aapt2" compile --dir app/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$ANDROID_JAR" --manifest "$MANIFEST" build/res.zip \
    --java build/gen --min-sdk-version $MIN_SDK --target-sdk-version $MIN_SDK

# --- java -> dex ------------------------------------------------------------------------------
javac -g --release 8 -cp "$ANDROID_JAR" -d build/classes \
    $(find app/src build/gen -name '*.java')
find build/classes -name '*.class' > build/classes.txt
"$BT/d8" --min-api $MIN_SDK --lib "$ANDROID_JAR" --output build/dex @build/classes.txt
[ -f build/dex/classes.dex ] || { echo "error: dexing produced no classes.dex" >&2; exit 1; }

# --- package, align, sign ---------------------------------------------------------------------
cp build/base.apk build/app.apk
python3 - <<PY
import zipfile
z = zipfile.ZipFile("build/app.apk", "a", zipfile.ZIP_DEFLATED)
z.write("build/dex/classes.dex", "classes.dex")
z.close()
PY
"$BT/zipalign" -f -p 4 build/app.apk build/app-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT" build/app-aligned.apk
"$BT/apksigner" verify "$OUT"

echo
echo "built: $OUT"
"$BT/aapt2" dump badging "$OUT" | grep -E "^package|sdkVersion|targetSdkVersion|launchable-activity"
