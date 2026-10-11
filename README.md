# KlippelGo

A ride-hailing protocol with no central server. Riders and drivers find each other phone to phone, agree a
fare, lock it in an on-chain escrow paid in a BRL stablecoin, and every rule is a policy the community governs on-chain.
Personal data stays encrypted on the phone. Each completed ride pays a flat R$ 1,00 to Instituto Klippel,
deducted from the fare.

The design is in the [KlippelGo Architecture Plan](https://claude.ai/code/artifact/9a1aa829-3b30-4485-9395-42fa77304dc8).
How maps, routes and trip proofs are computed on the phone is in [docs/maps-and-routing.md](docs/maps-and-routing.md).

## Layout

| Path | What it is |
| --- | --- |
| `contracts/` | Solidity contracts (Foundry): `PBACGovernor`, `IdentityRegistry`, `RideEscrow`, `FeeVault`, `Reputation`, `DisputeCourt`, `MapRegistry`, plus mock BRLA and BRZ for local use |
| `testnet/` | Scripts to run a local chain, deploy, run a demo ride and activate a map pack |
| `deployments/` | Contract addresses per network (`local.json` is written by the deploy script) |
| `core/` | Shared Rust core for the apps: the libp2p gossip network phones match through and its bootstrap node, map packs with on-device search, routes and trip proofs, money and fee maths, geohash discovery cells, network settings |
| `core/verify` | The deterministic verifier of route quotes and trip receipts: integers only, no I/O, the same answer on a phone, a juror's laptop and in a zkVM |
| `core/proofs` | Map packs on disk, Valhalla results turned into checkable paths, ride evidence as files, and `klippelgo-juror`, the tool a juror runs on them |
| `mappack/` | Builds a region's map pack from OpenStreetMap with Docker. See [its README](mappack/README.md) |
| `apps/android` | Android app (Kotlin/Compose): rider and driver modes against the local testnet. See [its README](apps/android/README.md) |
| `apps/ios` | iOS app shell (Swift/SwiftUI), not created yet |

## Local testnet

Needs [Foundry](https://book.getfoundry.sh/getting-started/installation) (`forge`, `anvil`, `cast`).

```sh
git clone --recurse-submodules <this repo>
make testnet      # terminal 1: anvil on http://127.0.0.1:8545, chain id 31337
make deploy       # terminal 2: deploy contracts, mock BRLA/BRZ, seed accounts
make demo         # run one R$ 23,40 ride: driver gets R$ 22,40, Instituto Klippel R$ 1,00
```

To play the ride on two Android emulators (rider and driver at once), see [apps/android](apps/android/README.md).
There the rider picks a destination on a map that lives on the phone, asks for a ride over gossip, the driver
prices the distance, and the rider locks the offer it takes. That needs a map pack:

```sh
make mappack      # build the pilot city's pack (Cascavel, Paraná) with Docker: about 15 minutes the first time
make publish-pack # activate it on the local chain: three validators attest its proof root, governance votes
make serve-packs  # terminal 3: serve it to the phones on port 4002
```

`make gossip-node` runs the bootstrap node the phones dial (port 4001) with Docker Compose.

No Foundry? `docker compose -f testnet/docker-compose.yml up` starts the same chain, along with the gossip
bootstrap node; deploy still needs `forge`.

The chain runs Osaka rules, which include the P-256 signature precompile at `0x100`. Polygon PoS has the same
precompile (RIP-7212), and the apps rely on it so Secure Enclave and StrongBox keys can sign for the user's
smart account. State persists in `testnet/.state`; `make testnet-reset` wipes it.

### Seeded accounts

All accounts come from anvil's public test mnemonic (`test test ... junk`). Never send real funds to them.

| # | Role | Setup |
| --- | --- | --- |
| 0 | Deployer | Deploys, then holds no privileges once bootstrap ends |
| 1 | Instituto Klippel | Receives the R$ 1,00 per ride from `FeeVault.sweep` |
| 2 | Attester | Trusted mock KYC/CNH attester |
| 3 | Rider | Registered rider, R$ 1.000 in each mock coin |
| 4 | Driver | Registered driver, R$ 100 stake, CNH credential hash |
| 5-7 | Validators | R$ 500 stake each, can judge disputes |

### Local governance settings

Voting lasts 2 minutes, the timelock is 1 minute and quorum is 3 votes. A vote counts 1 per completed ride plus
1 per R$ 100 staked. Use `cast rpc evm_increaseTime <seconds>` to skip ahead.

## Policies at launch

| Action | Who may call it |
| --- | --- |
| `RideEscrow.open` | Rider role |
| `RideEscrow.accept` | Driver role, R$ 100 stake, score at least 4.50 once rated, valid CNH credential |
| `DisputeCourt.commit` | Validator role, R$ 500 stake |
| `MapRegistry.attest` | Validator role, R$ 500 stake |
| `PBACGovernor.propose` | Any registered role |

Every policy, the BRL allow-list, fees, timeouts, trusted attesters, the active map packs and the tolerances of
the proof verifier change only through a governance proposal. A map pack can be activated only once three
validators have rebuilt its proof index and attested the same root.

## Tests

```sh
make test         # forge test + cargo test in core/ and mappack/
```

## Checking a ride

A rider or driver in a dispute reveals the ride's evidence to the jurors: the route quote, and after the trip
the receipt and the phones' GPS logs. A juror checks it against the public map pack, with no need to trust
either party's numbers:

```sh
cd core && cargo run -p klippelgo-proofs --bin klippelgo-juror -- <pack dir> <evidence.json>
```

It says whether the quoted route is drivable and as long as claimed, how far the trip went, whether it reached
the quoted drop-off, and whether the logs are signed, countersigned and physically possible.

## Not built yet

ERC-4337 smart accounts and the paymaster, the iOS app, and a Polygon Amoy deployment. The gossip layer carries
signed requests and offers, but not yet session certificates, encrypted offers, presence, real location cells,
relays or the encrypted ride channel (see [apps/android](apps/android/README.md)). The pickup proof is recorded as a hash the driver submits; checking both phones' signatures comes
with the smart accounts.

Of the trip proofs, the Android app does the first half: it computes and verifies the route and locks the real
quote hash. GPS logging, the signed trip receipt and the driver's own check of the route are built and tested
in the core but not wired into the app, because the phones exchange them over the encrypted ride channel. Until
then the app submits a stand-in receipt hash. See
[docs/maps-and-routing.md](docs/maps-and-routing.md#what-is-built).
