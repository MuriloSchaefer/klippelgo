#!/usr/bin/env bash
# Checks klippel-gov against a local testnet (make testnet, make deploy). There the team council has one
# member, Instituto Klippel (account #1), in every role, and every action needs one approval and no delay. The
# checks give it a colleague to show what two approvals and a suspension do. They use accounts of their own,
# so the seeded accounts and their rides are left as they were, and they put back every setting they change.
set -euo pipefail
cd "$(dirname "$0")/.."
RPC_URL="${RPC_URL:-http://127.0.0.1:8545}"
DEPLOYMENT="${DEPLOYMENT:-deployments/local.json}"
export RPC_URL DEPLOYMENT KLIPPEL_GOV_RPC="$RPC_URL" KLIPPEL_GOV_DEPLOYMENT="$DEPLOYMENT"
[ "$(cast chain-id --rpc-url "$RPC_URL")" = 31337 ] || { echo "Not the local testnet. Refusing to touch it." >&2; exit 1; }

gov() { gov/klippel-gov "$@"; }
call() { cast call --rpc-url "$RPC_URL" "$@" | cut -d' ' -f1; }
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
# A fresh account with gas, registered with the given roles (0 for none).
fresh() {
  local key who
  key=0x$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  who=$(cast wallet address --private-key "$key")
  cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$who" 0xde0b6b3a7640000 >/dev/null
  [ "$1" = 0 ] || cast send --rpc-url "$RPC_URL" --private-key "$key" "$REGISTRY" 'register(uint8)' "$1" >/dev/null
  echo "$key"
}
# The id of the action a request printed.
action_id() { sed -n 's/^\(Done: action\|Action\) \([0-9]*\).*/\2/p' <<<"$1"; }

REGISTRY=$(jq -er '.IdentityRegistry' "$DEPLOYMENT")
IDENTITIES=$(jq -er '.IdentityStore' "$DEPLOYMENT")
RELEASES=$(jq -er '.ReleaseRegistry' "$DEPLOYMENT")
GOVERNOR=$(jq -er '.PBACGovernor' "$DEPLOYMENT")
ESCROW=$(jq -er '.RideEscrow' "$DEPLOYMENT")
COUNCIL=$(jq -er '.TeamCouncil' "$DEPLOYMENT")
INSTITUTO=$(jq -er '.institutoKlippel' "$DEPLOYMENT")
CNH=$(cast keccak "klippelgo.credential.CNH")
OPEN=$(cast sig 'open(address,address,uint256,bytes32)')
SET_TAX=$(cast sig 'setTax(uint16,address)')
TAX_PERMISSION=""

KEY=$(fresh 3)
WHO=$(cast wallet address --private-key "$KEY")

echo "status: the council holds the seat"
SHOWN=$(gov status)
grep -q 'its guardian is the team council' <<<"$SHOWN" || fail "status does not show the council as guardian"
expect "guardian" "$(call "$GOVERNOR" 'guardian()(address)')" "$COUNCIL"

echo "a licence, issued and revoked by the council"
DOCUMENT=$(mktemp)
echo "a stand-in for the scan of a licence" >"$DOCUMENT"
SALT=0x00000000000000000000000000000000000000000000000000000000000000aa
gov cnh approve "$WHO" "$DOCUMENT" --expires 2099-01-01 --salt "$SALT" >/dev/null
expect "licence after approval" "$(call "$IDENTITIES" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" true
# The same document with the same salt gives the same hash, and not the bare hash of the document.
STORED=$(cast call --rpc-url "$RPC_URL" "$IDENTITIES" 'credentials(address,bytes32)(bytes32,address,uint64,bool)' "$WHO" "$CNH")
BARE=$(cast keccak "0x$(od -An -tx1 -v "$DOCUMENT" | tr -d ' \n')")
expect "credential hash" "$(sed -n 1p <<<"$STORED")" "$(cast keccak "$(cast concat-hex "$SALT" "$BARE")")"
expect "its issuer" "$(sed -n 2p <<<"$STORED")" "$COUNCIL"
SHOWN=$(gov account "$WHO")
grep -q 'CNH *valid' <<<"$SHOWN" || fail "account does not show the licence"
gov cnh revoke "$WHO" >/dev/null
expect "licence after revocation" "$(call "$IDENTITIES" 'hasCredential(address,bytes32)(bool)' "$WHO" "$CNH")" false
refuses "already revoked" gov cnh revoke "$WHO"
# A scan is larger than one command-line argument may be. It must still be the file that is hashed.
head -c 300000 /dev/urandom >"$DOCUMENT"
gov cnh approve "$WHO" "$DOCUMENT" --salt "$SALT" >/dev/null
STORED=$(cast call --rpc-url "$RPC_URL" "$IDENTITIES" 'credentials(address,bytes32)(bytes32,address,uint64,bool)' "$WHO" "$CNH" | sed -n 1p)
BARE=$({ printf 0x; od -An -tx1 -v "$DOCUMENT" | tr -d ' \n'; } | cast keccak)
expect "credential hash of a large file" "$STORED" "$(cast keccak "$(cast concat-hex "$SALT" "$BARE")")"
gov cnh revoke "$WHO" >/dev/null
: >"$DOCUMENT"
refuses "is empty" gov cnh approve "$WHO" "$DOCUMENT"
# The seeded driver's licence came from the mock attester: the council cannot take it over.
refuses "from another attester" gov cnh approve "$(jq -er '.driver' "$DEPLOYMENT")" "$SALT"
refuses "not a member" env KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov cnh approve "$WHO" "$SALT"
rm -f "$DOCUMENT"

