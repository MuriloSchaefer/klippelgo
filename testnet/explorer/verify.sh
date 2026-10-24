#!/usr/bin/env bash
# Verifies every contract in deployments/local.json on the local Blockscout (`make explorer TOOL=blockscout`), so
# its pages show the source, decode calls and events by name, and offer read and write tabs. Run it again after
# each `make deploy`; a contract already verified is skipped.
#
# Which contract is at an address comes from the deploy's broadcast, where forge recorded what it created.
#
# The EntryPoint stays unverified. testnet/entrypoint.sh copies its code to the canonical address with
# anvil_setCode, and sends a transaction from that address, so Blockscout sees an account with a nonce and no
# creation, never fetches its code and has nothing to verify against. Its calls show undecoded; the calls inside
# them, to the contracts here, are decoded.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
API="${BLOCKSCOUT_API:-http://127.0.0.1:5200/api/}"
DEPLOYMENT="$ROOT/deployments/local.json"
BROADCAST="$ROOT/contracts/broadcast/DeployLocal.s.sol/31337/run-latest.json"

[ "$(cast chain-id --rpc-url "$RPC_URL" 2>/dev/null)" = 31337 ] \
  || { echo "No local testnet at $RPC_URL. This only verifies on the local chain's Blockscout." >&2; exit 1; }
[ -f "$DEPLOYMENT" ] ||{ echo "No $DEPLOYMENT. Run 'make deploy' first." >&2; exit 1; }
[ -f "$BROADCAST" ] || { echo "No $BROADCAST: was this chain deployed from here with 'make deploy'?" >&2; exit 1; }
cd "$ROOT/contracts"
forge build --quiet

# Name of the contract forge created at an address, from the broadcast's transactions and the contracts they made.
contract_at() {
  jq -r --arg a "$(echo "$1" | tr 'A-F' 'a-f')" '
    [.transactions[] | select(.transactionType == "CREATE") | {name: .contractName, addr: .contractAddress}]
    + [.transactions[] | .additionalContracts[]? | {name: (.contractName // empty), addr: .address}]
    | map(select((.addr | ascii_downcase) == $a)) | .[0].name // empty' "$BROADCAST"
}

verified() { # address
  curl -sf "${API}v2/smart-contracts/$1" | jq -e '.is_verified == true' >/dev/null 2>&1
}

ok=0 failed=()
for key in $(jq -r 'to_entries[] | select(.value | type == "string" and test("^0x[0-9a-fA-F]{40}$")) | .key' "$DEPLOYMENT"); do
  addr=$(jq -r --arg k "$key" '.[$k]' "$DEPLOYMENT")
  # Accounts such as the rider or the attester hold no code.
  [ "$(cast code --rpc-url "$RPC_URL" "$addr")" != 0x ] || continue
  if verified "$addr"; then
    echo "$key ($addr): already verified"
    ok=$((ok + 1))
    continue
  fi
  if [ "$key" = EntryPoint ]; then
    echo "$key ($addr): code copied in by testnet/entrypoint.sh, which Blockscout cannot verify, skipped"
    continue
  fi
  name=$(contract_at "$addr")
  [ -n "$name" ] || { echo "$key ($addr): not created by the latest deploy, skipped" >&2; failed+=("$key"); continue; }
  if forge verify-contract "$addr" "$name" --guess-constructor-args --rpc-url "$RPC_URL" \
       --verifier blockscout --verifier-url "$API" --watch >/dev/null 2>&1; then
    echo "$key ($addr): verified as $name"
    ok=$((ok + 1))
  else
    echo "$key ($addr): could not verify $name" >&2
    failed+=("$key")
  fi
done

echo "$ok contracts verified on Blockscout."
[ ${#failed[@]} = 0 ] || { echo "Not verified: ${failed[*]}" >&2; exit 1; }
