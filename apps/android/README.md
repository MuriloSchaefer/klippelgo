# KlippelGo Android

Kotlin and Jetpack Compose app. The same app plays rider or driver, so two emulators side by side can run a
whole ride against the local testnet: the rider opens it, the driver accepts and starts it, the rider confirms
arrival, and both rate each other. The phones never talk to each other directly. Each step is a transaction on
`RideEscrow`, and each phone polls the chain every two seconds to see what the other did.

Portuguese is the default language. English and Spanish can be picked on the first screen.

| Path | What it is |
| --- | --- |
| `app/` | Compose UI and a view model that polls the chain |
| `chain/` | Plain Kotlin client for the contracts (web3j). Builds and tests without the Android SDK |
| `scripts/two-phones.sh` | Starts both emulators, installs the app and opens each in its role |

## What you need

- Android Studio (Ladybug or newer) with **Android SDK 35** and **Android SDK Command-line Tools (latest)**
  ticked under Settings > Languages & Frameworks > Android SDK > SDK Tools.
- JDK 17 (the one bundled with Android Studio works: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` on macOS).
- Foundry for the local chain (see the root README).
- About 8 GB of free RAM for two emulators.

## Run rider and driver at the same time

From the repo root, in three terminals:

```sh
make testnet                  # 1. local chain on http://127.0.0.1:8545 (leave it running)
make deploy                   # 2. contracts, mock BRLA/BRZ, seeded rider and driver
make android-two-phones       # 3. two emulators, app installed, rider on one and driver on the other
```

The first run downloads an Android 35 system image and creates two emulators, `KlippelGo_Rider` and
`KlippelGo_Driver`. Later runs reuse them.

Then play a ride:

1. **Rider** (emulator-5554): the seeded driver is already selected. Keep R$ 23,40 in BRZ and tap
   **Pedir corrida**. The fare and a R$ 2,00 bond are locked in the escrow.
2. **Driver** (emulator-5556): the ride appears under **Corridas para você** within a couple of seconds. Tap
   **Aceitar corrida** (posts a R$ 5,00 bond), then **Passageiro embarcou: iniciar**.
3. **Rider**: the status changes to **Em viagem**. Tap **Cheguei ao destino**.
4. Both: the ride moves to history. The driver received R$ 22,40 and R$ 1,00 went to Instituto Klippel. Rate
   each other with the stars.

### With Android Studio instead of the script

1. Open `apps/android` in Android Studio and let Gradle sync.
2. In Device Manager, create two virtual devices (any Pixel, API 35) and start both.
3. Pick the first emulator in the device dropdown and press Run, then pick the second and press Run again.
4. On one emulator tap **Sou passageiro**, on the other **Sou motorista**. The node address is already
   `http://10.0.2.2:8545`, which is how an emulator reaches your computer.

## Accounts

The rider and driver use seeded accounts #3 and #4 from anvil's public test mnemonic, so both phones are funded
and the driver already has stake and a CNH credential. These keys are public: the app only uses them on the
local testnet. Real phones will sign with a Secure Enclave or StrongBox key through a smart account, which is
not built yet.

## Troubleshooting

- **Sem conexão / Can't reach the node.** `make testnet` must be running on the same computer. From an
  emulator, the computer is `10.0.2.2`, not `127.0.0.1`.
- **A real phone on Wi-Fi.** Start the chain with `ANVIL_HOST=0.0.0.0 make testnet` and enter
  `http://<your computer's LAN IP>:8545` as the node address. Debug builds allow plain HTTP to any host.
- **Contracts not found after a restart.** If you deployed twice on the same chain the addresses moved. Run
  `make testnet-reset`, restart `make testnet`, `make deploy`, and rebuild the app (it bundles
  `deployments/local.json`).
- **Start over.** `make testnet-reset` wipes all rides and balances.

## Tests

`chain/` has a test that plays rider and driver on two threads against a running testnet, the same way the two
emulators do:

```sh
make android-test-chain       # needs make testnet and make deploy first
```

CI runs it on every push and also builds the debug APK, which you can download from the run's artifacts.
