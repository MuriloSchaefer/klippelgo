# Contracts: architecture overview

The contracts are the only shared state KlippelGo has. They hold the fare of a ride while it happens, keep the
rules everyone plays by, and record the few facts phones need to agree on. Everything else (matching, routes,
positions, personal data) happens between phones and never reaches the chain.

Related: [security.md](security.md), [scalability.md](scalability.md), [deployment.md](deployment.md), the
[core's overview](../../core/docs/overview.md) for the proofs these contracts anchor, and the
[app's overview](../../apps/docs/overview.md) for who calls them.

## What each contract does

| Contract | Responsibility | Holds funds |
| --- | --- | --- |
| [`PBACGovernor`](../src/PBACGovernor.sol) | Policies (who may call what), and the guardian: the one account whose calls it runs as the governor | No |
| [`TeamCouncil`](../src/TeamCouncil.sol) | The guardian: Instituto Klippel's team, its members and roles, and the approvals and delay each action needs. Also the attester the team issues credentials as | No |
| [`Governed`](../src/Governed.sol) | Base of every other contract: `permitted` and `onlyGovernor` | No |
| [`DataContract`](../src/DataContract.sol) | Base of the data contracts: the list of code contracts that may write them (`writers`), set only by the governor | No |
| [`ReleaseRegistry`](../src/ReleaseRegistry.sol) | Which releases apps may run: each version's contracts hash, the SHA-256 of its APKs, and whether it is active, deprecated or revoked | No |
| [`IdentityStore`](../src/IdentityStore.sol) | Data: roles and when an account registered, credential hashes from trusted attesters, the trusted attesters, and the accounts the guardian blocked | No |
| [`StakeVault`](../src/StakeVault.sol) | People's stake with unbonding. Final code: no writer list, no slashing | Stake |
| [`IdentityRegistry`](../src/IdentityRegistry.sol) | The release's code people register through: it adds roles in `IdentityStore` | No |
| [`RideEscrow`](../src/RideEscrow.sol) | The ride state machine: locks the fare and both bonds, pays out, and takes the fee and the government's tax from the fare. Keeps a payment it could not make until its payee withdraws it, and holds no more of a coin than its cap | Fares, bonds, payments owed |
| [`FeeVault`](../src/FeeVault.sol) | Collects R$ 1,00 per completed ride for Instituto Klippel | Fees |
| [`Reputation`](../src/Reputation.sol) | Data: completed-ride counts and star ratings between the two parties of a ride, reported by the escrows the guardian listed as writers | No |
| [`DisputeCourt`](../src/DisputeCourt.sol) | Commit-reveal juror vote that splits a disputed fare | No |
| [`MapRegistry`](../src/MapRegistry.sol) | Proof roots of the map packs, and the verifier's tolerances | No |
| [`FareGuide`](../src/FareGuide.sol) | The tariff and the band riders and drivers may price in, and what one native coin is worth in reais: the rate the app shows gas in, and the paymaster charges it at | No |
| [`KlippelGoAccount`](../src/account/KlippelGoAccount.sol) | A person's account: an ERC-4337 smart account owned by keys the phone keeps in hardware, which runs batches of calls and says what its keys signed (ERC-1271) | The person's coins |
| [`AccountFactory`](../src/account/AccountFactory.sol) | Makes accounts as clones, at an address that follows from the first key alone | No |
| [`DeviceKeyVerifier`](../src/account/DeviceKeyVerifier.sol) | Checks a phone chip's P-256 signature over SHA-256 of a hash | No |
| [`BrlPaymaster`](../src/account/BrlPaymaster.sol) | Pays the network for people's operations and charges them the same in a BRL coin, at `FareGuide`'s rate | Its native deposit at the EntryPoint, the reais it collected |

Each is one of three kinds, which decides how it changes ([docs/releases-and-data.md](../../docs/releases-and-data.md)):

| Kind | Contracts | Changes by |
| --- | --- | --- |
| Governance | `PBACGovernor`, `TeamCouncil`, `ReleaseRegistry` | Stays for the network's life; the council through `proposeGuardian` |
| Data, outlives every release | `IdentityStore`, `StakeVault`, `Reputation`, `MapRegistry`, `FareGuide` | Stays at its address. A release changes only who may write it; moving it is a migration |
| Code, one release's | `IdentityRegistry`, `RideEscrow`, `DisputeCourt`, `FeeVault`, `AccountFactory`, `BrlPaymaster` | A release deploys new ones, the board wires them in, the old ones drain |

## How they fit together

```mermaid
flowchart TB
    subgraph phones [Phones]
        rider[Rider]
        driver[Driver]
        validator[Validator]
    end

    gov[PBACGovernor<br/>policies, runs the guardian's calls]
    council[TeamCouncil<br/>the guardian: Instituto Klippel's team]
    rel[ReleaseRegistry<br/>releases apps may run]
    reg[IdentityRegistry<br/>register]
    ids[(IdentityStore<br/>roles, credentials, blocks)]
    stk[(StakeVault<br/>stake)]
    rep[(Reputation<br/>rides and ratings)]
    esc[RideEscrow<br/>fare and bonds]
    vault[FeeVault]
    court[DisputeCourt]
    maps[MapRegistry]
    fares[FareGuide]
    inst([Instituto Klippel])
    team([Team members])

    entry[EntryPoint<br/>ERC-4337 v0.9]
    acct[KlippelGoAccount<br/>each person's]
    pm[BrlPaymaster]
    rider & driver & validator -. "operations, through a bundler" .-> entry
    entry -- "validateUserOp, execute" --> acct
    entry -- "gas, charged in reais" --> pm
    pm -. price .-> fares
    rider & driver -- register --> reg
    reg -- "addRoles (a writer)" --> ids
    driver & validator -- "addStake, withdrawStake" --> stk
    rider & driver -. "status of their release" .-> rel
    rider -- open, complete, cancel --> esc
    driver -- accept, start, submitReceipt --> esc
    rider & driver -- raise --> court
    validator -- commit, reveal --> court
    validator -- attest --> maps
    rider & driver -. read tariff .-> fares
    rider & driver -. read proof root .-> maps

    esc -- "hasPermission?" --> gov
    court -- "hasPermission?" --> gov
    maps -- "hasPermission?" --> gov
    gov -- roles, credential, block --> ids
    gov -- stake --> stk
    gov -- score, completed rides --> rep

    esc -- "recordCompletion (a writer)" --> rep
    esc -- fee --> vault
    vault -- sweep --> inst
    court -- freeze, resolve --> esc

    team -- "request, approve, cancel, execute" --> council
    council == "execute (the guardian)" ==> gov
    council -- "attest, revoke (an attester)" --> ids
    gov == "onlyGovernor setters" ==> esc & ids & stk & vault & court & maps & fares & rep & rel
```

Riders, drivers and validators act through their own `KlippelGoAccount`: every call above is made by the
account, inside an ERC-4337 operation its phone's key signed and a bundler handed to the EntryPoint, with gas paid
by `BrlPaymaster` and charged to the account in reais. The contracts see the account's address as `msg.sender`,
as they would any other.

Two kinds of arrows matter:

- **Policy checks** (`hasPermission?`). A user-facing function marked `permitted` asks the governor whether
  `msg.sender` satisfies the policy stored for `(this contract, this selector)`. The governor answers from the
  caller's roles, account age and credentials in `IdentityStore`, its stake in `StakeVault` and its score in
  `Reputation`. An account governance blocked (`IdentityStore.setBlocked`) satisfies none.
- **Governor setters**. Every parameter has a setter guarded by `onlyGovernor`. The governor calls it only
  when its guardian asks, through `PBACGovernor.execute`. The guardian is the team council, which runs an
  action once enough members of the right role approved it and its delay is over.

No contract has an owner. The deployer wires the contracts through `bootstrapCall`, calls `finishBootstrap`,
which sets `bootstrapper` to zero for good, and hands the guardian's seat to the council it deployed.

## The ride state machine

```mermaid
stateDiagram-v2
    [*] --> Open: rider open()<br/>locks fare + rider bond
    Open --> Accepted: driver accept()<br/>locks driver bond, if no other ride of theirs waits for pickup
    Open --> Cancelled: rider or chosen driver cancel(), driver decline()<br/>rider refunded in full
    Accepted --> Started: driver start(pickupProofHash)<br/>if no other ride of theirs is under way
    Accepted --> Cancelled: cancel()<br/>canceller's bond goes to the other party
    Started --> Completed: rider complete(receiptHash)
    Started --> Completed: anyone timeout()<br/>after the driver's receipt stood for the ride's timeout
    Accepted --> Disputed: the ride's court freeze()
    Started --> Disputed: the ride's court freeze()
    Disputed --> Resolved: the ride's court resolve(riderBps)
    Completed --> [*]
    Cancelled --> [*]
    Resolved --> [*]
```

A driver has at most two rides in hand: one under way, with its rider in the car, and one accepted, on the way
to its pickup. The escrow keeps both per driver (`onTripOf`, `pickupOf`). `accept` reverts with `DriverBusy`
while a ride waits for its pickup, so the next ride is taken only once the current rider is in; `start` reverts
while a ride is under way. A ride frees its place when it starts, is cancelled, is frozen for a dispute or
settles. `open` is never limited: anyone may lock a ride naming a busy driver, and it waits Open until the
driver takes it, declines it or its rider withdraws it. The driver's `decline` is `cancel` for an Open ride only:
it reverts once the ride is accepted, so a decline that lands after an accept never costs the bond.

Where the money goes at each exit:

| Exit | Rider gets | Driver gets | FeeVault gets | Tax collector gets |
| --- | --- | --- | --- | --- |
| Completed | rider bond | fare − fee − tax + driver bond | fee | tax |
| Cancelled from Open | fare + rider bond | nothing (posted nothing) | nothing | nothing |
| Cancelled from Accepted by rider | fare | driver bond + rider bond | nothing | nothing |
| Cancelled from Accepted by driver | fare + rider bond + driver bond | nothing | nothing | nothing |
| Resolved | `riderBps` of the fare + rider bond | the rest − its tax − fee + driver bond | fee, only if what is left of the driver's share covers it | the ride's tax, in the proportion of the fare the driver keeps |

The fee and both bonds are copied from the token's config into the ride when it opens, the tax is worked out
then as `fare × taxBps` and kept with its collector, and so are the court and the completion timeout. A later
change never alters a ride already under way, nor where its tax goes, nor who may judge it. The rider never pays
more than the fare: fee and tax both come out of the driver's part.

Each payment in this table is made on its own. One that fails, because its payee's address is frozen by the
coin's issuer or cannot receive it, is kept in `owed` for that payee, who takes it with `withdraw`; the others
are paid as usual. Deposits are checked to arrive whole, and when the guardian set a cap on a coin, a deposit
that would take what the escrow holds of it (`held`) above the cap is refused.

Gas is not in this table. Each account pays the network for its own transactions in the chain's native coin,
and the escrow never sees it. `FareGuide.nativeCoinCentavos` is only a rate the guardian publishes so every
phone converts gas to reais the same way when it shows a ride's infrastructure cost.

## Governance

```mermaid
sequenceDiagram
    participant A as Member of a role
    participant B as Other members
    participant K as TeamCouncil
    participant G as PBACGovernor
    participant T as Target contract

    A->>K: request(permission, calls)
    Note over K: A is in the permission's role, the calls match it,<br/>stores keccak of the calls, readyAt = now + delay
    B->>K: approve(id, calls)
    Note over K: any member of the role or of the board<br/>may cancel until it runs
    A->>K: execute(id, same calls)
    Note over K: enough approvals by members still in the role,<br/>not suspended, and readyAt passed
    K->>G: execute(targets, calldatas)
    G->>T: onlyGovernor setter
```

`setPolicy` and `setModules` on the governor itself are `onlySelf`: a call the guardian runs through `execute`
is the only way to change a policy. The guardian changes in two steps: the guardian names a successor with
`proposeGuardian`, and nothing happens until that address calls `acceptGuardian`.

A permission names one function on one contract, optionally one value of one of its arguments (blocking but not
unblocking, say), how many approvals it needs and how long it waits; or it is a playbook, a list of calls
stored by hash that a role runs as it is. The council's own functions (members, suspensions, roles,
permissions) are reached the same way, under the board's permissions. Who holds which at launch, and why, is
[docs/governance.md](../../docs/governance.md); the code that sets it up is
[`script/TeamDeployer.sol`](../script/TeamDeployer.sol).

## Instituto Klippel's part

Instituto Klippel's team is the guardian. Each member acts with their own key, in the roles the board gave
them:

| It wants to | How | Takes effect |
| --- | --- | --- |
| Approve or revoke a driving licence (CNH), or KYC | Credenciamento, as the council, which is a trusted attester: `IdentityStore.attest`, `revoke` | Approve with two members, revoke with one, both at once |
| Block an account, or stop new rides | Segurança: `IdentityStore.setBlocked`, or the `stop-rides` playbook | One member, at once. Unblocking takes two |
| Revoke a release after a vulnerability | Segurança: `ReleaseRegistry.revoke`, or the release's `revoke-release-<version>` playbook, which also stops new rides on its escrow | One member, at once. Putting it back takes two directors |
| Change a fee, the tax, a cap, the tariff | Finanças | Two members, then a day |
| Change a policy, a timeout, the map packs | Operações | Two members, then a day |
| Change who is in the team, the guardian, the contracts wired in, the writers of a data contract, the fee beneficiary, or publish or deprecate a release | The board (Diretoria) | Three members, then 72 hours |

On the local testnet the council has one member, Instituto Klippel's account #1, in every role, with one
approval and no delay. The tool for all of this is [`gov/klippel-gov`](../../gov/README.md).

## What the chain knows about a ride

Addresses of rider and driver, the token, the fare, the bonds and fee, timestamps, and three hashes:
`quoteHash`, `pickupProofHash` and `receiptHash`. No place, no route, no name. What those hashes commit to is
defined in the core ([core/docs/overview.md](../../core/docs/overview.md)) and stays on the two phones unless a
dispute makes a party reveal it to jurors.

## Structure and patterns

These are the rules new contract code follows. The skill that applies them is
[`change-contracts`](../../.claude/skills/change-contracts/SKILL.md).

**Layout**

| Path | What goes there |
| --- | --- |
| `src/` | One contract per file, named after the contract |
| `src/interfaces/` | Interfaces other contracts import, to avoid import cycles |
| `src/account/` | People's accounts and what they need: the account, its factory, the device-key verifier, the paymaster. They follow ERC-4337 and OpenZeppelin's account contracts, not pattern 1: only the paymaster answers to the governor |
| `src/mocks/` | Test-only tokens. Never referenced by production contracts |
| `script/ProtocolDeployer.sol` | The one place that deploys and wires the protocol, lists each data contract's writers and sets launch policies |
| `script/AccountsDeployer.sol` | The account contracts and the paymaster over a deployed protocol, the paymaster funded and staked |
| `script/TeamDeployer.sol` | The team council's launch roles, permissions, playbooks and numbers, the handover of the guardian's seat, and publishing the launch release |
| `script/DeployLocal.s.sol`, `script/SeedPhones.s.sol` | Local network values and seeded accounts |
| [`../gov/`](../../gov/README.md) | `klippel-gov`, the command-line tool team members request, approve and execute actions with. Not a contract: it lists every call the council makes by signature, so a new one is added there too |
| `test/Protocol.t.sol` | Behaviour of the whole protocol, deployed through `ProtocolDeployer` |
| `test/TeamCouncil.t.sol` | The council with its launch numbers, deployed through `TeamDeployer` |
| `test/Accounts.t.sol` | Accounts and the paymaster through a real EntryPoint v0.9 |
| `test/ProofEncoding.t.sol` | Hash encodings shared with the Rust verifier, checked against the same vectors |

**Patterns**

1. Every protocol contract extends `Governed`; a data contract extends `DataContract`, whose writers only the
   governor lists, and keeps in its own code the rules no writer may break. Money held for as long as a person
   is on the network sits in a contract no writer list reaches (`StakeVault`). No `Ownable`, no admin
   address, no pausing key. The one account
   with power over them is the governor's guardian, `TeamCouncil`, which acts only through actions its members
   approve.
2. A function a user calls and that should be restricted is `permitted`, and its policy is set in
   `ProtocolDeployer`. A function that changes a parameter is `onlyGovernor`. A function called by another
   protocol contract checks that exact address (`NotCourt`), or the writer list of a data contract
   (`onlyWriter`).
3. A function that moves tokens is `nonReentrant`, uses `SafeERC20`, and writes its state before it transfers.
   The escrow pays through `_pay`, which keeps a failed payment as owed, and takes deposits through `_deposit`,
   which checks the cap and that the whole amount arrived.
4. Every state change emits an event. Events index the ids and addresses a phone filters by.
5. Failures are custom errors, never revert strings.
6. Amounts are in the token's own base units. BRL tokens differ in decimals (BRLA 18, BRZ 4), so nothing
   hard-codes a decimal count: per-token values live in `tokenConfig`.
7. No function loops over state that users can grow. See [scalability.md](scalability.md). The council loops
   over an action's approvers, roles and a role's permissions: state only the team grows.
8. Personal data never goes on chain, not even encrypted. Only hashes and addresses.
9. An encoding that the phones or the verifier also compute (quote hash, receipt hash, Merkle leaves) has a
   vector in `ProofEncoding.t.sol` and the same vector in `core/verify/tests/vectors.rs`.

**Adding or changing a contract**

1. Write the behaviour and its test in `test/Protocol.t.sol`.
2. Wire it in `ProtocolDeployer` and set its policies there. Add its address to the deployment file written by
   `DeployLocal.s.sol`, and to `Deployment.kt` in the app if phones call it. A new `onlyGovernor` function
   needs a permission for the role that should call it, in `TeamDeployer` (and, on a live network, a `grant`
   by the board), and goes into `KNOWN_CALLS` in `gov/klippel-gov`, or members see it as an unknown call.
3. Update the table and diagram on this page, the access table in [security.md](security.md), and the README's
   policy table if a policy changed.
4. `forge fmt` (line length 110) and `make test-contracts`.

**Tooling**: Foundry, Solidity 0.8.28, OpenZeppelin v5.7 and eth-infinitism's account-abstraction v0.9.0 (the
EntryPoint) as git submodules in `lib/`. CI runs `forge fmt --check`,
`forge test`, then deploys to a local chain, plays one ride (`testnet/demo-ride.sh`) and runs the governance
tool's checks (`gov/test.sh`).
