# Contracts: deployment

How contract code gets from this repository to a network people ride on, how a new version follows it, and
what happens to the version before.

Related: [overview.md](overview.md), [security.md](security.md), [scalability.md](scalability.md), and
[gov/README.md](../../gov/README.md) for the tool that governs a deployment once it is live.

**Status.** Only the local testnet is deployed today (`script/DeployLocal.s.sol`, `testnet/deploy.sh`), and CI
builds, tests and plays one ride on it. Everything about Polygon Amoy and Polygon PoS below is the design to
build to. The last section lists what is missing, so nobody reads this page as a description of something
that runs.

## What makes these contracts different to deploy

Four facts decide everything else on this page.

| Fact                                                                                                                                                                                                               | Consequence                                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| No contract has an owner or a proxy, and the one authority is the guardian, Instituto Klippel's team council ([security.md](security.md), C1). Code at an address never changes | A fix is never "pushed". It is either a parameter the council changes, a new contract the council wires in, or a whole new deployment |
| There is no pause in the contracts | Segurança's `stop-rides` playbook stops new rides at once; rides under way go on. What ships must be right, and the route for the day it is not is written down before launch |
| The deployer can do anything until `finishBootstrap` and the council accepting the guardian's seat (L11), and the deploy script is many transactions, not one | A deployment is not trusted because it was made. It is trusted after anyone has checked it against the published configuration |
| Addresses are identity. Every quote and receipt hash carries the chain id and the escrow's address, the app ships with the addresses compiled in, and stake, reputation and credentials live in specific contracts | A new deployment is a new network of people, not an update. Moving to one has a cost that no script removes                            |

## Environments

|                   | Local                                     | Testnet                                        | Production                                                    |
| ----------------- | ----------------------------------------- | ---------------------------------------------- | ------------------------------------------------------------- |
| Chain             | anvil, id 31337                           | Polygon Amoy, id 80002                         | Polygon PoS, id 137                                           |
| BRL tokens        | Mocks that mint to anyone                 | Mocks, deployed once and reused                | The real BRLA and BRZ contracts                               |
| Deployer key      | anvil's public mnemonic                   | A throwaway key held by CI                     | A key made for one deployment, on a hardware wallet           |
| Who broadcasts    | Anyone:`make deploy`                    | CI, on a release candidate tag                 | A person, in a ceremony two people attend                     |
| Governance timing | One member, one approval, no delay | The launch roles and approvals, delays of hours | The launch numbers: see [docs/governance.md](../../docs/governance.md) |
| Instituto Klippel | Seeded account #1, alone in every role of the council | Test members, each with a key of their own | The team council: each member's hardware wallet, Recuperação a cold multisig |
| Deployment file   | `deployments/local.json`, not committed | `deployments/amoy.json`, committed           | `deployments/polygon.json`, committed                       |
| Resets            | Whenever:`make testnet-reset`           | With each release candidate                    | Never                                                         |

Testnet exists to rehearse: the exact script, the exact configuration shape and the exact checks that
production will get, plus the app built against it. A step that was not run on testnet is not run for the
first time on production.

## From commit to production

```mermaid
flowchart LR
    pr[Pull request] --> ci[CI: fmt, tests,<br/>local deploy, a ride,<br/>governance checks]
    ci --> main[main]
    main --> rc[Tag<br/>contracts-vX.Y.Z-rc.N]
    rc --> amoy[CI deploys to Amoy<br/>and verifies it]
    amoy --> soak[Soak: app built against it,<br/>e2e suites, a real council action]
    soak --> tag[Tag<br/>contracts-vX.Y.Z]
    tag --> rel[CI: reproducible build,<br/>dry run on a fork of mainnet,<br/>release bundle]
    rel --> cer[Ceremony: a person broadcasts<br/>from a hardware wallet]
    cer --> ver[Verification by a second person<br/>and by CI]
    ver --> pub[deployments/polygon.json merged,<br/>sources verified, app released]
```

