# Contracts: scalability

The chain is the only resource every KlippelGo user shares, and the only one that costs money per use. The
design keeps it small: a ride touches the chain four times, each time for a constant amount of work, and
nothing else about the ride is ever written there.

Related: [overview.md](overview.md), [core scalability](../../core/docs/scalability.md),
[app scalability](../../apps/docs/scalability.md).

## What goes on chain and what does not

```mermaid
flowchart LR
    subgraph off [Off chain: grows with usage, costs nothing on chain]
        m[Matching: requests, quotes, re-quotes]
        r[Routing and route checks]
        p[Positions and GPS logs]
        s[Address search]
    end
    subgraph on [On chain: constant per ride]
        o[open] --> a[accept] --> st[start] --> c[complete]
    end
    m -- one chosen quote --> o
    r -- quoteHash --> o
    p -- receiptHash --> c
```

A rider may receive fifty quotes and reprice three times: the chain sees one `open`. A trip may log a
thousand GPS fixes: the chain sees one 32-byte hash.

## Cost of a ride

Gas measured by `forge test --gas-report` on the test suite, with the mock tokens and a tax set (the report's
maximum for `open` and `complete`, since most tests run with no tax). Real tokens cost what their
own `transferFrom` costs, so read these as the protocol's share.

| Step | Gas | Notes |
| --- | --- | --- |
| `open` | 390 000 | Writes the ride (the largest write: the tax, its collector, the court and the timeout are kept with it), counts what the escrow holds of the coin, pulls fare + bond and checks it all arrived |
| `accept` | 133 000 | Policy check reads registry and reputation; checks no other ride of the driver waits for its pickup and takes that place; pulls the bond and checks it arrived |
| `start` | 103 000 | Checks no other ride of the driver is under way, and moves the ride from the pickup place to that one |
| `complete` | 288 000 | Four transfers, the tax among them, each tried on its own, and the reputation record; frees the driver's place |
| **Ride, happy path** | **about 910 000** | Plus a token `approve` in the same operation as `open` and as `accept`: the app approves the exact amount each time |
| `cancel` | 80 000 | The higher figure for an accepted ride, which frees the driver's pickup place |
| `decline` | 63 000 | The driver turning down an Open ride: one refund |
| `submitReceipt` + `timeout` | 73 000 + 220 000 | When the rider does not confirm |
| `withdraw` | 80 000 | Only after a payment failed at settlement; anyone may send it on to the payee |
| Dispute: `raise`, `commit`, `reveal`, `settle` | 117 000, 85 000, 99 000, 176 000 | Per juror for commit and reveal |

### What the account adds

People call the escrow from their `KlippelGoAccount`, so each step above is one ERC-4337 operation: the
EntryPoint checks the phone's P-256 signature, `BrlPaymaster` prices the gas and takes it in reais, and the
account runs the call. Measured on the local testnet (Osaka rules, so the P-256 precompile), from the bundle
transactions of 345 operations, one operation per bundle:

| Step, as one operation | Transaction gas | Charged to the account | Notes |
| --- | --- | --- | --- |
| `approve` + `open` | 515 000 | 568 000 | |
| `approve` + `accept` | 265 000 | 307 000 | |
| `start` | 233 000 | 257 000 | About 130 000 over the escrow's own share: the EntryPoint, the signature check, the paymaster's price read and its two token transfers |
| `complete` | 330 000 | 372 000 | |
| **Ride, happy path** | **about 1 340 000** | **about 1 500 000** | |
| The first operation (creates the account, then registers) | 497 000 | 517 000 | Once per account |

What the account is charged is the EntryPoint's `actualGasUsed`: the transaction's share plus the
`preVerificationGas` the bundler asks for (the app adds 20%) and the EntryPoint's charge for unused gas. The
paymaster pays that to the bundler, which keeps the difference. In the account's own contracts, the signature
check (`DeviceKeyVerifier.verify`) is about 10 000 gas and `validateUserOp` about 19 000.