echo "a block and an unblock"
expect "may open before" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" true
gov block "$WHO" >/dev/null
expect "blocked" "$(call "$IDENTITIES" 'blocked(address)(bool)' "$WHO")" true
expect "may open while blocked" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" false
refuses "already blocked" gov block "$WHO"
refuses "not a member" env KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov unblock "$WHO"
gov unblock "$WHO" >/dev/null
expect "may open after" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$WHO" "$ESCROW" "$OPEN")" true

echo "a setting, and back"
BEFORE=$(call "$ESCROW" 'completionTimeout()(uint64)')
gov request completion-timeout 45m >/dev/null
expect "completion timeout" "$(call "$ESCROW" 'completionTimeout()(uint64)')" 2700
gov request completion-timeout "$BEFORE" >/dev/null
SHOWN=$(gov actions 2)
expect "actions listed" "$(grep -c executed <<<"$SHOWN")" 2

echo "the playbooks: new rides stop at once, and resume"
RIDER=$(jq -er '.rider' "$DEPLOYMENT")
gov stop-rides >/dev/null
expect "a rider may open while stopped" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$RIDER" "$ESCROW" "$OPEN")" false
gov resume-rides >/dev/null
expect "a rider may open once resumed" "$(call "$GOVERNOR" 'hasPermission(address,address,bytes4)(bool)' "$RIDER" "$ESCROW" "$OPEN")" true

echo "a colleague: two approvals, and a suspension"
MATE_KEY=$(fresh 0)
MATE=$(cast wallet address --private-key "$MATE_KEY")
mate() { KLIPPEL_GOV_WALLET="--private-key $MATE_KEY" gov/klippel-gov "$@"; }
gov team add "$MATE" finance >/dev/null
expect "the colleague is in Finanças" "$(call "$COUNCIL" 'hasRole(address,uint8)(bool)' "$MATE" 1)" true
# The permission over the tax, which from here on needs both of them.
COUNT=$(call "$COUNCIL" 'permissionCount()(uint256)')
for ((i = 0; i < COUNT; i++)); do
  P=$(cast call --json --rpc-url "$RPC_URL" "$COUNCIL" 'permission(uint32)((uint8,uint8,bool,bool,uint8,uint32,address,bytes4,uint16,bytes32))' "$i")
  [ "$(jq -r 'if type == "object" then .data else . end | .[0][7]' <<<"$P")" != "$SET_TAX" ] || TAX_PERMISSION=$i
done
[ -n "$TAX_PERMISSION" ] || fail "no permission over setTax"
gov team set "$TAX_PERMISSION" approvals=2 >/dev/null
TAX_BEFORE=$(call "$ESCROW" 'taxBps()(uint16)')
OUT=$(gov request tax 4)
ID=$(action_id "$OUT")
grep -q "is waiting" <<<"$OUT" || fail "a change that needs two approvals ran with one: $OUT"
expect "tax while one approved" "$(call "$ESCROW" 'taxBps()(uint16)')" "$TAX_BEFORE"
grep -q 'waiting for approvals: 1 of 2' <<<"$(gov action "$ID")" || fail "the action does not say it waits for a second approval"
refuses "already approved" gov approve "$ID"
mate approve "$ID" >/dev/null
expect "tax once both approved" "$(call "$ESCROW" 'taxBps()(uint16)')" 400

