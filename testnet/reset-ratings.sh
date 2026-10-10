#!/usr/bin/env bash
# Clears the ratings one account received on the local testnet, so it counts as unrated again. Rides, balances,
# stake and credentials stay as they are. Use it when a test rating pushed the driver below the policy's minimum
# score and they can no longer accept rides.
#
#   testnet/reset-ratings.sh driver        # or rider, or any 0x address
#
# It writes the contract's storage directly, which only anvil allows.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
WHO="${1:-}"
[ -n "$WHO" ] || { echo "Usage: $0 driver|rider|0x<address>" >&2; exit 1; }
cd "$(dirname "$0")/../contracts"
DEPLOYMENT=../deployments/local.json
[ -f "$DEPLOYMENT" ] || { echo "Deploy first: testnet/deploy.sh" >&2; exit 1; }

CHAIN_ID=$(cast chain-id --rpc-url "$RPC_URL") || { echo "No node at $RPC_URL. Run testnet/anvil.sh first." >&2; exit 1; }
[ "$CHAIN_ID" = 31337 ] || { echo "Chain $CHAIN_ID is not the local testnet. Refusing to touch it." >&2; exit 1; }

case "$WHO" in
  0x*) ACCOUNT="$WHO" ;;
  *) ACCOUNT=$(jq -er --arg who "$WHO" '.[$who]' "$DEPLOYMENT") || { echo "No account named '$WHO' in $DEPLOYMENT" >&2; exit 1; } ;;
esac
REPUTATION=$(jq -er '.Reputation' "$DEPLOYMENT")

# Slots come from the compiler, so the script keeps working if Reputation's fields move.
LAYOUT=$(forge inspect Reputation storageLayout --json)
ZERO=0x0000000000000000000000000000000000000000000000000000000000000000
for field in ratingSum ratingCount; do
  slot=$(jq -er --arg f "$field" '.storage[] | select(.label == $f) | .slot' <<<"$LAYOUT")
  cast rpc --rpc-url "$RPC_URL" anvil_setStorageAt "$REPUTATION" "$(cast index address "$ACCOUNT" "$slot")" "$ZERO" >/dev/null
done

echo "Ratings of $ACCOUNT cleared: $(cast call --rpc-url "$RPC_URL" "$REPUTATION" 'ratingCount(address)(uint256)' "$ACCOUNT") ratings, score $(cast call --rpc-url "$RPC_URL" "$REPUTATION" 'scoreOf(address)(uint256)' "$ACCOUNT")."
