# KlippelGo Android

Kotlin and Jetpack Compose app. The same app plays rider or driver, so two emulators side by side can run a
whole ride against the local testnet: the rider picks where to go on the map and asks for a ride with a max
fare, the driver sees how far the trip is and answers with a price, the rider takes the offer, the driver
accepts and starts the ride, the rider confirms arrival, and both rate each other.

The map, the address search and the route all run on the phone, from a map pack it downloads once: MapLibre
draws it, a SQLite index searches it, Valhalla routes on it, and the Rust core's verifier measures the route
against the pack's proof index (see [docs/maps-and-routing.md](../../docs/maps-and-routing.md)). No map, search
or routing service is asked anything.

Request and offers travel over the gossip network (libp2p GossipSub, in the Rust core), through a bootstrap
node on your computer. Nothing reaches the chain until the rider takes an offer. From then on each step is a
transaction on `RideEscrow`, and each phone polls the chain every two seconds to see what the other did.

Portuguese is the default language. English and Spanish can be picked on the first screen. The type is IBM Plex
(SIL Open Font License), bundled in `app/src/main/res/font`.

| Path | What it is |
| --- | --- |
| `app/` | Compose UI, a view model that polls the chain and the gossip network, and the map pack store |
| `app/src/main/assets/map` | The map's style, fonts and icons, bundled so no server is needed. `scripts/map-style.sh` regenerates them |
| `chain/` | Plain Kotlin client for the contracts (web3j) and the matchmaker that signs and checks gossip messages. Builds and tests without the Android SDK |
| `../../core` | Rust core with the gossip node, map packs and trip proofs, bound to Kotlin with UniFFI. Gradle builds it with cargo |
| `scripts/two-phones.sh` | Starts the gossip bootstrap node and both emulators, installs the app and opens each in its role |

## What you need

