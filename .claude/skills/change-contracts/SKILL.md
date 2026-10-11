---
name: change-contracts
description: Use when adding or changing anything under contracts/ (Solidity sources, deploy scripts, tests) or when a change elsewhere needs a new contract call, policy, parameter or on-chain hash. Walks the change through the contracts' documented structure, patterns and invariants.
---

# Changing the contracts

The docs are the source of truth. This skill only says which to read and in what order. If a doc and this
file disagree, the doc wins; if the code and a doc disagree, stop and tell the user which one is wrong.

## Before writing

1. Read `contracts/docs/overview.md`, section "Structure and patterns". It says where each kind of code goes
   and the nine patterns every contract follows.
2. Read `contracts/docs/security.md`: the access table, invariants C1 to C14, and the known limits. Name the
   invariants your change touches before you write it.
3. If the change adds state, a loop, or a transaction per ride, read `contracts/docs/scalability.md`,
   sections "Why every call is constant" and "What would not scale".
4. If the change alters a hash or struct that phones or the verifier also compute, read
   `core/docs/overview.md`, section "The hashes", and plan the change on both sides.

## While writing

Follow "Adding or changing a contract" in `contracts/docs/overview.md` step by step. In particular: policies
are set in `script/ProtocolDeployer.sol`, never in a constructor; a new address goes into the deployment file
and into `Deployment.kt`.

## Before finishing

1. `cd contracts && forge fmt && forge test` (or `make test-contracts`).
2. If a function's cost changed, rerun `forge test --gas-report` and update the table in
   `contracts/docs/scalability.md`.
3. Run the `sync-docs` skill for the contracts.
4. Run the `security-check` skill. It ends by handing the change to the `security-reviewer` agent.
