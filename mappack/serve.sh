#!/usr/bin/env bash
# Serves the packs built under mappack/build to phones on the local network, the way a mirror would: each pack
# under its id, so `http://<this computer>:4002/<pack id>/manifest.json` is the pack's manifest. Emulators reach
# it at 10.0.2.2:4002. Phones check everything they fetch against MapRegistry, so the mirror is not trusted.
set -euo pipefail
cd "$(dirname "$0")"
PORT="${PORT:-4002}"
MIRROR=build/mirror
rm -rf "$MIRROR"
mkdir -p "$MIRROR"
for manifest in build/*/pack/manifest.json; do
  [ -f "$manifest" ] || { echo "No packs yet. Build one: make mappack" >&2; exit 1; }
  id=$(cast keccak "0x$(od -An -tx1 -v "$manifest" | tr -d ' \n')")
  ln -s "$(cd "$(dirname "$manifest")" && pwd)" "$MIRROR/$id"
  echo "$(jq -r '"\(.region) \(.version)"' "$manifest")  $id"
done
echo "Serving on port $PORT"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$MIRROR"
