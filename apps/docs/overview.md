# Apps: architecture overview

The app is the whole product a rider or driver touches, and it is also the "server": it searches addresses,
computes routes, matches with other phones, checks what they claim, and talks to the chain. The Android app
exists; the iOS app is not created yet and will share the same Rust core.

Related: [security.md](security.md), [scalability.md](scalability.md), the
[core overview](../../core/docs/overview.md), the [contracts overview](../../contracts/docs/overview.md), and
how to build and run in [android/README.md](../android/README.md).

## Layers

```mermaid
flowchart TB
    subgraph app [":app — Android module"]
        ui[ui/<br/>Compose screens: Rider, Driver, Map, Screens, Components]
        vm[AppViewModel<br/>one UiState, one refresh loop]
        maps[Maps.kt<br/>MapStore: download, verify, open a pack]
    end
    subgraph chainmod [":chain — plain JVM module"]
        mm[Matchmaker<br/>signed gossip messages and their checks]
        client[KlippelGoClient<br/>contract calls, signing, fare band]
        models[Models, Deployment]
    end
    subgraph native [Native libraries]
        core[klippelgo-core<br/>Rust via UniFFI]
        val[valhalla-mobile<br/>route search, map matching]
        ml[MapLibre<br/>draws PMTiles]
    end
    subgraph outside [Outside the phone]
        rpc[(Chain RPC)]
        gossip((Gossip router))
        mirror[(Pack mirror)]
    end

    ui -- reads UiState, calls actions --> vm
    vm --> mm & client & maps
    vm --> val
    ui --> ml
    mm --> client
    mm --> core
    maps --> core
    client --> rpc
    core --> gossip
    maps --> mirror
```

| Piece | Path | Responsibility |
| --- | --- | --- |
| UI | [`app/…/ui/`](../android/app/src/main/kotlin/org/klippelgo/app/ui) | Composables that draw a `UiState` and call the view model. No chain, gossip or file access |
| `AppViewModel` | [`AppViewModel.kt`](../android/app/src/main/kotlin/org/klippelgo/app/AppViewModel.kt) | The only holder of state. Runs the refresh loop, owns the gossip node's lifetime, turns user actions into calls |
| `MapStore` | [`Maps.kt`](../android/app/src/main/kotlin/org/klippelgo/app/Maps.kt) | Finds the region's active pack on chain, downloads it, verifies it, opens it |
| `Matchmaker` | [`Matchmaker.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/Matchmaker.kt) | Every gossip message: building, signing, validating, expiring |
| `KlippelGoClient` | [`KlippelGoClient.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/KlippelGoClient.kt) | Every contract read and write, account signatures, the fare band rules |
| Core | [`core/`](../../core/docs/overview.md) | Hashes, route checks, sealing, the gossip transport, address search |

The `:chain` module has no Android dependency. That is deliberate: `TwoPhonesRideTest` plays a whole ride
between two in-process "phones" against a local chain and a real gossip node, on the JVM
(`make android-test-chain`).

## One state, one loop

```mermaid
flowchart LR
    user([User]) -- tap --> action[AppViewModel action]
    action -- contract call or gossip message --> world[(Chain and gossip)]
    loop[Refresh loop<br/>every 2 s, on Dispatchers.IO] -- Matchmaker.tick, reads --> world
    world --> loop
    loop -- copy --> state[UiState<br/>StateFlow]
    action -- copy --> state
    state --> ui[Compose UI]
    ui --> user
```

The UI never keeps a second copy of anything on chain or on gossip. It renders `UiState`; the loop rebuilds
the parts that come from outside. An action that changes the chain does the call, then lets the next refresh
show the result.

## A ride, end to end

```mermaid
sequenceDiagram
    autonumber
    participant R as Rider's phone
    participant G as Gossip cell
    participant D as Driver's phone
    participant C as Chain

    Note over R: search address, route on the phone,<br/>verify route, read tariff
    R->>G: request: distance, duration, two regions, routeHash, price
    G->>D: request
    Note over D: check signature, price band, active pack,<br/>apply auto-skip rules
    D->>G: offer: fare, quoteHash, offer key
    G->>R: offer
    Note over R: check signature and the driver's limits
    R->>C: RideEscrow.open(driver, token, fare, quoteHash)
    R->>G: route sealed to the offer key + rider's position key
    G->>D: sealed route
    Note over D: open, check regions, check route on own map,<br/>compare with the hash locked on chain
    D->>C: accept
    loop while this ride is the driver's current one
        D->>G: position sealed to the rider's key
        G->>R: driver's position on the map
    end
    D->>C: start
    R->>C: complete
    Note over R,D: both rate the ride
```

