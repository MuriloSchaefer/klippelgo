#!/usr/bin/env bash
# Checks what security-review.sh does with the settings files, in a throwaway repository: nothing here touches
# the real one.
#
#   .claude/hooks/test-security-review.sh
set -euo pipefail
HOOK="$(cd "$(dirname "$0")" && pwd)/security-review.sh"
REPO="$(mktemp -d)"
trap 'rm -rf "$REPO"' EXIT
cd "$REPO"
git init -q .
git config user.email test@example.com
git config user.name test
mkdir -p .claude/hooks
cp "$HOOK" .claude/hooks/
S=.claude/settings.json
L=.claude/settings.local.json
jq -n '{permissions: {allow: ["Bash(shared)"], deny: ["Bash(never)"]}, hooks: {Stop: []}}' >"$S"
echo '.claude/settings.local.json' >.gitignore
echo '.claude/security-reviewed' >>.gitignore
git add -A && git commit -qm base

fail() { echo "FAILED: $*" >&2; exit 1; }
hook() { .claude/hooks/security-review.sh "$@" </dev/null 2>/dev/null; }
blocks() { [ -n "$(hook check)" ]; }
add() { local t; t=$(mktemp); jq --arg r "$1" '.permissions.allow += [$r]' "$S" >"$t" && cat "$t" >"$S"; rm -f "$t"; }
has() { jq -e --arg r "$2" '.permissions.allow // [] | index($r) != null' "$1" >/dev/null; }

hook mark
blocks && fail "a clean tree asks for a review"

echo "an approval left in the shared file moves to the local one, and asks for no review"
add "Bash(approved in a session)"
blocks && fail "a session approval asked for a review"
has "$S" "Bash(approved in a session)" && fail "the approval stayed in the shared file"
has "$L" "Bash(approved in a session)" || fail "the approval is not in the local file"
git diff --quiet -- "$S" || fail "the shared file is not as committed"

echo "a staged rule stays, and asks for a review"
add "Bash(for everyone)"
git add "$S"
blocks || fail "a staged allow rule asked for no review"
has "$S" "Bash(for everyone)" || fail "a staged rule was moved"
git commit -qm rule && hook mark

echo "a committed rule stays when the index holds an older version of the file"
git restore --staged --source=HEAD~1 -- "$S"
hook check >/dev/null || true
has "$S" "Bash(for everyone)" || fail "a committed rule was moved"
git add "$S"

echo "a change to the hooks or to a deny rule still asks for a review, with an approval beside it"
add "Bash(another approval)"
t=$(mktemp); jq '.permissions.deny = []' "$S" >"$t" && cat "$t" >"$S"; rm -f "$t"
blocks || fail "removing a deny rule asked for no review"
git checkout -q -- "$S"

echo "a local file that is not JSON is left alone, and the rule stays where it was"
echo 'not json' >"$L"
add "Bash(kept)"
hook check >/dev/null || true
has "$S" "Bash(kept)" || fail "a rule was removed though it could not be moved"
[ "$(cat "$L")" = 'not json' ] || fail "a broken local file was rewritten"
git checkout -q -- "$S"; rm -f "$L"

echo "a shared file in another format is left alone"
jq --indent 4 . "$S" >"$S.tmp" && mv "$S.tmp" "$S" && git add "$S" && git commit -qm format && hook mark
jq --indent 4 '.permissions.allow += ["Bash(kept too)"]' "$S" >"$S.tmp" && mv "$S.tmp" "$S"
hook check >/dev/null || true
has "$S" "Bash(kept too)" || fail "a file in another format was rewritten"

echo "security-review.sh: all checks passed"
