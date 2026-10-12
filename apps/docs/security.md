# Apps: security

The app is where an attacker actually meets KlippelGo: it takes messages from strangers, downloads map data
from a mirror, holds the keys, and shows the user what to believe. The numbered invariants (`A1` to `A14`) are
what the [security reviewer](../../.claude/agents/security-reviewer.md) checks a change against.

Related: [overview.md](overview.md), [core security](../../core/docs/security.md),
[contract security](../../contracts/docs/security.md).

## What the app must protect

| Asset | Threat |
| --- | --- |
| The account key | Theft means the user's funds, stake and reputation |
| Where the user is and where they go | Any peer in the cell, the router, the mirror, the RPC provider |
| The user's money | Being shown a wrong price, a wrong route or a wrong counterparty and locking funds on it |
| The user's attention | Fake requests and offers that waste a driver's or rider's time |

## Trust boundaries

```mermaid
flowchart LR
    subgraph phone [The phone: trusted]
        ui[UI]
        vm[AppViewModel]
        mm[Matchmaker]
        cl[KlippelGoClient]
        ms[MapStore]
        core[Rust core]
        store[(App-private storage)]
    end
    gossip((Gossip<br/>anyone)):::untrusted
    mirror[(Pack mirror)]:::untrusted
    rpc[(RPC provider)]:::semi
    chain[(Contracts)]:::trusted

    gossip -- "every message validated on entry" --> mm
    mirror -- "hashes checked against the chain" --> ms
    rpc -- "reads are believed" --> cl
    cl -- "signed transactions" --> rpc --> chain
    mm --> vm --> ui
    ms --> core
    mm --> core
    vm --> store

    classDef untrusted fill:#fdd,stroke:#c00
    classDef semi fill:#ffd,stroke:#a80
    classDef trusted fill:#dfd,stroke:#080
```

Gossip and mirrors are hostile by assumption. The RPC provider is the weak point: the app believes what it
reads from it (see limit P3).

## Gossip messages and their checks

Every message is JSON, signed by the account it speaks for over a hash that starts with a versioned domain
tag. `Matchmaker` drops a message that fails any check, silently, before storing anything.

| Message | Signed by | Domain | Checks on receipt |
| --- | --- | --- | --- |
| `request` | Rider | `KlippelGo request v3` | Not expired and expiring within an hour; under the cap of 200 requests; token is a known coin; positive fare, distance and duration; both region cells valid; price inside the fare guide's range for the stated trip; signature recovers the rider; signer has the rider role on chain; the map pack is active in `MapRegistry` |
| `offer` | Driver | `KlippelGo offer v2` | For a known request; sequence number newer than the driver's last; under 50 offers per request; expires before the request does; fare within the driver's floor and ceiling; fare above the token's fee plus the tax on it; signature recovers the driver over the quote hash recomputed locally |
| `close` | Rider | `KlippelGo close v1` | For a known request; signature recovers that request's rider |
| `route` | Rider | `KlippelGo sealed route v2` | Addressed to this account; for a known request this phone quoted; signature recovers the request's rider over the sealed bytes and the rider's position key; opens with this phone's offer secret for one of its own quote hashes; the route starts and ends in the regions the request showed |
| `position` | Driver | `KlippelGo sealed position v1` | For a ride this phone locked; signature recovers that ride's driver; opens with this phone's key for that quote; stamped by the driver later than every position before it, so a replayed one is dropped. How fresh it is counts from when it arrived, not from the driver's clock |

The request id is the hash of all its fields and a random nonce, and the quote hash names the chain, the
escrow, the route hash, the fare, the token, both parties and the expiry. A message copied to another chain,
escrow, request or price fails its signature check.

## Matching, with the checks in place

```mermaid
sequenceDiagram
    autonumber
    participant R as Rider's phone
    participant D as Driver's phone
    participant C as Chain

    R->>R: route verified on the pack, price inside riderRange
    R->>D: request, signed
    D->>C: riderMayAsk? rolesOf(rider)? packIsActive?
    D->>D: recover signer = rider
    Note over D: auto-skip: price per km, share below recommended,<br/>rider this driver just declined
    D->>R: offer, signed over quoteHash, with an offer key
    R->>C: driverMayQuote? fare above fee?
    R->>R: recompute quoteHash, recover signer = driver
    R->>C: open(driver, token, fare, quoteHash)
    R->>D: route sealed to the offer key
    D->>D: open, regions match the request
    D->>D: MapPack.check_quote: path on the map, distance as stated
    D->>C: ride(id).quoteHash == hash of the route received?
    D->>C: accept
```

The driver accepts only after step 12: the route exists on their own copy of the map, is as long as the rider
said, and is the one whose hash holds the money.

## The map pack

