# Apps: security

The app is where an attacker actually meets KlippelGo: it takes messages from strangers, downloads map data
from a mirror, holds the keys, and shows the user what to believe. The numbered invariants (`A1` to `A17`) are
what the [security reviewer](../../.claude/agents/security-reviewer.md) checks a change against.

Related: [overview.md](overview.md), [core security](../../core/docs/security.md),
[contract security](../../contracts/docs/security.md).

## What the app must protect

| Asset | Threat |
| --- | --- |
| The account key | Theft means the user's funds, stake and reputation. It is a P-256 key in the phone's chip (StrongBox or the TEE, `KeystoreDeviceKey`), never exportable. It asks for no user check until recovery exists (P20) |
| The session key | Theft lets someone speak for the account on gossip until its certificate expires (a day). It moves no money |
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
    apkmirror[(APK mirror)]:::untrusted
    installer[Android installer<br/>same signing key only]
    rpc[(RPC provider)]:::semi
    bundler[(Bundler)]:::semi
    chain[(Contracts)]:::trusted

    gossip -- "every message validated on entry" --> mm
    mirror -- "hashes checked against the chain" --> ms
    apkmirror -- "SHA-256 checked against ReleaseRegistry" --> up[Updater] -- "the person confirms" --> installer
    rpc -- "reads are believed" --> cl
    cl -- "operations the device key signed" --> bundler --> chain
    bundler -- "gas estimates (capped), receipts (believed)" --> cl
    mm --> vm --> ui
    ms --> core
    mm --> core
    vm --> store

    classDef untrusted fill:#fdd,stroke:#c00
    classDef semi fill:#ffd,stroke:#a80
    classDef trusted fill:#dfd,stroke:#080
