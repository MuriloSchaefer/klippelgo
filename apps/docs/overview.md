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
        ks["KeystoreDeviceKey<br/>the account's key, in the chip"]
    end
    subgraph chainmod [":chain — plain JVM module"]
        mm[Matchmaker<br/>signed gossip messages and their checks]
        client[KlippelGoClient<br/>contract calls, operations, session key, fare band]
        acct[SmartAccount, SessionKey, Bundler<br/>address, operation hash, certificates, bundler RPC]
        models[Models, Deployment]
    end
    subgraph native [Native libraries]
        core[klippelgo-core<br/>Rust via UniFFI]
        val[valhalla-mobile<br/>route search, map matching]
        ml[MapLibre<br/>draws PMTiles]
    end
    subgraph outside [Outside the phone]
        rpc[(Chain RPC)]
        bundler[(Bundler)]
        gossip((Gossip router))
        mirror[(Pack mirror)]
    end

    ui -- reads UiState, calls actions --> vm
    vm --> mm & client & maps
    vm --> val
    vm -- gives the key --> client
    ks --> vm
    ui --> ml
    mm --> client
    mm --> core
    maps --> core
    client --> acct
    client -- reads --> rpc
    acct -- signed operations --> bundler
    core --> gossip
    maps --> mirror
```

| Piece | Path | Responsibility |
| --- | --- | --- |
| UI | [`app/…/ui/`](../android/app/src/main/kotlin/org/klippelgo/app/ui) | Composables that draw a `UiState` and call the view model. No chain, gossip or file access |
| `AppViewModel` | [`AppViewModel.kt`](../android/app/src/main/kotlin/org/klippelgo/app/AppViewModel.kt) | The only holder of state. Runs the refresh loop, owns the gossip node's lifetime, turns user actions into calls |
| `MapStore` | [`Maps.kt`](../android/app/src/main/kotlin/org/klippelgo/app/Maps.kt) | Finds the region's active pack on chain, downloads it, verifies it, opens it |
| `Matchmaker` | [`Matchmaker.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/Matchmaker.kt) | Every gossip message: building, signing, validating, expiring. Which cells to subscribe to: a request's messages go to its pickup's cell, a driver hears the cells around them |
| `KlippelGoClient` | [`KlippelGoClient.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/KlippelGoClient.kt) | Every contract read and write, the session key and the check of others' certificates, the fare band rules. Writes are the account's operations (`sendCalls`): built, hashed and signed here, sent through a bundler |
| Accounts | [`SmartAccount.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/SmartAccount.kt), [`DeviceKey.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/DeviceKey.kt), [`SessionKey.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/SessionKey.kt), [`Bundler.kt`](../android/chain/src/main/kotlin/org/klippelgo/chain/Bundler.kt) | The account's address and encodings, the user operation and its EIP-712 hash, the device key interface and its software form, session keys and certificates, the bundler's JSON-RPC |
| `KeystoreDeviceKey` | [`KeystoreDeviceKey.kt`](../android/app/src/main/kotlin/org/klippelgo/app/KeystoreDeviceKey.kt) | The account's key in the phone's chip |
| Core | [`core/`](../../core/docs/overview.md) | Hashes, route checks, sealing, the gossip transport, address search |
| Tags | [`ui/tags/Tags.kt`](../android/app/src/main/kotlin/org/klippelgo/app/ui/tags/Tags.kt) | The name of every piece of UI a person reads or touches. The end-to-end tests compile this same file |
| UI agent | [`app/src/androidTest/…/UiAgent.kt`](../android/app/src/androidTest/kotlin/org/klippelgo/app/agent/UiAgent.kt) | Test APK only. Reads the screen and injects touches for the [end-to-end tests](../../tests/README.md) |

The `:chain` module has no Android dependency. That is deliberate: `TwoPhonesRideTest` plays a whole ride
between two in-process "phones" against a local chain and a real gossip node, on the JVM
(`make android-test-chain`).

## One state, one loop

Beside the refresh loop below runs a small one, every second, that reads nothing from the chain: on the ride
under way it sends the driver's position, or on the rider's phone takes in the positions that arrived and moves
the car on the map. Which ride it is about is what the last refresh said.

```mermaid
flowchart LR
    user([User]) -- tap --> action[AppViewModel action]
    action -- contract call or gossip message --> world[(Chain and gossip)]
    loop[Refresh loop<br/>every 2 s, on Dispatchers.IO] -- "Matchmaker.tick; new block? its logs; full read on a change" --> world
    world --> loop
    loop -- copy --> state[UiState<br/>StateFlow]
    action -- copy --> state
    state --> ui[Compose UI]
    ui --> user
```

The UI never keeps a second copy of anything on chain or on gossip. It renders `UiState`; the loop rebuilds
the parts that come from outside. Gossip is taken in on every turn. The chain is read in full only when the logs
of the new blocks show something about this account, after an action, when the app comes back on screen, and
every 30 s; off screen with no ride under way it is not read at all
([scalability.md](scalability.md), "Reading the chain"). An action that changes the chain does the call, then lets the next refresh
show the result.

## A ride, end to end

