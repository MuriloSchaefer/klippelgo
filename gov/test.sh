#!/usr/bin/env bash
# Checks klippel-gov against a local testnet (make testnet, make deploy): it approves and revokes a licence,
# and takes a block, an unblock and a setting through proposals, reading the chain after each. It uses an
# account of its own, so the seeded accounts and their rides are left as they were. Proposals move the chain's
# clock ahead, as testnet/pass-proposal.sh does.
set -euo pipefail
cd "$(dirname "$0")/.."
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
DEPLOYMENT="${DEPLOYMENT:-deployments/local.json}"
export RPC_URL DEPLOYMENT KLIPPEL_GOV_RPC="$RPC_URL" KLIPPEL_GOV_DEPLOYMENT="$DEPLOYMENT"
[ "$(cast chain-id --rpc-url "$RPC_URL")" = 31337 ] || { echo "Not the local testnet. Refusing to touch it." >&2; exit 1; }

gov() { gov/klippel-gov "$@"; }
call() { cast call --rpc-url "$RPC_URL" "$@"; }
# On a stream of its own, so a failure is seen even where a command's output is thrown away.
exec 4>&2
fail() { echo "FAILED: $*" >&4; exit 1; }
expect() { [ "$2" = "$3" ] || fail "$1: expected $3, got $2"; }
# refuses <words expected in the message> <command...>: the command must fail and say why.
refuses() {
  local want=$1 out
  shift
  if out=$("$@" 2>&1); then fail "should have been refused: $*"; fi
  grep -qi -- "$want" <<<"$out" || fail "refused for another reason than '$want': $out"
}
# Opens a proposal with klippel-gov and carries it through with the validators' votes.
pass() {
  local out id
  out=$(gov propose "$@")
  id=$(sed -n 's/^Proposal \([0-9]*\) is open.*/\1/p' <<<"$out")
  [ -n "$id" ] || fail "no proposal id in: $out"
  testnet/pass-proposal.sh "$id" >/dev/null
}

REGISTRY=$(jq -er '.IdentityRegistry' "$DEPLOYMENT")
GOVERNOR=$(jq -er '.PBACGovernor' "$DEPLOYMENT")
ESCROW=$(jq -er '.RideEscrow' "$DEPLOYMENT")
CNH=$(cast keccak "klippelgo.credential.CNH")
OPEN=$(cast sig 'open(address,address,uint256,bytes32)')

# Someone new: a key of their own, gas from the faucet, both roles.
KEY=0x$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
WHO=$(cast wallet address --private-key "$KEY")
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$WHO" 0xde0b6b3a7640000 >/dev/null
cast send --rpc-url "$RPC_URL" --private-key "$KEY" "$REGISTRY" 'register(uint8)' 3 >/dev/null

echo "status"
gov status >/dev/null

echo "a licence, approved and revoked at once"
DOCUMENT=$(mktemp)
echo "a stand-in for the scan of a licence" >"$DOCUMENT"
SALT=0x00000000000000000000000000000000000000000000000000000000000000aa
gov cnh approve "$WHO" "$DOCUMENT" --expires 2099-01-01 --salt "$SALT" >/dev/null
expect "licence after approval" "$(call "$REGISTRY" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" true
# The same document with the same salt gives the same hash, and not the bare hash of the document.
STORED=$(call "$REGISTRY" 'credentials(address,bytes32)(bytes32,address,uint64,bool)' "$WHO" "$CNH" | sed -n 1p)
BARE=$(cast keccak "0x$(od -An -tx1 -v "$DOCUMENT" | tr -d ' \n')")
expect "credential hash" "$STORED" "$(cast keccak "$(cast concat-hex "$SALT" "$BARE")")"
# Into a variable first: grep -q stops reading at the first match, which would fail the pipe.
SHOWN=$(gov account "$WHO")
grep -q 'CNH *valid' <<<"$SHOWN" || fail "account does not show the licence"
gov cnh revoke "$WHO" >/dev/null
expect "licence after revocation" "$(call "$REGISTRY" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" false
refuses "already revoked" gov cnh revoke "$WHO"
# A scan is larger than one command-line argument may be. It must still be the file that is hashed.
head -c 300000 /dev/urandom >"$DOCUMENT"
gov cnh approve "$WHO" "$DOCUMENT" --salt "$SALT" >/dev/null
STORED=$(call "$REGISTRY" 'credentials(address,bytes32)(bytes32,address,uint64,bool)' "$WHO" "$CNH" | sed -n 1p)
BARE=$({ printf 0x; od -An -tx1 -v "$DOCUMENT" | tr -d ' \n'; } | cast keccak)
expect "credential hash of a large file" "$STORED" "$(cast keccak "$(cast concat-hex "$SALT" "$BARE")")"
gov cnh revoke "$WHO" >/dev/null
: >"$DOCUMENT"
refuses "is empty" gov cnh approve "$WHO" "$DOCUMENT"
# The seeded driver's licence came from the mock attester: Instituto Klippel cannot take it over.
refuses "from another attester" gov cnh approve "$(jq -er '.driver' "$DEPLOYMENT")" "$SALT"
refuses "not a trusted attester" env KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov cnh approve "$WHO" "$DOCUMENT"
rm -f "$DOCUMENT"