| Stage             | Gate to pass                                                                                                                                                       | Who                      |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------ |
| Pull request      | `forge fmt --check`, `forge test`, local deploy, `demo-ride.sh`, `gov/test.sh`, the security review ([CLAUDE.md](../../CLAUDE.md))                          | Author, reviewer, CI     |
| Release candidate | Deploys and verifies on Amoy with no manual step                                                                                                                   | CI                       |
| Soak | The app's end-to-end suites against Amoy; one action taken through request, approve and execute with `klippel-gov` by two members; a map pack activated | Maintainers |
| Audit | For any change to a contract that holds funds (`RideEscrow`, `IdentityRegistry`, `FeeVault`), to `PBACGovernor` or to `TeamCouncil`: an external review of the tagged commit | External |
| Release           | The build from the tag reproduces byte for byte; the dry run on a fork of mainnet ends with the expected addresses and a closed bootstrap                          | CI                       |
| Ceremony          | Runbook below                                                                                                                                                      | Two people               |
| Publication       | Verification passes when run by someone who was not the deployer                                                                                                   | A second person, then CI |

**Versions.** A tag `contracts-vMAJOR.MINOR.PATCH` names what is on chain:

| Part  | Changes when                                                          | On chain                                            |
| ----- | --------------------------------------------------------------------- | --------------------------------------------------- |
| MAJOR | A new full deployment: new governor, new addresses                    | New network of contracts. The old one keeps running |
| MINOR | A contract is replaced or added inside a live deployment, by the board | One or more new addresses in the deployment file |
| PATCH | Scripts, tests, docs, tooling                                         | Nothing                                             |

A parameter changed by the council is not a version: it is governance doing its job, and `klippel-gov status`
shows the result.

## Configuration

Two kinds of value go into a deployment, and they are kept apart on purpose.

**Launch parameters are public and live in git.** `ProtocolDeployer.Config` and `TeamDeployer.Team` are filled from
`deployments/config/<network>.json`, a file reviewed in a pull request like code. Nothing that decides how the
protocol behaves comes from an environment variable: a variable leaves no record of who chose it or why.

| Field                                            | Production guidance                                                                                                                             | Why                                                                                       |
| ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `tokens`                                       | Addresses of the real BRLA and BRZ, taken from their issuers and checked on the explorer by two people                                          | L10: only plain ERC-20s. A wrong address here is where fares go                           |
| `stakeToken`                                   | One of the above                                                                                                                                |                                                                                           |
| `feeBeneficiary`                               | A multisig or cold wallet that never signs day to day                                                                                           | It receives every fee                                                                     |
| `attesters` | None besides the council, which `TeamDeployer` adds. Any other: a multisig, threshold 2 or more | L22: one key here approves any licence |
| `taxBps`, `taxCollector` | The collector is a plain account that can receive the token | A payment it cannot receive waits in the escrow as owed (C16) until it can |
| `team` | Every member's address, read aloud by its holder; at least three directors; Recuperação a cold multisig, threshold 3 of 4 | Who governs. A swapped address here is a stranger on the board |
| `unbondingPeriod` | Days | How long stake waits; also how long a driver who wants to leave stays bound |
| `mapRebuildQuorum`                             | At least 3                                                                                                                                      | C13                                                                                       |
| Everything else                                  | `completionTimeout`, `commitPeriod`, `revealPeriod`, the tariff and the verifier tolerances start from the values in `ProtocolDeployer` | Reviewed again for real traffic before launch                                             |

The local testnet's numbers (one member in every role, one approval, no delay) are for tests: `TeamDeployer`
uses them only with `local: true`. With the launch numbers the council itself refuses a board with fewer
members than its approvals, or with a delay over who decides under a day. The production script is to refuse a
zero address in any role, or a chain id that does not match the file's name. Caps on the coins are set by
Finanças after launch, once real volumes are known.

**Secrets and endpoints come from the environment**, and none of them can change what gets deployed.

