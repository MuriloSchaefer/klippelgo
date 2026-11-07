#!/usr/bin/env bash
# Deploys the protocol and mock BRL coins to the local testnet and seeds test accounts: a rider and a driver,
# three validators, and everything the eight phone accounts need to ride or drive (script/SeedPhones.s.sol): four
# for phones played by hand, four for the end-to-end tests.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
cd "$(dirname "$0")/../contracts"
cast chain-id --rpc-url "$RPC_URL" >/dev/null || { echo "No node at $RPC_URL. Run make testnet first." >&2; exit 1; }
RPC_URL="$RPC_URL" ../testnet/entrypoint.sh
forge script script/DeployLocal.s.sol --rpc-url "$RPC_URL" --broadcast --slow "$@"
# The seeded accounts' operations go to the EntryPoint in one transaction each, which needs room for its own
# checks on top of what the simulation measured.
forge script script/SeedPhones.s.sol --rpc-url "$RPC_URL" --broadcast --slow --gas-estimate-multiplier 200