echo "a block and an unblock, by proposal"
expect "may open before" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" true
pass block "$WHO"
expect "blocked" "$(call "$REGISTRY" 'blocked(address)(bool)' "$WHO")" true
expect "may open while blocked" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" false
refuses "already blocked" gov propose block "$WHO"
refuses "may not write proposals" env KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov propose unblock "$WHO"
pass unblock "$WHO"
expect "may open after" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" true

echo "a setting, by proposal, and back"
BEFORE=$(call "$ESCROW" 'completionTimeout()(uint64)' | cut -d' ' -f1)
pass completion-timeout 45m
expect "completion timeout" "$(call "$ESCROW" 'completionTimeout()(uint64)' | cut -d' ' -f1)" 2700
pass completion-timeout "$BEFORE"
SHOWN=$(gov proposals 3)
grep -q executed <<<"$SHOWN" || fail "the proposals are not listed as executed"

echo "a description cannot pass for the tool's own lines, and an unknown call is called one"
# A line break, the escape character, the one-byte-wide escape U+009B, a next-line U+0085 and a right-to-left
# override U+202E: each a way to redraw or reorder what a voter reads.
FORGED=$(printf 'Routine\n  calls\n      RideEscrow.setTax  200\n\033]0;\302\2333A\302\205\342\200\256')
OUT=$(gov propose call IdentityRegistry 'register(uint8)' 1 --description "$FORGED")
ID=$(sed -n 's/^Proposal \([0-9]*\) is open.*/\1/p' <<<"$OUT")
SHOWN=$(gov proposal "$ID")
expect "lines naming calls" "$(grep -c '^  calls$' <<<"$SHOWN")" 1
grep -q 'UNKNOWN CALL on IdentityRegistry' <<<"$SHOWN" || fail "an unknown call is not marked"
! LC_ALL=C grep -q $'[\033\302\342]' <<<"$SHOWN" || fail "a control character of the description reached the screen"
expect "lines for one proposal in the list" "$(gov proposals 1 | wc -l)" 1

