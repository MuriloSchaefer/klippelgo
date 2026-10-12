#!/usr/bin/env bash
# Regenerates the map style the app bundles in app/src/main/assets/map: the Protomaps light style in Portuguese,
# the Noto Sans glyphs it uses and its sprites. They are committed, so run this only to move to a newer style.
# The map then draws from the pack's basemap.pmtiles with no font or sprite server.
#
# Needs node. Only the glyph ranges Portuguese, Spanish and English use are kept; text in other scripts is
# simply not drawn.
set -euo pipefail
STYLE_VERSION="${STYLE_VERSION:-5.7.2}"
ASSETS="https://protomaps.github.io/basemaps-assets"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/map"
rm -rf "$OUT"
mkdir -p "$OUT/sprites"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# The app fills in {basemap} with the path of the pack's basemap.pmtiles.
(cd "$tmp" && npx -y -p tsx -p "@protomaps/basemaps@$STYLE_VERSION" generate_style style.json "pmtiles://file://{basemap}" light pt >/dev/null)
jq '.glyphs = "asset://map/fonts/{fontstack}/{range}.pbf" | .sprite = "asset://map/sprites/light"' "$tmp/style.json" > "$OUT/style.json"

for font in "Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic"; do
  mkdir -p "$OUT/fonts/$font"
  # Basic Latin and Latin-1, Latin Extended, general punctuation.
  for range in 0-255 256-511 8192-8447; do
    curl -fsSL -o "$OUT/fonts/$font/$range.pbf" "$ASSETS/fonts/${font// /%20}/$range.pbf"
  done
done
for file in light.json light.png light@2x.json light@2x.png; do
  curl -fsSL -o "$OUT/sprites/$file" "$ASSETS/sprites/v4/$file"
done
du -sh "$OUT"
