# KlippelGo

A ride-hailing protocol with no central server. Riders and drivers find each other phone to phone, lock the
fare in an on-chain escrow paid in a BRL stablecoin, and every rule is a policy the community governs on-chain.
Personal data stays encrypted on the phone. Each completed ride pays a flat R$ 1,00 to Instituto Klippel,
deducted from the fare.

The design is in the [KlippelGo Architecture Plan](https://claude.ai/code/artifact/9a1aa829-3b30-4485-9395-42fa77304dc8).

## Layout

| Path | What it is |
| --- | --- |
| `contracts/` | Solidity contracts (Foundry): `PBACGovernor`, `IdentityRegistry`, `RideEscrow`, `FeeVault`, `Reputation`, `DisputeCourt`, plus mock BRLA and BRZ for local use |
| `testnet/` | Scripts to run a local chain, deploy, and run a demo ride |
| `deployments/` | Contract addresses per network (`local.json` is written by the deploy script) |
| `core/` | Shared Rust core for the apps (money and fee maths, geohash discovery cells, network settings) |
| `apps/android`, `apps/ios` | Native app shells (Kotlin/Compose, Swift/SwiftUI), not created yet |

## Local testnet

Needs [Foundry](https://book.getfoundry.sh/getting-started/installation) (`forge`, `anvil`, `cast`).

```sh
git clone --recurse-submodules <this repo>
make testnet      # terminal 1: anvil on http://127.0.0.1:8545, chain id 31337
make deploy       # terminal 2: deploy contracts, mock BRLA/BRZ, seed accounts
make demo         # run one R$ 23,40 ride: driver gets R$ 22,40, Instituto Klippel R$ 1,00
```

No Foundry? `docker compose -f testnet/docker-compose.yml up` starts the same chain; deploy still needs `forge`.

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
| `PBACGovernor.propose` | Any registered role |

Every policy, the BRL allow-list, fees, timeouts and trusted attesters change only through a governance
proposal.

## Tests

```sh
make test         # forge test + cargo test
```

## Not built yet

ERC-4337 smart accounts and the paymaster, the libp2p discovery layer, the native apps, and a Polygon Amoy
deployment. The pickup proof is recorded as a hash the driver submits; checking both phones' signatures comes
with the smart accounts.