echo "as a multisig: nothing is sent, and what is written out does the job once its owners execute it"
# A stand-in for the multisig: a contract account whose transactions anvil lets this script send, the way
# the owners' approvals would. What is checked is the transaction klippel-gov prepares, not a multisig.
MULTISIG=0x$(od -An -N20 -tx1 /dev/urandom | tr -d ' \n')
OUT_DIR=$(mktemp -d)
export KLIPPEL_GOV_OUT="$OUT_DIR"
cast rpc --rpc-url "$RPC_URL" anvil_setCode "$MULTISIG" 0x00 >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$MULTISIG" 0xde0b6b3a7640000 >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_impersonateAccount "$MULTISIG" >/dev/null
# Sends the one transaction klippel-gov wrote out, from the multisig, and forgets the file.
owners_execute() {
  local file
  file=$(ls "$OUT_DIR"/*.json)
  expect "files written" "$(wc -l <<<"$file")" 1
  expect "the file's chain" "$(jq -r '.chainId' "$file")" 31337
  cast send --rpc-url "$RPC_URL" --unlocked --from "$MULTISIG" "$(jq -r '.transactions[0].to' "$file")" "$(jq -r '.transactions[0].data' "$file")" >/dev/null
  rm -f "$file"
}
# as_multisig <command...>: runs klippel-gov as the multisig, and fails if any key this script could have
# signed with sent a transaction while it ran.
INSTITUTO=$(jq -er '.institutoKlippel' "$DEPLOYMENT")
as_multisig() {
  local before
  before=$(cast nonce --rpc-url "$RPC_URL" "$INSTITUTO")
  KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov --safe "$MULTISIG" "$@"
  expect "transactions sent by Instituto Klippel's key" "$(cast nonce --rpc-url "$RPC_URL" "$INSTITUTO")" "$before"
  expect "transactions sent by the wallet's key" "$(cast nonce --rpc-url "$RPC_URL" "$WHO")" "$NONCE"
}
NONCE=$(cast nonce --rpc-url "$RPC_URL" "$WHO")
refuses "not a multisig" gov --safe "$WHO" status
refuses "needs an address" gov/klippel-gov --safe "" register
refuses "goes before the command" gov register --safe "$MULTISIG"
refuses "goes before the command" gov propose tax 4 --safe "$MULTISIG"
refuses "goes before the command" gov register "--safe=$MULTISIG"
refuses "set but empty" env KLIPPEL_GOV_SAFE= gov/klippel-gov register
refuses "could not create" env KLIPPEL_GOV_OUT="$OUT_DIR/missing" gov/klippel-gov --safe "$MULTISIG" register
# Two prepared in the same second are two files, and each holds what was printed for it. A clock that stands
# still makes it the same second every time.
STILL=$(mktemp -d)
printf '#!/bin/sh\ncase "$*" in *%%s*) echo 1700000000 ;; *) echo 20260101-000000 ;; esac\n' >"$STILL/date"
chmod +x "$STILL/date"
PRINTED=$(PATH="$STILL:$PATH" as_multisig register 2>&1)
PATH="$STILL:$PATH" as_multisig register >/dev/null 2>&1
rm -rf "$STILL"
expect "files for two transactions" "$(ls "$OUT_DIR" | wc -l)" 2
expect "who may read a prepared file" "$(stat -c %a "$OUT_DIR"/* | sort -u)" 600
FIRST=$(sed -n 's/^  file   \([^ ]*\).*/\1/p' <<<"$PRINTED")
expect "data in the file" "$(jq -r '.transactions[0].data' "$FIRST")" "$(sed -n 's/^  data   //p' <<<"$PRINTED")"
expect "to in the file" "$(jq -r '.transactions[0].to' "$FIRST")" "$(sed -n 's/^  to     //p' <<<"$PRINTED")"
expect "value in the file" "$(jq -r '.transactions[0].value' "$FIRST")" 0
expect "transactions in the file" "$(jq -r '.transactions | length' "$FIRST")" 1
rm -f "$OUT_DIR"/*.json
as_multisig register >/dev/null 2>&1
expect "registered before the owners act" "$(call "$REGISTRY" 'registeredAt(address)(uint64)' "$MULTISIG" | cut -d' ' -f1)" 0
owners_execute
[ "$(call "$REGISTRY" 'registeredAt(address)(uint64)' "$MULTISIG" | cut -d' ' -f1)" != 0 ] || fail "the multisig is not registered"
DOCUMENT=$(mktemp)
echo "another stand-in for a licence" >"$DOCUMENT"
refuses "not a trusted attester" gov --safe "$MULTISIG" cnh approve "$WHO" "$DOCUMENT"
pass attester "$MULTISIG" on
as_multisig cnh approve "$WHO" "$DOCUMENT" >/dev/null 2>&1
expect "licence before the owners act" "$(call "$REGISTRY" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" false
owners_execute
expect "licence once they did" "$(call "$REGISTRY" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" true
expect "its issuer" "$(call "$REGISTRY" 'credentials(address,bytes32)(bytes32,address,uint64,bool)' "$WHO" "$CNH" | sed -n 2p | tr 'A-F' 'a-f')" "$MULTISIG"
# Only the multisig revokes what the multisig issued: Instituto Klippel's own key is refused.
refuses "not by" gov cnh revoke "$WHO"
as_multisig cnh revoke "$WHO" >/dev/null 2>&1
owners_execute
expect "licence after the multisig revoked it" "$(call "$REGISTRY" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" false
BEFORE_COUNT=$(call "$GOVERNOR" 'proposalCount()(uint256)')
as_multisig propose completion-timeout 40m >/dev/null 2>&1
expect "proposals before the owners act" "$(call "$GOVERNOR" 'proposalCount()(uint256)')" "$BEFORE_COUNT"
owners_execute
expect "proposals once they did" "$(call "$GOVERNOR" 'proposalCount()(uint256)')" "$((BEFORE_COUNT + 1))"
pass attester "$MULTISIG" off
cast rpc --rpc-url "$RPC_URL" anvil_stopImpersonatingAccount "$MULTISIG" >/dev/null
rm -rf "$OUT_DIR" "$DOCUMENT"
unset KLIPPEL_GOV_OUT

echo "what it refuses to propose"
refuses "ambiguous" gov propose native-coin-price 1.000
refuses "under 100" gov propose tax 100
refuses "end governance" gov propose policy PBACGovernor.propose enabled=false
refuses "ever change it back" gov propose voting stakeUnit=0
refuses "ever change it back" gov propose voting votingPeriod=0
refuses "must stay longer" gov propose voting votingPeriod=2d
refuses "must stay longer" gov propose unbonding-period 60s
refuses "not a time" gov propose completion-timeout 08x
refuses "not an address" gov propose block 0x123
refuses "is not one of" gov propose tariff perMile=2
ELSEWHERE=$(mktemp)
jq '.chainId = 137' "$DEPLOYMENT" >"$ELSEWHERE"
refuses "is for chain" gov/klippel-gov --deployment "$ELSEWHERE" status
rm -f "$ELSEWHERE"

echo "klippel-gov: all checks passed"