`forge test --gas-report` runs Cancun rules, with no P-256 precompile, so it shows the Solidity fallback
instead: about 250 000 for `verify` and 260 000 for `validateUserOp`. `forge test --evm-version osaka` gives the
figures the chain pays. The figures in this table come from the chain: read `UserOperationEvent` from the
EntryPoint, and each bundle's receipt.

Governance is paid by the team, not by riders and drivers:

| Step | Gas | Notes |
| --- | --- | --- |
| `TeamCouncil.request` | up to 248 000 | Stores the action and emits its calls; runs it too when it needs one approval and no delay |
| `TeamCouncil.approve` | 77 000, up to 217 000 | The higher figure when it is the approval that makes the action ready, and runs it |
| `TeamCouncil.execute` | up to 213 000 | Counts the approvers again, then runs the calls through the governor |
| `TeamCouncil.cancel` | 48 000 | |

Rerun the report after changing a contract: `cd contracts && forge test --gas-report`.

## Why every call is constant

- Rides, disputes, packs and identities are mappings keyed by id or address. No function iterates over them.
- A policy check reads a fixed number of slots: one policy, the block flag, one identity, at most two
  reputation values and one credential. The block flag is a mapping read by address, about 3 000 gas on every
  guarded call, however many accounts are blocked.
- The loops over caller-supplied data are `PBACGovernor.execute` and the council's `request` and `execute`,
  over the calls of one action, paid by the team member who sends it. The council also loops over an action's
  approvers, the roles, and a role's permissions: all of them grow only when the team grows, never with users.

So cost per ride does not grow with the number of rides, users or regions. State grows linearly: one `Ride`
(about twelve slots) per ride, kept for good, and one counter per coin (`held`).

## What limits growth

| Limit | Where it bites | Lever |
| --- | --- | --- |
| Chain throughput | Rides per second across all cities share the chain's block space. At about 1 340 000 gas a ride through people's accounts, block gas limit ÷ 1 340 000 is the ceiling per block. At 100 000 drivers and 1 000 000 riders the busiest hour needs about 18.6 M gas a second, 83% of Polygon PoS at 45 M gas per 2 s block ([core/bench](../../core/bench/README.md)). Bundling several operations in one transaction saves each one the transaction's base cost, not the EntryPoint's work | Deploy per region or on a rollup: `Domain` (chain id + escrow address) already scopes every hash, so several escrows can coexist |
| Gas price against a R$ 1,00 fee | If four operations cost a meaningful share of a fare, short rides stop making sense. The paymaster charges them in reais, so nobody needs the chain's coin, but it does not make them cheaper | A low-fee chain (the target is Polygon PoS); merging `accept` and `start` is possible but removes the driver's bond before pickup |
| Reading history | Phones find their rides by filtering `RideOpened` logs by indexed rider or driver. Public RPCs cap log ranges | See [app scalability](../../apps/docs/scalability.md): keep a cursor, or use an indexer as a cache that is never trusted |
| Disputes | Each needs humans to read evidence. Juror work grows with the dispute rate, not with rides | `klippelgo-juror` makes the check mechanical; the verifier is written to run in a zkVM so a proof can replace the vote for what is provable |
| Map packs | One activation and `rebuildQuorum` attestations per region per release | Constant per region. Rebuilding is off chain and takes minutes of validator compute |
| Governance | One transaction per approving member per action | Fine at team scale |

## Adding regions and tokens

A new city is a new map pack in `MapRegistry` and nothing else: the escrow does not know regions exist. A new
BRL token is one `setToken` action by Finanças. Neither requires redeploying or migrating.

## What would not scale, and is therefore not done

- Storing requests, quotes or driver presence on chain.
- Matching on chain.
- Any list a user can append to and a function then walks.
- Verifying routes on chain step by step. The chain holds one root per pack; checks happen on phones and
  jurors' machines against that root.

A change that adds any of these needs a very good reason and a line in this file.
