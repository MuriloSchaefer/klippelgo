#!/usr/bin/env bash
# Activates a map pack on the local testnet, the way it happens on a real network: the three seeded validators
# attest the pack's proof root, and Operações activates it through the team council, with klippel-gov as
# Instituto Klippel (account #1, the local council's only member). The contract refuses the activation unless
# three validators attested that root.
#
#   testnet/publish-pack.sh mappack/build/<region>/pack        # or any folder with a manifest.json
#
# On a real network each validator first rebuilds the proof index (`klippelgo-mappack root`) and attests the
# root it got. Here they attest the manifest's root unchecked.
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
MNEMONIC="${MNEMONIC:-test test test test test test test test test test test junk}"
PACK="${1:-}"
[ -n "$PACK" ] || { echo "Usage: $0 <pack dir>" >&2; exit 1; }
MANIFEST="$PACK/manifest.json"
[ -f "$MANIFEST" ] || { echo "No manifest.json in $PACK. Build the pack first: make mappack" >&2; exit 1; }
DEPLOYMENT="${DEPLOYMENT:-$(dirname "$0")/../deployments/local.json}"
[ -f "$DEPLOYMENT" ] || { echo "Deploy first: testnet/deploy.sh" >&2; exit 1; }

CHAIN_ID=$(cast chain-id --rpc-url "$RPC_URL") || { echo "No node at $RPC_URL. Run make testnet first." >&2; exit 1; }
[ "$CHAIN_ID" = 31337 ] || { echo "Chain $CHAIN_ID is not the local testnet. Refusing to touch it." >&2; exit 1; }
MAPS=$(jq -er '.MapRegistry' "$DEPLOYMENT") || { echo "$DEPLOYMENT has no MapRegistry. Deploy again: make testnet-reset, make testnet, make deploy" >&2; exit 1; }

# The pack id is the keccak-256 of the manifest's bytes.
PACK_ID=$(cast keccak "0x$(od -An -tx1 -v "$MANIFEST" | tr -d ' \n')")
ROOT=$(jq -er '.proofRoot' "$MANIFEST")
REGION=$(jq -er '.region' "$MANIFEST")
VERSION=$(jq -er '.version' "$MANIFEST")

call() { cast call --rpc-url "$RPC_URL" "$@"; }
send() { cast send --rpc-url "$RPC_URL" "$@" >/dev/null; }

if [ "$(call "$MAPS" 'proofRootOf(bytes32)(bytes32)' "$PACK_ID")" != 0x0000000000000000000000000000000000000000000000000000000000000000 ]; then
  echo "Pack $REGION $VERSION ($PACK_ID) is already active."
  exit 0
fi

KEYS=()
for i in 5 6 7; do KEYS+=("$(cast wallet private-key --mnemonic "$MNEMONIC" --mnemonic-index "$i")"); done

echo "Pack $REGION $VERSION"
echo "  id          $PACK_ID"
echo "  proof root  $ROOT"
for key in "${KEYS[@]}"; do
  validator=$(cast wallet address --private-key "$key")
  if [ "$(call "$MAPS" 'attestedRoot(bytes32,address)(bytes32)' "$PACK_ID" "$validator")" = 0x0000000000000000000000000000000000000000000000000000000000000000 ]; then
    send "$MAPS" 'attest(bytes32,bytes32)' "$PACK_ID" "$ROOT" --private-key "$key"
  fi
done
echo "  attested by 3 validators"

PACK_TUPLE="(\"$REGION\",$VERSION,$(jq -er '.osm.sequence' "$MANIFEST"),$(jq -er '.osm.sha256' "$MANIFEST"),$ROOT,\"${CID:-}\",0,0)"
KLIPPEL_GOV_RPC="$RPC_URL" KLIPPEL_GOV_DEPLOYMENT="$DEPLOYMENT" "$(dirname "$0")/../gov/klippel-gov" --yes \
  request call MapRegistry 'activate(bytes32,(string,uint32,uint64,bytes32,bytes32,string,uint64,uint64))' "$PACK_ID" "$PACK_TUPLE" >/dev/null
[ "$(call "$MAPS" 'proofRootOf(bytes32)(bytes32)' "$PACK_ID")" = "$ROOT" ] || { echo "Activation did not take effect." >&2; exit 1; }
echo "  activated by Operações, through the team council"
