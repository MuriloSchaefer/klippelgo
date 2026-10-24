# KlippelGo

A ride-hailing protocol with no central server. Riders and drivers find each other phone to phone, agree a
fare, lock it in an on-chain escrow paid in a BRL stablecoin, and every rule is a policy Instituto Klippel's team governs on-chain, in roles, with no member acting alone on what moves money.
Personal data stays encrypted on the phone. Each completed ride pays a flat R$ 1,00 to Instituto Klippel and
the government's tax, a share of the fare Instituto Klippel's team sets, both deducted from the fare: the rider pays the
fare and nothing on top. The app also shows what a ride costs the network (gas) as its infrastructure cost.

The design is in the [KlippelGo Architecture Plan](https://claude.ai/code/artifact/9a1aa829-3b30-4485-9395-42fa77304dc8).
How maps, routes and trip proofs are computed on the phone is in [docs/maps-and-routing.md](docs/maps-and-routing.md).
How users get an account without a password, and top it up with Pix, is in [docs/accounts-and-pix.md](docs/accounts-and-pix.md).
How Instituto Klippel's team governs, by roles and members, is in [docs/governance.md](docs/governance.md).

## Documentation

| Area | Architecture | Security | Scalability |
| --- | --- | --- | --- |
| Contracts | [overview](contracts/docs/overview.md) | [security](contracts/docs/security.md) | [scalability](contracts/docs/scalability.md) |
| Rust core | [overview](core/docs/overview.md) | [security](core/docs/security.md) | [scalability](core/docs/scalability.md) |
| Apps | [overview](apps/docs/overview.md) | [security](apps/docs/security.md) | [scalability](apps/docs/scalability.md) |

How the contracts get deployed, upgraded and retired is [contracts/docs/deployment.md](contracts/docs/deployment.md).
These are the source of truth for structure, patterns and guarantees. [CLAUDE.md](CLAUDE.md) says how the
skills and the security reviewer in `.claude/` use them.

## Layout

| Path | What it is |
| --- | --- |
| `contracts/` | Solidity contracts (Foundry): `PBACGovernor`, `TeamCouncil`, `IdentityRegistry`, `RideEscrow`, `FeeVault`, `Reputation`, `DisputeCourt`, `MapRegistry`, `FareGuide`, plus mock BRLA and BRZ for local use |
| `testnet/` | Scripts to run a local chain, deploy, run a demo ride and activate a map pack |
| `gov/` | `klippel-gov`, the command-line tool Instituto Klippel's team governs with: each member requests, approves and executes the council's actions, licences among them. See [its README](gov/README.md) |
| `deployments/` | Contract addresses per network (`local.json` is written by the deploy script) |
| `core/` | Shared Rust core for the apps: the libp2p gossip network phones match through and its bootstrap node, map packs with on-device search, routes and trip proofs, money and fee maths, geohash discovery cells, network settings |
| `core/verify` | The deterministic verifier of route quotes and trip receipts: integers only, no I/O, the same answer on a phone, a juror's laptop and in a zkVM |
| `core/proofs` | Map packs on disk, Valhalla results turned into checkable paths, ride evidence as files, and `klippelgo-juror`, the tool a juror runs on them |
| `mappack/` | Builds a region's map pack from OpenStreetMap with Docker. See [its README](mappack/README.md) |
| `apps/android` | Android app (Kotlin/Compose): rider and driver modes against the local testnet. See [its README](apps/android/README.md) |
| `apps/ios` | iOS app shell (Swift/SwiftUI), not created yet |
| `tests/` | End-to-end tests: the real app on an emulator against a real chain, gossip network and map pack, split into integrity, security and performance, with a CLI to use the app by hand or by agent. See [its README](tests/README.md) |

## Local testnet

Needs [Foundry](https://book.getfoundry.sh/getting-started/installation) (`forge`, `anvil`, `cast`).

```sh
git clone --recurse-submodules <this repo>
make testnet      # terminal 1: anvil on http://127.0.0.1:8545, chain id 31337
make deploy       # terminal 2: deploy contracts, mock BRLA/BRZ, seed accounts
make bundler      # terminal 3: the ERC-4337 bundler every account's operations go through (Docker, port 4337)
make demo         # run one R$ 23,40 ride: driver gets R$ 21,23, Instituto Klippel R$ 1,00, tax R$ 1,17
```

To play the ride on two Android emulators (rider and driver at once), see [apps/android](apps/android/README.md).
There the rider picks a destination on a map that lives on the phone, asks for a ride over gossip, the driver
prices the distance, and the rider locks the offer it takes. That needs a map pack:

```sh
make mappack      # build the pilot city's pack (Cascavel, Paraná) with Docker: about 15 minutes the first time
make publish-pack # activate it on the local chain: three validators attest its proof root, Operações activates it
make serve-packs  # terminal 3: serve it to the phones on port 4002
```

`make gossip-node` runs the bootstrap node the phones dial (port 4001) with Docker Compose.

To watch the chain, `make explorer` starts [Otterscan](https://otterscan.io) on http://localhost:5100: blocks,
hashes, transactions with their call traces, events and balances. `make explorer TOOL=blockscout` starts
[Blockscout](https://www.blockscout.com) on http://localhost:5200 instead, and verifies the contracts in
`deployments/local.json` on it, so their pages show the source, decode calls and events by name, and have read
and write tabs. The first start pulls a few GB of images and takes a minute or two to index. After a
`make deploy`, `make explorer-verify` verifies the new contracts; after a `make testnet-reset`,
`make explorer-reset` clears what Blockscout indexed of the old chain. `make explorer-down` stops either one.
Phones' operations reach the chain as one `handleOps` call from the bundler to the EntryPoint; the ride itself
is in that call's internal calls (Otterscan's trace tab, Blockscout's "Internal txns"). The EntryPoint is the
one contract Blockscout cannot verify ([testnet/explorer/verify.sh](testnet/explorer/verify.sh) says why).

No Foundry? `docker compose -f testnet/docker-compose.yml up` starts the same chain, along with the gossip
bootstrap node and the bundler; deploy still needs `forge`.

The chain runs Osaka rules, which include the P-256 signature precompile at `0x100`. Polygon PoS has the same
precompile (RIP-7212), and the apps rely on it so Secure Enclave and StrongBox keys can sign for the user's
smart account. State persists in `testnet/.state`; `make testnet-reset` wipes it.

### Seeded accounts

The numbered accounts come from anvil's public test mnemonic (`test test ... junk`); the phones' accounts are
smart accounts whose device keys are public test secrets. Never send real funds to any of them.

People's accounts are ERC-4337 smart accounts (`contracts/src/account`): a phone makes a P-256 key in its chip
and its account's address follows from that key; every write goes through the bundler, with gas paid in reais
through the paymaster. A debug build launched with `devAccounts` (as scripts and the tests do) uses the seeded
phone accounts below instead.

| # | Role | Setup |
| --- | --- | --- |
| 0 | Deployer | Deploys, then holds no privileges once the team council takes the guardian's seat |
| 1 | Instituto Klippel | Receives the R$ 1,00 per ride from `FeeVault.sweep`. The team council's only member, in every role. The account `gov/klippel-gov` signs with |
| 2 | Attester | Trusted mock KYC/CNH attester |
| 3, 4 | Rider and driver of `make demo` | Plain accounts, funded and registered, for the scripted demo ride |
| 5-7 | Validators | R$ 500 stake each, can judge disputes |
| 10 | Tax collector | Receives the government's tax on each ride (5% of the fare on the local testnet). It only receives |
| 11, 12 | The bundler's executor and utility accounts | Sign the bundles of operations (`testnet/bundler`) |
| 13 | Puts the EntryPoint in place | `testnet/entrypoint.sh` |
| `DevAccount.RIDER`, `DRIVER` | First and second phone (smart accounts, device-key secrets `0xab001`, `0xab002`) | R$ 1.000 in each mock coin, both roles, R$ 100 stake, CNH credential (`script/SeedPhones.s.sol`) |
| `DevAccount.RIDER_2`, `DRIVER_2` | Third and fourth phone (`0xab003`, `0xab004`) | The same, for trying four phones at once |

### Local governance settings

The guardian is the team council, as on a real network, but with one member (account #1) in every role, one
approval and no delay: every request runs at once. The launch numbers (three of five directors and 72 hours,
two members and a day, one member at once to protect people) are in [docs/governance.md](docs/governance.md),
and `contracts/test/TeamCouncil.t.sol` runs the council with them.

### Governing

[`gov/klippel-gov`](gov/README.md) is what Instituto Klippel's team uses. Each member signs with their own key;
the council decides whether an action needs others' approvals and how long it waits.

```sh
gov/klippel-gov status                               # every setting of every contract
gov/klippel-gov account 0x15d3…6A65                  # what an account is and may do
gov/klippel-gov cnh approve 0x15d3…6A65 cnh.pdf      # at once: only a salted hash of the file goes on chain
gov/klippel-gov cnh revoke 0x15d3…6A65               # at once: the driver accepts no more rides
gov/klippel-gov block 0x15d3…6A65                    # Segurança, at once
gov/klippel-gov request tax 4                        # Finanças: two approvals and a day on a real network
gov/klippel-gov approve 7                            # another member of the role
gov/klippel-gov stop-rides                           # Segurança's playbook: nobody opens or accepts a ride
gov/klippel-gov team                                 # who is in which role, and what each role may do
```

A member that is a multisig (Recuperação, say) uses `--safe <address>`: the tool writes each transaction out
for the owners to approve instead of sending it ([gov/README.md](gov/README.md)).

## Policies at launch

| Action | Who may call it |
| --- | --- |
| `RideEscrow.open` | Rider role |
| `RideEscrow.accept` | Driver role, R$ 100 stake, score at least 4.50 once rated, valid CNH credential |
| `DisputeCourt.commit` | Validator role, R$ 500 stake |
| `MapRegistry.attest` | Validator role, R$ 500 stake |

Every policy, the BRL allow-list, fees, caps, timeouts, trusted attesters, the blocked accounts, the active
map packs and the tolerances of the proof verifier change only through the team council, each under the role
[docs/governance.md](docs/governance.md) gives it. A blocked account passes
none of the policies above; what it is owed (a ride under way, its stake) it can still take. A map pack can be activated only once three
validators have rebuilt its proof index and attested the same root.

## Prices

Fares are a market inside a band Finanças sets in `FareGuide`. The app recommends a price for the trip
from its distance and duration, starting at R$ 3,00 plus R$ 2,00 per km and R$ 0,25 per minute, never under
R$ 8,00. The rider may ask up to 10% above or below that. A driver may quote from 20% under the rider's price
up to 10% above the recommended one, so a driver can ask for more than the rider offered and the rider decides.
The escrow never learns how long a trip is, so the phones keep the band: a driver's phone ignores a request
priced outside it, and a rider's phone ignores a quote outside the driver's limits. The tariff and both limits
change only through the council.

What comes out of a fare, and what does not:

| | On the local testnet | Who bears it | Where it is set |
| --- | --- | --- | --- |
| Instituto Klippel | R$ 1,00 per ride | The driver: deducted from the fare | `RideEscrow.setToken` |
| Government tax (imposto) | 5% of the fare | The driver: deducted from the fare | `RideEscrow.setTax` |
| Infrastructure cost | About R$ 0,004 a ride at local gas prices (1 gwei, R$ 2,50 a coin) | Each side pays the network for its own operations, outside the escrow: `BrlPaymaster` pays in the chain's coin and charges the account the same in reais | The network's gas price; `FareGuide.setNativeCoinPrice` sets the rate the paymaster charges at and the app shows it in |

A ride keeps the tax it opened with. The app shows all three before the rider locks and before the driver
quotes, and what was actually paid when the ride ends.

## Tests

```sh
make test         # forge test + cargo test in core/ and mappack/
make test-gov     # gov/klippel-gov against the running local testnet
make e2e          # the real app on an emulator, against the real chain, gossip and map: integrity, security, performance
```

`make e2e` brings up whatever is missing (or uses the chain at `KLIPPELGO_RPC`), installs the app and runs
every suite. Nothing in it is mocked; the other people in a ride are peer accounts on the same network.
[tests/README.md](tests/README.md) explains the suites, the UI drivers and the `klippelgo` CLI.

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

Account recovery by passkey (the account accepts one; there is no screen to add it), Pix top-ups, the iOS app,
and a Polygon Amoy deployment. The gossip layer carries signed requests and offers under session certificates,
in cells of about 5 km around the pickup, but not yet encrypted offers, presence, relays or the encrypted ride channel (see
[apps/android](apps/android/README.md)). The pickup proof is recorded as a hash the driver submits; nothing
checks both phones' signatures on it yet.

Of the trip proofs, the Android app does the first half: the rider's phone computes and verifies the route and
locks the real quote hash, and the chosen driver's phone receives the route sealed to it and checks it before
the driver accepts. GPS logging and the signed trip receipt are built and tested in the core but not wired into
the app, because the phones exchange them over the encrypted ride channel. Until
then the app submits a stand-in receipt hash. See
[docs/maps-and-routing.md](docs/maps-and-routing.md#what-is-built).
