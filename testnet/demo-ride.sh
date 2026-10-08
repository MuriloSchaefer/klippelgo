#!/usr/bin/env bash
# Runs one R$ 23,40 ride end to end on the local testnet and prints who got paid.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
cd "$(dirname "$0")/../contracts"
[ -f ../deployments/local.json ] || { echo "Deploy first: testnet/deploy.sh" >&2; exit 1; }
forge script script/DemoRide.s.sol --rpc-url "$RPC_URL" --broadcast --slow "$@"
