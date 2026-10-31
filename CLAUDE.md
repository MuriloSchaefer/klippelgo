# KlippelGo

A ride-hailing protocol with no central server. Start with [README.md](README.md) for the layout and how to
run things.

## The docs are the source of truth

Each part of the system documents its own architecture, security and scaling. Read the relevant ones before
changing code, and keep them true when the code changes.

| Area | Overview and patterns | Security | Scalability |
| --- | --- | --- | --- |
| `contracts/`, `gov/` | [overview](contracts/docs/overview.md) | [security](contracts/docs/security.md) | [scalability](contracts/docs/scalability.md) |
| `core/`, `mappack/` | [overview](core/docs/overview.md) | [security](core/docs/security.md) | [scalability](core/docs/scalability.md) |
| `apps/` | [overview](apps/docs/overview.md) | [security](apps/docs/security.md) | [scalability](apps/docs/scalability.md) |

How contracts go from this repository to a network, and what happens to old versions, is
[contracts/docs/deployment.md](contracts/docs/deployment.md).
How the scalability benchmark is built and runs is [core/docs/benchmark.md](core/docs/benchmark.md).
The design of maps, routes and trip proofs is [docs/maps-and-routing.md](docs/maps-and-routing.md).
The design of user accounts, recovery, gas and Pix top-ups is [docs/accounts-and-pix.md](docs/accounts-and-pix.md).
The design of team governance (members, roles, permissions, the guardian) is [docs/governance.md](docs/governance.md).
The end-to-end tests, their UI drivers and the CLI that plays a user are in [tests/README.md](tests/README.md).

## How to work here

Skills in `.claude/skills/` link those docs into procedures. They hold no rules of their own.

| When | Skill |
| --- | --- |
| Changing `contracts/` or `gov/` | `change-contracts` |
| Changing `core/` or `mappack/` | `change-core` |
| Changing `apps/` | `change-app` |
| After any code change, before saying it is done | `security-check` |
| When structure, a flow, a guarantee, a limit or a cost changed | `sync-docs` |

## Security review is not optional

After implementing anything under `apps/`, `contracts/`, `core/`, `gov/`, `mappack/` or `testnet/`, run the
`security-check` skill, which launches the `security-reviewer` agent. Do this without being asked, before
reporting the work as finished, and tell the user what it found. Fix blocking findings or explain to the user
why one stands; never drop one silently.

A Stop hook (`.claude/hooks/security-review.sh`) backs this up. It fingerprints the code by content and, when
that differs from the last review the reviewer recorded, stops the session from ending once and asks for the
review. Once a review has been recorded, committing does not clear it. Changes to Markdown alone need no
review, which includes the skills and the reviewer's own instructions.

`.claude/settings.json` is part of what the hook fingerprints, and is kept by hand: an allow rule is there
because someone staged or committed it. Claude Code also writes the approvals given during a session into that
file; the hook moves those to `.claude/settings.local.json`, which git ignores, so they stay one person's and
ask for no review. To allow a command for everyone, edit the file and `git add` it: the hook
cannot tell an approval from an edit, and moves any allow rule that is neither staged nor committed.
`.claude/hooks/test-security-review.sh` checks this.

It is a reminder, not a lock: it lets the second stop through, and nothing prevents someone from recording a
review that did not happen. Only the reviewer runs `security-review.sh mark`. After pulling other people's
changes it asks once for a review of what came in.

## Conventions

- Match the surrounding code: its naming, comment density and idiom. Comments say why, in plain sentences.
- User-facing text is Portuguese first, with English and Spanish alongside.
- Do not commit or push unless asked.
- A piece of UI a person can read or touch has a tag in `Tags.kt` and a function in its driver under
  `tests/harness/drivers`. A new screen, flow or gossip check comes with a test in `tests/integrity`,
  `tests/security` or `tests/performance` ([tests/README.md](tests/README.md), "Writing a test").