| Variable                                                                                                                                       | Used by                                 | Holds                                                                                      | Where it is set                                                 |
| ---------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| `RPC_URL`                                                                                                                                    | `testnet/*.sh`, deploy scripts        | Chain node. Default`http://127.0.0.1:8545`                                               | Shell; GitHub environment secret for Amoy and for mainnet reads |
| `MNEMONIC`                                                                                                                                   | `LocalAccounts.sol`, `testnet/*.sh` | The local testnet's accounts. Default: anvil's public mnemonic                             | Local only. Never set to a real one                             |
| `DEPLOYMENT`                                                                                                                                 | `testnet/*.sh`, `gov/test.sh`       | Path to the deployment file                                                                | Shell                                                           |
| `NETWORK`                                                                                                                                    | Production deploy script (to build)     | `amoy` or `polygon`: selects `deployments/config/<network>.json` and the output file | Workflow input; shell at the ceremony                           |
| `DEPLOYER_KEY`                                                                                                                               | Amoy deploy only (to build)             | The throwaway testnet deployer                                                             | GitHub environment`amoy`, secret                              |
| `ETHERSCAN_API_KEY`                                                                                                                          | `forge verify-contract`               | Explorer API key for source verification                                                   | GitHub environment secret; shell at the ceremony                |
| `KLIPPEL_GOV_RPC`, `KLIPPEL_GOV_DEPLOYMENT`, `KLIPPEL_GOV_WALLET`, `KLIPPEL_GOV_SAFE`, `KLIPPEL_GOV_OUT`, `KLIPPEL_GOV_FROM_BLOCK` | `gov/klippel-gov`                     | See[gov/README.md](../../gov/README.md)                                                     | Operator's shell                                                |
| `KLIPPELGO_RPC`, `KLIPPELGO_DEPLOYMENT`                                                                                                    | `tests/`, the app's build             | Chain and addresses the tests and the app use                                              | See[tests/README.md](../../tests/README.md)                      |

There is no variable for a production private key, anywhere. The production deployer signs on a hardware
wallet (`--ledger`), and no workflow holds a key that can do anything on mainnet.

**The deployment file** is the output, and the one thing the app, the tests and `klippel-gov` all read:

```json
{
  "chainId": 137,
  "version": "1.0.0",
  "commit": "<the tagged commit>",
  "deployBlock": 0,
  "PBACGovernor": "0x…", "TeamCouncil": "0x…", "IdentityRegistry": "0x…", "Reputation": "0x…", "FeeVault": "0x…",
  "RideEscrow": "0x…", "DisputeCourt": "0x…", "MapRegistry": "0x…", "FareGuide": "0x…",
  "BRLA": "0x…", "BRZ": "0x…",
  "institutoKlippel": "0x…", "taxCollector": "0x…"
}
```

`deployBlock` is where `klippel-gov` starts reading the council's events from. Forge's own record of the transactions
(`contracts/broadcast/<script>/<chain id>/`) is committed with it for every chain but the local one.

## Keys

| Key                        | Power                                                                          | Kept                                                                                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Production deployer | Everything, from the first transaction until the council accepts the guardian's seat. Nothing after | A fresh account on a hardware wallet, funded with gas for one deployment, never used again. Its only value afterwards is that its nonce explains the addresses |
| Amoy deployer              | The same, on a testnet                                                         | GitHub environment secret. Worthless by design                                                                                                                 |
| Team members | What their roles allow, with the approvals and delays of [docs/governance.md](../../docs/governance.md) | Each member's own hardware wallet; Recuperação a cold multisig in separate safes |
| Fee beneficiary            | The fees                                                                       | A separate multisig or cold wallet                                                                                                                             |
| First validators | The map quorum, and the first jurors | Each validator's own |
| CI                         | Reads mainnet. Writes to Amoy                                                  | GitHub environments, with required reviewers on`polygon` even though it only reads                                                                           |

## Workflows

| Workflow                  | Trigger                                                      | Does                                                                                                                                    | Can write to |
| ------------------------- | ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- | ------------ |
| `ci.yml` (exists)       | Pull request, push to`main`                                | Format, tests, local deploy, one ride,`gov/test.sh`, core and Android jobs                                                            | Nothing      |
| `deploy-testnet.yml`    | Tag`contracts-v*-rc.*`, or by hand                         | Deploys to Amoy, verifies the deployment and the sources, opens a pull request with`deployments/amoy.json` and the broadcast record   | Amoy         |
| `release.yml`           | Tag`contracts-v*` (no `-rc`)                             | Clean build from the tag, twice, comparing bytecode; dry run of the production script on a fork of mainnet; attaches the release bundle | Nothing      |
| `verify-deployment.yml` | After a deployment file changes on`main`; nightly; by hand | Runs the verification below against each committed deployment and reports drift                                                         | Nothing      |