```mermaid
sequenceDiagram
    autonumber
    participant A as MapStore
    participant C as MapRegistry
    participant M as Mirror

    A->>C: latest(region)
    C-->>A: pack id
    A->>M: manifest.json
    A->>A: keccak(manifest) == pack id, or stop
    loop each file in the manifest
        A->>M: file
        A->>A: path stays inside the pack directory
        A->>A: size and SHA-256 match the manifest, or delete
    end
    A->>A: MapPack.open, check_files
    A->>C: proofRootOf(pack id)
    A->>A: proof index root == on-chain root, or refuse the pack
```

## Who learns what, and when

| Moment | Everyone in the cell | The chosen driver | The rider | The chain |
| --- | --- | --- | --- | --- |
| Request | Distance, duration, price, two regions of about 1 km, rider's address | same | | nothing |
| Offer | Driver's address and fare | | | nothing |
| Lock | That a sealed route went to that driver | The exact route | | Both addresses, token, fare, quote hash |
| Driver on another ride | That the driver is sending positions for some other ride | | "Yours is next", no position | |
| Driver on this ride | That the driver is sending positions for this ride: the message names the quote hash, which is on chain. Not where the driver is | | Driver's position, every few seconds, and every second once the ride has started | |
| Arrival | | | | Receipt hash |

The driver's position is sealed to a key the rider sent inside the signed `route` message, and the driver's
phone sends it only for the ride it is on now: of all the rides it accepted and has not finished, the one
that started, or else the one locked first. A ride in dispute is frozen and takes no turn, so the next rider's
turn comes while it is judged.
That is read from every ride naming the driver, not from the newest few, because anyone can open a ride naming
a driver and a short window could be filled to hide the real one.

## Invariants

- **A1. Nothing from gossip is stored or shown before its signature and limits are checked.** Validation
  lives in `Matchmaker.receive…`; nothing else parses gossip.
- **A2. Every signed structure has its own domain tag and version.** A changed field means a new version.
- **A3. The quote hash is always recomputed locally** from the request and the offer's fields, never taken
  from the message.
- **A4. A rider locks only what was verified.** The route passed the verifier on an active pack; the price is
  inside `riderRange`; the offer is inside the driver's limits and unexpired. The offer is for the request
  this phone has out now: `acceptOffer` refuses unless the route and salt it holds hash to the request's
  route hash, and the offer names that request, so an offer for a request since replaced (another price,
  another pickup) cannot be locked against the new route.
- **A5. A driver accepts only a route they checked** on their own pack, in the announced regions, matching
  the quote hash locked in the escrow.
- **A6. Prices outside the band are ignored on both sides.** The band is read from `FareGuide`, never
  hard-coded.
- **A7. A pack is used only if the chain vouches for it**: manifest hash is the on-chain id, files match the
  manifest, proof root matches `MapRegistry`, and the pack is active.
- **A8. Downloaded paths cannot escape the pack directory.**
- **A9. Exact places leave the phone only sealed to one counterparty.** In the clear: geohash cells only.
  A rider's phone reads its own position only to put the pickup pin there. It is never sent as a position;
  it leaves as any pickup does, inside the sealed route.
- **A10. A driver's position goes only to the rider of the ride under way**, and stops when that ride ends.
  Once a ride has started it goes out every second, between refreshes, on what the last refresh read from the
  chain: that is believed for six seconds from when it was read, so sending stops within that of the ride
  ending, and as soon as a refresh is seen to fail or the driver signs out.
- **A11. Memory taken by strangers is bounded**: caps on requests and on offers per request, and requests
  expire. Limit P13 lists what is not bounded yet.
- **A12. Secrets stay in app-private storage or memory.** Offer secrets are memory only. Nothing secret is
  logged, put in an intent, or written to shared storage. `allowBackup` is off.
- **A13. Cleartext traffic is allowed only to loopback and the emulator's host alias.**
- **A14. Money shown is money locked.** The amounts on the confirmation screen are computed from the same
  values passed to `open`, including fee, bonds and the tax rate read from the chain. The tax shown is the sum
  the escrow does at `open`, at a rate read again every ten seconds, and it is inside the fare: it changes what
  the driver receives, never what the rider locks. Once a ride is open, what the driver is shown is the ride's
  own tax. The infrastructure cost is outside what is locked and is shown as an estimate, marked "≈"; it is
  shown without the mark only at the end of a ride this phone paid for from its first transaction.

## Known limits

Real today. A change touching one should close it or leave it no worse, and say which.

