#!/usr/bin/env bash
# Deploys the protocol and mock BRL coins to the local testnet and seeds test accounts: a rider and a driver,
# three validators, and a second rider and driver for trying four phones at once.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
cd "$(dirname "$0")/../contracts"
cast chain-id --rpc-url "$RPC_URL" >/dev/null || { echo "No node at $RPC_URL. Run testnet/anvil.sh first." >&2; exit 1; }
forge script script/DeployLocal.s.sol --rpc-url "$RPC_URL" --broadcast --slow "$@"
forge script script/SeedSecondPair.s.sol --rpc-url "$RPC_URL" --broadcast --slow