What the two new deployment workflows look like:

```yaml
# deploy-testnet.yml
on:
  push: { tags: ['contracts-v*-rc.*'] }
  workflow_dispatch:
jobs:
  deploy:
    environment: amoy                      # holds RPC_URL, DEPLOYER_KEY, ETHERSCAN_API_KEY
    steps:
      - checkout with submodules, foundry-toolchain pinned to a version
      - forge build && forge test
      - NETWORK=amoy forge script script/Deploy.s.sol --rpc-url "$RPC_URL" --broadcast --slow --verify
      - contracts/script/verify-deployment.sh deployments/amoy.json
      - open a pull request with deployments/amoy.json and contracts/broadcast/Deploy.s.sol/80002/
```

```yaml
# release.yml
on:
  push: { tags: ['contracts-v[0-9]+.[0-9]+.[0-9]+'] }
jobs:
  bundle:
    environment: polygon                   # RPC_URL only, read access; required reviewers
    steps:
      - checkout with submodules, foundry-toolchain pinned to a version
      - forge build --force, record the hash of every runtime bytecode; build again in a clean job and compare
      - forge test
      - anvil --fork-url "$RPC_URL" & NETWORK=polygon forge script script/Deploy.s.sol --rpc-url localhost
      - verify-deployment.sh against the fork: expected addresses, closed bootstrap, state equal to the config
      - attach to the release: ABIs, bytecode hashes, deployments/config/polygon.json, the dry run's addresses
```

Neither workflow, nor any other, broadcasts to mainnet. Third-party actions are pinned to a commit, and the
Foundry version is pinned, because the compiler is part of what gets deployed.

## The production deployment

Two people, one of whom does not hold the deployer key. Everything is read aloud from the release bundle.

1. **Freeze.** The tag is on `main`, the audit covers that commit, the Amoy soak ran on the same commit.
2. **Rebuild.** Check out the tag with submodules, build, and compare the bytecode hashes with the release
   bundle. A mismatch stops the ceremony.
3. **Check the configuration.** Read `deployments/config/polygon.json` against the explorer: both token
   addresses, the beneficiary, the tax collector, Recuperação's multisig (threshold and owners, in the Safe
   app). Each team member reads their own address aloud from their hardware wallet.
4. **Fund the deployer** with gas for the deployment and little more.
5. **Dry run** on a fork, from the deployer's address. Note the addresses it predicts.
6. **Broadcast** with `--ledger --slow`. The script deploys, wires through `bootstrapCall`, sets the launch
   policies, calls `finishBootstrap`, deploys the team council and hands it the guardian's seat. If it stops part of the way, nothing is announced: the half-made
   deployment is abandoned and the ceremony starts again from step 4 with a new deployer account.
7. **Verify**, by the person who did not broadcast, from their own machine and node. See below.
8. **Publish.** Verify sources on the explorer, open the pull request with `deployments/polygon.json` and the
   broadcast record, and only after it merges tell anyone the addresses.

Until step 8, the deployment does not exist as far as anyone else knows, which is what makes step 6 safe to
repeat.

**Verification** is what turns "someone deployed something" into a deployment. `verify-deployment.sh` checks,
and fails on any difference:

| Check                                                                                                                                                                                 | Against                               | Catches                                                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------------------- |
| Runtime bytecode at each address                                                                                                                                                      | The build from the tag                | Different code                                                                        |
| `bootstrapper() == 0`, `guardian() == TeamCouncil`, `pendingGuardian() == 0` | | L11: a deployer that kept its power |
| `governor()` of every contract and the council's `governor`, the governor's `registry` and `reputation`, the escrow's `reputation`, `feeVault`, `court`, the court's `escrow`, `Reputation.isEscrow` of the escrow | The deployment file | A contract wired to something else |
| Every parameter, policy, token configuration, attester, the tariff and the verifier tolerances                                                                                        | `deployments/config/<network>.json` | A value changed between deploy and bootstrap's end                                    |
| The full list of `PolicySet`, `AttesterSet`, `TokenConfigured`, `ModulesSet`, `RoleCreated`, `MemberRoleSet`, `PermissionGranted` and `PlaybookGranted` events since `deployBlock` | The same file | Something extra set during bootstrap: a second attester, a policy on another function, an extra member or permission |
| No council actions, no identities, no stake, no rides | | A deployment someone used before it was announced |

