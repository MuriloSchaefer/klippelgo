#!/usr/bin/env bash
# Builds a map pack for one region (docs/maps-and-routing.md):
#
#   mappack/build.sh br-pr-cascavel        # a file name from mappack/regions, without .env
#
# Every tool runs in a Docker image pinned by digest or commit, on an OpenStreetMap extract pinned by its
# SHA-256. The proof index comes out byte for byte the same for anyone who runs this; the vector tiles and the
# routing graph may not, and nothing that moves money rests on them.
#
# The pack lands in mappack/build/<region>/pack. The first build downloads the extract (about 0.9 GB for
# Sudeste) and the basemap's shared sources (about 2.5 GB: coastlines, Natural Earth, land cover).
#
#   STEPS="cut index search routing basemap manifest"    run only some steps (default: all)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REGION_FILE="$HERE/regions/${1:?Usage: $0 <region>}.env"
[ -f "$REGION_FILE" ] || { echo "No such region: $REGION_FILE" >&2; exit 1; }
# shellcheck disable=SC1090
. "$REGION_FILE"
STEPS="${STEPS:-cut index search routing basemap manifest}"

# Pinned tools. To move to newer ones, change them here and publish a new pack version.
VALHALLA_IMAGE="ghcr.io/valhalla/valhalla@sha256:ff426b01a20f342b5d1e7ed139ea8132eba4bc1299622b70a8cd6073d85125e5" # 3.9.1
BASEMAP_COMMIT="42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123" # protomaps/basemaps 4.15.2
TOOLS_IMAGE="klippelgo-mappack"
BASEMAP_IMAGE="klippelgo-basemap:$BASEMAP_COMMIT"

BUILD="$HERE/build"
SOURCE="$BUILD/src/$(basename "$OSM_URL")"
WORK="$BUILD/$REGION"
PACK="$WORK/pack"
mkdir -p "$BUILD/src" "$BUILD/sources" "$PACK" "$WORK/valhalla"
AS_ME=(--rm -u "$(id -u):$(id -g)")
has() { case " $STEPS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
tools() { docker run "${AS_ME[@]}" -v "$BUILD":/work "$TOOLS_IMAGE" "$@"; }

echo "== Tools"
docker build -q -f "$HERE/Dockerfile" -t "$TOOLS_IMAGE" "$HERE/.." >/dev/null

echo "== OpenStreetMap extract"
if [ ! -f "$SOURCE" ]; then
  curl -fL --progress-bar -o "$SOURCE.part" "$OSM_URL"
  mv "$SOURCE.part" "$SOURCE"
fi
echo "$OSM_SHA256  $SOURCE" | sha256sum -c - >/dev/null || { echo "$SOURCE is not the pinned extract (SHA-256 differs)" >&2; exit 1; }

if has cut; then
  echo "== Cutting $REGION ($BBOX)"
  tools osmium extract --bbox="$BBOX" --strategy=complete_ways --set-bounds --overwrite \
    -o "$REGION/region.osm.pbf" "src/$(basename "$SOURCE")"
fi

if has index; then
  echo "== Proof index"
  echo "proof root $(tools klippelgo-mappack proof-index "$REGION/region.osm.pbf" "$REGION/pack")"
fi

if has search; then
  echo "== Search"
  tools klippelgo-mappack search "$REGION/region.osm.pbf" "$REGION/pack"
fi

if has routing; then
  echo "== Routing graph (Valhalla)"
  rm -rf "$WORK/valhalla/tiles" "$PACK/valhalla_tiles.tar"
  docker run "${AS_ME[@]}" -v "$WORK":/data "$VALHALLA_IMAGE" sh -c '
    set -e
    valhalla_build_config --mjolnir-tile-dir /data/valhalla/tiles --mjolnir-tile-extract /data/pack/valhalla_tiles.tar \
      --mjolnir-admin "" --mjolnir-timezone "" > /data/valhalla/valhalla.json
    valhalla_build_tiles -c /data/valhalla/valhalla.json /data/region.osm.pbf > /data/valhalla/build.log 2>&1
    valhalla_build_extract -c /data/valhalla/valhalla.json -v >> /data/valhalla/build.log 2>&1'
fi

if has basemap; then
  echo "== Vector tiles (Protomaps basemap on Planetiler)"
  docker image inspect "$BASEMAP_IMAGE" >/dev/null 2>&1 \
    || docker build -q -t "$BASEMAP_IMAGE" "https://github.com/protomaps/basemaps.git#$BASEMAP_COMMIT:tiles" >/dev/null
  docker run "${AS_ME[@]}" -e JAVA_TOOL_OPTIONS="-Xmx${BASEMAP_HEAP:-4g}" -v "$WORK":/data -v "$BUILD/sources":/tiles/data/sources \
    "$BASEMAP_IMAGE" --osm-path=/data/region.osm.pbf --output=/data/pack/basemap.pmtiles --bounds="$BBOX" \
    --download --force --nodemap-type=sparsearray --tmpdir=/data/tmp > "$WORK/basemap.log" 2>&1
  rm -rf "$WORK/tmp"
fi

if has manifest; then
  echo "== Manifest"
  id=$(tools klippelgo-mappack manifest "$REGION/pack" region="$REGION" version="$VERSION" osm-source="$OSM_URL" \
    osm-sequence="$OSM_SEQUENCE" osm="src/$(basename "$SOURCE")" bbox="$BBOX" \
    tool:valhalla="$VALHALLA_IMAGE" tool:protomaps-basemaps="$BASEMAP_COMMIT")
  tools klippelgo-mappack check "$REGION/pack"
  du -sh "$PACK" | cut -f1 | xargs echo "pack size"
  echo
  echo "Pack $id is in $PACK"
  echo "Local testnet: make publish-pack PACK=$PACK, then make serve-packs"
fi
