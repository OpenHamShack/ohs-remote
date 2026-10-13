#!/bin/sh
# Copy the shared band-plan data Remote's Radio screen reads (the strip
# along the waterfall, the band buttons' first visit) from ohs-libs into
# the app's assets, byte for byte, and record where it came from.
#
#   tools/sync-bandplan.sh [<ohs-libs checkout or bare repo>] [<rev>]
#
# Defaults: shack's hub (~/git/ohs-libs.git) at main. The files are never
# edited here: a wrong row is fixed in ohs-libs (its data/bandplan-overrides
# and export) and copied again, so every app reads the same plan
# (openhamshack docs/contract/BANDPLAN-DATA.md). Run it when ohs-libs raises
# dataVersion, and commit the result with the new SOURCE.
set -eu
repo=${1:-$HOME/git/ohs-libs.git}
rev=${2:-main}
here=$(cd "$(dirname "$0")/.." && pwd)
out=$here/android/app/src/main/assets/bandplan
mkdir -p "$out/plans"
for f in manifest.json vocab.json plans/iaru-r1.json plans/iaru-r2.json plans/iaru-r3.json plans/rsgb-2026.json; do
    git --git-dir="$( [ -d "$repo/.git" ] && echo "$repo/.git" || echo "$repo")" show "$rev:data/bandplan/$f" > "$out/$f"
done
commit=$(git --git-dir="$( [ -d "$repo/.git" ] && echo "$repo/.git" || echo "$repo")" rev-parse "$rev")
version=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['dataVersion'])" "$out/manifest.json")
printf 'ohs-libs %s data/bandplan, dataVersion %s\nCopied by tools/sync-bandplan.sh; do not edit these files here.\n' "$commit" "$version" > "$out/SOURCE"
cat "$out/SOURCE"