If the chosen driver declines (`cancel` on an Open ride), the rider's phone gets the refund and announces the
same trip again, skipping that driver. A driver already on a ride can take the next one; the waiting rider
sees that the driver is finishing another ride and gets no position until theirs is the current one.

## What is stored on the phone

| What | Where | Notes |
| --- | --- | --- |
| Role, RPC address, name, account choice, auto-skip settings | `SharedPreferences("klippelgo")` | |
| The quote behind each ride this phone asked for | `filesDir/rides/<rideId>.quote.json` | The rider's evidence |
| Routes revealed to this driver, and each rider's position key | `filesDir/rides/<quoteHash>.route.json`, `.riderkey` | So a ride survives the app closing |
| Map packs | App storage, one directory per pack id | Verified on download and on open |
| Open requests, offers, offer secrets, the rider's key for the driver's position | Memory only, in `Matchmaker` | Gone when the app closes; requests expire by themselves. A rider who restarts the app mid-ride gets no more positions for that ride |

## Structure and patterns

These are the rules new app code follows. The skill that applies them is
[`change-app`](../../.claude/skills/change-app/SKILL.md).

**Where code goes**

| If the code… | It belongs in |
| --- | --- |
| Draws something or reacts to a tap | `ui/`, as a composable taking `UiState` and callbacks |
| Holds or derives state, or sequences calls | `AppViewModel` |
| Builds, signs or validates a gossip message | `Matchmaker` |
| Reads or writes a contract, or encodes something the contracts hash | `KlippelGoClient` |
| Must give the same answer on iOS, or on a juror's machine | The Rust core, not Kotlin |
| Downloads, verifies or opens map data | `MapStore` |

**Patterns**

1. **One `UiState`.** Immutable, updated with `copy`, exposed as a `StateFlow`. A new thing the UI shows is a
   new field with a doc comment saying which role it is for.
2. **Composables are stateless about the world.** They may hold view state (a text field, a sheet's position)
   and nothing else.
3. **Nothing blocks the main thread.** Chain, gossip, files and routing run on `Dispatchers.IO`.
4. **Validation happens where a message enters**, in `Matchmaker.receive…`, before anything is stored. The
   view model and the UI only ever see messages that passed. See [security.md](security.md).
5. **Protocol rules are read, not copied.** Tariff, limits, fees, bonds and policies come from the contracts
   at run time. The app has no constant for a value governance can change.
6. **Money is a `Coin` amount in base units**, formatted at the edge. Never a float.
7. **Coordinates are E7 integers** from the map to the core and back.
8. **Every user-facing string is a resource in three languages**: `values/` (Portuguese, the default),
   `values-en/`, `values-es/`. No literal text in a composable.
9. **The same account in both modes.** A phone has one account whether it is riding or driving; switching mode
   never switches account.
10. **Leave the screen before replacing the app on an emulator** (`KEYCODE_HOME`): the emulator's graphics can
    crash when the app is killed with the map showing. The scripts do it.

**Changing the app**

1. Put the code where the table says. If it must match iOS or a juror, change the core instead
   ([core overview](../../core/docs/overview.md)).
2. A new or changed gossip message: bump its domain version, validate every field on receipt, add it to the
   message table in [security.md](security.md), and cover it in `TwoPhonesRideTest`.
3. Add strings in all three languages.
4. `make android-test-chain`, then `./gradlew :app:assembleDebug`, then play it on emulators
   (`make android-two-phones` or `make android-four-phones`).
5. Update these docs when a layer, a flow, a stored file or a guarantee changed.

**Tooling**: Kotlin 2.3, Compose, compileSdk 36, JDK 17. Chain access through web3j. The Rust core is built for
Android by Gradle through `cargo-ndk`.
