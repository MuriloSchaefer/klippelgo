#!/usr/bin/env bash
# Deploys the protocol and mock BRL coins to the local testnet and seeds test accounts: a rider and a driver,
# three validators, and everything the four phone accounts need to ride or drive (script/SeedPhones.s.sol).
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
cd "$(dirname "$0")/../contracts"
cast chain-id --rpc-url "$RPC_URL" >/dev/null || { echo "No node at $RPC_URL. Run testnet/anvil.sh first." >&2; exit 1; }
forge script script/DeployLocal.s.sol --rpc-url "$RPC_URL" --broadcast --slow "$@"
forge script script/SeedPhones.s.sol --rpc-url "$RPC_URL" --broadcast --slow