- JDK 17 or 21. Gradle 8 does not run on anything newer than 24, so a distro default like JDK 26 will not
  work. On Arch: `sudo pacman -S jdk17-openjdk`. The script picks it up from `/usr/lib/jvm` on its own; for
  plain `./gradlew` set `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.
- Rust through [rustup](https://rustup.rs). The core pins its own toolchain and Android targets, and rustup
  fetches them on the first build.
- Docker with Compose, which runs the gossip bootstrap node.
- Foundry for the local chain (see the root README).
- About 8 GB of free RAM for two emulators, and about 6 GB of disk for the SDK and system image. Building the
  map pack takes about 5 GB more, mostly shared sources downloaded once.
- On Linux, access to `/dev/kvm` (without it the emulator is unusably slow).

Android Studio is optional. `make android-two-phones` downloads Google's command-line tools into
`~/Android/Sdk` (`~/Library/Android/sdk` on macOS) when no SDK is found, and uses `sdkmanager` to fetch the
emulator, platform tools, Android 35 and the NDK. It also installs `cargo-ndk`, which builds the Rust core for
Android. Set `ANDROID_HOME` to put the SDK somewhere else or to reuse one you
already have. If you do use Android Studio, tick **Android SDK Command-line Tools (latest)** under Settings >
Languages & Frameworks > Android SDK > SDK Tools.

## Run rider and driver at the same time

From the repo root, in three terminals:

```sh
make testnet                  # 1. local chain on http://127.0.0.1:8545 (leave it running)
make deploy                   # 2. contracts, mock BRLA/BRZ, seeded rider and driver
make mappack                  #    the pilot city's map pack (Cascavel, Paraná), built with Docker; once
make publish-pack             #    activate it on the chain: validators attest it, governance votes
make serve-packs              # 3. serve the pack to the phones on port 4002 (leave it running)
make android-two-phones       # 4. gossip node, two emulators, app installed, rider on one and driver on the other
```

For two riders and two drivers at once, run `make android-four-phones` instead of step 4. It adds the
emulators `KlippelGo_Rider2` and `KlippelGo_Driver2`, which use a second seeded rider and driver (accounts #8
and #9), and puts each driver's emulator at a spot in the centre of Cascavel. Four emulators take about 16 GB of
RAM between them.

`make publish-pack` is needed again after every `make testnet-reset`. It moves the chain's clock three minutes
ahead to get through the vote and the timelock.

The first run downloads an Android 35 system image and the NDK, compiles the Rust core, and creates two
emulators, `KlippelGo_Rider` and `KlippelGo_Driver`. Later runs reuse them. The script starts the gossip
bootstrap node with Docker Compose in the background (`docker compose -f testnet/docker-compose.yml logs gossip`
shows its log); run `make gossip-node` yourself to keep it in a terminal instead.

Then play a ride:

1. **Rider** (emulator-5554): tap **Baixar mapa** the first time. The app fetches the pack and checks it
   against `MapRegistry`. Move the map so the pin sits where the car should come, then tap **Para onde?**, type
   an address or a place, and pick a result. The route is worked out on the phone and shown as distance and
   time, with the recommended price under it. Adjust the price if you like and tap **Pedir ofertas**. The request goes out over
   gossip with the trip's distance and duration, rounded, the region of about a square kilometre that the pickup
   and the destination are each in, and a salted hash of the route. Nothing is locked yet.
2. **Driver** (emulator-5556): tap **Ficar online**. Within a few seconds the request appears in the list
   under **Ignorar solicitações automaticamente**, with the rider, the two neighbourhoods, the trip's length and
   the rider's price. Tap it: it opens, and its two regions are shaded on the map. The offer is for the one
   that is open. Set your price with − and +, from 20% under the rider's price to 10% over the recommended
   one, and tap **Enviar oferta**. You can change it and send again; **Pular** takes it off the list, and
   tapping another request moves the map and the offer to that one.
3. **Rider**: the offer appears under **Escolha um motorista**, cheapest first. Tap it, check the route, the
   price per km and the breakdown, and tap **Bloquear**. The offered fare and a R$ 2,00 bond are locked in the
   escrow, with the hash of the quote the driver signed. The quote itself, with the exact route, is saved on
   the rider's phone under `files/rides/`.
4. **Driver**: the screen changes to **O passageiro escolheu você**, with the ride's route on the map once the
   rider's sealed route has arrived and checked out. Tap **Aceitar corrida** (posts a R$ 5,00 bond), then
   **Passageiro embarcou: iniciar**.
5. **Rider**: the status changes to **Em viagem**. Tap **Cheguei ao destino**, pick the stars and tap
   **Confirmar e pagar**.
6. **Driver**: the screen shows what you received, the fare minus R$ 1,00 that went to Instituto Klippel. Rate
   the rider with the stars. Keep it at 5: the launch policy blocks a driver whose average falls below 4,50.

A phone started by hand opens on the welcome screen, where you pick rider or driver and the node address. The
rider's **Carteira** tab adds test reais (the mock coins mint to anyone, standing in for PIX) and lists past
rides; **Perfil** switches to driver mode.

### What the screens leave out

The screens follow the [KlippelGo Wireframes](https://claude.ai/artifact/1W2kMTkP6EYG93XmUXHQ93). Parts the
protocol cannot back yet are left out instead of faked: saved places, names, car and plate, the driver's
position and arrival time, turn-by-turn directions, chat, call, share and SOS, the pickup QR code and Bluetooth
check, PIX, and Face ID. A phone without the map pack shows a grey grid where the map goes.

The pickup point is the pin. It starts where the phone is (the app asks for the location permission) and
follows it until the rider places it by hand or starts asking; after a ride it follows again. A phone outside
the map pack, as an emulator in California is, leaves the pin in the middle of the map. The route is for
a car: the core also routes and verifies for motorcycles, and the app has no way to pick one yet.

### What the trip proofs do and do not do yet

The rider's phone computes the route with Valhalla, turns it into a path on the pack's proof index, has the
verifier measure it, and locks the hash of the resulting quote. `klippelgo-juror` confirms a saved quote against
the pack and the hash on chain.

Once the rider has locked an offer, their phone seals the quote, with the exact route, to a key the driver put
in that offer, and gossips it. Everyone in the cell receives it and only the chosen driver can open it. The
driver's phone checks the route on its own copy of the map pack and against the hash locked in the escrow, and
only then draws it, before the driver accepts. This one sealed message stands in for the encrypted ride
channel. A driver without the map pack cannot check a route and is shown none.

Not wired into the app, though built and tested in the core: both phones logging GPS and countersigning each
other's log, and the trip receipt at arrival. They need messages both ways throughout the ride, which is the
encrypted ride channel proper. Until it exists the app sends a stand-in hash where the trip receipt's hash
belongs, as it already does for the pickup proof.

### Prices and the driver's rules

The rider does not type a price. The app shows the price `FareGuide` recommends for the trip and the rider
moves it with − and +, up to 10% either way. A driver quotes at the rider's price, under it down to 20% less,
or above it up to 10% over the recommended price; the rider sees every quote and picks. Phones drop requests and quotes outside those limits, so they cannot be worked around from a modified
app without every other phone ignoring the result.

On the driver's home screen, **Ignorar solicitações automaticamente** hides requests the driver does not want
to see: those under a price per km the driver sets, and those whose rider went further under the recommended
price than the driver accepts. A driver who gives a ride up, by declining or cancelling, is also not shown that
rider's requests for three minutes, so the request the rider's phone sends again goes to the other drivers.
The card says how many open requests are hidden at the moment.

### The driver's map

The driver's screens show the phone's own position (the app asks for the location permission; the position is
not sent anywhere), the ride being driven, and the way to its pickup. A driver with a ride in hand still sees
new requests and can quote on them. When a rider picks that quote, the new ride appears in orange with its
route and the way from the current drop-off to its pickup, to accept or leave.

On an emulator, set the position in the emulator's location panel or with
`adb -s emulator-5556 emu geo fix -53.4590 -24.9520` (longitude first): it starts in California, outside the
pack.

### What the gossip layer does and does not do yet

Every request, offer and withdrawal is signed by the account it speaks for, and each phone checks the signature,
the expiry, the max fare, the rider's registration and that the request's map pack is one governance activated
before showing anything. A quote names the route's hash, rider, driver, token, fare, chain, escrow and expiry,
so it cannot be replayed elsewhere or moved to another route. Gossip keeps no history, so a
rider's app announces its request every few seconds, and a request nobody announces for 10 seconds disappears.

Still missing from the architecture plan and its security review: session certificates (the wallet key signs
every message for now), offers encrypted to the rider (the whole cell sees who quoted what), driver presence,
real location cells (every phone uses one fixed cell), a cost for posting requests, relays for phones behind
NAT, and the encrypted ride channel.

### With Android Studio instead of the script

1. Open `apps/android` in Android Studio and let Gradle sync.
2. In Device Manager, create two virtual devices (any Pixel, API 35) and start both.
3. Pick the first emulator in the device dropdown and press Run, then pick the second and press Run again.
4. On each emulator tap **Começar**, then pick **Sou passageiro** on one and **Sou motorista** on the other. The node address is already
   `http://10.0.2.2:8545`, which is how an emulator reaches your computer.

