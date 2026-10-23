#!/bin/bash
# Put the "fast" build (release code, debug key; see app/build.gradle.kts)
# on a device that already runs the Remote, keeping its pairing.
#
#   android/tools/install-fast.sh <adb-serial> [abi]     # abi: armeabi-v7a (default), arm64-v8a, x86_64, x86
#
# Build first: (cd android && ./gradlew assembleFast). Then this
#  - refuses unless the APK's signer is the installed app's signer: a
#    different signer would need an uninstall, and an uninstall loses the
#    device's pairings and saved boxes;
#  - installs with `install-multiple -r` and the baseline profile's .dm
#    beside it, so Android 9-11 compiles the startup path at install
#    (a plain `install -r` leaves that to the idle job, overnight on a TV:
#    0.46 s against 0.95 s to the first frame on a Fire TV until then).
# Nothing else on the device is changed.
set -euo pipefail
SERIAL=${1:?usage: install-fast.sh <adb-serial> [abi]}
ABI=${2:-armeabi-v7a}
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$HERE/app/build/outputs/apk/fast
APK=$OUT/app-$ABI-fast.apk
PKG=com.openhamshack.remote
SDK=${ANDROID_HOME:-$HOME/android-toolchain/sdk}
ADB="$SDK/platform-tools/adb -s $SERIAL"
# apksigner is a Java program; the build's JDK if java is not on the PATH.
command -v java >/dev/null || export PATH=${JAVA_HOME:-$HOME/android-toolchain/jdk17}/bin:$PATH
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
[ -f "$APK" ] || { echo "no $APK - run ./gradlew assembleFast" >&2; exit 1; }

# The installed APK is as big as the new one: pulled into the build
# directory, not /tmp (a small tmpfs on the shared hosts).
TMP=$(mktemp -d "$HERE/app/build/install-fast.XXXXXX"); trap 'rm -rf "$TMP"' EXIT
signer() { "$APKSIGNER" verify --print-certs "$1" | grep -m1 'SHA-256 digest' | sed 's/.*: //'; }
installed=$($ADB shell pm path $PKG | sed -n 's/^package://p' | head -1 | tr -d '\r')
if [ -n "$installed" ]; then
    $ADB pull "$installed" "$TMP/installed.apk" >/dev/null 2>&1
    have=$(signer "$TMP/installed.apk"); want=$(signer "$APK")
    echo "installed signer: $have"
    echo "this APK:         $want"
    [ -n "$have" ] && [ "$have" = "$want" ] || { echo "signers differ - not installing (an uninstall would lose pairing)" >&2; exit 1; }
fi

# The .dm for the device's Android version: 1/ is API 28-30, 0/ is 31 and
# later (output-metadata.json says which). Before 28 there is no .dm.
api=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
dm=""
if [ "$api" -ge 28 ] && [ "$api" -le 30 ]; then dm=$OUT/baselineProfiles/1/app-$ABI-fast.dm
elif [ "$api" -ge 31 ]; then dm=$OUT/baselineProfiles/0/app-$ABI-fast.dm; fi
if [ -n "$dm" ] && [ -f "$dm" ]; then
    $ADB install-multiple -r "$APK" "$dm"
else
    $ADB install -r "$APK"
fi
$ADB shell dumpsys package dexopt | grep -A2 "\[$PKG\]" | tail -1 || true