| # | Limit | Consequence | What closes it |
| --- | --- | --- | --- |
| P1 | **Accounts are anvil's public test keys** (`DevAccount`) | No real key protection exists yet. Never usable outside a local testnet | Hardware-backed P-256 keys (StrongBox, Secure Enclave) behind ERC-4337 smart accounts |
| P2 | `MainActivity` is exported and reads `role`, `rpc` and `pair` extras | Another app on the device could start KlippelGo with a role, a test account and an RPC it controls, and the RPC is saved, so it outlasts that launch | Accept the extras only in debug builds |
| P3 | The app believes its RPC provider | A lying RPC can show a fake tariff, pack root, ride state or balance. A ride once reported finished is remembered as finished until the app restarts | Light-client proofs or several independent RPCs; at minimum a user-chosen RPC |
| P4 | In `receiveOffer`, the token's fee is read from the chain (`tokenConfig`) before the signature is checked. A request signed by any throwaway key costs one `rolesOf` read | A flood of offers for a known request, or of requests from fresh keys, makes each phone do RPC calls | Check the signature first; cache the fee like the tariff |
| P5 | Offers are stored from any key, 50 per request, and an expired offer keeps its slot | Fifty throwaway keys can fill a request's slots so real drivers' offers are dropped until the request expires. The rider's screen hides offers from accounts that cannot accept, so no funds are at risk | Check `canAcceptRides(driver)` on receipt, cached; drop expired offers |
| P6 | The account key signs every gossip message | Heavy use of the wallet key; no way to revoke a session | Session certificates |
| P7 | Everyone uses one cell (`DEV_CELL`) | No geographic privacy or sharding yet | Cells from the phone's location |
| P8 | Routes, quotes and rider keys on disk are not encrypted beyond Android's app sandbox | A rooted or seized phone reveals past rides | Encrypted database, retention limit |
| P9 | Pickup proof and trip receipt are stand-in hashes; GPS logging is not wired | The trip itself is unproven on the app today | The ride channel, then `TripLog` and receipts from the core |
| P10 | The rider's address and price are visible to the cell, linked to a region | A watcher can profile an address's trips by region | Fresh accounts per ride; encrypted offers |
| P11 | A position is whatever the driver's phone says, and it counts as fresh from when it arrives | A driver can report a place they are not at, and on a ride it also decides how much of the route the rider's map still shows, when it is within 150 m of the route. A relay that holds the messages back and releases them in order makes old positions look current. A driver whose clock steps backwards mid-ride stops being shown | It is for display. Never use it as evidence: that is what the signed trip log is for. A per-ride counter instead of a clock, and an estimate of the clock offset, would close the last two |
| P12 | Anyone with the rider role can open rides naming any driver, and cancel them later at no cost | Each one left Open costs that driver's phone, and the phones of riders waiting for that driver, one chain read per refresh; a few hundred slow the refresh enough that positions stop looking fresh. The driver's screen shows the newest ten rides, so ten junk rides hide a real rider's Open ride, and older junk cannot be declined from the app | Find accepted rides from `RideAccepted` logs in one query; show the driver the oldest Open rides; a cost for opening |
| P13 | The matchmaker's caches of "is a rider" and "pack is active" answers are never pruned, and a "no" is kept until the app restarts | Memory grows with distinct signers seen; an account that registers after its first request is ignored by phones that saw it | Bound and expire both caches |
| P14 | The driver's Accept button does not require the revealed route to have been checked | A5 holds by what the screen shows the driver ("rota conferida no seu mapa"), not by code: a driver can accept a ride whose route never arrived or failed the check | Enable Accept only once the route passed `check_quote` against the locked hash |
| P15 | The rider's "driver is busy" notice starts from "nobody ahead" until the first successful read | A waiting rider whose first read fails sees no notice for a moment. No position is shown or sent | Keep the count unknown until read, and show neither state meanwhile |
| P16 | The matchmaker's requests are in memory, and a phone takes any validly signed request from its own account as its own | After a restart, or with the same account on two phones, a peer replaying this rider's unexpired request (up to 10 minutes) puts it back on the rider's screen and it is announced again. It cannot be locked: the phone no longer holds its route (A4). A pickup move in progress is refused with a message | Keep the open request with its route on disk; ignore own requests this session did not make |
| P17 | The driver's list shows the one picked and the newest others, 20 in all, of the up to 200 requests a phone keeps, and one rider-role account can post all 200 | An older real request sits under "N more" until those above it are passed or expire. Before the list, one request showed at a time and the same flood cost one pass each | A cost for posting requests (P4), and ordering by something a stranger cannot buy: distance to the pickup region, or the rider's score |

## How it is tested

`TwoPhonesRideTest` plays a ride between two clients over a real gossip node and chain, and asserts what each
side learned.

The end-to-end suite in [`tests/security`](../../tests/security) holds the app itself to the invariants: the
real app on an emulator, a real chain and gossip network, and a stranger on that network who records the cell
and sends forged messages ([tests/README.md](../../tests/README.md)). A new check on a message needs a case
there that sends the bad message, shows the screen never reflects it, and then shows an honest one getting
through.

