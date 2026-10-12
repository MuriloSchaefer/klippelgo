---
name: change-app
description: Use when adding or changing anything under apps/ (Android UI, AppViewModel, Matchmaker, KlippelGoClient, map handling, scripts, later iOS). Walks the change through the app's documented layers, patterns and invariants.
---

# Changing the app

The docs are the source of truth. This skill only says which to read and in what order. If a doc and this
file disagree, the doc wins; if the code and a doc disagree, stop and tell the user which one is wrong.

## Before writing

1. Read `apps/docs/overview.md`, section "Structure and patterns". The table "Where code goes" decides the
   file. If the code must give the same answer on iOS or for a juror, it belongs in the core: switch to the
   `change-core` skill.
2. Read `apps/docs/security.md`: the message table, invariants A1 to A14, and the known limits. Name the
   invariants your change touches before you write it. Anything that receives from gossip, a mirror or an
   intent is covered there.
3. If the change adds a chain read, a gossip message, a timer or stored data, read `apps/docs/scalability.md`,
   table "What limits growth today". Do not add to S1, S2 or S3.
4. If the change needs a new contract call, read `contracts/docs/security.md` for who may call it, and use the
   `change-contracts` skill if the contract itself must change.

## While writing

Follow "Changing the app" in `apps/docs/overview.md` step by step. In particular: validation of a gossip
message goes in `Matchmaker.receive…` and nowhere else; strings go in `values/`, `values-en/` and
`values-es/`; protocol values are read from the chain, not written as constants.

## Before finishing

1. `make android-test-chain` when `:chain` changed. It needs the local testnet (`make testnet`, `make deploy`).
2. `cd apps/android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug`
3. For a visible change, play it on emulators (`make android-two-phones` or `make android-four-phones`) and
   look at the screen. Build and usage notes are in `apps/android/README.md`.
4. Run the `sync-docs` skill for the app.
5. Run the `security-check` skill. It ends by handing the change to the `security-reviewer` agent.
