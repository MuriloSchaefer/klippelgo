#!/usr/bin/env bash
# Puts ERC-4337's EntryPoint v0.9 at its canonical address on the local testnet, where it is on Polygon and every
# public chain. Bundlers simulate operations with contracts built for that address, so an EntryPoint anywhere
# else makes them refuse every signature.
#
# A real chain gets it from the deterministic deployer and the exact bytecode eth-infinitism released; this one is
# built here, so its bytes differ. So it is deployed at an ordinary address and its code copied to the canonical
# one with anvil_setCode, which only anvil allows. The EntryPoint creates accounts through a SenderCreator that
# answers only to the EntryPoint that made it: one made by the canonical address is copied over that too.
#
#   testnet/entrypoint.sh          (testnet/deploy.sh runs it first)
set -euo pipefail
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
MNEMONIC="${MNEMONIC:-test test test test test test test test test test test junk}"
CANONICAL=0x433709009B8330FDa32311DF1C2AFA402eD8D009
cd "$(dirname "$0")/../contracts"

[ "$(cast chain-id --rpc-url "$RPC_URL")" = 31337 ] || { echo "Not the local testnet. Refusing to touch it." >&2; exit 1; }
# The bundler signs with accounts #11 and #12 (testnet/bundler/run.sh). A chain started before anvil funded fifteen
# accounts has no coin on them.
for i in 11 12; do
  who=$(cast wallet address --private-key "$(cast wallet private-key --mnemonic "$MNEMONIC" --mnemonic-index "$i")")
  [ "$(cast balance --rpc-url "$RPC_URL" "$who")" != 0 ] || cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$who" 0x3635C9ADC5DEA00000 >/dev/null
done

if [ "$(cast code --rpc-url "$RPC_URL" "$CANONICAL")" != 0x ]; then
  echo "EntryPoint v0.9 already at $CANONICAL"
  exit 0
fi

# Account #13 deploys the stand-in, so that the deployer's nonce, and with it the protocol's addresses, stay put.
KEY=$(cast wallet private-key --mnemonic "$MNEMONIC" --mnemonic-index 13)
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$(cast wallet address --private-key "$KEY")" 0x56BC75E2D63100000 >/dev/null
forge build --quiet
deployed=$(forge create --rpc-url "$RPC_URL" --private-key "$KEY" --broadcast \
  lib/account-abstraction/contracts/core/EntryPoint.sol:EntryPoint --json | jq -r '.deployedTo')
creator=$(cast call --rpc-url "$RPC_URL" "$deployed" 'senderCreator()(address)')

# A SenderCreator whose EntryPoint is the canonical address: made by that address, which anvil lets this script use.
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$CANONICAL" 0x56BC75E2D63100000 >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_impersonateAccount "$CANONICAL" >/dev/null
creator_code=$(forge inspect lib/account-abstraction/contracts/core/SenderCreator.sol:SenderCreator bytecode)
made=$(cast send --rpc-url "$RPC_URL" --unlocked --from "$CANONICAL" --create "$creator_code" --json | jq -r '.contractAddress')
cast rpc --rpc-url "$RPC_URL" anvil_stopImpersonatingAccount "$CANONICAL" >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$CANONICAL" 0x0 >/dev/null

cast rpc --rpc-url "$RPC_URL" anvil_setCode "$CANONICAL" "$(cast code --rpc-url "$RPC_URL" "$deployed")" >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_setCode "$creator" "$(cast code --rpc-url "$RPC_URL" "$made")" >/dev/null
# And where the released EntryPoint keeps its own SenderCreator (made by it, at its nonce 1): bundlers simulate with
# that EntryPoint's exact bytes, which call it there.
released=$(cast compute-address "$CANONICAL" --nonce 1 | awk '{print $NF}')
cast rpc --rpc-url "$RPC_URL" anvil_setCode "$released" "$(cast code --rpc-url "$RPC_URL" "$made")" >/dev/null

for c in "$creator" "$released"; do
  [ "$(cast call --rpc-url "$RPC_URL" "$c" 'entryPoint()(address)')" = "$CANONICAL" ] || { echo "The SenderCreator at $c does not answer to $CANONICAL" >&2; exit 1; }
done
echo "EntryPoint v0.9 at $CANONICAL"