```

Gossip and mirrors are hostile by assumption. The RPC provider is the weak point: the app believes what it
reads from it (see limit P3). The bundler takes the account's signed operations to the chain; it cannot change
one, and the phone never takes from it the account's address or an operation's hash (A15), but it is believed
for the gas an operation needs, which the phone caps, and for the receipt the phone reads a ride's id from. Its
address comes from the RPC address, so P2 and P3 cover it too.

## Gossip messages and their checks

Every message is JSON, signed over a hash that starts with a versioned domain tag. The signer is the account's
session key: a secp256k1 key the phone keeps in app-private storage, rotated before a day is out. Each message
carries the session key's certificate, which the account's own key signed: this session key speaks for this
account until then (`KlippelGo session v1`, with the chain and both addresses). A peer recovers the session key
from the signature, checks that the certificate names that key and the account the message claims, and asks the
account on chain whether its keys signed the certificate (ERC-1271 `isValidSignature`); the answer is kept for
the certificate's life. "Signature recovers X" below means all of that, for account X. `Matchmaker` drops a
message that fails any check, silently, before storing anything.

| Message | Signed by | Domain | Checks on receipt |
| --- | --- | --- | --- |
| `request` | Rider | `KlippelGo request v4` | Heard in the gossip cell its pickup region lies in, and in no other; not a request this phone saw closed; not expired and expiring within an hour; at the cap of 200 requests, no farther than the farthest one held; token is a known coin; positive fare, distance and duration; both region cells valid; price inside the fare guide's range for the stated trip; signature recovers the rider, a certificate not yet confirmed being one of 64 such reads per tick; signer passes the policy on `RideEscrow.open` on chain (the rider role, and not blocked by governance), asked again every five minutes; the map pack is active in `MapRegistry` |
| `offer` | Driver | `KlippelGo offer v3` | For a known request; sequence number newer than the driver's last; expires before the request does; fare within the driver's floor and ceiling; fare above the token's fee plus the tax on it; signature recovers the driver over the quote hash recomputed locally; at 50 offers a request, after all of those, it takes an expired offer's slot, or if the driver passes the policy on `RideEscrow.accept` that of an account that does not, or if cheaper the dearest offer's, and is dropped otherwise |
| `close` | Rider | `KlippelGo close v2` | For a known request; signature recovers that request's rider. The request is then forgotten, unless it is this phone's or it quoted it, and its id remembered until it expires (the newest 2 048) |
| `route` | Rider | `KlippelGo sealed route v3` | Addressed to this account; for a known request this phone quoted; signature recovers the request's rider over the sealed bytes and the rider's position key; opens with this phone's offer secret for one of its own quote hashes; the route starts and ends in the regions the request showed |
| `position` | Driver | `KlippelGo sealed position v2` | Heard on the ride's own topic, and named for that ride; for a ride this phone locked; signature recovers that ride's driver; opens with this phone's key for that quote; stamped by the driver later than every position before it, so a replayed one is dropped. How fresh it is counts from when it arrived, not from the driver's clock |

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
    D->>C: riderMayAsk? hasPermission(rider, open)? packIsActive?
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
| Driver on another ride | Nothing | | "Yours is next", no position | |
| Driver on this ride | Nothing, unless they join the ride's topic, `/klippelgo/ride/1/<quote hash>`, which anyone can from the hash on chain: then, as the router does, that the driver is sending positions for this ride, not where the driver is | | Driver's position, every few seconds, and every second once the ride has started | |
| Arrival | | | | Receipt hash |

A driver's phone subscribes to the cell it is in and the eight around it, chosen from the cell and not from
where in it the phone is: the router and the phone's peers learn which 5 km cell an online driver is in, and
when they cross into another, and nothing finer. A rider's phone subscribes to the cell of each request it has
out, so they learn the 5 km cell of that pickup, and to the topic of each ride it watches, so the router learns which
phone is the rider of that ride and, from who publishes there, which is the driver.

Every signed message also carries its session key's certificate: the account, the session key's address and
when it expires. Nothing there was not already learnable from the signature, but it means every message an
account sends in a day names the same session key.

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
  the quote hash locked in the escrow, and only for the ride the quote names: the ride's fare, coin, rider and
  driver must be the quote's own (`CheckedQuote` gives them), since anyone can open a ride with a quote hash
  they saw on gossip.
- **A6. Prices outside the band are ignored on both sides.** The band is read from `FareGuide`, never
  hard-coded, and read again when the last read is half a minute old, so a tariff Finanças changes reaches a
  running phone without a restart. A read that fails keeps the last tariff for another half minute.
- **A7. A pack is used only if the chain vouches for it**: manifest hash is the on-chain id, files match the
  manifest, proof root matches `MapRegistry`, and the pack is active.
- **A8. Downloaded paths cannot escape the pack directory.**
- **A9. Exact places leave the phone only sealed to one counterparty.** In the clear: geohash cells only.
  A rider's phone reads its own position only to put the pickup pin there. It is never sent as a position;
  it leaves as any pickup does, inside the sealed route.
- **A10. A driver's position goes only to the rider of the ride under way**, and stops when that ride ends.
  It goes out from a loop of its own, at most every four seconds on the way to the pickup and every second once
  the ride has started, on what the last full read took from the chain: that is believed for six seconds from
  when it was read, or from the last check of the new blocks that found no event about the rides on screen, so
  sending stops within that of the ride ending, and as soon as a full read or a check is seen to fail or the
  driver signs out. Each check reads the 16 blocks before the new ones again, so an event a lagging node left out,
  or a short reorganisation added, still ends the belief at a later check. A full read that a node behind the
  others answers after a check reported the event can miss it, and then the belief holds until the next full
  read, at most 30 s.
- **A11. Memory taken by strangers is bounded**: caps on requests and on offers per request, topic names of a
  fixed length and a router's topics capped (K12), and requests expire. At a cap, something is given up only for a newcomer whose signature and limits passed (A1), and never
  this phone's own request, a request it quoted, or its own quote. Limit P13 lists what is not bounded yet.
- **A12. Secrets stay in app-private storage or memory.** Offer secrets are memory only. Nothing secret is
  logged, put in an intent, or written to shared storage. `allowBackup` is off. The account's key never leaves
  the phone's chip; the session key is in app-private preferences.
- **A15. Every write is the account's own operation, its hash is the phone's, and its cost is bounded.** The
  phone computes the account's address from its key and the operation's hash (EIP-712, as the EntryPoint does)
  itself, never from the node or the bundler, and signs only that hash: a lying RPC or bundler cannot get a
  signature for another operation, nor have reais sent to an address the key does not own. Before signing, it
  refuses gas limits above fixed ceilings (per kind of gas), a fee per gas above 1 000 gwei, and any operation
  that could cost more than R$ 5,00 at the worst, at the rate it reads from `FareGuide`: a bundler cannot make
  one operation take the account's balance. A lying RPC can lower that rate (P3); the gas ceilings still bound
  the operation. It gives the paymaster's check at least 80 000 gas whatever the bundler estimates, since that
  check's cost depends on what is cold when the bundle runs; the EntryPoint charges verification gas as used, so
  the floor costs nothing unless spent, and the R$ 5,00 worst case includes it.
- **A13. Cleartext traffic is allowed only to loopback and the emulator's host alias.**
- **A16. A revoked release starts nothing, and nothing else stops the app.** When the release registry says this
  build's release is revoked, the view model refuses to ask for a ride, change its price, take an offer, quote
  and accept, and shows why (the guard is in `announce`, which every request goes through, and in each action
  that quotes, locks or accepts); a ride under way still finishes and money owed is still withdrawn. While a
  ride of the account is unfinished, the app offers no update, since the new release's app could not finish it
  (P25). A release the
  registry does not name, names with other code contracts than this build's, or that cannot be read, counts as
  unknown and changes nothing, so neither the RPC nor the registry is a switch that turns the app off.
- **A17. Only an APK the release registry names is handed to the installer.** `Updater` keeps a download only if
  its SHA-256 is one `ReleaseRegistry.isApk` names for that release; anything else is deleted unread. A download
  is capped at 400 MB, lives in `cacheDir/updates/`, the only folder the FileProvider shares, and Android
  installs it only after the person confirms and only if it is signed with the app's own key.
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
| P2 | `MainActivity` is exported and reads `role`, `rpc` and `pair` extras | Another app on the device could start KlippelGo with a role and an RPC it controls, and the RPC is saved, so it outlasts that launch. The seeded test accounts, and the `devAccounts` and `e2eAccounts` extras, work in debug builds only. Through that RPC it can also reach everything P3 lists, and so the case there of a service kept running. | Accept the remaining extras only in debug builds too |
| P3 | The app believes its RPC provider | A lying RPC can show a fake tariff, pack root, ride state or balance. A ride once reported finished is remembered as finished until the app restarts. The event scans and the check for changes believe its logs too: one that leaves out a ride's events keeps the screen up to 30 s behind, and one that leaves out a `RideOpened` keeps that ride off the screen, as a fake ride state could. Reporting a ride under way keeps `OnDutyService`, its wake lock and the phone's GPS going off screen, on a phone that came on screen once. The RPC's host also names the gossip router (K12). | Light-client proofs or several independent RPCs; at minimum a user-chosen RPC |
| P4 | In `receiveOffer`, the token's fee is read from the chain (`tokenConfig`) before the signature is checked. A request from an account that confirms its certificate on chain costs one `hasPermission` read, and one more every five minutes while it keeps posting; the answers are kept per key and never pruned. Each request naming a new pack costs a `packIsActive` read (two calls), under the matchmaker's lock, with no limit per tick, and the answer is kept (P13). Requests under a certificate not yet confirmed, and the `canAcceptRides` reads of offers making room, share 64 per tick, charged even when `certified` then answers without a read. Offers, closes, routes and positions under forged certificates are outside that count, a read each. A rider whose certificate this phone already confirmed spends none of the 64 until their daily key change, so only an overflowing inbox (about 2 000 messages a second) can drop their announcements. A rider new to this phone competes with whatever came first in the drain: about 32 messages a second under one forged certificate use up the 64, at no read, and can keep that rider off that phone for as long as they last. Every message's certificate costs one `isValidSignature` read the first time it is seen; the same certificate again costs nothing (four lookups a minute per session key, answers kept bounded at 4 096). A stranger can send forged certificates with fresh session keys, one read each | A flood of offers for a known request, of requests from fresh keys, or of messages under forged certificates makes each phone do RPC calls, one per forgery. It cannot use up an honest session key's lookups with forged ones. A `position` the driver really signed, sent again with junk certificate bytes, is charged to that driver's session: until a phone has confirmed the certificate (its first read failed, or around the daily key change) such copies can keep its positions off that rider's screen. No money is at stake | Check the signature first; cache the fee like the tariff; a cost or a limit per gossip sender |
| P5 | Offers are stored from any key until 50 arrive. Past that an offer takes the slot of an account that may not accept, or of a dearer offer, so `canAcceptRides` is read only then: once per driver in five minutes, up to 1 024 answers kept, within the 64 per tick (P4); with none left a new driver's offer is dropped until announced again. Those reads hold the matchmaker's lock. An offer evicted loses its sequence number, so an older unexpired quote of that driver can be replayed into its place | Throwaway keys hold slots only until real drivers need them, while reads are left: with none, a real driver takes the dearest offer's slot instead. Fifty accounts that may accept, quoting at the floor, keep dearer drivers' offers out until theirs expire. The rider's screen hides offers from accounts that cannot accept, so no funds are at risk | A cost for quoting; a limit per gossip sender |
| P8 | Routes, quotes and rider keys on disk are not encrypted beyond Android's app sandbox | A rooted or seized phone reveals past rides | Encrypted database, retention limit |
| P9 | Pickup proof and trip receipt are stand-in hashes; GPS logging is not wired | The trip itself is unproven on the app today | The ride channel, then `TripLog` and receipts from the core |
| P10 | The rider's address and price are visible to the cell, linked to a region | A watcher can profile an address's trips by region | Fresh accounts per ride; encrypted offers |
| P11 | A position is whatever the driver's phone says, and it counts as fresh from when it arrives | A driver can report a place they are not at, and on a ride it also decides how much of the route the rider's map still shows, when it is within 150 m of the route. A relay that holds the messages back and releases them in order makes old positions look current. A driver whose clock steps backwards mid-ride stops being shown | It is for display. Never use it as evidence: that is what the signed trip log is for. A per-ride counter instead of a clock, and an estimate of the clock offset, would close the last two |
| P12 | Anyone with the rider role can open rides naming any driver, and cancel them later at no cost | Each one costs that driver's phone its id kept in the phone's scans, and its opening wakes a full read on that driver's phone and on the phones of riders waiting for that driver; while it is among the driver's ten newest and Open, its events do too. Its ride is not read: a full read reads the ten newest rides and the two the driver is busy with, which the escrow names (`pickupOf`, `onTripOf`), however many name the driver. The driver's screen shows the newest ten rides, so ten junk rides hide a real rider's Open ride, and older junk cannot be declined from the app. None of them takes the driver's places in the escrow (C22). The phone declines on its own only a ride locked on a quote it made void by accepting another, by the rider that quote was for, and once per quote: a hash copied into rides by anyone else, or into more rides by that rider, costs the driver nothing. A rider who opens the same quote twice gets two rides that both pass the route check (A5), as all their terms are the rider's own; accepting one leaves the other Open, for the driver to decline or the rider to withdraw. A ride left Open does not keep that driver's phone working off screen: only one they accepted does. | Find accepted rides from `RideAccepted` logs in one query; show the driver the oldest Open rides; a cost for opening |
| P13 | The matchmaker's caches of "is a rider" and "pack is active" answers are never pruned, and a "no" is kept until the app restarts | Memory grows with distinct signers seen; an account that registers after its first request is ignored by phones that saw it | Bound and expire both caches |
| P14 | The driver's Accept button does not require the revealed route to have been checked | A5 holds by what the screen shows the driver ("rota conferida no seu mapa"), not by code: a driver can accept a ride whose route never arrived or failed the check | Enable Accept only once the route passed `check_quote` against the locked hash |
| P15 | The rider's "driver is busy" notice starts from "nobody ahead" until the first successful read | A waiting rider whose first read fails sees no notice for a moment. No position is shown or sent | Keep the count unknown until read, and show neither state meanwhile |
| P16 | The matchmaker's requests are in memory, and a phone takes any validly signed request from its own account as its own | After a restart, or with the same account on two phones, a peer replaying this rider's unexpired request (up to 10 minutes) puts it back on the rider's screen and it is announced again. It cannot be locked: the phone no longer holds its route (A4). A pickup move in progress is refused with a message | Keep the open request with its route on disk; ignore own requests this session did not make |
| P17 | The driver's list shows the one picked and the newest others, 20 in all, of the up to 200 requests a phone keeps, and one rider-role account can post all 200. A full list keeps the nearest, but the pickup region is what the request says, unproved until a route is revealed, and it costs nothing to name one in a busy part of the cell | An older real request sits under "N more" until those above it are passed or expire, and a flood naming places nearer than a real pickup pushes that request out of the list until it is announced again and is nearer than the farthest. Before the list, one request showed at a time and the same flood cost one pass each | A cost for posting requests (P4), and ordering the screen too, by the rider's score or by distance |
| P18 | Debug builds hold anvil's public deployer key, to mint test reais (the Pix stand-in, `Faucet`) | Anyone can mint mock coins on a local testnet anyway | It refuses any chain but 31337. Release builds keep the code: removing it from them would close it |
| P19 | A session certificate cannot be revoked before it expires: a stolen session key speaks for the account on gossip for up to a day | Peers check it once and keep the answer | A shorter life, or a revocation list on chain the account writes to. Removing the account's key does not help: the certificate was valid when it was checked |
| P20 | The account's key is not bound to the phone's screen lock: whoever holds the unlocked phone can sign for the account and move its money | Android destroys a key bound to the lock when the lock is turned off; with no second signer on the account yet (P21), that would lose the account and its money for good | Recovery first (P21); then make the key with `setUserAuthenticationRequired`, so that a lock turned off costs only this phone's key |
| P21 | Losing the phone loses the account: recovery by a passkey (designed in docs/accounts-and-pix.md, accepted by the account's WebAuthn signers) has no screen yet | Android passkeys need a domain that serves `assetlinks.json` | The domain, then the setup screen that adds the passkey as a second signer |
| P22 | A closed request stops being announced, and a phone that never heard it close has nothing telling it so | A peer can replay a closed request's signed message to a driver who missed the close or arrived later, and it shows as open for as long as the replay goes on, up to its expiry. The rider's phone answers what it hears with the close, at most every 10 s, while it is online and in that cell, which also tells any peer that this rider's phone is still there. An offer on it goes nowhere: the rider no longer listens for it. No money is at stake | The ride channel; a cost for relaying |
| P23 | Anyone can join a ride's topic and publish there, as on any topic | They see when a driver's positions go out, sealed. Each forged position costs the rider's phone one signature recovery, and a read for a new certificate, with no limit per tick (P4). It cannot be believed or stop the route | A topic name only the two phones know, sent inside the sealed route; a limit per gossip sender |
| P24 | The release status and an APK's hash are what the RPC says. A lying RPC can call this build's release revoked, and the app starts no ride until the person changes provider or the lie stops. It can also call a release active that the registry revoked, with `isApk` true: whoever runs both the RPC and the APK mirror (by default the mirror is the host next to the chain node) can then serve that release's genuine APK, signed with the release key, as the update, and Android installs it once the person confirms | The registry is read like any other contract (P3) | A revoked status stops only new rides, never money (A16). The app offers only a higher version than its own, so it is never taken back to an older release. A stranger's own APK still needs the release key, which Android checks. An escrow closed on chain refuses rides whatever the RPC says. A second source for the latest release's status, light-client proofs or several RPCs, as for P3 |
| P25 | A ride left on an earlier release's escrow cannot be finished from the app: it lists the ride and withdraws money owed there, but sends `start`, `complete`, `cancel`, receipts and timeouts only to its own release's escrow | Not built | The app holds an update back until the chain was read once and while a ride of the account in its current role is accepted, started, disputed, or open and locked by this account; it checks again before opening the installer, so a ride started during the download keeps the checked file waiting. An Open ride someone else opened naming the driver does not hold it: it holds no bond of the driver's, and its rider takes it back on the old escrow. Not covered: a ride of the account in its other role, a ride left behind another way (the app reinstalled at a newer release, the other party updating). Those need their transactions sent by hand. Closes when the app can act on `previousEscrows` |

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
| `ForgedRequestsTest` | A1, A6, and of A7 that a request on an inactive pack is ignored; the `request` and `close` rows, and the session certificate every message carries | Requests that each break one rule never reach a driver's screen: wrong signer, no rider role, price over and under the range, inactive pack, expired, expiring too far ahead, invalid pickup cell, zero distance, unknown coin, said in a cell its pickup is not in; and, in the honest rider's name, a certificate another account signed, one that expired, one claiming to last a week, and the rider's real certificate on a message another key signed. A request signed by a rider governance blocked never reaches it either. A withdrawal signed by anyone but the rider does nothing |
| `ForgedOffersTest` | A1, A3, A6; the `offer` row | Nine offers that each break one rule never reach a rider's screen: wrong signer, over the ceiling, under the floor, a valid signature moved to another fare or another expiry, expiring after the request, expired, for another request, and a well-formed one from an account the policy does not let accept |
| `OnDutyTest` | A10; `OnDutyService` | On an emulator: the service starts and stops with going online and offline, outlasts a ride's end while online, its notification brings back the running activity, and with the driver's screen off a rider goes on hearing the car |
| `RoutePrivacyTest` | A9; "Who learns what" | Everything said in the cell, and on the ride's own topic, during a ride is recorded: the request has the documented fields and no more, and no point of the route or of the driver appears in the clear, nor inside the sealed fields once their base64 is undone |
| `ReleaseTest` (integrity) | A16 | A revoked release refuses a rider's request and a driver's quote and says why, and works again once put back; a deprecated one goes on; an update to a newer release waits until the driver's ride is over. `ReleaseCheckTest` (`:chain`) pins that only a revoked status refuses, that an update goes only to a higher active release, and that an unreadable registry or a release published with other code contracts is unknown and refuses nothing |
| `ReleaseUpdatesTest` | A17 | Against the chain, not the screen: a release published naming one APK file; that file is accepted, an altered copy and the same file under another version are refused |
| `RevealedRouteTest` | A5 | A drivable route in the right regions that is not the one locked in the escrow is never shown as checked, and the same trip revealed honestly is. A ride a stranger opened with the honest quote's hash, driver, coin and fare is never shown with that route, while the honest ride beside it is. It stops short of P14 |
| `ThreePhonesTest` (integrity) | The honest path of A4 and A5 between real apps | Three copies of the app, a rider and two drivers: the driver not picked gets no ride and no route, and the one picked is shown the route as checked |
| `RiderRideTest` (integrity) | A14, and the honest path of A4 | The amounts on the confirmation screen are the amounts the escrow holds after the lock |
| `OnboardingTest` (integrity) | The account's key and A15 on the honest path | A phone given no seeded account makes its own key; its account is created and registered as a rider by its first operation, which the account pays in reais, and the address the profile shows is that account's |
| `RuleChangesTest` | A14 with governance in the middle; contracts C1, C5, C14 | A ride under way keeps its tax when Finanças halves it mid-ride: on the ride-end screen, in the collector's balance and in what the driver nets, while the rules screen moves to the new rate. An offer both phones matched cannot be locked once Segurança stopped new rides: the app shows the refusal and no money moves; once the board resumes, the same offer locks. A tax change one member of two asked for changes nothing on screen or on chain until the second approves |
| `GovernanceTest` (integrity) | A6, and the driver's notice of why they cannot accept | A tariff Finanças changes reaches the running app's rules screen and the price of its next request. `stop-rides` and `resume-rides` show and clear the "disabled" reason on a driver's screen; a block shows and clears the "account" reason, and the unblocked driver hears requests again |

In each forgery test the stranger also sends a message of its own that breaks no rule, and it must be shown:
that is what says each forgery was turned away for the one thing wrong with it.

Still owed:

- A position on a topic of another ride this rider watches is dropped: no test locks two rides. One on the cell's topic is tested (`TwoPhonesRideTest`).
- The service stopping on sign-out, on a change of role, and for a rider at the end of a ride.
- A10 from the driver's side: a driver's app holding two rides sends positions only for the current one.
  That it stops when the ride ends is covered (`OnTheRideTest`); that it stops when the chain cannot be read
  is not.
- `RouteProgress` on a route that passes the same place twice has no test.
- That a refresh belonging to a session that was left (another account picked, or the phone switched to
  riding) cannot set the ride positions are sent for, nor send one itself (the role and session check in
  `sharePosition`), has no test.
- The rider's pin following the phone (`RiderRideTest`) is shown to stop once the rider has placed the pin by
  hand, and to start again after a ride. With the pin placed by hand the later steps of that test (a
  destination picked, a request out) no longer show those two checks by themselves, and that it stays put
  during a ride has no test. The tests read the pin's name: that the map itself moves under the pin when the
  pickup follows the phone has none.
- The replay rule for positions, and a forged `position` shown to be ignored.
- The `route` row's signature and addressee checks, sent as forgeries.
- The request cap and ranking, a request nobody signed at a full list, a forged position and the replay of a closed
  request are held by `TwoPhonesRideTest`, not end to end in `tests/security`. Eviction sparing own and quoted
  requests has no test. Making room at 50 offers (`roomFor`) has none: it takes fifty accounts with a session
  certificate on chain. The limit of 64 reads per tick has none either.
- The offer sequence rule, zero fare, zero duration, and an
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
