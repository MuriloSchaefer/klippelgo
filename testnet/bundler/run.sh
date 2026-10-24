#!/usr/bin/env bash
# Starts Alto against the local testnet once the chain answers and the EntryPoint v0.9 is at its canonical address
# (testnet/deploy.sh puts it there). It signs bundles with anvil's public accounts #11 (executor) and #12 (utility):
# local only, never anything with real money.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
ENTRYPOINT=0x433709009B8330FDa32311DF1C2AFA402eD8D009
EXECUTOR=0x701b615bbdfb9de65240bc28bd21bbc0d996645a3dd57e7b12bc2bdf6f192c82
UTILITY=0xa267530f49f8280200edf313ee7af6b827f2a8bce2897751d06a843f644967b1

rpc() { curl -s -X POST "$RPC_URL" -H 'content-type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$1\",\"params\":$2}"; }
until [ "$(rpc eth_chainId '[]' | jq -r '.result // empty')" = 0x7a69 ]; do echo "waiting for the local testnet at $RPC_URL"; sleep 2; done
until [ "$(rpc eth_getCode "[\"$ENTRYPOINT\",\"latest\"]" | jq -r '.result // "0x"')" != 0x ]; do echo "waiting for the EntryPoint (make deploy)"; sleep 3; done

# Bundles simulate everything first; the ERC-7562 rules a public mempool enforces are off, as on any private bundler.
exec alto \
  --entrypoints "$ENTRYPOINT" \
  --executor-private-keys "$EXECUTOR" \
  --utility-private-key "$UTILITY" \
  --rpc-url "$RPC_URL" \
  --port "${BUNDLER_PORT:-4337}" \
  --safe-mode false \
  --min-entity-stake 1 \
  --min-entity-unstake-delay 1
