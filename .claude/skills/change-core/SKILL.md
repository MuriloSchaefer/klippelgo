---
name: change-core
description: Use when adding or changing anything under core/ or mappack/ (the verifier, proofs, map packs, gossip node, sealing, UniFFI surface). Walks the change through the core's documented crate boundaries, patterns and invariants.
---

# Changing the Rust core

The docs are the source of truth. This skill only says which to read and in what order. If a doc and this
file disagree, the doc wins; if the code and a doc disagree, stop and tell the user which one is wrong.

## Before writing

1. Read `core/docs/overview.md`, section "Structure and patterns". The table "Where code goes" decides the
   crate. The most common mistake is putting I/O, floats or a clock in `verify`.
2. Read `core/docs/security.md`: the table of who is not trusted, invariants R1 to R12, and the known limits.
   Name the invariants your change touches before you write it.
3. For anything about map packs, routes, traces or receipts, read the matching section of
   `docs/maps-and-routing.md`. It holds the reasoning the code implements.
4. If the change affects message sizes, gossip behaviour, witness size or what a phone must hold in memory,
   read `core/docs/scalability.md`.
5. If an encoding shared with Solidity changes, read `contracts/docs/overview.md`, pattern 9.

## While writing

Follow "Changing the core" in `core/docs/overview.md` step by step. In particular: a changed encoding gets a
new version tag and new vectors in both `core/verify/tests/vectors.rs` and
`contracts/test/ProofEncoding.t.sol`; a changed gossip limit needs the router container rebuilt.

## Before finishing

1. `cd core && cargo fmt --all && cargo clippy --workspace --all-features --all-targets -- -D warnings`
2. `make test-core` (and `make test-mappack` if `mappack/` changed).
3. If the UniFFI surface changed, build the app and fix its callers: use the `change-app` skill.
4. Run the `sync-docs` skill for the core.
5. Run the `security-check` skill. It ends by handing the change to the `security-reviewer` agent.
