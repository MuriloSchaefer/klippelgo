#!/usr/bin/env bash
# Reminds a session to have the security-reviewer agent look at the code as it stands before it ends.
#
#   security-review.sh check    Stop hook: blocks once when the code differs from the last recorded review
#   security-review.sh mark     records that the code as it stands was reviewed (the reviewer runs this)
#   security-review.sh state    prints the fingerprint of the code
#
# "Code" is every file git knows or would add under the paths below, by content, so committing a change does
# not make it count as reviewed. Markdown is left out: writing docs asks for no review.
#
# This is a reminder, not a lock: anyone who can run `mark` or edit the record can silence it. It blocks a
# stop once and lets the next one through, so a session can always end.
set -uo pipefail

MARK=.claude/security-reviewed
PATHS=(apps contracts core mappack testnet .claude/hooks .claude/settings.json ':(exclude)*.md')

block() {
  printf '{"decision":"block","reason":"%s"}\n' "$1"
  exit 0
}

# Prints the fingerprint, or fails. Every step is checked by hand: `set -e` does nothing inside a function
# called on the left of `||`.
state() {
  local list sums modules
  list="$(mktemp)" || return 1
  if ! git ls-files -z --cached --others --exclude-standard -- "${PATHS[@]}" | sort -z >"$list"; then
    rm -f "$list"
    return 1
  fi
  sums="$(
    regular=()
    while IFS= read -r -d '' f; do
      if [ -f "$f" ] && [ ! -L "$f" ]; then regular+=("$f"); else printf 'other %s %s\n' "$f" "$(readlink "$f" 2>/dev/null || true)"; fi
    done <"$list"
    [ "${#regular[@]}" -eq 0 ] || printf '%s\0' "${regular[@]}" | xargs -0 sha256sum
  )" || { rm -f "$list"; return 1; }
  rm -f "$list"
  modules="$(git submodule status)" || return 1
  printf '%s\n%s\n' "$sums" "$modules" | sha256sum | cut -d' ' -f1
}

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || {
  [ "${1:-check}" = check ] && block "The security review hook could not find the repository. Run the security-check skill and tell the user the hook is broken."
  exit 1
}

case "${1:-check}" in
  state) state ;;
  mark)
    now="$(state)" && [ -n "$now" ] || { echo "could not fingerprint the code; nothing recorded" >&2; exit 1; }
    printf '%s\n' "$now" >"$MARK"
    ;;
  check)
    # Already asked once in this turn: let the session end instead of looping.
    # Run by hand with nothing piped in, there is no hook input to read.
    if [ ! -t 0 ] && grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then exit 0; fi
    now="$(state)" && [ -n "$now" ] || block "The security review hook failed to read the code. Run the security-check skill and tell the user the hook is broken."
    if [ ! -f "$MARK" ]; then
      # Nothing recorded yet, as on a fresh clone: only uncommitted work asks for a review.
      [ -z "$(git status --porcelain -- "${PATHS[@]}")" ] && exit 0
    elif [ "$(cat "$MARK")" = "$now" ]; then
      exit 0
    fi
    block "Code under apps/, contracts/, core/, mappack/, testnet/ or .claude/hooks changed since the last recorded security review. Run the security-check skill now: it maps the change to the documented invariants and launches the security-reviewer agent. Then report its findings to the user."
    ;;
  *) echo "usage: $0 check|mark|state" >&2; exit 2 ;;
esac