# The colleague asks for another change, and is suspended before Instituto Klippel approves it.
OUT=$(mate request tax 6)
ID=$(action_id "$OUT")
gov team suspend "$MATE" >/dev/null
SHOWN=$(gov action "$ID")
grep -q 'suspended: does not count' <<<"$SHOWN" || fail "a suspended member's approval is not marked as not counting"
gov approve "$ID" >/dev/null
expect "tax after an approval of a suspended member" "$(call "$ESCROW" 'taxBps()(uint16)')" 400
refuses "not a member\|NotMember" mate request tax 7
gov cancel "$ID" >/dev/null
grep -q 'cancelled' <<<"$(gov action "$ID")" || fail "the action is not cancelled"
gov team unsuspend "$MATE" >/dev/null

# Taking the colleague out would leave Finanças unable to reach two approvals: the council refuses it.
refuses "WouldLockOut" gov team remove "$MATE" finance
OUT=$(gov request tax "$(cast format-units "$TAX_BEFORE" 2)")
mate approve "$(action_id "$OUT")" >/dev/null
expect "tax back" "$(call "$ESCROW" 'taxBps()(uint16)')" "$TAX_BEFORE"
gov team set "$TAX_PERMISSION" approvals=1 >/dev/null
gov team remove "$MATE" finance >/dev/null
expect "the colleague has left" "$(call "$COUNCIL" 'hasRole(address,uint8)(bool)' "$MATE" 1)" false

echo "a release, published with its APKs, deprecated, revoked and put back"
APK=$(mktemp)
echo "a stand-in for an APK" >"$APK"
NEXT=$(( $(call "$RELEASES" 'latest()(uint48)') + 1 ))
NEXT_TEXT="$((NEXT >> 32)).$(((NEXT >> 16) & 65535)).$((NEXT & 65535))"
refuses "published already" gov request release publish "$(jq -r .version "$DEPLOYMENT")"
refuses "not a version" gov request release publish 1.2
OUT=$(gov request release publish "$NEXT_TEXT" "$APK")
grep -q "APK sha-256 *0x$(sha256sum "$APK" | cut -d' ' -f1)" <<<"$OUT" || fail "the request does not show the APK's hash"
# What a director reads before approving: the version as text, the contracts hash against the deployment file.
SHOWN=$(gov action "$(action_id "$OUT")")
grep -q "release $NEXT_TEXT" <<<"$SHOWN" || fail "the action does not show the release's version"
grep -q "contracts hash matches" <<<"$SHOWN" || fail "the action does not compare the contracts hash with the deployment file"
expect "the APK is the release's" "$(call "$RELEASES" 'isApk(uint48,bytes32)(bool)' "$NEXT" "0x$(sha256sum "$APK" | cut -d' ' -f1)")" true
expect "the latest release" "$(call "$RELEASES" 'latest()(uint48)')" "$NEXT"
grep -q "are this release" <<<"$(gov release "$NEXT_TEXT")" || fail "the release's code contracts are not the deployment file's"
gov request release deprecate "$NEXT_TEXT" >/dev/null
expect "deprecated" "$(call "$RELEASES" 'statusOf(uint48)(uint8)' "$NEXT")" 2
refuses "not active" gov request release deprecate "$NEXT_TEXT"
gov request release revoke "$NEXT_TEXT" >/dev/null
expect "revoked" "$(call "$RELEASES" 'statusOf(uint48)(uint8)' "$NEXT")" 3
gov request release reinstate "$NEXT_TEXT" >/dev/null
expect "back" "$(call "$RELEASES" 'statusOf(uint48)(uint8)' "$NEXT")" 1
# A release cannot be unpublished. Revoked, this stand-in is never offered to the phones on this chain as an update.
gov request release revoke "$NEXT_TEXT" >/dev/null
# The release this deployment file is stays what the app on it reads.
expect "the deployment's release" "$(call "$RELEASES" 'statusOf(uint48)(uint8)' "$(( $(jq -r .version "$DEPLOYMENT" | awk -F. '{print ($1 * 4294967296) + ($2 * 65536) + $3}') ))")" 1
rm -f "$APK"

echo "a call no permission covers is refused before anything is sent"
refuses "no permission" gov request call IdentityRegistry 'register(uint8)' 1

