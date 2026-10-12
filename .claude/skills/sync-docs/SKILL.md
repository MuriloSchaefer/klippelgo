---
name: sync-docs
description: Use after any change that alters structure, a flow, a guarantee, a limit, a parameter or a cost in apps/, contracts/ or core/, and whenever the user asks to update or check the documentation. Keeps the three docs folders true to the code.
---

# Keeping the docs true

The docs are the source of truth for structure, patterns, security and scale. That only works while they
describe the code as it is. The skills and the security reviewer read them and nothing else.

## The documents

| Folder | `overview.md` | `security.md` | `scalability.md` |
| --- | --- | --- | --- |
| `contracts/docs/` | Contracts, state machine, governance, patterns | Access table, flows, C1–C14, L1–L12 | Gas per step, growth limits |
| `core/docs/` | Crates, modules, hashes, patterns | Trust table, flows, R1–R12, K1–K10 | Pack and witness sizes, gossip load |
| `apps/docs/` | Layers, state loop, ride flow, storage, patterns | Message checks, flows, A1–A14, P1–P15 | Dependencies, S1–S8 |

`docs/maps-and-routing.md` is the design of maps and proofs and its section "What is built". The root
`README.md` and `apps/android/README.md` say how to run things. Do not repeat their content in the three
folders: link to it.

## What to update for which change

| The change… | Update |
| --- | --- |
| Adds, removes or moves a contract, crate, module, layer or stored file | The table and the diagram in that area's `overview.md` |
| Changes who may call something, or a check on a message, file or input | The table in that area's `security.md`, and the sequence diagram that shows the flow |
| Adds or removes a guarantee | The numbered invariants. Keep existing numbers stable; append new ones |
| Closes a known limit | Remove its row and say so in your report. Do not renumber the others |
| Opens a new known limit, or you find one | Append a row with the next number |
| Changes a cost, a size, a cap, an interval | That area's `scalability.md`. Measured numbers must be measured again, not edited by hand |
| Changes a pattern or a rule of where code goes | "Structure and patterns" in `overview.md`, and check the matching `change-…` skill still points at the right section |
| Changes how to build or run | The READMEs, not these folders |

## Rules

1. Write what the code does now. No history, no plans beyond the "what closes it" column.
2. Every claim must be checkable in the code. If you have not read the code that proves a sentence, read it
   or do not write the sentence.
3. Diagrams are Mermaid, in the file. After editing one, read it once more for syntax: unbalanced brackets and
   unquoted special characters are the usual mistakes.
4. The identifiers (C…, L…, R…, K…, A…, P…, S…) are referenced by the skills and the reviewer. Changing a
   number breaks those references.
5. If the code contradicts a doc and you did not cause it, tell the user instead of quietly picking one.
