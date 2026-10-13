#!/bin/bash
# Fetch VLCKit for the player: MobileVLCKit (iPad) and TVVLCKit (Apple TV),
# VideoLAN's own prebuilt xcframeworks, into ios/OpenHamShackMedia/Vendor/ where
# Package.swift's binary targets expect them. Run on the Mac, once per
# checkout (and again when the version below changes).
#
#   ios/tools/fetch-vlckit.sh [cache-dir]
#
# Why a script and not SwiftPM: VideoLAN publishes VLCKit 3 as CocoaPods
# tarballs, not as a Swift package, and the unofficial SPM mirrors are one
# person's re-upload of the same files. Pinned to a version and a SHA-256
# here, the binary in the app is VideoLAN's, checked.
#
# Why thinned: the tarballs carry armv7/armv7s/i386/x86_64 slices and
# dSYMs, 1.7 GB unpacked, for an app whose oldest target (iPadOS 17) is
# arm64-only. The build Mac is Apple silicon, so the simulator slice is
# kept as arm64 only as well; set KEEP_X86=1 on an Intel Mac.
#
# VLCKit is LGPL-2.1-or-later, compatible with this app's GPL-3.0; it is a
# dynamic framework embedded in the app as VideoLAN ships it.
set -eu

VERSION=3.7.3-319ed2c0-79128878
BASE=https://download.videolan.org/pub/cocoapods/prod
# macOS's bash is 3.2: no associative arrays.
sha_of() {
  case $1 in
    MobileVLCKit) echo 0d04059906962ddc9a7bd1ebaa12e1f9ae85eb2466116a97a2f46886dd27a0a9;;
    TVVLCKit) echo b5f90c226ed54d9dc1c03901c60dc7749b74a53caace2c3047e4c0b7a063e46c;;
  esac
}

HERE=$(cd "$(dirname "$0")/.." && pwd)
VENDOR=$HERE/OpenHamShackMedia/Vendor
CACHE=${1:-$HERE/.vlckit-cache}
mkdir -p "$VENDOR" "$CACHE"

for kit in MobileVLCKit TVVLCKit; do
  if [ -f "$VENDOR/$kit.xcframework/.version" ] && [ "$(cat "$VENDOR/$kit.xcframework/.version")" = "$VERSION" ]; then
    echo "$kit $VERSION already in place"
    continue
  fi
  tarball=$CACHE/$kit-$VERSION.tar.xz
  if [ ! -f "$tarball" ]; then
    echo "Downloading $kit $VERSION"
    curl -fL --retry 3 -o "$tarball.part" "$BASE/$kit-$VERSION.tar.xz"
    mv "$tarball.part" "$tarball"
  fi
  got=$(shasum -a 256 "$tarball" | cut -d' ' -f1)
  if [ "$got" != "$(sha_of $kit)" ]; then
    echo "$kit: SHA-256 is $got, expected $(sha_of $kit): not using it" >&2
    exit 1
  fi
  work=$CACHE/unpack-$kit
  rm -rf "$work" && mkdir -p "$work"
  # Only the framework: the docs and sample code are most of the rest.
  tar -xJf "$tarball" -C "$work" "$kit-binary/$kit.xcframework"
  xc=$work/$kit-binary/$kit.xcframework
  for lib in "$xc"/*/; do
    bin=$lib$kit.framework/$kit
    rm -rf "$lib/dSYMs"
    sim=0
    case "$lib" in *simulator*) sim=1;; esac
    if [ $sim = 1 ] && [ "${KEEP_X86:-0}" = 1 ]; then
      lipo "$bin" -extract arm64 -extract x86_64 -output "$bin.thin" && mv "$bin.thin" "$bin"
    elif [ "$(lipo -archs "$bin")" != arm64 ]; then
      lipo "$bin" -thin arm64 -output "$bin.thin" && mv "$bin.thin" "$bin"
    fi
    echo "$kit $(basename "$lib"): $(lipo -archs "$bin")"
  done
  # The xcframework's own list of slices must say what is left, or Xcode
  # picks a slice by an architecture that is no longer in it.
  n=0
  while plutil -extract "AvailableLibraries.$n" xml1 -o /dev/null "$xc/Info.plist" 2>/dev/null; do
    plutil -remove "AvailableLibraries.$n.DebugSymbolsPath" "$xc/Info.plist" 2>/dev/null || true
    variant=$(plutil -extract "AvailableLibraries.$n.SupportedPlatformVariant" raw -o - "$xc/Info.plist" 2>/dev/null || true)
    archs='["arm64"]'
    [ "$variant" = simulator ] && [ "${KEEP_X86:-0}" = 1 ] && archs='["arm64","x86_64"]'
    plutil -replace "AvailableLibraries.$n.SupportedArchitectures" -json "$archs" "$xc/Info.plist"
    n=$((n + 1))
  done
  echo "$VERSION" > "$xc/.version"
  rm -rf "$VENDOR/$kit.xcframework"
  mv "$xc" "$VENDOR/"
  rm -rf "$work"
  du -sh "$VENDOR/$kit.xcframework"
done