echo "as a multisig: nothing is sent, and what is written out does the job once its owners execute it"
# A stand-in for a multisig member of Segurança: a contract account whose transactions anvil lets this
# script send, the way the owners' approvals would. What is checked is the transaction klippel-gov prepares.
MULTISIG=0x$(od -An -N20 -tx1 /dev/urandom | tr -d ' \n')
MULTISIG=$(cast to-check-sum-address "$MULTISIG")
OUT_DIR=$(mktemp -d)
export KLIPPEL_GOV_OUT="$OUT_DIR"
cast rpc --rpc-url "$RPC_URL" anvil_setCode "$MULTISIG" 0x00 >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_setBalance "$MULTISIG" 0xde0b6b3a7640000 >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_impersonateAccount "$MULTISIG" >/dev/null
gov team add "$MULTISIG" security >/dev/null
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
NONCE=$(cast nonce --rpc-url "$RPC_URL" "$WHO")
as_multisig() {
  local before
  before=$(cast nonce --rpc-url "$RPC_URL" "$INSTITUTO")
  KLIPPEL_GOV_WALLET="--private-key $KEY" gov/klippel-gov --safe "$MULTISIG" "$@"
  expect "transactions sent by Instituto Klippel's key" "$(cast nonce --rpc-url "$RPC_URL" "$INSTITUTO")" "$before"
  expect "transactions sent by the wallet's key" "$(cast nonce --rpc-url "$RPC_URL" "$WHO")" "$NONCE"
}
refuses "not a multisig" gov --safe "$WHO" status
refuses "needs an address" gov/klippel-gov --safe "" status
refuses "goes before the command" gov block "$WHO" --safe "$MULTISIG"
refuses "goes before the command" gov block "--safe=$MULTISIG"
refuses "set but empty" env KLIPPEL_GOV_SAFE= gov/klippel-gov status
refuses "could not create" env KLIPPEL_GOV_OUT="$OUT_DIR/missing" gov/klippel-gov --safe "$MULTISIG" block "$WHO"
# Two prepared in the same second are two files, and each holds what was printed for it. A clock that stands
# still makes it the same second every time.
STILL=$(mktemp -d)
printf '#!/bin/sh\ncase "$*" in *%%s*) echo 1700000000 ;; *) echo 20260101-000000 ;; esac\n' >"$STILL/date"
chmod +x "$STILL/date"
PRINTED=$(PATH="$STILL:$PATH" as_multisig block "$WHO" 2>&1)
PATH="$STILL:$PATH" as_multisig block "$WHO" >/dev/null 2>&1
rm -rf "$STILL"
expect "files for two transactions" "$(ls "$OUT_DIR" | wc -l)" 2
expect "who may read a prepared file" "$(stat -c %a "$OUT_DIR"/* | sort -u)" 600
FIRST=$(sed -n 's/^  file   \([^ ]*\).*/\1/p' <<<"$PRINTED")
expect "data in the file" "$(jq -r '.transactions[0].data' "$FIRST")" "$(sed -n 's/^  data   //p' <<<"$PRINTED")"
expect "to in the file" "$(jq -r '.transactions[0].to' "$FIRST")" "$COUNCIL"
expect "value in the file" "$(jq -r '.transactions[0].value' "$FIRST")" 0
rm -f "$OUT_DIR"/*.json
as_multisig block "$WHO" >/dev/null 2>&1
expect "blocked before the owners act" "$(call "$IDENTITIES" 'blocked(address)(bool)' "$WHO")" false
owners_execute
expect "blocked once they did" "$(call "$IDENTITIES" 'blocked(address)(bool)' "$WHO")" true
gov unblock "$WHO" >/dev/null
gov team remove "$MULTISIG" security >/dev/null
cast rpc --rpc-url "$RPC_URL" anvil_stopImpersonatingAccount "$MULTISIG" >/dev/null
rm -rf "$OUT_DIR"
unset KLIPPEL_GOV_OUT

echo "what it refuses to request"
refuses "ambiguous" gov request native-coin-price 1.000
refuses "under 100" gov request tax 100
refuses "not a time" gov request completion-timeout 08x
refuses "not an address" gov block 0x123
refuses "is not one of" gov request tariff perMile=2
refuses "own guardian" gov request guardian "$GOVERNOR"
refuses "is gone" gov propose tax 4
refuses "not a role" gov team add "$WHO" treasurers
ELSEWHERE=$(mktemp)
jq '.chainId = 137' "$DEPLOYMENT" >"$ELSEWHERE"
refuses "is for chain" gov/klippel-gov --deployment "$ELSEWHERE" status
rm -f "$ELSEWHERE"

echo "klippel-gov: all checks passed"
