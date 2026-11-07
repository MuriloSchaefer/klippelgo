#!/usr/bin/env bash
# Starts a block explorer for the local testnet (testnet/explorer/docker-compose.yml) in the background.
#
#   run.sh otterscan     http://localhost:5100, ready in seconds
#   run.sh blockscout    http://localhost:5200, then verifies the contracts in deployments/local.json
#   run.sh all           both
#   run.sh down          stops whichever is running. Blockscout keeps what it indexed for the next start
#   run.sh reset         stops it and forgets what it indexed: needed after `make testnet-reset`, as the old
#                        chain's blocks would stay in its database
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
export RPC_URL
API=http://127.0.0.1:5200/api
compose() { docker compose -f "$HERE/docker-compose.yml" "$@"; }

need_chain() {
  [ "$(cast chain-id --rpc-url "$RPC_URL" 2>/dev/null)" = 31337 ] \
    || { echo "No local testnet at $RPC_URL. Run 'make testnet' (and 'make deploy') first." >&2; exit 1; }
}

start_otterscan() {
  compose --profile otterscan up -d
  echo "Otterscan: http://localhost:5100"
}

# The Docker network of the chain `make testnet` runs (testnet/docker-compose.yml), if that is the one running.
docker_chain_network() {
  local id
  id="$(docker compose -f "$HERE/../docker-compose.yml" ps -q --status running anvil 2>/dev/null)"
  [ -n "$id" ] || return 1
  docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{$name}} {{end}}' "$id" | awk '{print $1}'
}

# Whether Blockscout has indexed every block up to one. Verifying earlier finds no contract at the address, as
# its creation is not indexed yet. Both checks: it indexes the newest block first and fills in older ones after,
# and reports the filling done while it has no block at all. Not the internal calls: some never index (here they
# stay at 88%), and verifying does not need them.
blockscout_caught_up() { # block
  local height filled
  height="$(curl -sf "$API/v2/blocks" | jq -r '.items[0].height // 0' 2>/dev/null)"
  filled="$(curl -sf "$API/v2/main-page/indexing-status" | jq -r '.finished_indexing_blocks // false' 2>/dev/null)"
  [ "${height:-0}" -ge "$1" ] && [ "$filled" = true ]
}

start_blockscout() {
  BLOCKSCOUT_SECRET_KEY_BASE="$(openssl rand -base64 48 | tr -d '\n')"
  export BLOCKSCOUT_SECRET_KEY_BASE
  # With the Docker chain, the backend joins its network and reads it at anvil:8545. Through chain-relay it
  # would need to reach a port on this computer, which Docker may not let containers do (Docker 29.9 here
  # drops it), and then Blockscout never sees a block.
  local net=""
  if net="$(docker_chain_network)" && [ -n "$net" ]; then
    export BLOCKSCOUT_CHAIN_URL=http://anvil:8545
    compose --profile blockscout up -d --no-start
    local backend
    backend="$(compose --profile blockscout ps -aq blockscout-backend)"
    local joined
    joined="$(docker inspect -f '{{json .NetworkSettings.Networks}}' "$backend")"
    [[ "$joined" == *"\"$net\""* ]] || docker network connect "$net" "$backend"
    echo "Blockscout reads the Docker chain on its network ($net)"
  fi
  compose --profile blockscout up -d
  echo "Waiting for Blockscout's backend to start"
  for _ in $(seq 120); do
    curl -sf "$API/v2/stats" >/dev/null && break
    sleep 2
  done
  curl -sf "$API/v2/stats" >/dev/null \
    || { echo "Blockscout did not come up. See: docker compose -f $HERE/docker-compose.yml logs blockscout-backend" >&2; exit 1; }
  local head
  head="$(cast block-number --rpc-url "$RPC_URL")"
  echo "Waiting for Blockscout to index the chain up to block $head"
  for _ in $(seq 120); do
    blockscout_caught_up "$head" && break
    sleep 5
  done
  blockscout_caught_up "$head" || {
    echo "Blockscout has not indexed up to block $head after 10 minutes. It is at http://localhost:5200; run 'make explorer-verify' once it has." >&2
    echo "If it shows no blocks at all, it cannot reach the chain. See: docker compose -f $HERE/docker-compose.yml logs blockscout-backend" >&2
    exit 1
  }
  if [ -f "$HERE/../../deployments/local.json" ]; then
    "$HERE/verify.sh"
  else
    echo "No deployments/local.json yet: after 'make deploy', run 'make explorer-verify'."
  fi
  echo "Blockscout: http://localhost:5200"
}

case "${1:-otterscan}" in
  otterscan)
    need_chain
    start_otterscan
    ;;
  blockscout)
    need_chain
    start_blockscout
    ;;
  all)
    need_chain
    start_otterscan
    start_blockscout
    ;;
  down)
    compose --profile otterscan --profile blockscout down
    ;;
  reset)
    compose --profile otterscan --profile blockscout down -v
    ;;
  *)
    echo "usage: run.sh otterscan|blockscout|all|down|reset" >&2
    exit 1
    ;;
esac