`klippel-gov status` already checks that the bootstrap is closed, that every contract answers to the governor,
that the council is the guardian and that no handover is waiting, and is what an operator runs day to day; it
does not compare bytecode or the rest of the wiring. After launch the nightly run compares state with the
council's executed actions instead of the launch file: parameters are meant to move, by the council and only
by the council.

**Day one.** A fresh deployment has no map, so nobody can ride yet:

1. Every member runs `klippel-gov team` and checks their own address and roles.
2. The first validators register and stake.
3. Each rebuilds the pilot city's map pack and attests its root; Operações requests the activation, a second
   member approves, and it runs after its day ([docs/maps-and-routing.md](../../docs/maps-and-routing.md)).
4. Drivers register, stake, and have their licences approved by Credenciamento.
5. The app built with `deployments/polygon.json` is released.

Step 3 takes a day at least. Plan the ceremony that far ahead.

## Shipping a change after launch

```mermaid
flowchart TD
    q{What changes?}
    q -- "A number, a policy, a token,<br/>an attester, a block" --> p[Council action<br/>no deployment]
    q -- "The code of FareGuide, FeeVault,<br/>MapRegistry, DisputeCourt,<br/>RideEscrow or TeamCouncil" --> m[Deploy the one contract,<br/>the board wires it in<br/>MINOR]
    q -- "The code of Reputation,<br/>IdentityRegistry or PBACGovernor" --> v[New full deployment<br/>MAJOR]
```

**A parameter** needs no engineer: `klippel-gov request …`, the role's approvals, its delay. It is the
efficient path, and most of what will ever need changing was made a parameter for that reason.

**A replaced contract** goes through the same pipeline as a release (tag, Amoy, audit if it holds funds),
is deployed by anyone since it has no privilege of its own (its constructor takes the governor), is verified
and source-verified, and only then is wired in by a board action. Each director checks the bytecode at that
address against the tag before approving. The 72 hours are everyone else's window to see it, and the
team's to cancel it. What each replacement costs today:

