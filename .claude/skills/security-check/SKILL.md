---
name: security-check
description: Use after implementing any change to code under apps/, contracts/, core/, mappack/ or testnet/, and whenever the user asks whether something is safe. Maps the change to the documented invariants and known limits, then hands it to the security-reviewer agent.
---

# Security check

The security rules of this project live in three documents. This skill routes a change to the right ones and
to the reviewer. It adds no rule of its own.

| Area changed | Document | Invariants | Known limits |
| --- | --- | --- | --- |
| `contracts/` | `contracts/docs/security.md` | C1 to C14 | L1 to L12 |
| `core/`, `mappack/` | `core/docs/security.md` | R1 to R12 | K1 to K10 |
| `apps/` | `apps/docs/security.md` | A1 to A14 | P1 to P15 |

A change that crosses areas (a new gossip message, a new hash, a new contract call from the app) is checked
against every area it touches. The flows that cross areas are drawn in `apps/docs/security.md` ("Matching,
with the checks in place") and `core/docs/security.md` ("Sealing a route for one driver", "Checking a trip").

## Steps

1. List the files changed: `git status --short` and `git diff HEAD --stat`.
2. For each area touched, open its security document and write down, for yourself, which invariants the
   change could affect and which known limits it touches.
3. Fix anything you can already see breaks an invariant. Do not leave it for the reviewer.
4. Launch the `security-reviewer` agent. Give it: what the change is for, the areas touched, and the
   invariants and limits you identified. Wait for its report.
5. For each finding: fix it, or tell the user plainly why it stands. A blocking finding is never dropped
   silently. After fixes, run the reviewer again: it re-reviews only what changed.
6. If the change closes a known limit, opens a new one or changes a guarantee, update the security document
   itself (use the `sync-docs` skill). The document must describe the code as it now is.
7. Report to the user: what was reviewed, what was found, what was fixed, what stands.

The reviewer records a completed review itself (`.claude/hooks/security-review.sh mark`). Do not run that
command on its behalf.
