# Maps and routing on the phone

Status: build-order steps 1 to 5 are built, in part or in full; [What is built](#what-is-built) says how far
each went and where the build departed from this design. Decisions marked **Default** stand until the project
decides otherwise (see [Open decisions](#open-decisions)).

KlippelGo computes maps, routes, ETAs and trip distances on the rider's and driver's phones, from OpenStreetMap
data, with open-source libraries only. No routing API, tile server or geocoder ever sees a trip. Every number
that moves money (the quoted route distance and the driven distance) comes with a proof that anyone holding the
public map and the ride's private data can check, and the chain stores only hashes of those proofs.

## The idea in one paragraph

Searching for a route is expensive and hard to make bit-for-bit reproducible across phones. Checking a route
someone hands you is cheap and easy to make exact. So the design splits the two: **Valhalla searches**
(routes, ETAs, map-matching of GPS traces), and a **small deterministic verifier** written in integer-only Rust
checks every result against a map snapshot whose hash governance has published on-chain. Both phones run the
same search, both run the verifier, and both sign the result. A juror, or later a zero-knowledge proof, only
ever needs to re-run the verifier.

## Libraries

All of them are open source, widely used, and run without any third-party service.

| Job | Library | Licence | Why this one |
| --- | --- | --- | --- |
| Map data | [OpenStreetMap](https://www.openstreetmap.org), Brazil extract from [Geofabrik](https://download.geofabrik.de/south-america/brazil.html) | ODbL | The only open, global, editable road map. Brazil's coverage is good in the large cities. |
| Map drawing | [MapLibre Native](https://maplibre.org) (Android and iOS) | BSD-2 | The open fork of Mapbox GL. Renders vector tiles on the GPU and [reads PMTiles files](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) offline. |
| Vector tiles | [Planetiler](https://github.com/onthegomap/planetiler) building the [Protomaps basemap](https://github.com/protomaps/basemaps) into one [PMTiles](https://github.com/protomaps/PMTiles) file | Apache-2.0, BSD-3 | One file per region, no tile server, fast to build. |
| Routing, ETA, map-matching | [Valhalla](https://github.com/valhalla/valhalla), on phones through [valhalla-mobile](https://github.com/Rallista/valhalla-mobile) | MIT | Tiled routing graph designed for offline use, car and motorcycle profiles, and map-matching (Meili) in the same engine. valhalla-mobile ships it built for Android and iOS. |
| Turn-by-turn for drivers | [Ferrostar](https://github.com/stadiamaps/ferrostar) | BSD-3 | Navigation core in Rust with Kotlin and Swift UIs, built around Valhalla. We plug in a local route adapter instead of its HTTP one. |
| Address and place search | [SQLite](https://sqlite.org) FTS5 and R*Tree, built from OSM addresses and places | Public domain | Search and reverse geocoding fully offline. Accent folding (`remove_diacritics`) handles Portuguese. |
| Build tools | [osmium-tool](https://osmcode.org/osmium-tool/) to cut regions, Rust [`osmpbf`](https://crates.io/crates/osmpbf) for the proof index | GPL-3.0 (build time only), MIT/Apache-2.0 | Standard OSM tooling. |
| Later, zero-knowledge | [SP1](https://github.com/succinctlabs/sp1) or [RISC Zero](https://github.com/risc0/risc0) | MIT/Apache-2.0 | Both run ordinary Rust programs as provable code, so the verifier crate can be reused unchanged. |

**Alternative router.** [GraphHopper](https://github.com/graphhopper/graphhopper) (Apache-2.0, Java) runs well on
Android but is awkward on iOS. [OSRM](https://github.com/Project-OSRM/osrm-backend) is fast but needs too much
memory for a phone. **Default: Valhalla.**

## Map packs

A **map pack** is everything a phone needs for one region, built from one OSM snapshot.

| File | Built by | Used for |
| --- | --- | --- |
| `basemap.pmtiles` | Planetiler | Drawing the map |
| `style/` (style JSON, glyphs, sprites) | Bundled in the app, versioned with the pack | Drawing the map, no font server |
| `valhalla_tiles.tar` | `valhalla_build_tiles` | Routing, ETA, map-matching |
| `search.sqlite` | Our build script | Address search, reverse geocoding |
| `proof-index/` | Our Rust tool, single-threaded and deterministic | Verifying routes and trips |
| `manifest.json` | Our build script | SHA-256 of every file, OSM snapshot, tool versions, `proofRoot` |

**Regions.** One pack per metro area, starting with the pilot city, so a phone downloads only what it drives in.
A metro pack should land in the low hundreds of megabytes; the prototype will measure it. Drivers near a boundary
download the neighbouring pack too.

**The proof index** is the part every proof rests on. It is a canonical table of the road network: for each OSM
way that cars may use, its node ids, node coordinates as integers (degrees × 10⁷, exactly as OSM stores them),
direction and access flags. Rows are sorted by way id and split into chunks, each chunk is hashed with
keccak-256, and the chunk hashes form a Merkle tree whose root is `proofRoot`. The tree follows OpenZeppelin's
`MerkleProof` layout so a contract can check a chunk if it ever needs to.

**Reproducible build.** The build runs in Docker images pinned by digest, from a Geofabrik extract pinned by its
replication sequence number and SHA-256. The proof index must come out byte-identical for anyone who rebuilds
it. Valhalla's and Planetiler's outputs may not be byte-identical across machines, and that is fine: nothing
that moves money trusts them, only the proof index.

**Publishing a pack on-chain.** A new `MapRegistry` contract, owned by the governor like every other contract,
records each pack:

```
region        "br-sp-sao-paulo"
version       2026.11
osmSnapshot   Geofabrik sequence number + SHA-256 of the .pbf
proofRoot     Merkle root of the proof index
manifestHash  keccak-256 of manifest.json (this is the pack id)
cid           IPFS CID of the pack
activeFrom    timestamp
retiredAfter  timestamp (old packs stay valid for a grace period)
```

Any validator can rebuild the proof index and post an attestation of the root it got. A governance proposal
activates a pack only once a quorum of independent rebuilds (**Default: 3**) report the same `proofRoot`. This
makes the map public and verifiable, like the rest of the protocol's data.

**Distribution.** Packs are content-addressed, so it does not matter who serves them: IPFS, mirrors run by
Instituto Klippel, or another phone over the P2P layer. The app checks `manifestHash` against `MapRegistry`
before using a pack and each file against the manifest. A mirror only learns which metro area a phone asked for,
never where in it. **Default cadence: a new pack monthly**, so OSM fixes reach drivers without churning proofs.

## What happens during a ride

```mermaid
sequenceDiagram
    participant R as Rider phone
    participant G as Gossip (geohash-5 cell)
    participant D as Driver phone
    participant C as Chain

    R->>R: Search address (search.sqlite), compute route A→B (Valhalla)
    R->>G: Request: pickup cell, destination cell, route ≈ 8.5 km / 22 min, pack id
    D->>D: Price the route at the driver's own rate
    D->>G: Signed offer (fare)
    R->>D: Noise channel: exact pickup and destination
    D->>D: Recompute route, run verifier, compare with rider's
    R-->>D: Both sign the route quote
    R->>C: RideEscrow.open(..., quoteHash)
    D->>C: accept
    Note over R,D: Ride: both phones log GPS, swap signed log heads every 30 s
    D->>C: start(pickupProofHash)
    R-->>D: Both sign the trip receipt
    R->>C: complete(rideId, receiptHash)
```

### 1. Route and price, before the ride

1. The rider types an address or drops a pin. Search runs on `search.sqlite`; house numbers are patchy in OSM in
   many Brazilian neighbourhoods, so a pin is always available.
2. The rider's phone asks Valhalla for the route from pickup to destination and gets distance, duration and the
   path.
3. The gossip request that drivers see gains the route's distance and duration, rounded (0.5 km, 1 min), and
   the pack id. It names the region the pickup is in and the region the drop-off is in, and no address: each
   region is a geohash-6 cell, about 1.2 km by 0.6 km, large enough to hold hundreds of addresses in a city and
   small enough for a driver to judge the trip. Drivers can now price a known distance between known areas
   instead of the rider typing a maximum fare blind. When the route is later revealed to the chosen driver, their
   phone checks that it starts and ends inside those two regions.
4. Drivers reply with signed offers, as they do in the current Android app.
5. After the rider picks an offer, the exact points cross the encrypted channel. The driver's phone computes the
   same route on the same pack and runs the verifier on the rider's path. The phones must agree on distance
   within a tolerance (**Default: 2%**, set by governance); if not, the driver's app shows the difference and
   the driver can decline before anything is locked.
6. Both sign the **route quote** and its hash goes into `RideEscrow.open` as `quoteHash`, which the contract
   already takes.

```
RouteQuote {
  packId        manifestHash of the map pack
  pickup        lat, lon as int32 × 10⁷, where the route meets the road
  dropoff       lat, lon as int32 × 10⁷
  vehicle       car or motorcycle
  path          the route as a list of (OSM way id, OSM node id) steps
  distanceM     uint32, metres, computed by the verifier
  durationS     uint32, seconds, Valhalla's estimate
  salt          32 random bytes
  fare          token amount, the accepted offer
  token         stablecoin address
  rider, driver addresses
  expiry        timestamp
  previous      hash of the quote this one amends, or zero
}
routeHash = keccak256("KlippelGo route v1", packId … salt)
quoteHash = keccak256("KlippelGo route quote v1", chain id, escrow, routeHash, fare … previous)
```

The hash has two levels so that a driver can sign an offer before seeing the route. The gossip request carries
`routeHash`, which the salt makes useless for guessing places, and every offer signs the `quoteHash` built from
it. When the rider later reveals the route, the driver checks that it hashes to what they priced. The exact
byte layout is in `core/verify/src/quote.rs`, and `contracts/test/ProofEncoding.t.sol` rebuilds the same hashes
in Solidity.

A step moves from one node to a neighbouring node of its way, so each step is one road segment. The pickup and
drop-off in the quote are the points where Valhalla put the route on the road, not the pins the rider dropped:
a pin in the middle of a square can be further from any road than the endpoint tolerance.

The fare stays a free market between rider and driver. What the quote proves is the distance the fare was
agreed for, so the rider sees an honest R$/km and nobody can later argue about which route was priced.

### 2. Trip trace, during the ride

Each phone records its own GNSS fixes every 2 seconds into an append-only log:

```
fix  = (t ms, lat ×10⁷, lon ×10⁷, accuracy cm, speed cm/s, mockFlag)
head = keccak256(previous head, fix)
```

- Every 30 seconds the head is signed with a **trace key**: a hardware key (Secure Enclave or StrongBox) created
  for this ride without a biometric prompt, and certified at ride start by the user's wallet key.
- The two phones swap signed heads over the encrypted channel and countersign each other's. Afterwards each side
  holds the other's signatures over its log, so neither can rewrite its own trace later without being caught,
  and the two logs back each other up.
- The driver's phone uses the live log to show the rider's map and to recompute ETA with Valhalla, all on-device.
- Logs stay on the two phones, encrypted like the rest of their private data.

### 3. Trip receipt, at arrival

At drop-off each phone map-matches its own log with Valhalla's Meili, which returns the driven path, and runs
the verifier on it. Both sign:

```
TripReceipt {
  rideId, quoteHash
  riderTraceHead, driverTraceHead   final countersigned heads
  drivenPath                        (way id, node id) steps
  drivenDistanceM                   computed by the verifier
  startedAt, endedAt                from the pickup proof and the final fix
  fare                              the quoted fare, or a signed amendment's
}
receiptHash = keccak256(canonical encoding of TripReceipt)
```

- **Detours.** A stop or route change the rider asks for mid-trip is a new quote both phones sign, chained to
  the old one. Without one, the fare is the quoted fare (upfront pricing, as riders in Brazil expect).
- **On-chain.** `complete` gains a `receiptHash` argument. On the timeout path the driver submits their own
  receipt hash first with `submitReceipt`, so an unresponsive rider cannot block payment and the driver is on
  record with evidence. The timeout runs from that submission, not from the start of the ride, and a rider who
  then completes must name the same receipt; one who disagrees raises a dispute.
- **Map-matching is not trusted either.** Meili sometimes sends a noisy log a few metres up a side street and
  back. The verifier refuses a path with a stretch the log did not drive, so the phone drops such spurs, one at
  a time and only while the verifier still objects, before it writes the receipt.

## The verifier

A new crate, `core/verify`, written in plain Rust with no floating point, no allocation surprises and no I/O,
so it gives the same answer on every phone, every juror's laptop and inside a zkVM.

It checks:

1. **The map.** Every proof-index chunk it reads hashes into the pack's `proofRoot`.
2. **The path.** Each step follows an OSM way that exists in the proof index, consecutive steps share a node,
   one-way and access rules are respected for the vehicle class.
3. **The endpoints.** The path starts within 50 m of `pickup` and ends within 50 m of `dropoff` (governed).
4. **The distance.** It recomputes `distanceM` itself from node coordinates using a fixed-point equirectangular
   formula, accurate to well under 0.1% at city scale. It never trusts the number Valhalla reported.
5. **The trace** (for receipts). Fixes are in time order, every fix lies within its own reported accuracy plus
   a margin of the path, speeds and accelerations are physically possible, the mock-location flag is off, and
   the hash chain and signatures check out.
6. **The driven distance** (for receipts). The log is followed along the driven path: each fix is placed on the
   path near where it was taken, never behind the one before, and never further ahead than the log itself moved
   in a straight line, stretched by a detour tolerance (**Default: 25%**, governed). The distance is what lies
   between the first and the last fix. This is what stops a receipt from claiming streets nobody drove.

Its tolerances live in `MapRegistry.verifierParams`, so governance sets them and every checker reads the same
ones. Fixes the phone itself rates worse than 50 m, as in a tunnel, are kept in the hash chain but not used to
place the trip.

Valhalla stays untrusted: if it returns a bad path, the verifier rejects it and the app shows an error.
Translating Valhalla's output into `(way id, node id)` steps works from the way ids and shape points of its
`trace_attributes` answer (`core/proofs/src/valhalla.rs`). Valhalla's shape points are the way's nodes rounded
to six decimals, which is enough to find them again; see [What is built](#what-is-built) for the measurements.

## Who checks what

| Moment | Checked by | How |
| --- | --- | --- |
| Pack activated | Validators, then governance | Rebuild the proof index, compare `proofRoot` |
| Before locking the fare | Both phones | Same Valhalla search, same verifier, then both sign |
| Arrival | Both phones | Map-match own trace, verifier, both sign the receipt |
| Dispute | Jurors | Download the public pack, receive the ride data from the parties, run the verifier |
| Later | A contract | Check a zero-knowledge proof of the verifier, with no locations revealed |

`DisputeCourt` already takes an evidence hash. With this design the evidence is the quote, the receipt and both
trace logs, and a juror's tool can answer the usual questions mechanically: was the trip taken, how far, did the
driver detour, did it end early. That supports the plan's aim of settling small fares by default rules and
sending only real disagreements to jurors.

## Zero-knowledge, later

Re-running the verifier means a juror sees the ride's locations. A zero-knowledge proof would let a contract
learn "this receipt describes a trip of 8.4 km on pack 2026.11, ending within 50 m of the quoted drop-off"
without seeing where it was. Because the verifier is plain Rust, SP1 or RISC Zero can prove it unchanged.

Not at launch, for two reasons: proving a trace of a few hundred fixes plus the map chunks it touches is likely
too heavy for a mid-range phone today, and handing the inputs to an outside prover would leak the locations the
proof is meant to hide. **Default: ship signed, re-checkable proofs first, keep the verifier zkVM-ready, and
benchmark on-device proving once the verifier exists.**

## Honest limits

- **GPS cannot be proven.** A rooted phone can fake its location. The design raises the cost: two independent
  devices must agree, the trace must fit the road network and physics, mock-location flags and Play Integrity
  or App Attest verdicts are checked by the other phone, and a lying party has signed their lie.
- **Colluding riders and drivers** can fake whole rides to farm reputation or governance weight. Proofs cannot
  stop two people agreeing on a fiction. Each completed ride costs the R$ 1,00 fee, which prices this, and
  governance can weight votes by rides with distinct counterparties.
- **ETAs have no live traffic.** Valhalla uses speeds from OSM road types, so São Paulo rush-hour ETAs will run
  short. Drivers' anonymised trip speeds could later feed a shared, governed speed table; that is a separate
  design.
- **Map quality** is OSM's. Missing streets or wrong one-ways show up as route mismatches. KlippelGo should
  encourage drivers to fix OSM directly, and those fixes arrive with the next monthly pack.
- **Padding within the detour tolerance.** The driven distance is bounded by how far the log itself moved,
  stretched by the detour tolerance. A receipt can therefore overstate a trip by up to that tolerance where the
  streets allow it. With upfront fares this moves no money; it matters if fares ever become metered.
- **Trips that leave their route on rural tracks** may get no receipt, because map-matching fails there (see
  [What is built](#what-is-built)). The ride can still be settled by the rider confirming, or disputed.
- **Turn restrictions** are not in the proof index, so the verifier accepts a path through a forbidden turn.
  Valhalla does not route through them.
- **App size and build effort.** Valhalla is a large C++ dependency. The app uses prebuilt libraries for it,
  which ties the app to their release schedule and their Android SDK and Kotlin versions.
- **Licences.** OSM data is ODbL: the app shows "© OpenStreetMap contributors" on the map, and packs are
  published openly under ODbL, which suits a project that publishes its packs anyway. All libraries above are
  compatible with the repository's AGPL-3.0.

## Changes to existing code

| Where | Change |
| --- | --- |
| `contracts/` | New `MapRegistry` (governed). `RideEscrow.complete` and the timeout path take a `receiptHash`. |
| `core/` | New `verify` crate. Canonical encodings and hashes for `RouteQuote` and `TripReceipt`. Trace log and key certification. New `proofs` crate: packs on disk, Valhalla's answers turned into paths, evidence files, the juror tool. |
| Android app | Replace `MapSurface` and `RouteArt` placeholders with MapLibre. Request carries route distance and duration. Pack download and check screen. |
| New `mappack/` | Docker build for a region pack and the deterministic proof-index tool. |

## Build order

1. **Pilot pack and a real map.** Build the pilot city's pack, show it in the Android app with MapLibre, offline
   address search. No proofs yet.
2. **On-device routing.** Valhalla in the Rust core, route and ETA on the rider's phone, distance in the gossip
   request, driver navigation with Ferrostar.
3. **Route quote proof.** Proof index, verifier, signed quote, `MapRegistry` on the local chain.
4. **Trip proof.** Trace logs with countersigned heads, map-matching, signed receipt, `receiptHash` on-chain.
5. **Dispute tooling.** A juror tool that runs the verifier on revealed evidence, and default rules for small
   fares.
6. **Zero-knowledge study.** Benchmark the verifier in SP1 and RISC Zero on a phone.

## Open decisions

| Decision | Options | Default |
| --- | --- | --- |
| Router | Valhalla, GraphHopper | Valhalla |
| Proof strength at launch | Signed and re-checkable, or zero-knowledge from day one | Signed and re-checkable, zkVM-ready verifier |
| Fare basis | Upfront fare bound to the quoted route, or metered from the trip proof | Upfront, with signed amendments for detours |
| Distance tolerance between phones | Any percentage | 2% |
| Pack update cadence | Weekly, monthly, quarterly | Monthly |
| Rebuild quorum to activate a pack | Any number of validators | 3 |
| Pilot city | One metro area | Cascavel, Paraná |
| Detour tolerance when following a log along a path | Any percentage | 25% |

## What is built

| Step | State |
| --- | --- |
| 1. Pilot pack and a real map | Built. `mappack/` builds the Cascavel pack; the Android app downloads it, checks it against `MapRegistry`, draws it with MapLibre and searches it offline. |
| 2. On-device routing | Built on Android, for cars: Valhalla routes on the phone and the request carries distance and duration. Driver navigation with Ferrostar is not started. |
| 3. Route quote proof | Built: proof index, verifier, `MapRegistry` with rebuild attestations, the rider's phone locking the real quote hash, and the chosen driver's phone checking the route on its own pack before the driver accepts. The route reaches the driver sealed to a key in their offer, over gossip, in place of the encrypted channel. |
| 4. Trip proof | Built and tested in the core and on chain: logs with countersigned heads, map-matching, the receipt, `receiptHash` in `complete`, `submitReceipt` and `timeout`. Not in the app, which sends a stand-in receipt hash. |
| 5. Dispute tooling | `klippelgo-juror` checks revealed evidence against a pack. Default rules for small fares are not started. |
| 6. Zero-knowledge study | Not started. The verifier is `no_std` and integer-only, as that study needs. |

What keeps step 4 out of the app is the encrypted ride channel: the trace keys and the log heads cross it in
both directions throughout the ride, and it is not built. The one message step 3 needs, the route for the chosen
driver, travels sealed over gossip instead (`core/src/sealed.rs`): X25519 to a key the driver put in their
signed offer, then ChaCha20-Poly1305, bound to the quote hash. The rider's app places the pickup with a pin
instead of reading the phone's location, and has no way to choose a motorcycle.

**Where the build departed from the design above**

- Valhalla reaches the phone through valhalla-mobile's prebuilt libraries, called from Kotlin, instead of a
  `cxx` binding inside the Rust core. The core builds Valhalla's requests and reads its answers as JSON, so the
  platform code only passes strings. iOS can do the same with valhalla-mobile's Swift package.
- Search runs in the Rust core on a SQLite it bundles, so it does not depend on which SQLite features a phone's
  system library has.
- The quote hash has two levels and a salt, and the quote names its vehicle and the quote it amends (see
  [Route and price](#1-route-and-price-before-the-ride)).
- `timeout` needs the driver's receipt on record and counts from it.
- Trace keys sign a head's digest with ECDSA P-256 over SHA-256, which is what the Secure Enclave and StrongBox
  offer. Each log carries the other phone's trace key and its certificate, so one log is enough to check a
  receipt when a party withholds theirs.

**What the prototype measured**, on Valhalla 3.9.1, with the Cascavel pack and with a pack of central São
Paulo, a much denser network:

- Every sampled route, car and motorcycle, translated into a path the verifier accepts: 1000 of 1000 in
  Cascavel (15 769 km) and 1500 of 1500 in São Paulo (14 485 km).
- The verifier's distances are within 0.27% and 0.31% of Valhalla's own for every one of them.
- Every simulated ride passed from route to juror, 100 in Cascavel and 120 in São Paulo: a route, two GPS logs
  with 3 to 4 m of noise, signed and countersigned heads, a receipt.
- A receipt's driven path is the quoted route when the logs followed it. Written from the map-matched log
  alone, as a trip that left its route needs, all 120 São Paulo rides still pass, 4 of them after dropping a
  detour map-matching invented. In Cascavel 87 of 100 do: on the farm tracks around the city Valhalla's
  map-matching loses the thread and returns disconnected pieces. Bridging those is not built.
- The Cascavel pack is 7.6 MB; the São Paulo one, 15 km across, 73 MB.
- A 14 km trip's evidence, with both logs, is 355 KB of JSON. A 3.8 km route touches 23 chunks of the proof
  index, 162 KB.
- The proof index is rebuilt to the same root on the host and in Docker.
- Getting there took aligning the proof index's tag rules with Valhalla's in two places: driveways, and paths
  explicitly opened to motor vehicles. `mappack/README.md` has the commands to repeat all of this on any pack.

Not measured: anything on a real phone. Route time, memory and battery on a mid-range Android device, and real
GPS logs instead of simulated ones, are still open. The debug APK is 123 MB with two architectures, most of it
Valhalla and MapLibre.
