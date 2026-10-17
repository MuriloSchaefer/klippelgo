# Contracts: security

What the contracts protect, who may do what, the rules that must always hold, and where the protection ends
today. The numbered invariants (`C1` to `C15`) are what the
[security reviewer](../../.claude/agents/security-reviewer.md) checks a change against.

Related: [overview.md](overview.md), [core security](../../core/docs/security.md),
[app security](../../apps/docs/security.md).

## What is at stake

| Asset | Where | Who could want it |
| --- | --- | --- |
| Fares and bonds of rides under way | `RideEscrow` | The other party, a third party, a malicious token |
| Stake | `IdentityRegistry` | Anyone |
| Collected fees | `FeeVault` | Anyone |
| The rules (policies, parameters, tariff, map roots) | `PBACGovernor` and every setter | Anyone who gains from changing them |
| Reputation and voting weight | `Reputation`, `IdentityRegistry` | Sybil accounts |

The chain itself is trusted to order and execute transactions. BRL tokens on the allow-list are trusted to
behave as plain ERC-20s. Nothing else is trusted: not the deployer after bootstrap, not attesters beyond the
credential they sign, not validators individually.

## Who may call what

| Function | Caller | Enforced by |
| --- | --- | --- |
| `RideEscrow.open` | Rider role | `permitted` |
| `RideEscrow.accept` | The driver named in the ride, with driver role, stake, score and CNH credential | `permitted` + `NotDriver` |
| `RideEscrow.start`, `submitReceipt` | The ride's driver | `NotDriver` |
| `RideEscrow.complete` | The ride's rider | `NotRider` |
| `RideEscrow.cancel` | Rider or driver of the ride | `NotParty` |
| `RideEscrow.timeout` | Anyone, after the driver's receipt stood for `completionTimeout` | time check |
| `RideEscrow.freeze`, `resolve` | `DisputeCourt` only | `NotCourt` |
| `DisputeCourt.raise` | Rider or driver of the ride, once | `NotParty`, `AlreadyRaised` |
| `DisputeCourt.commit` | Validator with stake, not a party to the ride | `permitted` + `PartyCannotJudge` |
| `DisputeCourt.reveal` | A juror who committed, with the matching value and salt | commitment check |
| `MapRegistry.attest` | Validator with stake, once per pack | `permitted` |
| `Reputation.recordCompletion` | `RideEscrow` only | `NotEscrow` |
| `Reputation.rate` | A party of a completed ride, once | `NotParty`, `AlreadyRated` |
| `IdentityRegistry.attest` | A trusted attester, for an account with no valid credential of that kind from another attester | `UntrustedAttester`, `NotIssuer` |
| `IdentityRegistry.revoke` | The credential's attester, or the governor | `NotIssuer` |
| `FeeVault.sweep` | Anyone: the destination is fixed | none needed |
| Every `set…` (among them `IdentityRegistry.setBlocked`), `MapRegistry.activate`/`retire`, `FeeVault.payClaim` | The governor, executing a passed proposal | `onlyGovernor` |
| `PBACGovernor.setPolicy`, `setParams`, `setModules` | The governor calling itself | `onlySelf` |
| `PBACGovernor.bootstrapCall` | The deployer, until `finishBootstrap` | `NotBootstrapper` |

Instituto Klippel is not in this table as a caller of its own. It acts through two rows any account can
stand in: as a trusted attester it issues and revokes credentials, and as a registered account it proposes.
[`gov/klippel-gov`](../../gov/README.md) is the tool it does both with, and holds no power the table does not
give.

## A ride, and what protects each step

```mermaid
sequenceDiagram
    autonumber
    participant R as Rider
    participant E as RideEscrow
    participant G as PBACGovernor
    participant D as Driver
    participant V as FeeVault
    participant T as Tax collector
    participant P as Reputation

    R->>E: open(driver, token, fare, quoteHash)
    E->>G: hasPermission(rider, open)
    Note over E: token on allow-list, fare > fee + tax<br/>fee, bonds, tax and its collector copied into the ride
    R-->>E: fare + rider bond
    D->>E: accept(rideId)
    E->>G: hasPermission(driver, accept)
    Note over G: driver role, stake, score, CNH credential
    D-->>E: driver bond
    D->>E: start(rideId, pickupProofHash)
    R->>E: complete(rideId, receiptHash)
    Note over E: status set to Completed before any transfer
    E-->>V: fee
    E-->>T: tax
    E-->>D: fare - fee - tax + driver bond
    E-->>R: rider bond
    E->>P: recordCompletion(rideId, rider, driver)
```

If the rider never confirms, the driver calls `submitReceipt`, which fixes the driver's evidence on chain and
starts `completionTimeout`. During that time the rider can still `complete` with the same receipt or raise a
dispute. After it, anyone can call `timeout`.

## A dispute

