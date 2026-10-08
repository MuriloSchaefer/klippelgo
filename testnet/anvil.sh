#!/usr/bin/env bash
# Starts the KlippelGo local testnet: a single anvil node with Osaka rules, which include the P-256 signature
# precompile at 0x100 (Polygon PoS has the same precompile via RIP-7212). Chain state persists in
# testnet/.state so a restart keeps your deployment; delete that folder (or run `make testnet-reset`) to start
# over.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .state
exec anvil \
  --chain-id "${CHAIN_ID:-31337}" \
  --hardfork osaka \
  --host "${ANVIL_HOST:-127.0.0.1}" \
  --port "${ANVIL_PORT:-8545}" \
  --block-time "${BLOCK_TIME:-2}" \
  --accounts 10 \
  --balance 10000 \
  --state .state/anvil.json \
  "$@"