The Gradle build runs `cargo ndk`, so the NDK and `cargo-ndk` must be installed, and `make gossip-node` must be
running for the two phones to find each other.

## Moving the car

An emulator stays where it was put, so the driver's dot never moves by itself. This moves it along the route
the driver is on, a tenth of the way each second:

```sh
make android-drive                        # the emulator in driver mode: emulator-5556 with two phones
make android-drive PHONE=emulator-5560    # the second driver of android-four-phones
make android-drive LEG=ride STEPS=20 EVERY=0.5
```

It reads which ride the driver's account is on from the chain, and goes to the pickup while the ride has not
started, then along the ride once it has: run it after "Aceitar corrida", and again after "Iniciar corrida".
The ride's route is the one the app itself checked and saved, so the app must show "rota conferida" first.
The way to the pickup comes from the router `tests/stack.sh up` starts; without it the car goes there in a
straight line. `LEG=pickup` or `LEG=ride` picks the stretch by hand. The rider's phone shows the car move.

Along the ride the rider is in the car, so the rider's emulator moves with it: the one signed in as the ride's
rider, found by itself. `RIDER=emulator-5554` names it when several fit.

It is the `drive` command of the test CLI ([tests/README.md](../../tests/README.md)), and reads the app's saved
route with `run-as`, which only a debug build allows.

