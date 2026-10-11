# Map packs

A map pack is everything a phone needs for one region, built from one OpenStreetMap snapshot: vector tiles to
draw the map, a Valhalla graph to route on, a search database, and the **proof index** that route quotes and
trip receipts are checked against. The design is in [docs/maps-and-routing.md](../docs/maps-and-routing.md).

| Path | What it is |
| --- | --- |
| `build.sh` | Builds a pack for a region with Docker |
| `regions/` | One file per region: the pinned extract and the area to cut from it |
| `src/` | `klippelgo-mappack`: the proof index, the search database, the manifest |
| `Dockerfile` | Image with `klippelgo-mappack` and osmium |
| `serve.sh` | Serves built packs to phones on the local network |
| `tools/` | Scripts that check a pack against a running Valhalla |

## Build a pack

Needs Docker. The first build downloads the extract (0.9 GB for Brazil's Sudeste) and the basemap's shared
sources (2.5 GB), and compiles the Protomaps basemap.

```sh
make mappack                    # the pilot region, into mappack/build/br-pr-cascavel/pack
make publish-pack               # activate it on the local testnet (needs make testnet, make deploy)
make serve-packs                # serve it to phones on port 4002
```

`br-pr-cascavel` is Cascavel, Paraná, the pilot city, with the roads around it: 28 km by 27 km, 7 233 drivable
ways. It is what the Android app downloads. `make mappack REGION=<name>` builds any other file in `regions/`.

| File | Built by | Size, Cascavel | Central São Paulo, 15 km across |
| --- | --- | --- | --- |
| `basemap.pmtiles` | Protomaps basemap on Planetiler | 2.2 MB | 19 MB |
| `valhalla_tiles.tar` | Valhalla 3.9.1 | 3.7 MB | 21 MB |
| `search.sqlite` | `klippelgo-mappack search` | 1.0 MB | 30 MB |
| `proof-index/ways.bin`, `index.bin` | `klippelgo-mappack proof-index` | 1.0 MB | 3.9 MB |
| `manifest.json` | `klippelgo-mappack manifest` | | |
| Whole pack | | 7.6 MB | 73 MB |

OpenStreetMap has house numbers for only 668 addresses in Cascavel, against 2 131 streets and 2 130 named
places, so search there finds streets and places far more often than exact addresses. That is why the rider
can always place the pickup with a pin.

The pack id is the keccak-256 of `manifest.json`. `klippelgo-mappack check <pack dir>` verifies every file
against the manifest and prints the id and the proof root.

## The proof index

One row per OpenStreetMap way a car or motorcycle may drive: its id, direction flags per vehicle, and its
nodes with their coordinates exactly as OpenStreetMap stores them. Rows are sorted by way id, cut into chunks
of 64 and hashed into a Merkle tree; the root is the pack's `proofRoot`. The byte format is specified in
[`core/verify/src/index.rs`](../core/verify/src/index.rs) and the tag rules in [`src/roads.rs`](src/roads.rs).
Both are part of the format: changing either changes every root.

The index is built on one thread from nothing but the extract, so everyone gets the same bytes. A validator
checks a pack before attesting it in `MapRegistry` by rebuilding the root:

```sh
sha256sum sudeste-261009.osm.pbf                                    # must be the manifest's osm.sha256
osmium extract --bbox=<manifest's bbox> --strategy=complete_ways -o region.osm.pbf sudeste-261009.osm.pbf
klippelgo-mappack root region.osm.pbf                               # must be the manifest's proofRoot
```

The tag rules are generous on purpose. The index says what is legal at most and Valhalla picks routes inside
it, so a way the index allows and Valhalla never uses costs nothing, while a way Valhalla uses and the index
lacks makes an honest route fail. Turn restrictions are not in the index.

## Checking a pack against Valhalla

Valhalla is not trusted: the phone turns its answers into `(way, node)` paths and the verifier measures them on
the proof index. These scripts check that this keeps working when a pack, the tag rules or Valhalla change.

```sh
cd mappack/build/br-pr-cascavel
docker run -d --name kg-valhalla -u "$(id -u):$(id -g)" -p 127.0.0.1:8002:8002 -v "$PWD":/data \
  ghcr.io/valhalla/valhalla:latest valhalla_service /data/valhalla/valhalla.json 2

# 1. Routes: sample them, translate them, measure them.
../../tools/sample-routes.py samples 1500
(cd ../../../core && cargo run --release -p klippelgo-proofs --example measure_many -- \
  ../mappack/build/br-pr-cascavel/pack ../mappack/build/br-pr-cascavel/samples)

# 2. A whole ride: route, two noisy GPS logs, map-matching, signed evidence, and the juror's check.
../../tools/simulate-ride.py ride.json 1
(cd ../../../core && cargo run -p klippelgo-proofs --example simulate_ride -- \
  ../mappack/build/br-pr-cascavel/pack ../mappack/build/br-pr-cascavel/ride.json evidence.json \
  && cargo run -p klippelgo-proofs --bin klippelgo-juror -- ../mappack/build/br-pr-cascavel/pack evidence.json)
```

| | Cascavel | Central São Paulo |
| --- | --- | --- |
| Sampled routes that translate and pass the verifier | 1000 of 1000 (15 769 km) | 1500 of 1500 (14 485 km) |
| Largest difference from Valhalla's own distance | 0.27% | 0.31% |
| Simulated rides that pass from route to juror | 100 of 100 | 120 of 120 |
| The same, writing the receipt from the map-matched log alone (`ONLY_MATCHED=1`) | 87 of 100 | 120 of 120 |

A receipt's driven path is the quoted route whenever the logs followed it, which is why every simulated ride
passes. A trip that leaves its quoted route needs the map-matched log instead. In dense streets that works,
after dropping the short detours map-matching sometimes invents. On the farm tracks around Cascavel it failed
in 13 of 100 rides: Valhalla's map-matching loses the thread there and returns disconnected pieces. Random
points put many of these simulated trips on such tracks; rides inside the city are not affected. Bridging those
gaps is not built.

## What a mirror is

Packs are fetched by id: `http://<mirror>/<pack id>/manifest.json`, then each file the manifest lists. The
phone checks the manifest against the id `MapRegistry` gave it and every file against the manifest, so a mirror
is not trusted. It learns which region a phone asked for and nothing else. `serve.sh` is such a mirror for the
local network.