| Contract             | Wired by                                                                                                                          | Safe to replace in place?    | What is lost                                                                                                                                                                    |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FareGuide`        | Nothing on chain: the app reads its address                                                                                       | Yes, with an app release     | Nothing. Old apps keep reading the old tariff                                                                                                                                   |
| `MapRegistry`      | `setPolicy` for `attest`; the app reads its address                                                                           | Yes, with an app release     | Activated packs must be attested and activated again                                                                                                                            |
| `FeeVault`         | `RideEscrow.setModules`                                                                                                         | Yes                          | Nothing: sweep the old vault. Rides already open pay the new one, since the vault is read at settlement                                                                         |
| `DisputeCourt` | `RideEscrow.setModules`, `setPolicy` for `commit` | Yes | Nothing: each ride keeps the court it opened with, so the old court finishes its disputes and the new one hears rides opened after it. A court with a bug cannot be swapped out of the rides that name it (L23) |
| `RideEscrow` | `Reputation.setEscrow(new, true)`, `setPolicy` for `open` and `accept`, a new `DisputeCourt` (its escrow is fixed at construction), and new rides closed on the old one | Yes, with an app release | Nothing: `Reputation` listens to both, rides are keyed by escrow and id, and the old escrow settles what it holds. Delist it once its last ride settled |
| `Reputation` | `PBACGovernor.setModules`, `RideEscrow.setModules` | No | Every score and every completed-ride count |
| `IdentityRegistry` | `PBACGovernor.setModules`                                                                                                       | No                           | Roles, credentials, blocks. Stake stays withdrawable from the old registry but counts for nothing                                                                               |
| `PBACGovernor` | It is `immutable` in every contract | No | Everything |
| `TeamCouncil` | `PBACGovernor.proposeGuardian` by the board, then `acceptGuardianSeat` on the new council; `setAttester` for the new one | Yes | Credentials the old council issued stay valid while it stays on the attester list. Pending actions on the old council die with its seat |

So `Reputation`, `IdentityRegistry` and `PBACGovernor` are a new deployment in practice, and that is the
honest state of deployability today: cheap for parameters, the leaf contracts, the escrow and the council,
expensive for the ledgers and the governor.

## Old versions

An old deployment cannot be switched off, and should not need to be: it holds people's money, and the rule
is that what an account is owed it can always take (C15 says the same of a block).

**Retiring a deployment** is something its own council does, in this order:

1. **Announce** the successor: its tag, its addresses, the date. The app release that moves to it ships.
2. **Close the door.** Finanças, on the old council, calls `setToken` with a fee of zero for each coin: no new
   ride can open. Rides under way are untouched, since each keeps the terms it opened with (C5).
3. **Drain.** Rides complete, time out or are disputed. Nothing forces the last one: a Started ride with no
   receipt waits for one of its parties (L3). Stake leaves through `requestUnstake` and `withdrawStake`, which
   need no policy. `FeeVault.sweep` sends the last fees. Leave the dispute court and its jurors' policy alone
   until no ride is open.
4. **Keep reading it.** `deployments/polygon.json` now names the successor, and the old file moves to
   `deployments/retired/polygon-v1.json`. `verify-deployment.yml` keeps watching it, and
   `klippel-gov --deployment deployments/retired/polygon-v1.json` keeps governing it, for as long as it holds
   a single token.

What does not move to the successor, and has to be said plainly to users before step 1:

|                                 | In the new deployment                                                                       |
| ------------------------------- | ------------------------------------------------------------------------------------------- |
| Stake                           | Withdrawn from the old registry after`unbondingPeriod`, staked again by its owner         |
| Driving licence and KYC | Approved again by Credenciamento. It can prepare these in bulk from its own records |
| Rides completed, score, ratings | Start at zero                                                                               |
| Blocked accounts | Blocked again by Segurança, if still wanted |
| Map packs                       | Attested and activated again                                                                |

**The app** ships with one deployment's addresses, so an app version belongs to a deployment. During a
retirement, the old app keeps working against the old contracts for whoever has a ride under way, and tells
its user to update before starting another. Two things must be settled before the first retirement: the gossip
topic (`/klippelgo/match/1/<cell>`) does not carry the deployment, so phones on both would hear each other's
requests; and the app needs to hold a retired deployment long enough to finish a ride and withdraw stake.

**If a deployed contract turns out to be exploitable**, in order of speed:

1. Segurança runs `stop-rides`, one member, at once: nobody may open or accept a ride. Rides under way go on,
   on their own terms.
2. Credenciamento revokes licences where a driver is the problem: one member, at once.
3. An app release that refuses to open rides on that deployment. It reaches only phones that update.
4. The board closes the door for good (step 2 of retiring a deployment), or wires in a fix.

The board's `resume-rides` puts the policies back once it is safe.

## What has to be built

| Missing                                                                                                                                                                  | For                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------ |
| `script/Deploy.s.sol`: reads `deployments/config/<network>.json`, refuses unsafe values, writes the deployment file with `version`, `commit` and `deployBlock` | Any network that is not local                    |
| `deployments/config/amoy.json`, `polygon.json`                                                                                                                       | The reviewed launch parameters                   |
| `script/verify-deployment.sh`                                                                                                                                          | Trusting a deployment                            |
| `deploy-testnet.yml`, `release.yml`, `verify-deployment.yml`, and the GitHub environments `amoy` and `polygon`                                                 | The pipeline                                     |
| A pinned Foundry version in every workflow                                                                                                                               | Reproducible bytecode                            |
| The app reading a deployment per build flavour instead of`local.json` only (`apps/android/app/build.gradle.kts`)                                                     | Testnet and production builds                    |
| The deployment in the gossip topic; the app keeping a retired deployment                                                                                                 | Retiring a version                               |
| `TeamDeployer` reading the team from `deployments/config/<network>.json` | The production council |
| The watcher that pages every member on each council request ([docs/governance.md](../../docs/governance.md)) | Production |
| An external audit                                                                                                                                                        | Production                                       |

A change that builds one of these updates this page in the same pull request, and moves its row out of this
table.
