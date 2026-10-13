#!/bin/bash
# Merge the strings the compiler extracted from App/, TV/ and Shared/ into
# Shared/Localizable.xcstrings, the apps' one String Catalog. Run on the
# Mac after building BOTH apps into the same derived-data folder:
#
#   ios/tools/sync-strings.sh <derivedDataPath>
#
# Why a script: Xcode's IDE merges extracted strings into a catalog when it
# builds, but xcodebuild only writes the .stringsdata files (Xcode 26) and
# leaves the catalog alone, and nobody builds Remote in the IDE - the
# project is generated (tools/genproject.py). xcstringstool sync is the
# step the IDE runs.
#
# Why both apps: sync marks a key stale when no .stringsdata names it, so a
# sync after building only the iPad app would mark every TV-only string
# stale. DebugLaunch (#if DEBUG launch arguments) is left out so debug-only
# words never reach a translator, and so are the App Intents metadata files,
# which hold no strings of ours.
set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
DD=${1:?usage: sync-strings.sh <derivedDataPath used for both builds>}
BUILT=$DD/Build/Intermediates.noindex/OpenHamShackRemote.build
for target in OpenHamShackRemote OpenHamShackRemoteTV; do
  if ! find "$BUILT" -path "*/$target.build/*" -name '*.stringsdata' 2>/dev/null | grep -q .; then
    echo "no extracted strings for $target under $BUILT: build both schemes first" >&2
    exit 1
  fi
done

args=()
while IFS= read -r f; do
  args+=(--stringsdata "$f")
done < <(find "$BUILT" -name '*.stringsdata' ! -name DebugLaunch.stringsdata \
           ! -name ExtractedAppShortcutsMetadata.stringsdata | sort)
xcrun xcstringstool sync "$HERE/Shared/Localizable.xcstrings" "${args[@]}"
echo "Shared/Localizable.xcstrings: $(xcrun xcstringstool print "$HERE/Shared/Localizable.xcstrings" | grep -c .) line(s) of keys"