```mermaid
sequenceDiagram
    autonumber
    participant X as Rider or driver
    participant C as DisputeCourt
    participant E as RideEscrow
    participant J as Jurors

    X->>C: raise(rideId, evidenceHash)
    C->>E: freeze(rideId)
    Note over E: Accepted or Started becomes Disputed<br/>complete, cancel and timeout now revert
    X--)J: evidence, off chain
    Note over J: each juror runs klippelgo-juror<br/>on the evidence and the public map pack
    J->>C: commit(keccak(rideId, juror, riderBps, salt))
    Note over C: commit phase ends
    J->>C: reveal(riderBps, salt)
    Note over C: reveal phase ends
    X->>C: settle(rideId)
    C->>E: resolve(rideId, average riderBps)
    E-->>X: fare split, bonds returned
```

The commitment binds the ride id and the juror's address, so a commitment cannot be copied by another juror or
reused for another ride.

## Where data lives

```mermaid
flowchart LR
    subgraph phone [Stays on the phones]
        places[Pickup, drop-off, route]
        gps[GPS logs]
        docs[KYC, CNH, vehicle documents]
        salt[Route salt]
    end
    subgraph chain [On chain, public]
        qh[quoteHash]
        ph[pickupProofHash]
        rh[receiptHash]
        ch[credential hash + attester]
        addr[addresses, token, fare, timestamps]
        root[map pack proof root]
    end
    subgraph jurors [Revealed to jurors only in a dispute]
        ev[evidence.json]
    end
    places -- salted hash --> qh
    gps -- hash --> rh
    docs -- hash --> ch
    places & gps -. a party chooses to reveal .-> ev
    ev -. checked against .-> root
```

The route hash inside `quoteHash` is salted, so a known pair of addresses cannot be confirmed by hashing
guesses. Fares and the pair of addresses in a ride are public: that is what an escrow on a public chain costs.

## Invariants

A change that breaks one of these is a security bug, whatever the tests say.

- **C1. No privileged key.** After `finishBootstrap`, no address can change a parameter, move funds or bypass
  a policy except the governor executing a passed proposal.
- **C2. Escrow is solvent per ride.** For every ride not in a final state, the escrow holds
  `fare + riderBond` (plus `driverBond` from Accepted on) of that ride's token. Every exit pays out exactly
  what was locked: nothing is left behind and nothing is taken from another ride.
- **C3. One exit per ride.** A ride reaches Completed, Cancelled or Resolved once. The status is written
  before any transfer, and every function that transfers is `nonReentrant`.
- **C4. Only parties move a ride.** Outside the court and `timeout`, only the ride's rider or driver changes
  its state, and each only through their own functions.
- **C5. A ride keeps the terms it opened with.** Fee, bonds, the tax amount and the tax collector are read
  from the ride, never from the current `tokenConfig`, `taxBps` or `taxCollector`. Switching the tax off, or
  naming another collector, cannot lock or redirect a ride already under way.
- **C6. Only allow-listed tokens.** `open` reverts for a token whose fee is zero, and for a fare not above
  the fee plus the tax, so a settlement never pays out more than the fare. `setTax` refuses a rate of 100% or
  more, and a non-zero rate with no collector.
- **C7. A dispute freezes the ride.** While Disputed, only `resolve` from the court moves funds.
- **C8. A split is bounded.** `riderBps` is at most 10 000 at both the court and the escrow.
- **C9. A juror is not a party**, votes once per dispute, and cannot change a committed vote.
- **C10. A receipt cannot be swapped.** Once a receipt hash is on record, `complete` accepts only the same
  hash and `submitReceipt` reverts.
- **C11. Stake cannot be borrowed for an instant.** Stake counts for policies and votes only while it is in
  the registry, and leaves only after `unbondingPeriod`.
- **C12. Reputation comes only from real settlements.** `recordCompletion` is callable only by the escrow, and
  only parties of a recorded ride can rate, once each.
- **C13. A map root needs a quorum.** `activate` reverts unless `rebuildQuorum` distinct validators attested
  exactly that root, and a pack is activated once.
- **C14. A proposal executes exactly what was voted.** `execute` checks the calls against the stored hash,
  after quorum, majority and timelock, once.
- **C15. A block stops what an account starts, never what it is owed.** A blocked account satisfies no
  policy, so it cannot `open`, `accept`, `commit`, `attest` a map or `propose`. No function that returns
  funds or ends a ride is `permitted`: a blocked rider or driver still completes, cancels, files a receipt,
  raises a dispute and withdraws stake. Only the governor sets the flag, and it can clear it.

## Known limits

These are real today. A change that touches one should either close it or leave it no worse, and say which.