```mermaid
sequenceDiagram
    autonumber
    participant R as Rider's phone
    participant G as Gossip: the pickup's cell
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

Every message about a request goes to the gossip cell (geohash 5, about 5 km) its pickup is in, and the
driver's position for that ride too. A driver hears the cell their phone is in and the eight around it, while
the phone is inside the map pack; without a position there, the whole pack, or its middle when the pack is large. A
driver keeps hearing the cell of a request they quoted after driving out of it, and a rider hears only the cells
of their own requests and of the rides whose driver they watch.

The pickup pin starts where the rider's phone is, when the rider allows reading it and the place is inside
the map pack, and follows the phone until the rider moves the home map under the pin, picks a pickup by name
on the ask screen from the pack's own search, or starts asking for a ride. After a ride it follows again, so
the next one starts where the last one ended. Without the permission it starts in the middle of the map. Once a destination is picked the trip is drawn on a map there, and stays drawn while offers come
in. A request commits to its route, so its pickup cannot be edited in place: moving the pickup of a request
that is out asks the rider first, then withdraws it and asks again for the route from the new pickup, at the
price the fare guide recommends for that route. The new route is measured before anything is withdrawn. If
asking again then fails, no request is out and the error is shown.

If the chosen driver declines (`cancel` on an Open ride), the rider's phone gets the refund and announces the
same trip again, skipping that driver. A driver already on a ride can take the next one; the waiting rider
sees that the driver is finishing another ride and gets no position until theirs is the current one.

A driver has at most two rides locked, as the escrow allows (C22 in the
[contracts' security](../../contracts/docs/security.md)): the one in hand and the next. While a ride waits for
its pickup, the next one's Accept button is off and says why; with two rides locked the driver is shown no
further request to quote. Every ride riders locked and the driver has not answered is listed, oldest first, each
with its own Accept and Decline; taking one gives the others back, as below. A quote is a signed offer a rider can lock until it expires, and gossip has no way to
take one back. So when the driver accepts a ride, every other quote this phone sent since it started (the newest
200) is marked void with the rider it was for, and a ride that rider locks on one of them, before or after, is
declined by the phone on its own: the rider gets everything back, and the driver pays the gas of the decline.
The phone declines with the escrow's `decline`, which does nothing to a ride already accepted, so a decline that
lands after the driver tapped Accept costs nothing but its gas. It declines from a task of its own, so the
refresh and the position it sends do not wait for it. A quote sent before the app last started is not known
to it, and a ride locked on one waits Open for the driver to decline it by hand.

## What is stored on the phone

| What | Where | Notes |
| --- | --- | --- |
| Where this account's event scans stopped and what they found (its rides, the registered drivers), with a block and its hash to recognise the chain | `SharedPreferences("klippelgo")`, `scans.<account>` | Thrown away and read again when that block's hash is not the chain's: a local testnet started again |
| Role, RPC address, name, account choice, auto-skip settings | `SharedPreferences("klippelgo")` | The account choice is `device` (the chip's key) or a seeded account's name; `devAccounts` asks for the seeded ones, and only a debug build sets it |
| Driver: the quotes made void by accepting a ride, with the rider each was for | `SharedPreferences("klippelgo")`, `voidQuotes` | The newest 200. Kept so a ride locked on one after a restart is still declined |
| The account's key | The phone's chip: Android Keystore, in StrongBox when there is one (`KeystoreDeviceKey`) | Never leaves it. Debug builds asked for `devAccounts` use the local testnet's seeded keys instead |
| The session key and its certificate | `SharedPreferences("klippelgo")`, `session.*` | Signs gossip for a day; kept so a restart does not ask the account's key, and so the person, again |
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
11. **Every piece a person reads or touches has a tag** from `Tags.kt`, as `<component>.<element>`. A whole
    screen's tag is on its outermost box. A tag that says a state carries it in the name
    (`on-the-way.status.Accepted`), so tests do not depend on the language. `Tags.kt` stays plain Kotlin: the
    test harness compiles it. A native Android view takes no tag from Compose; tag the box around it.

**Changing the app**

1. Put the code where the table says. If it must match iOS or a juror, change the core instead
   ([core overview](../../core/docs/overview.md)).
2. A new or changed gossip message: bump its domain version, validate every field on receipt, add it to the
   message table in [security.md](security.md), and cover it in `TwoPhonesRideTest`.
3. Add strings in all three languages.
4. Tag what a person can read or touch, and give it a function in the component's driver
   (`tests/harness/drivers`). A new flow gets a test in `tests/integrity`; a new check on what comes from
   outside gets one in `tests/security` ([tests/README.md](../../tests/README.md)).
5. `make android-test-chain`, then `make e2e`, which builds the app, installs it on an emulator and uses it
   as a person would. To watch two apps ride together: `make android-two-phones`, `make android-three-phones` or `make android-four-phones`.
6. Update these docs when a layer, a flow, a stored file or a guarantee changed.

**Tooling**: Kotlin 2.3, Compose, compileSdk 36, JDK 17. Chain access through web3j. The Rust core is built for
Android by Gradle through `cargo-ndk`.
