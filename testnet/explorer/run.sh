#!/usr/bin/env bash
# Starts a block explorer for the local testnet (testnet/explorer/docker-compose.yml) in the background.
#
#   run.sh otterscan     http://localhost:5100, ready in seconds
#   run.sh blockscout    http://localhost:5200, then verifies the contracts in deployments/local.json
#   run.sh down          stops whichever is running. Blockscout keeps what it indexed for the next start
#   run.sh reset         stops it and forgets what it indexed: needed after `make testnet-reset`, as the old
#                        chain's blocks would stay in its database
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
export RPC_URL
compose() { docker compose -f "$HERE/docker-compose.yml" "$@"; }

need_chain() {
  [ "$(cast chain-id --rpc-url "$RPC_URL" 2>/dev/null)" = 31337 ] \
    || { echo "No local testnet at $RPC_URL. Run 'make testnet' (and 'make deploy') first." >&2; exit 1; }
}

case "${1:-otterscan}" in
  otterscan)
    need_chain
    compose --profile otterscan up -d
    echo "Otterscan: http://localhost:5100"
    ;;
  blockscout)
    need_chain
    BLOCKSCOUT_SECRET_KEY_BASE="$(openssl rand -base64 48 | tr -d '\n')" compose --profile blockscout up -d
    echo "Waiting for Blockscout's backend to index the chain"
    for _ in $(seq 120); do
      curl -sf http://127.0.0.1:5200/api/v2/stats >/dev/null && break
      sleep 2
    done
    curl -sf http://127.0.0.1:5200/api/v2/stats >/dev/null \
      || { echo "Blockscout did not come up. See: docker compose -f $HERE/docker-compose.yml logs blockscout-backend" >&2; exit 1; }
    if [ -f "$HERE/../../deployments/local.json" ]; then
      "$HERE/verify.sh"
    else
      echo "No deployments/local.json yet: after 'make deploy', run 'make explorer-verify'."
    fi
    echo "Blockscout: http://localhost:5200"
    ;;
  down)
    compose --profile otterscan --profile blockscout down
    ;;
  reset)
    compose --profile otterscan --profile blockscout down -v
    ;;
  *)
    echo "usage: run.sh otterscan|blockscout|down|reset" >&2
    exit 1
    ;;
esac