| # | Limit | Why it stands | What closes it |
| --- | --- | --- | --- |
| L1 | `pickupProofHash` and `receiptHash` are recorded, not verified | Checking needs both phones' signatures and the proof data | Smart accounts with P-256 keys; later a validity proof from `core/verify` |
| L2 | `open` does not check the fare against `FareGuide` | The escrow never learns the trip's length | By design: the phones keep the band ([app security](../../apps/docs/security.md)) |
| L3 | A Started ride with no receipt and no dispute stays locked until a party acts | Nobody else knows what happened | Either party can always act: `complete`, `submitReceipt` then `timeout`, or `raise` |
| L4 | Jurors are not paid and not slashed; one juror alone decides if only one reveals; no reveal means 50/50 | First version of the court | Juror selection, rewards, slashing, a minimum number of reveals |
| L5 | Stake is a gate, never seized: there is no slashing anywhere | Not built | Slashing by court or governance decision |
| L6 | Voting weight is read when the vote is cast, with no snapshot | Simplicity | Safe only while `unbondingPeriod` is longer than `votingPeriod`. Neither setter enforces that: a proposal changing either must keep it |
| L7 | Completed rides give voting weight and cost R$ 1,00 plus the tax each to fake between two own accounts | Sybil cost is the fee, the tax and gas | Credential-gated weight, or weight from rated rides only |
| L8 | An account with no ratings passes any `minScore` | New drivers must be able to start | Credential and stake gates carry the weight until the first rating |
| L9 | A dispute can be raised once per ride, by either party, at no cost | First version | A dispute bond |
| L10 | Fee-on-transfer, rebasing or hook tokens would break C2 | The escrow assumes plain ERC-20s | Governance must only allow-list plain tokens |
| L11 | The deployer is all-powerful until `finishBootstrap` | Wiring needs it | `ProtocolDeployer` calls it in the same script, which is many transactions and not one. Verify `bootstrapper() == 0` and the rest of [deployment.md](deployment.md), "Verification", on any deployment |
| L12 | Rider and driver addresses and fares are public and linkable across rides | Public chain | Fresh accounts per ride with smart accounts; not built |
| L13 | A tax collector that cannot receive the token (a contract that reverts, or an address a real BRL token blocklists) blocks the settlement of the rides that name it | The escrow pays in the same transaction, as it does the fee vault and the parties | Governance must name a plain account. Because the collector is fixed per ride, naming another one later does not free a ride already open: its only exit is a dispute resolved wholly to the rider. A pull payment (the collector withdraws) would remove the dependency |
| L14 | `FareGuide.nativeCoinCentavos` is a number governance types in, not a price feed | No oracle | It prices nothing on chain: a stale rate only makes the app's infrastructure-cost line wrong. An oracle if it ever gates anything |
| L15 | `setTax` accepts any rate under 100% | Same kind of power as the fee in `setToken` | Rides under way are unaffected and the rider never pays more than the fare; a high rate makes `open` revert or drivers stop quoting. A cap in the contract, if the community wants one |
| L16 | A block follows an address, not a person. A rider needs no credential, so a blocked rider can register another address and ride again | Riders are pseudonymous by design | For drivers the CNH credential carries the weight: the attester refuses the same licence on a new address, and revoking it acts at once. For riders, a policy on `open` that asks for a KYC credential |
| L17 | A blocked account keeps its votes, and can still `reveal` a vote it committed in a dispute | A majority that could block could otherwise silence the minority before a vote | By design. Its votes fall as its stake leaves, like anyone's |
| L18 | A block takes a vote and a timelock, so it is slow | C1: nobody acts alone | An attester revoking the credential it issued is immediate, and is what stops a driver in the meantime. It cannot touch a valid credential another attester issued: `attest` refuses to replace one |
| L19 | Nothing in the contracts stops a proposal from ending governance: `setParams` with a `stakeUnit` or a `votingPeriod` of zero or a quorum nobody reaches, a disabled policy on `propose`, or `setModules` naming a registry that does not answer, could never be voted back | The setters check nothing | `klippel-gov` refuses to build a proposal with a zero `stakeUnit`, a zero `votingPeriod` or a disabled `propose` policy, and only warns on the rest; its raw `propose call`, and any other tool, can still write all of them. Voters must read the calls. Bounds in `setParams` and `setPolicy` would close it |
| L20 | Whoever votes with `klippel-gov` believes the chain node: it serves both a proposal's calls and the hash they are checked against, so a node that lies consistently shows a voter other calls than the ones voted on | The tool has no light client | Use a node Instituto Klippel runs or trusts. `execute` is not exposed: the governor rechecks the calls itself |
| L21 | An attester re-issues a credential governance revoked, and a block does not stop a trusted attester from attesting | Attesters are trusted for what they sign | Governance removes the attester (`setAttester`), which voids everything it issued |
| L22 | A trusted attester is one key unless whoever holds it makes it more: the contracts cannot tell a multisig from a single account, and removing an attester voids every credential it issued | An attester is an address | An operating rule, which no code enforces: Instituto Klippel's attester should be a multisig with a threshold of at least 2, whose owners change without touching the list. `klippel-gov --safe` prepares its transactions. The local testnet lists a plain key. Governance should ask the same of any attester it adds |

## How it is tested

`test/Protocol.t.sol` deploys through the real `ProtocolDeployer` and covers each exit of the state machine,
each `Not…` guard, the dispute flow, governance changing a parameter, a block and what it leaves alone, and
map activation with and without quorum. [`gov/test.sh`](../../gov/test.sh) runs the governance tool against a
local chain: a licence approved and revoked, a block and a setting taken through real proposals. `test/ProofEncoding.t.sol` pins the encodings shared with the verifier. A change to a guard or to a
payout needs a test that fails without it.