## Accounts

Each phone has one account and keeps it in passenger mode and in driver mode. The phones use four seeded
accounts from anvil's public test mnemonic: #3 and #4 for the phones that start as rider and driver, and #8
and #9 for the second pair of `make android-four-phones`. `contracts/script/SeedPhones.s.sol` gives each of
them R$ 1.000 in each coin, both roles, R$ 100 staked and a CNH credential, so any phone can ride or drive.
`make deploy` runs it, and so do the emulator scripts on a chain deployed before it existed. Which account a
phone has is decided the first time it is opened and when a script launches it. These keys are public: the app only uses them on the
local testnet. Real phones will sign with a Secure Enclave or StrongBox key through a smart account, which is
not built yet.

## Troubleshooting

- **Sem conexão / Can't reach the node.** `make testnet` must be running on the same computer. From an
  emulator, the computer is `10.0.2.2`, not `127.0.0.1`.
- **Sem pares na rede P2P / No peers on the P2P network.** The gossip bootstrap node is not running. Start it
  with `make gossip-node`. The app looks for it on port 4001 of the same host as the node address.
- **The driver never sees the request.** Both phones must show **Conectado à rede P2P**. A request takes up to
  five seconds to appear. Drivers also ignore requests whose map pack is not active on the chain: run
  `make publish-pack` after a `make testnet-reset`.
- **Nenhum mapa foi publicado nesta rede ainda / No map has been published.** Run `make mappack` once and
  `make publish-pack`.
- **O mapa não pôde ser usado / The map could not be used.** `make serve-packs` must be running. The app looks
  for the mirror on port 4002 of the same host as the node address.
- **Aguardando a rota do passageiro… and it never comes.** The gossip node is older than the app and drops the
  sealed route. `make android-two-phones` rebuilds it; by hand, `docker compose -f testnet/docker-compose.yml up
  -d --build gossip`. A driver whose app restarted after quoting cannot open that ride's route either.
- **The driver emulator closes by itself.** The emulator's graphics can crash when the app is killed while
  the map is on screen (`adb install`, force-stop). Press Home first, or just start the emulator again.
- **A real phone on Wi-Fi.** Start the chain with `ANVIL_HOST=0.0.0.0 make testnet` and enter
  `http://<your computer's LAN IP>:8545` as the node address. Debug builds allow plain HTTP to any host. The
  gossip node's port 4001 is already published on every interface.
- **Contracts not found after a restart.** If you deployed twice on the same chain the addresses moved. Run
  `make testnet-reset`, restart `make testnet`, `make deploy`, and rebuild the app (it bundles
  `deployments/local.json`).
- **Bloqueado: sua nota é menor que o mínimo.** A test rating below 5 stars can push the driver under the
  policy's 4,50. `make reset-ratings WHO=driver` clears the ratings that account received and keeps rides,
  balances, stake and CNH. `WHO` also takes `rider` or any address.
- **Start over.** `make testnet-reset` wipes all rides and balances.

## Tests

`chain/` has a test that plays rider and driver on two threads against a running testnet, the same way the two
emulators do. Each side gets its own gossip node, and they agree the fare through a bootstrap node the test
starts. It activates a stand-in map pack on the chain first, since drivers ignore requests on unknown packs:

```sh
make android-test-chain       # needs make testnet and make deploy first
```

CI runs it on every push and also builds the debug APK, which you can download from the run's artifacts.
