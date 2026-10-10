# KlippelGo Android

Kotlin and Jetpack Compose app. The same app plays rider or driver, so two emulators side by side can run a
whole ride against the local testnet: the rider asks for a ride with a max fare, the driver answers with a
price, the rider takes the offer, the driver accepts and starts the ride, the rider confirms arrival, and both
rate each other.

Request and offers travel over the gossip network (libp2p GossipSub, in the Rust core), through a bootstrap
node on your computer. Nothing reaches the chain until the rider takes an offer. From then on each step is a
transaction on `RideEscrow`, and each phone polls the chain every two seconds to see what the other did.

Portuguese is the default language. English and Spanish can be picked on the first screen.

| Path | What it is |
| --- | --- |
| `app/` | Compose UI and a view model that polls the chain and the gossip network |
| `chain/` | Plain Kotlin client for the contracts (web3j) and the matchmaker that signs and checks gossip messages. Builds and tests without the Android SDK |
| `../../core` | Rust core with the gossip node, bound to Kotlin with UniFFI. Gradle builds it with cargo |
| `scripts/two-phones.sh` | Starts the gossip bootstrap node and both emulators, installs the app and opens each in its role |

## What you need

- JDK 17 or 21. Gradle 8 does not run on anything newer than 24, so a distro default like JDK 26 will not
  work. On Arch: `sudo pacman -S jdk17-openjdk`. The script picks it up from `/usr/lib/jvm` on its own; for
  plain `./gradlew` set `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.
- Rust through [rustup](https://rustup.rs). The core pins its own toolchain and Android targets, and rustup
  fetches them on the first build.
- Docker with Compose, which runs the gossip bootstrap node.
- Foundry for the local chain (see the root README).
- About 8 GB of free RAM for two emulators, and about 6 GB of disk for the SDK and system image.
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
make android-two-phones       # 3. gossip node, two emulators, app installed, rider on one and driver on the other
```

The first run downloads an Android 35 system image and the NDK, compiles the Rust core, and creates two
emulators, `KlippelGo_Rider` and `KlippelGo_Driver`. Later runs reuse them. The script starts the gossip
bootstrap node with Docker Compose in the background (`docker compose -f testnet/docker-compose.yml logs gossip`
shows its log); run `make gossip-node` yourself to keep it in a terminal instead.

Then play a ride:

1. **Rider** (emulator-5554): keep R$ 23,40 in BRZ as the most you will pay and tap **Pedir corrida**. The
   request goes out over gossip. Nothing is locked yet.
2. **Driver** (emulator-5556): the request appears under **Pedidos de corrida** within a few seconds. Set your
   price, at or below the rider's max, and tap **Enviar oferta**. You can change it and send again.
3. **Rider**: the offer appears under **Ofertas dos motoristas**, cheapest first. Tap **Aceitar**. The offered
   fare and a R$ 2,00 bond are locked in the escrow, with the hash of the quote the driver signed.
4. **Driver**: the ride appears under **Corridas para você**. Tap **Aceitar corrida** (posts a R$ 5,00 bond),
   then **Passageiro embarcou: iniciar**.
5. **Rider**: the status changes to **Em viagem**. Tap **Cheguei ao destino**.
6. Both: the ride moves to history. The driver received the fare minus R$ 1,00, which went to Instituto
   Klippel. Rate each other with the stars.

### What the gossip layer does and does not do yet

Every request, offer and withdrawal is signed by the account it speaks for, and each phone checks the signature,
the expiry, the max fare and the rider's registration before showing anything. A quote names its request, rider,
driver, token, fare, chain, escrow and expiry, so it cannot be replayed elsewhere. Gossip keeps no history, so a
rider's app announces its request every few seconds, and a request nobody announces for 10 seconds disappears.

Still missing from the architecture plan and its security review: session certificates (the wallet key signs
every message for now), offers encrypted to the rider (the whole cell sees who quoted what), driver presence,
real location cells (every phone uses one fixed cell), a cost for posting requests, relays for phones behind
NAT, and the encrypted ride channel.

### With Android Studio instead of the script

1. Open `apps/android` in Android Studio and let Gradle sync.
2. In Device Manager, create two virtual devices (any Pixel, API 35) and start both.
3. Pick the first emulator in the device dropdown and press Run, then pick the second and press Run again.
4. On one emulator tap **Sou passageiro**, on the other **Sou motorista**. The node address is already
   `http://10.0.2.2:8545`, which is how an emulator reaches your computer.

The Gradle build runs `cargo ndk`, so the NDK and `cargo-ndk` must be installed, and `make gossip-node` must be
running for the two phones to find each other.

## Accounts

The rider and driver use seeded accounts #3 and #4 from anvil's public test mnemonic, so both phones are funded
and the driver already has stake and a CNH credential. These keys are public: the app only uses them on the
local testnet. Real phones will sign with a Secure Enclave or StrongBox key through a smart account, which is
not built yet.

## Troubleshooting

- **Sem conexão / Can't reach the node.** `make testnet` must be running on the same computer. From an
  emulator, the computer is `10.0.2.2`, not `127.0.0.1`.
- **Sem pares na rede P2P / No peers on the P2P network.** The gossip bootstrap node is not running. Start it
  with `make gossip-node`. The app looks for it on port 4001 of the same host as the node address.
- **The driver never sees the request.** Both phones must show **Conectado à rede P2P**. A request takes up to
  five seconds to appear.
- **A real phone on Wi-Fi.** Start the chain with `ANVIL_HOST=0.0.0.0 make testnet` and enter
  `http://<your computer's LAN IP>:8545` as the node address. Debug builds allow plain HTTP to any host. The
  gossip node's port 4001 is already published on every interface.
- **Contracts not found after a restart.** If you deployed twice on the same chain the addresses moved. Run
  `make testnet-reset`, restart `make testnet`, `make deploy`, and rebuild the app (it bundles
  `deployments/local.json`).
- **Start over.** `make testnet-reset` wipes all rides and balances.

## Tests

`chain/` has a test that plays rider and driver on two threads against a running testnet, the same way the two
emulators do. Each side gets its own gossip node, and they agree the fare through a bootstrap node the test
starts:

```sh
make android-test-chain       # needs make testnet and make deploy first
```

CI runs it on every push and also builds the debug APK, which you can download from the run's artifacts.