| Test | Holds the app to | How |
| --- | --- | --- |
| `ForgedRequestsTest` | A1, A6, and of A7 that a request on an inactive pack is ignored; the `request` and `close` rows | Ten requests that each break one rule never reach a driver's screen: wrong signer, no rider role, price over and under the range, inactive pack, expired, expiring too far ahead, invalid pickup cell, zero distance, unknown coin. A withdrawal signed by anyone but the rider does nothing |
| `ForgedOffersTest` | A1, A3, A6; the `offer` row | Nine offers that each break one rule never reach a rider's screen: wrong signer, over the ceiling, under the floor, a valid signature moved to another fare or another expiry, expiring after the request, expired, for another request, and a well-formed one from an account the policy does not let accept |
| `RoutePrivacyTest` | A9; "Who learns what" | Everything said in the cell during a ride is recorded: the request has the documented fields and no more, and no point of the route or of the driver appears in the clear, nor inside the sealed fields once their base64 is undone |
| `RevealedRouteTest` | A5 | A drivable route in the right regions that is not the one locked in the escrow is never shown as checked, and the same trip revealed honestly is. It stops short of P14 |
| `ThreePhonesTest` (integrity) | The honest path of A4 and A5 between real apps | Three copies of the app, a rider and two drivers: the driver not picked gets no ride and no route, and the one picked is shown the route as checked |
| `RiderRideTest` (integrity) | A14, and the honest path of A4 | The amounts on the confirmation screen are the amounts the escrow holds after the lock |

In each forgery test the stranger also sends a message of its own that breaks no rule, and it must be shown:
that is what says each forgery was turned away for the one thing wrong with it.

Still owed:

- A10 from the driver's side: a driver's app holding two rides sends positions only for the current one.
  That it stops when the ride ends is covered (`OnTheRideTest`); that it stops when the chain cannot be read
  is not.
- `RouteProgress` on a route that passes the same place twice has no test.
- That a refresh belonging to a session that was left (another account picked, or the phone switched to
  riding) cannot set the ride positions are sent for, nor send one itself (the role and session check in
  `sharePosition`), has no test.
- The position a refresh itself sends uses the rides read at its start however long the refresh takes: with a
  slow chain, or many rides opened naming the driver (P12), one position can go out that long after the ride
  ended. Sending straight after the read would close it.
- The rider's pin following the phone (`RiderRideTest`) is shown to stop once the rider has placed the pin by
  hand, and to start again after a ride. With the pin placed by hand the later steps of that test (a
  destination picked, a request out) no longer show those two checks by themselves, and that it stays put
  during a ride has no test. The tests read the pin's name: that the map itself moves under the pin when the
  pickup follows the phone has none.
- The replay rule for positions, and a forged `position` shown to be ignored.
- The `route` row's signature and addressee checks, sent as forgeries.
- The caps (200 requests, 50 offers per request), the offer sequence rule, zero fare, zero duration, and an
  invalid drop-off cell.
- "Fare above the token's fee plus the tax" in the `offer` row: with today's tariff and tax the driver's
  floor is always above that, so no gossip message reaches that check. It needs a unit test with a tariff where it can.
- The tax and gas figures the app shows have no unit test of their own: that `taxBps()` is read again after
  ten seconds, and that `gasSpent` gives nothing for a ride this client did not open or accept. The end-to-end
  rides check the figures on the honest path.
- A4's refusals: a lock that the app should decline to make. The check that an offer belongs to the request
  now out lives in `AppViewModel.acceptOffer`, which has no unit test harness; the window it closes (a tap
  in the moment a request is being replaced) is not reachable on purpose from the UI.
- The map pack checks against a lying mirror (the rest of A7, and A8).
- A12 and A13, which are about the device and not the network.

The UI agent these tests drive the app through is in the test APK only. It can read the screen and inject
touches, so that APK belongs on test emulators and never on a phone someone uses; `tests/stack.sh` refuses a
device that is not an emulator, as do the tests and the CLI, and the agent answers only adb's shell user and root.
One CLI command reaches into the app itself: `klippelgo drive` reads a debug build's settings and the route it
saved for the ride under way, with `run-as`, to move the emulator's GPS along it. It makes the same emulator
check first, and a release build does not allow `run-as` at all. The app's own APK gains only the
tags: names on UI pieces, in every build type, readable by the accessibility services that can already read
the text beside them. Two tags say slightly more than the screen: an offer's tag carries the driver's whole
address, where the card shortens it, and a listed request's tag carries the request's id, which the screen
does not show. Both are public: the address on gossip and on chain, the id on gossip.
